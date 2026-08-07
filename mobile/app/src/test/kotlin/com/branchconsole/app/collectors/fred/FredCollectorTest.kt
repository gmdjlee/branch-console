package com.branchconsole.app.collectors.fred

import com.branchconsole.app.collectors.CollectFailureReason
import com.branchconsole.app.collectors.CollectOutcome
import com.branchconsole.app.collectors.RetryPolicy
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * MT1-04g — [FredCollector]가 [FredObservationsCollector]의 결과를 [CollectOutcome] 공통
 * 계약으로 정확히 옮기는지만 검증한다(응답 파싱 자체는 [FredObservationsCollectorTest]가 이미
 * 덮는다). 네트워크 금지(MockWebServer만 사용).
 */
class FredCollectorTest {
    private lateinit var server: MockWebServer
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-08-06T00:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private fun fred(apiKey: () -> String = { "test-key" }) =
        FredObservationsCollector(
            credentials = FredCredentialsProvider(apiKey),
            baseUrl = server.url("/fred").toString(),
            retryPolicy = RetryPolicy(attempts = 1, backoffMs = emptyList()),
            clock = fixedClock,
        )

    @Test
    fun `collect maps observations from both series into value rows`() =
        runTest {
            server.enqueue(MockResponse().setBody(VIXCLS_LIKE_FIXTURE).setResponseCode(200))
            server.enqueue(MockResponse().setBody(EMPTY_FIXTURE).setResponseCode(200))
            val collector = FredCollector(fred(), seriesIds = listOf("BAMLH0A0HYM2", "T10Y2Y"))

            val outcome = collector.collect(LocalDate.of(2026, 8, 1)..LocalDate.of(2026, 8, 6))

            check(outcome is CollectOutcome.Ok) { "expected Ok, got $outcome" }
            assertEquals(3, outcome.rows.size)
            assertTrue(outcome.rows.all { it.field == "value" && it.seriesId == "BAMLH0A0HYM2" })
        }

    @Test
    fun `collect drops an explicit dot-missing observation without inserting a row`() =
        runTest {
            server.enqueue(MockResponse().setBody(DOT_MISSING_FIXTURE).setResponseCode(200))
            val collector = FredCollector(fred(), seriesIds = listOf("T10Y2Y"))

            val outcome = collector.collect(LocalDate.of(2026, 6, 15)..LocalDate.of(2026, 6, 23))

            check(outcome is CollectOutcome.Ok) { "expected Ok, got $outcome" }
            assertEquals(1, outcome.rows.size) // 2 observations returned, 1 is "." -> dropped
        }

    @Test
    fun `collect forwards the requested range as observation_start and observation_end`() =
        runTest {
            server.enqueue(MockResponse().setBody(EMPTY_FIXTURE).setResponseCode(200))
            val collector = FredCollector(fred(), seriesIds = listOf("BAMLH0A0HYM2"))

            collector.collect(LocalDate.of(2023, 8, 7)..LocalDate.of(2026, 8, 6))

            val url = server.takeRequest().requestUrl!!
            assertEquals("2023-08-07", url.queryParameter("observation_start"))
            assertEquals("2026-08-06", url.queryParameter("observation_end"))
        }

    @Test
    fun `collect maps a 500 into a SeriesFailure wrapping the HTTP reason`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(500))
            val collector = FredCollector(fred(), seriesIds = listOf("BAMLH0A0HYM2"))

            val outcome = collector.collect(LocalDate.of(2026, 8, 1)..LocalDate.of(2026, 8, 6))

            check(outcome is CollectOutcome.Partial) { "expected Partial, got $outcome" }
            assertTrue(outcome.failures.single().reason is CollectFailureReason.Http)
        }

    @Test
    fun `collect maps a missing api key into NotConfigured without crashing`() =
        runTest {
            val collector =
                FredCollector(
                    fred(apiKey = { error("FRED_API_KEY not set") }),
                    seriesIds = listOf("BAMLH0A0HYM2", "T10Y2Y"),
                )

            val outcome = collector.collect(LocalDate.of(2026, 8, 1)..LocalDate.of(2026, 8, 6))

            check(outcome is CollectOutcome.Partial) { "expected Partial, got $outcome" }
            assertEquals(2, outcome.failures.size)
            assertTrue(outcome.failures.all { it.reason == CollectFailureReason.NotConfigured })
            assertEquals(0, server.requestCount) // never even attempted the HTTP call
        }

    private companion object {
        val VIXCLS_LIKE_FIXTURE =
            """
            {"count":3,"observations":[
              {"date":"2026-08-03","value":"2.63"},
              {"date":"2026-08-04","value":"2.66"},
              {"date":"2026-08-05","value":"2.71"}
            ]}
            """.trimIndent()

        const val EMPTY_FIXTURE = """{"count":0,"observations":[]}"""

        val DOT_MISSING_FIXTURE =
            """
            {"count":2,"observations":[
              {"date":"2026-06-19","value":"."},
              {"date":"2026-06-22","value":"0.27"}
            ]}
            """.trimIndent()
    }
}
