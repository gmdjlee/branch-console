package com.branchconsole.app.collectors.fred

import com.branchconsole.app.collectors.CollectorResult
import com.branchconsole.app.collectors.FailureReason
import com.branchconsole.app.collectors.RetryPolicy
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * MT1-04b — 00a 저널 §9~§13 실측 계약의 픽스처 재현. 네트워크 금지(MockWebServer만 사용).
 */
class FredObservationsCollectorTest {
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

    private fun collector(retryPolicy: RetryPolicy = RetryPolicy(attempts = 1, backoffMs = emptyList())) =
        FredObservationsCollector(
            credentials = FredCredentialsProvider { "test-key" },
            baseUrl = server.url("/fred").toString(),
            retryPolicy = retryPolicy,
            clock = fixedClock,
        )

    private val fastRetryThrice = RetryPolicy(attempts = 3, backoffMs = listOf(0L, 0L, 0L))

    // ---------------------------------------------------------------- success

    @Test
    fun `fetchObservations parses a normal desc-sorted response`() =
        runBlocking {
            server.enqueue(MockResponse().setBody(VIXCLS_FIXTURE).setResponseCode(200))

            val result = collector().fetchObservations("VIXCLS")

            check(result is CollectorResult.Success)
            assertEquals("VIXCLS", result.value.seriesId)
            assertEquals(3, result.value.observations.size)
            assertEquals(LocalDate.of(2026, 8, 5), result.value.observations[0].asOf)
            assertEquals(15.81, result.value.observations[0].value!!, 1e-9)
        }

    @Test
    fun `fetchObservations always sends explicit realtime_start and realtime_end`() =
        runBlocking {
            server.enqueue(MockResponse().setBody(VIXCLS_FIXTURE).setResponseCode(200))

            collector().fetchObservations("VIXCLS")

            val request = server.takeRequest()
            val url = request.requestUrl!!
            assertEquals("2026-08-06", url.queryParameter("realtime_start"))
            assertEquals("2026-08-06", url.queryParameter("realtime_end"))
            assertEquals("json", url.queryParameter("file_type"))
            assertEquals("VIXCLS", url.queryParameter("series_id"))
            assertEquals("test-key", url.queryParameter("api_key"))
        }

    @Test
    fun `fetchObservations forwards observation_start and observation_end when provided`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"count":0,"observations":[]}""").setResponseCode(200))

            collector().fetchObservations(
                "BAMLH0A0HYM2",
                observationStart = LocalDate.of(2023, 8, 7),
                observationEnd = LocalDate.of(2026, 8, 6),
            )

            val url = server.takeRequest().requestUrl!!
            assertEquals("2023-08-07", url.queryParameter("observation_start"))
            assertEquals("2026-08-06", url.queryParameter("observation_end"))
        }

    @Test
    fun `fetchObservations returns an empty list for the pre-window range, not an error`() =
        runBlocking {
            // 00a §12.2 — BAMLH0A0HYM2의 3년 롤링 윈도 이전 구간은 빈 배열, 오류 아님.
            server.enqueue(MockResponse().setBody("""{"count":0,"observations":[]}""").setResponseCode(200))

            val result = collector().fetchObservations("BAMLH0A0HYM2")

            check(result is CollectorResult.Success)
            assertEquals(emptyList<FredObservation>(), result.value.observations)
        }

    @Test
    fun `fetchObservations converts an explicit dot to null missing value`() =
        runBlocking {
            // 00a §12.3 — T10Y2Y Juneteenth(06-19)는 "."로 정직 결측, 주말(06-20~21)은 행 자체가 없음.
            server.enqueue(MockResponse().setBody(T10Y2Y_JUNETEENTH_FIXTURE).setResponseCode(200))

            val result = collector().fetchObservations("T10Y2Y")

            check(result is CollectorResult.Success)
            assertEquals(7, result.value.observations.size)
            val juneteenth = result.value.observations.first { it.asOf == LocalDate.of(2026, 6, 19) }
            assertNull(juneteenth.value)
            val nextTradingDay = result.value.observations.first { it.asOf == LocalDate.of(2026, 6, 22) }
            assertEquals(0.27, nextTradingDay.value!!, 1e-9)
        }

    @Test
    fun `fetchObservations stores a holiday repeat value as-is, not as missing`() =
        runBlocking {
            // 00a §12.3-2 — BAMLH0A0HYM2는 같은 공휴일에 전일값을 반복 출력(결측 아님).
            server.enqueue(MockResponse().setBody(BAMLH0A0HYM2_JUNETEENTH_FIXTURE).setResponseCode(200))

            val result = collector().fetchObservations("BAMLH0A0HYM2")

            check(result is CollectorResult.Success)
            val juneteenth = result.value.observations.first { it.asOf == LocalDate.of(2026, 6, 19) }
            val previousDay = result.value.observations.first { it.asOf == LocalDate.of(2026, 6, 18) }
            assertEquals(previousDay.value!!, juneteenth.value!!, 1e-9)
        }

    // ---------------------------------------------------------------- errors

    @Test
    fun `fetchObservations retries on 429 and succeeds on second attempt`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setBody(VIXCLS_FIXTURE).setResponseCode(200))

            val result = collector(fastRetryThrice).fetchObservations("VIXCLS")

            check(result is CollectorResult.Success)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `fetchObservations exhausts retries on repeated HTTP 500`() =
        runBlocking {
            repeat(3) { server.enqueue(MockResponse().setResponseCode(500)) }

            val result = collector(fastRetryThrice).fetchObservations("VIXCLS")

            check(result is CollectorResult.Failed)
            assertEquals(FailureReason.SERVER_ERROR, result.reason)
            assertEquals(3, server.requestCount)
        }

    @Test
    fun `fetchObservations returns CLIENT_ERROR without retry on HTTP 400`() =
        runBlocking {
            val errorBody = """{"error_code":400,"error_message":"Bad Request."}"""
            server.enqueue(MockResponse().setBody(errorBody).setResponseCode(400))

            val result = collector(fastRetryThrice).fetchObservations("NOT_A_SERIES")

            check(result is CollectorResult.Failed)
            assertEquals(FailureReason.CLIENT_ERROR, result.reason)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `fetchObservations returns PARSE_ERROR on malformed JSON`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("not json at all").setResponseCode(200))

            val result = collector().fetchObservations("VIXCLS")

            check(result is CollectorResult.Failed)
            assertEquals(FailureReason.PARSE_ERROR, result.reason)
        }

    @Test
    fun `fetchObservations returns PARSE_ERROR on an unparseable non-dot value`() =
        runBlocking {
            val body = """{"count":1,"observations":[{"date":"2026-08-05","value":"n/a"}]}"""
            server.enqueue(MockResponse().setBody(body).setResponseCode(200))

            val result = collector().fetchObservations("VIXCLS")

            check(result is CollectorResult.Failed)
            assertEquals(FailureReason.PARSE_ERROR, result.reason)
        }

    private companion object {
        val VIXCLS_FIXTURE =
            """
            {
              "realtime_start": "2026-08-06",
              "realtime_end": "2026-08-06",
              "count": 3,
              "observations": [
                {"realtime_start":"2026-08-06","realtime_end":"2026-08-06","date":"2026-08-05","value":"15.81"},
                {"realtime_start":"2026-08-06","realtime_end":"2026-08-06","date":"2026-08-04","value":"16.50"},
                {"realtime_start":"2026-08-06","realtime_end":"2026-08-06","date":"2026-08-03","value":"15.86"}
              ]
            }
            """.trimIndent()

        // 00a §12.3 표 재현 — 주말(06-20~21)은 행 부재, Juneteenth(06-19)는 "." 결측.
        val T10Y2Y_JUNETEENTH_FIXTURE =
            """
            {
              "count": 7,
              "observations": [
                {"date":"2026-06-23","value":"0.34"},
                {"date":"2026-06-22","value":"0.27"},
                {"date":"2026-06-19","value":"."},
                {"date":"2026-06-18","value":"0.27"},
                {"date":"2026-06-17","value":"0.29"},
                {"date":"2026-06-16","value":"0.38"},
                {"date":"2026-06-15","value":"0.40"}
              ]
            }
            """.trimIndent()

        // 00a §12.3-2 — 같은 주, BAMLH0A0HYM2는 Juneteenth에 전일값(2.66)을 반복.
        val BAMLH0A0HYM2_JUNETEENTH_FIXTURE =
            """
            {
              "count": 7,
              "observations": [
                {"date":"2026-06-23","value":"2.71"},
                {"date":"2026-06-22","value":"2.65"},
                {"date":"2026-06-19","value":"2.66"},
                {"date":"2026-06-18","value":"2.66"},
                {"date":"2026-06-17","value":"2.63"},
                {"date":"2026-06-16","value":"2.71"},
                {"date":"2026-06-15","value":"2.66"}
              ]
            }
            """.trimIndent()
    }
}
