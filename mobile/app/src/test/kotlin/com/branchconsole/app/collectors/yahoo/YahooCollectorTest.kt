package com.branchconsole.app.collectors.yahoo

import com.branchconsole.app.collectors.CollectFailureReason
import com.branchconsole.app.collectors.CollectOutcome
import com.branchconsole.app.collectors.RetryPolicy
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * MT1-04g — [YahooCollector]가 [YahooChartCollector]의 결과를 [CollectOutcome] 공통 계약으로
 * 정확히 옮기는지만 검증한다(파싱 자체는 [YahooChartCollectorTest]가 이미 덮는다). 네트워크
 * 금지(MockWebServer만 사용).
 */
class YahooCollectorTest {
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

    private fun chart() =
        YahooChartCollector(
            baseUrl = server.url("/v8/finance/chart").toString(),
            userAgent = "test-agent",
            retryPolicy = RetryPolicy(attempts = 1, backoffMs = emptyList()),
        )

    @Test
    fun `yahooRangeCovering picks the smallest bucket that covers the padding window`() {
        assertEquals("5d", YahooCollector.yahooRangeCovering(3))
        assertEquals("1mo", YahooCollector.yahooRangeCovering(20))
        assertEquals("2y", YahooCollector.yahooRangeCovering(550))
        assertEquals("max", YahooCollector.yahooRangeCovering(4000))
    }

    @Test
    fun `collect maps a bar inside the window into observations and skips a null field`() =
        runTest {
            server.enqueue(MockResponse().setBody(GSPC_5D_FIXTURE).setResponseCode(200))
            val collector = YahooCollector(chart(), symbols = listOf("^GSPC"))

            // Fixture has 5 bars (2026-07-28..07-31, 2026-08-05) — only the last falls inside
            // this window; the other 4 are July history outside the requested range.
            val outcome = collector.collect(LocalDate.parse("2026-08-01")..LocalDate.parse("2026-08-06"))

            check(outcome is CollectOutcome.Ok) { "expected Ok, got $outcome" }
            val closeRows = outcome.rows.filter { it.field == "close" }
            assertEquals(1, closeRows.size)
            assertEquals(7723.5498, closeRows.single().value, 1e-9)
            assertEquals("^GSPC", closeRows.single().seriesId)
            // the in-window bar's "open" is null (00a §1 null-skip contract) — no open row emitted.
            assertTrue(outcome.rows.none { it.field == "open" })
        }

    @Test
    fun `collect maps a 404 into a SeriesFailure wrapping the HTTP reason`() =
        runTest {
            server.enqueue(MockResponse().setBody(NOT_FOUND_FIXTURE).setResponseCode(404))
            val collector = YahooCollector(chart(), symbols = listOf("^GSPC"))

            val outcome = collector.collect(LocalDate.parse("2026-08-01")..LocalDate.parse("2026-08-06"))

            check(outcome is CollectOutcome.Partial) { "expected Partial, got $outcome" }
            assertTrue(outcome.rows.isEmpty())
            val failure = outcome.failures.single()
            assertEquals("^GSPC", failure.seriesId)
            assertTrue(failure.reason is CollectFailureReason.Http)
        }

    @Test
    fun `collect returns Ok with zero rows when the window excludes every bar`() =
        runTest {
            server.enqueue(MockResponse().setBody(GSPC_5D_FIXTURE).setResponseCode(200))
            val collector = YahooCollector(chart(), symbols = listOf("^GSPC"))

            val outcome = collector.collect(LocalDate.parse("2000-01-01")..LocalDate.parse("2000-01-02"))

            check(outcome is CollectOutcome.Ok) { "expected Ok, got $outcome" }
            assertEquals(0, outcome.rows.size)
            assertNull(outcome.rows.firstOrNull())
        }

    private companion object {
        // 00a 저널 §1 실측 응답 재현(YahooChartCollectorTest와 동일 픽스처 형태) — 4개 봉은
        // 2026-07-28~07-31(요청 구간 밖), 마지막 봉만 2026-08-05(UTC epoch 1785960000, 요청
        // 구간 안). 마지막 봉의 open은 null(00a §1 "null 스킵 금지" 계약 재현).
        val GSPC_5D_FIXTURE =
            """
            {"chart":{"result":[{
              "meta":{"symbol":"^GSPC","currency":"USD"},
              "timestamp":[1785268800,1785355200,1785441600,1785528000,1785960000],
              "indicators":{"quote":[{
                "open":[7600.0,7650.0,7680.0,7700.0,null],
                "high":[7650.0,7690.0,7700.0,7720.0,7730.0],
                "low":[7580.0,7620.0,7660.0,7690.0,7700.0],
                "close":[7600.50,7650.20,7690.30,7710.10,7723.5498],
                "volume":[3000000000,3000000000,3000000000,3000000000,3000000000]
              }]}
            }],"error":null}}
            """.trimIndent()

        val NOT_FOUND_FIXTURE =
            """
            {"chart":{"result":null,
              "error":{"code":"Not Found","description":"No data found, symbol may be delisted"}}}
            """.trimIndent()
    }
}
