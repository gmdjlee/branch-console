package com.branchconsole.app.collectors.ecos

import com.branchconsole.app.collectors.CollectorResult
import com.branchconsole.app.collectors.FailureReason
import com.branchconsole.app.collectors.RetryPolicy
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

private const val STAT_CODE = "817Y002"
private const val ITEM_CORP_AA3Y = "010300000"
private const val ITEM_KTB_3Y = "010200000"

/**
 * MT1-04d — 00b 저널 §7.3 실측 캡처(2026-07-20~2026-08-07, 15영업일, 국고채·회사채 두 계열)를
 * 픽스처로 한 파서 계약 테스트. `backtest/parity` 픽스처에는 ECOS 데이터가 0행이라
 * [com.branchconsole.app.collectors.FixtureCrossCheckTest]로 대조할 수 없으므로(VKOSPI와 동일
 * 사유), 저널이 직접 캡처한 실제 응답값을 이 클래스가 대신 재현한다. 네트워크 금지(MockWebServer만).
 */
class EcosObservationsCollectorTest {
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

    private fun collector(retryPolicy: RetryPolicy = RetryPolicy(attempts = 1, backoffMs = emptyList())) =
        EcosObservationsCollector(
            credentials = EcosCredentialsProvider { "test-key" },
            baseUrl = server.url("/api").toString(),
            retryPolicy = retryPolicy,
        )

    // ---------------------------------------------------------------- success

    @Test
    fun `fetchSeries parses the corp_aa3y 15-business-day capture`() =
        runBlocking {
            server.enqueue(MockResponse().setBody(CORP_AA3Y_FIXTURE).setResponseCode(200))

            val result =
                collector().fetchSeries(
                    ITEM_CORP_AA3Y,
                    STAT_CODE,
                    LocalDate.of(2026, 7, 20),
                    LocalDate.of(2026, 8, 8),
                )

            check(result is CollectorResult.Success)
            assertEquals(15, result.value.observations.size)
            val first = result.value.observations.first { it.asOf == LocalDate.of(2026, 7, 20) }
            assertEquals(4.590, first.value, 1e-9)
            val last = result.value.observations.first { it.asOf == LocalDate.of(2026, 8, 7) }
            assertEquals(4.448, last.value, 1e-9)
        }

    @Test
    fun `fetchSeries parses the ktb_3y 15-business-day capture`() =
        runBlocking {
            server.enqueue(MockResponse().setBody(KTB_3Y_FIXTURE).setResponseCode(200))

            val result =
                collector().fetchSeries(ITEM_KTB_3Y, STAT_CODE, LocalDate.of(2026, 7, 20), LocalDate.of(2026, 8, 8))

            check(result is CollectorResult.Success)
            assertEquals(15, result.value.observations.size)
            val first = result.value.observations.first { it.asOf == LocalDate.of(2026, 7, 20) }
            assertEquals(3.895, first.value, 1e-9)
        }

    @Test
    fun `fetchSeries omits weekend rows entirely rather than emitting a missing marker`() =
        runBlocking {
            // 00b §7.3 — 07-25/26(토·일)은 응답 배열에 행 자체가 없다(FRED의 "." 결측과 다름).
            server.enqueue(MockResponse().setBody(CORP_AA3Y_FIXTURE).setResponseCode(200))

            val result =
                collector().fetchSeries(
                    ITEM_CORP_AA3Y,
                    STAT_CODE,
                    LocalDate.of(2026, 7, 20),
                    LocalDate.of(2026, 8, 8),
                )

            check(result is CollectorResult.Success)
            assertTrue(result.value.observations.none { it.asOf == LocalDate.of(2026, 7, 25) })
            assertTrue(result.value.observations.none { it.asOf == LocalDate.of(2026, 7, 26) })
        }

    @Test
    fun `fetchSeries puts the authkey in the URL path, not a query parameter`() =
        runBlocking {
            server.enqueue(MockResponse().setBody(EMPTY_FIXTURE).setResponseCode(200))

            collector().fetchSeries(ITEM_CORP_AA3Y, STAT_CODE, LocalDate.of(2026, 7, 20), LocalDate.of(2026, 8, 8))

            val url = server.takeRequest().requestUrl!!
            assertTrue(url.pathSegments.containsAll(listOf("StatisticSearch", "test-key", "json", "kr")))
            assertEquals(0, url.querySize)
        }

    // ---------------------------------------------------------------- defensive parsing

    @Test
    fun `fetchSeries drops a single row with unparseable DATA_VALUE instead of failing the whole request`() =
        runBlocking {
            val body =
                """{"StatisticSearch":{"list_total_count":2,"row":[
                    {"TIME":"20260720","DATA_VALUE":"4.590"},
                    {"TIME":"20260721","DATA_VALUE":""}
                ]}}"""
            server.enqueue(MockResponse().setBody(body).setResponseCode(200))

            val result =
                collector().fetchSeries(
                    ITEM_CORP_AA3Y,
                    STAT_CODE,
                    LocalDate.of(2026, 7, 20),
                    LocalDate.of(2026, 7, 21),
                )

            check(result is CollectorResult.Success)
            assertEquals(1, result.value.observations.size)
            assertEquals(4.590, result.value.observations.single().value, 1e-9)
        }

    // ---------------------------------------------------------------- errors

    @Test
    fun `fetchSeries maps a RESULT error envelope to CLIENT_ERROR`() =
        runBlocking {
            val body = """{"RESULT":{"CODE":"INFO-200","MESSAGE":"해당하는 데이터가 없습니다."}}"""
            server.enqueue(MockResponse().setBody(body).setResponseCode(200))

            val result =
                collector().fetchSeries(
                    ITEM_CORP_AA3Y,
                    STAT_CODE,
                    LocalDate.of(2026, 7, 20),
                    LocalDate.of(2026, 8, 8),
                )

            check(result is CollectorResult.Failed)
            assertEquals(FailureReason.CLIENT_ERROR, result.reason)
        }

    @Test
    fun `fetchSeries returns PARSE_ERROR on malformed JSON`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("not json at all").setResponseCode(200))

            val result =
                collector().fetchSeries(
                    ITEM_CORP_AA3Y,
                    STAT_CODE,
                    LocalDate.of(2026, 7, 20),
                    LocalDate.of(2026, 8, 8),
                )

            check(result is CollectorResult.Failed)
            assertEquals(FailureReason.PARSE_ERROR, result.reason)
        }

    @Test
    fun `fetchSeries retries on 429 and succeeds on second attempt`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setBody(EMPTY_FIXTURE).setResponseCode(200))

            val result =
                collector(RetryPolicy(attempts = 3, backoffMs = listOf(0L, 0L, 0L))).fetchSeries(
                    ITEM_CORP_AA3Y,
                    STAT_CODE,
                    LocalDate.of(2026, 7, 20),
                    LocalDate.of(2026, 8, 8),
                )

            check(result is CollectorResult.Success)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `fetchSeries exhausts retries on repeated HTTP 500`() =
        runBlocking {
            repeat(3) { server.enqueue(MockResponse().setResponseCode(500)) }

            val result =
                collector(RetryPolicy(attempts = 3, backoffMs = listOf(0L, 0L, 0L))).fetchSeries(
                    ITEM_CORP_AA3Y,
                    STAT_CODE,
                    LocalDate.of(2026, 7, 20),
                    LocalDate.of(2026, 8, 8),
                )

            check(result is CollectorResult.Failed)
            assertEquals(FailureReason.SERVER_ERROR, result.reason)
            assertEquals(3, server.requestCount)
        }

    private companion object {
        const val EMPTY_FIXTURE = """{"StatisticSearch":{"list_total_count":0,"row":[]}}"""

        // 00b 저널 §7.3 실측 원문 그대로(회사채 3년,AA-, item_code 010300000). 주말(07-25/26,
        // 08-01/02)은 행 자체가 없다.
        val CORP_AA3Y_FIXTURE =
            """
            {"StatisticSearch":{"list_total_count":15,"row":[
              {"TIME":"20260720","DATA_VALUE":"4.590"},
              {"TIME":"20260721","DATA_VALUE":"4.571"},
              {"TIME":"20260722","DATA_VALUE":"4.604"},
              {"TIME":"20260723","DATA_VALUE":"4.609"},
              {"TIME":"20260724","DATA_VALUE":"4.653"},
              {"TIME":"20260727","DATA_VALUE":"4.576"},
              {"TIME":"20260728","DATA_VALUE":"4.545"},
              {"TIME":"20260729","DATA_VALUE":"4.512"},
              {"TIME":"20260730","DATA_VALUE":"4.541"},
              {"TIME":"20260731","DATA_VALUE":"4.478"},
              {"TIME":"20260803","DATA_VALUE":"4.462"},
              {"TIME":"20260804","DATA_VALUE":"4.458"},
              {"TIME":"20260805","DATA_VALUE":"4.392"},
              {"TIME":"20260806","DATA_VALUE":"4.445"},
              {"TIME":"20260807","DATA_VALUE":"4.448"}
            ]}}
            """.trimIndent()

        // 00b 저널 §7.3 실측 원문 그대로(국고채 3년, item_code 010200000).
        val KTB_3Y_FIXTURE =
            """
            {"StatisticSearch":{"list_total_count":15,"row":[
              {"TIME":"20260720","DATA_VALUE":"3.895"},
              {"TIME":"20260721","DATA_VALUE":"3.867"},
              {"TIME":"20260722","DATA_VALUE":"3.913"},
              {"TIME":"20260723","DATA_VALUE":"3.917"},
              {"TIME":"20260724","DATA_VALUE":"3.959"},
              {"TIME":"20260727","DATA_VALUE":"3.865"},
              {"TIME":"20260728","DATA_VALUE":"3.829"},
              {"TIME":"20260729","DATA_VALUE":"3.800"},
              {"TIME":"20260730","DATA_VALUE":"3.831"},
              {"TIME":"20260731","DATA_VALUE":"3.758"},
              {"TIME":"20260803","DATA_VALUE":"3.742"},
              {"TIME":"20260804","DATA_VALUE":"3.740"},
              {"TIME":"20260805","DATA_VALUE":"3.669"},
              {"TIME":"20260806","DATA_VALUE":"3.742"},
              {"TIME":"20260807","DATA_VALUE":"3.746"}
            ]}}
            """.trimIndent()
    }
}
