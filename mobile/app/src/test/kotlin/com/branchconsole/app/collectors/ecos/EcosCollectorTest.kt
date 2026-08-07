package com.branchconsole.app.collectors.ecos

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
import java.time.LocalDate

private val ITEM_CODES = EcosSeriesConfig.ItemCodes(corpAa3y = "010300000", ktb3y = "010200000")
private const val STAT_CODE = "817Y002"

/**
 * MT1-04d — [EcosCollector]가 [EcosObservationsCollector]의 결과를 [CollectOutcome] 공통 계약으로
 * 정확히 옮기는지만 검증한다(응답 파싱 자체는 [EcosObservationsCollectorTest]가 이미 덮는다,
 * `FredCollectorTest`와 동일 분업). 네트워크 금지(MockWebServer만).
 */
class EcosCollectorTest {
    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private fun ecos(apiKey: () -> String = { "test-key" }) =
        EcosObservationsCollector(
            credentials = EcosCredentialsProvider(apiKey),
            baseUrl = server.url("/api").toString(),
            retryPolicy = RetryPolicy(attempts = 1, backoffMs = emptyList()),
        )

    @Test
    fun `collect fetches both item codes and maps them into two seriesId rows`() =
        runTest {
            server.enqueue(MockResponse().setBody(ROW_FIXTURE).setResponseCode(200)) // corp_aa3y
            server.enqueue(MockResponse().setBody(ROW_FIXTURE).setResponseCode(200)) // ktb_3y
            val collector = EcosCollector(ecos(), STAT_CODE, ITEM_CODES)

            val outcome = collector.collect(LocalDate.of(2026, 7, 20)..LocalDate.of(2026, 7, 21))

            check(outcome is CollectOutcome.Ok) { "expected Ok, got $outcome" }
            assertEquals(4, outcome.rows.size) // 2 rows x 2 series
            assertEquals(setOf(SERIES_CORP_AA3Y, SERIES_KTB_3Y), outcome.rows.map { it.seriesId }.toSet())
            assertTrue(outcome.rows.all { it.field == "value" && it.source == "ecos" })
        }

    @Test
    fun `collect requests the SSOT stat_code and both configured item codes`() =
        runTest {
            server.enqueue(MockResponse().setBody(EMPTY_FIXTURE).setResponseCode(200))
            server.enqueue(MockResponse().setBody(EMPTY_FIXTURE).setResponseCode(200))
            val collector = EcosCollector(ecos(), STAT_CODE, ITEM_CODES)

            collector.collect(LocalDate.of(2026, 7, 20)..LocalDate.of(2026, 7, 21))

            val first = server.takeRequest().requestUrl!!.pathSegments
            val second = server.takeRequest().requestUrl!!.pathSegments
            assertTrue(first.containsAll(listOf(STAT_CODE, "010300000")))
            assertTrue(second.containsAll(listOf(STAT_CODE, "010200000")))
        }

    @Test
    fun `collect maps a 500 on one item code into a Partial SeriesFailure`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(500)) // corp_aa3y fails
            server.enqueue(MockResponse().setBody(EMPTY_FIXTURE).setResponseCode(200)) // ktb_3y ok
            val collector = EcosCollector(ecos(), STAT_CODE, ITEM_CODES)

            val outcome = collector.collect(LocalDate.of(2026, 7, 20)..LocalDate.of(2026, 7, 21))

            check(outcome is CollectOutcome.Partial) { "expected Partial, got $outcome" }
            val failure = outcome.failures.single()
            assertEquals(SERIES_CORP_AA3Y, failure.seriesId)
            assertTrue(failure.reason is CollectFailureReason.Http)
        }

    @Test
    fun `collect maps a missing api key into NotConfigured for both series without crashing`() =
        runTest {
            val collector = EcosCollector(ecos(apiKey = { error("ECOS_API_KEY not set") }), STAT_CODE, ITEM_CODES)

            val outcome = collector.collect(LocalDate.of(2026, 7, 20)..LocalDate.of(2026, 7, 21))

            check(outcome is CollectOutcome.Partial) { "expected Partial, got $outcome" }
            assertEquals(2, outcome.failures.size)
            assertTrue(outcome.failures.all { it.reason == CollectFailureReason.NotConfigured })
            assertEquals(0, server.requestCount) // ECOS is optional -- never even attempted the HTTP call
        }

    private companion object {
        const val EMPTY_FIXTURE = """{"StatisticSearch":{"list_total_count":0,"row":[]}}"""
        val ROW_FIXTURE =
            """
            {"StatisticSearch":{"list_total_count":2,"row":[
              {"TIME":"20260720","DATA_VALUE":"4.590"},
              {"TIME":"20260721","DATA_VALUE":"4.571"}
            ]}}
            """.trimIndent()
    }
}
