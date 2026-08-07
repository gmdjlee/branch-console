package com.branchconsole.app.collectors.yahoo

import com.branchconsole.app.collectors.CollectorResult
import com.branchconsole.app.collectors.FailureReason
import com.branchconsole.app.collectors.RetryPolicy
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * MT1-04a — 00a 저널 §1·§2·§6 실측 계약의 픽스처 재현. 네트워크 금지(MockWebServer만 사용),
 * 재시도 테스트는 백오프를 0으로 낮춰 실행 시간을 줄인다(:krx KrxClientTest와 동일 선례).
 */
class YahooChartCollectorTest {
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
        YahooChartCollector(
            baseUrl = server.url("/v8/finance/chart").toString(),
            userAgent = "test-agent",
            retryPolicy = retryPolicy,
        )

    private val fastRetryThrice = RetryPolicy(attempts = 3, backoffMs = listOf(0L, 0L, 0L))

    // ---------------------------------------------------------------- success

    @Test
    fun `fetchDailyChart parses a normal 5-bar response`() =
        runBlocking {
            server.enqueue(MockResponse().setBody(GSPC_5D_FIXTURE).setResponseCode(200))

            val result = collector().fetchDailyChart("^GSPC")

            check(result is CollectorResult.Success)
            assertEquals("^GSPC", result.value.symbol)
            assertEquals(5, result.value.bars.size)
            val last = result.value.bars.last()
            assertEquals(Instant.ofEpochSecond(1754500800L), last.asOf)
            assertEquals(7723.5498, last.close!!, 1e-9)
            assertEquals(3_000_000_000L, last.volume)
        }

    @Test
    fun `fetchDailyChart sends only a User-Agent header, no crumb or cookies`() =
        runBlocking {
            server.enqueue(MockResponse().setBody(GSPC_5D_FIXTURE).setResponseCode(200))

            collector().fetchDailyChart("^GSPC")

            val request = server.takeRequest()
            assertEquals("test-agent", request.getHeader("User-Agent"))
            assertNull(request.getHeader("Cookie"))
            assertEquals("^GSPC", request.requestUrl!!.pathSegments.last())
            assertEquals("5d", request.requestUrl!!.queryParameter("range"))
            assertEquals("1d", request.requestUrl!!.queryParameter("interval"))
        }

    @Test
    fun `fetchDailyChart preserves KRW=X high low close and encodes the symbol`() =
        runBlocking {
            server.enqueue(MockResponse().setBody(KRWUSD_FIXTURE).setResponseCode(200))

            val result = collector().fetchDailyChart("KRW=X")

            check(result is CollectorResult.Success)
            val bar = result.value.bars.last()
            assertEquals(1420.60, bar.close!!, 1e-9)
            assertEquals(1424.00, bar.high!!, 1e-9)
            assertEquals(1415.30, bar.low!!, 1e-9)
            val request = server.takeRequest()
            assertEquals("KRW=X", request.requestUrl!!.pathSegments.last())
        }

    @Test
    fun `fetchDailyChart keeps null bars explicit for MOVE-style truncation`() =
        runBlocking {
            server.enqueue(MockResponse().setBody(MOVE_TRUNCATED_FIXTURE).setResponseCode(200))

            val result = collector().fetchDailyChart("^MOVE")

            check(result is CollectorResult.Success)
            assertEquals(5, result.value.bars.size)
            assertNull(result.value.bars[1].close)
            assertNull(result.value.bars[2].close)
            assertNull(result.value.bars[3].close)
            assertEquals(102.0, result.value.bars[0].close!!, 1e-9)
            assertEquals(98.5, result.value.bars[4].close!!, 1e-9)
        }

    // ---------------------------------------------------------------- errors

    @Test
    fun `fetchDailyChart returns NOT_FOUND without retry on HTTP 404`() =
        runBlocking {
            server.enqueue(MockResponse().setBody(NOT_FOUND_FIXTURE).setResponseCode(404))

            val result = collector(fastRetryThrice).fetchDailyChart("^NOPE")

            check(result is CollectorResult.Failed)
            assertEquals(FailureReason.NOT_FOUND, result.reason)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `fetchDailyChart retries on 429 and succeeds on second attempt`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setBody(GSPC_5D_FIXTURE).setResponseCode(200))

            val result = collector(fastRetryThrice).fetchDailyChart("^GSPC")

            check(result is CollectorResult.Success)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `fetchDailyChart exhausts retries on repeated HTTP 500`() =
        runBlocking {
            repeat(3) { server.enqueue(MockResponse().setResponseCode(500)) }

            val result = collector(fastRetryThrice).fetchDailyChart("^GSPC")

            check(result is CollectorResult.Failed)
            assertEquals(FailureReason.SERVER_ERROR, result.reason)
            assertEquals(3, server.requestCount)
        }

    @Test
    fun `fetchDailyChart classifies IOException as NETWORK and retries`() =
        runBlocking {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setBody(GSPC_5D_FIXTURE).setResponseCode(200))

            val result = collector(RetryPolicy(attempts = 2, backoffMs = listOf(0L))).fetchDailyChart("^GSPC")

            check(result is CollectorResult.Success)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `fetchDailyChart returns PARSE_ERROR without retry on malformed JSON`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("not json at all").setResponseCode(200))

            val result = collector(fastRetryThrice).fetchDailyChart("^GSPC")

            check(result is CollectorResult.Failed)
            assertEquals(FailureReason.PARSE_ERROR, result.reason)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `fetchDailyChart returns PARSE_ERROR when quote block is missing`() =
        runBlocking {
            server.enqueue(MockResponse().setBody(MISSING_QUOTE_BLOCK_FIXTURE).setResponseCode(200))

            val result = collector().fetchDailyChart("^GSPC")

            check(result is CollectorResult.Failed)
            assertEquals(FailureReason.PARSE_ERROR, result.reason)
        }

    // ---------------------------------------------------------------- stooq stub

    @Test
    fun `StooqFallback is disabled and always fails`() =
        runBlocking {
            assertTrue(!StooqFallback.ENABLED)
            val result = StooqFallback.fetchDailyCsv("^spx")
            check(result is CollectorResult.Failed)
            assertEquals(FailureReason.DISABLED, result.reason)
        }

    private companion object {
        // 00a 저널 §1 실측 구조 재현(값은 저널 §9.1 표본 대조 수치 재사용).
        val GSPC_5D_FIXTURE =
            """
            {
              "chart": {
                "result": [
                  {
                    "meta": {"currency": "USD", "symbol": "^GSPC"},
                    "timestamp": [1754068800, 1754155200, 1754241600, 1754328000, 1754500800],
                    "indicators": {
                      "quote": [{
                        "open": [7600.50, 7605.20, 7650.10, 7700.00, 7710.30],
                        "high": [7620.00, 7660.00, 7690.00, 7740.00, 7750.00],
                        "low": [7580.00, 7590.00, 7630.00, 7680.00, 7690.00],
                        "close": [7600.50, 7650.10, 7680.20, 7736.52, 7723.5498],
                        "volume": [3200000000, 3100000000, 3300000000, 3400000000, 3000000000]
                      }],
                      "adjclose": [{"adjclose": [7600.50, 7650.10, 7680.20, 7736.52, 7723.5498]}]
                    }
                  }
                ],
                "error": null
              }
            }
            """.trimIndent()

        // 00a §1: KRW=X는 high/low/close 전부 필요(usdkrw_intraday_force).
        val KRWUSD_FIXTURE =
            """
            {
              "chart": {
                "result": [
                  {
                    "meta": {"currency": "KRW", "symbol": "KRW=X"},
                    "timestamp": [1754068800],
                    "indicators": {
                      "quote": [{
                        "open": [1418.00],
                        "high": [1424.00],
                        "low": [1415.30],
                        "close": [1420.60],
                        "volume": [0]
                      }]
                    }
                  }
                ],
                "error": null
              }
            }
            """.trimIndent()

        // 00a §2: 절단 구간 재현 — 중간 결측 + 마지막 슬롯만 값 존재.
        val MOVE_TRUNCATED_FIXTURE =
            """
            {
              "chart": {
                "result": [
                  {
                    "meta": {"currency": "USD", "symbol": "^MOVE"},
                    "timestamp": [1750000000, 1750086400, 1750172800, 1750259200, 1750345600],
                    "indicators": {
                      "quote": [{
                        "open": [102.0, null, null, null, 98.5],
                        "high": [102.0, null, null, null, 98.5],
                        "low": [102.0, null, null, null, 98.5],
                        "close": [102.0, null, null, null, 98.5],
                        "volume": [0, null, null, null, 0]
                      }]
                    }
                  }
                ],
                "error": null
              }
            }
            """.trimIndent()

        // 00a §1 실측 오류 형식 그대로(JSON 토큰 사이 공백은 유효 — 물리 줄만 나눔).
        const val NOT_FOUND_FIXTURE =
            """{"chart":{"result":null,
            "error":{"code":"Not Found","description":"No data found, symbol may be delisted"}}}"""

        val MISSING_QUOTE_BLOCK_FIXTURE =
            """
            {
              "chart": {
                "result": [
                  {
                    "meta": {"symbol": "^GSPC"},
                    "timestamp": [1],
                    "indicators": {"quote": []}
                  }
                ],
                "error": null
              }
            }
            """.trimIndent()
    }
}
