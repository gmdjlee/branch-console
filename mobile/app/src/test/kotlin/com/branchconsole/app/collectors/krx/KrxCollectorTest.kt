package com.branchconsole.app.collectors.krx

import com.krxkt.api.KrxClient
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.time.LocalDate

private val CREDENTIALS = KrxCredentials(id = "test-id", password = "test-pw")

// Fresh instance per call — KrxRateLimiter carries mutable last-call state that must not leak
// across test methods (a shared top-level val would be a JVM-wide singleton for the test class).
private fun noSleepLimiter() = KrxRateLimiter(minIntervalMs = 0)

class KrxCollectorTest {
    private lateinit var mockServer: MockWebServer

    @Before
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()
    }

    @After
    fun teardown() {
        mockServer.shutdown()
    }

    /**
     * `KrxClient.setLoggedInForTest` is `internal` to `:krx` — not visible from `:app`. Instead
     * this drives the real public `login()` handshake against the same mock server (3 requests:
     * GET page, GET jsp, POST login/CD001), matching the pattern `:krx`'s own `KrxClientTest`
     * uses for login coverage. Callers must invoke this **before** enqueueing any data-call
     * responses, since `login()` consumes whatever is currently at the front of the queue.
     */
    private fun loggedInClient(): KrxClient {
        mockServer.enqueue(MockResponse().setBody("<html></html>").setResponseCode(200)) // login page
        mockServer.enqueue(MockResponse().setBody("<html></html>").setResponseCode(200)) // login jsp
        mockServer.enqueue(MockResponse().setBody("""{"_error_code": "CD001"}""").setResponseCode(200)) // login
        val client =
            KrxClient(
                baseUrl = mockServer.url("/base").toString(),
                loginPageUrl = mockServer.url("/page").toString(),
                loginJspUrl = mockServer.url("/jsp").toString(),
                loginUrl = mockServer.url("/login").toString(),
            )
        check(client.login("seed-id", "seed-pw")) { "test setup: seed login failed" }
        return client
    }

    private fun bodyOf(skip: Int): String {
        repeat(skip) { mockServer.takeRequest() }
        return URLDecoder.decode(mockServer.takeRequest().body.readUtf8(), "UTF-8")
    }

    // ====================================================
    // Happy path — one trading day across all four series
    // ====================================================

    @Test
    fun `collect returns Ok with mapped observations for a single trading day`() =
        runTest {
            val client = loggedInClient()
            mockServer.enqueue(MockResponse().setBody(KOSPI_OHLCV_20260805).setResponseCode(200))
            mockServer.enqueue(MockResponse().setBody(KOSDAQ_OHLCV_20260805).setResponseCode(200))
            mockServer.enqueue(MockResponse().setBody(INVESTOR_TRADING_KOSPI_20260805).setResponseCode(200))
            mockServer.enqueue(MockResponse().setBody(VKOSPI_20260805).setResponseCode(200))

            val collector =
                KrxCollector(
                    credentialsProvider = { CREDENTIALS },
                    rateLimiter = noSleepLimiter(),
                    client = client,
                )

            val outcome = collector.collect(LocalDate.parse("2026-08-05")..LocalDate.parse("2026-08-05"))

            check(outcome is CollectOutcome.Ok) { "expected Ok, got $outcome" }
            // 6 KOSPI OHLCV fields + 6 KOSDAQ OHLCV fields + 1 investor + 1 vkospi
            assertEquals(14, outcome.rows.size)

            val investorRow = outcome.rows.single { it.seriesId == "kospi_investor_trading" }
            // TRDVAL10 + TRDVAL11 (KOSPI scope) = 1,451,333,652,408 + (-4,965,867,133)
            assertEquals(1_446_367_785_275.0, investorRow.value, 0.0)

            val vkospiRow = outcome.rows.single { it.seriesId == "vkospi" }
            assertEquals(78.55, vkospiRow.value, 0.001)
        }

    @Test
    fun `collect requests investor trading scoped to KOSPI market per indicators yaml`() =
        runTest {
            val client = loggedInClient()
            mockServer.enqueue(MockResponse().setBody(KOSPI_OHLCV_20260805).setResponseCode(200))
            mockServer.enqueue(MockResponse().setBody(KOSDAQ_OHLCV_20260805).setResponseCode(200))
            mockServer.enqueue(MockResponse().setBody(INVESTOR_TRADING_KOSPI_20260805).setResponseCode(200))
            mockServer.enqueue(MockResponse().setBody(VKOSPI_20260805).setResponseCode(200))

            val collector =
                KrxCollector(
                    credentialsProvider = { CREDENTIALS },
                    rateLimiter = noSleepLimiter(),
                    client = client,
                )

            collector.collect(LocalDate.parse("2026-08-05")..LocalDate.parse("2026-08-05"))

            // requests: 0-2=login, 3=kospi, 4=kosdaq, 5=investor, 6=vkospi
            val investorBody = bodyOf(skip = 5)
            assertTrue(investorBody.contains("mktId=STK"))
        }

    // ====================================================
    // Holiday — no trading days, remaining series untouched (K-10 call budget)
    // ====================================================

    @Test
    fun `collect returns Ok empty and skips other series when there are no trading days`() =
        runTest {
            val client = loggedInClient()
            mockServer.enqueue(MockResponse().setBody(EMPTY_OUTBLOCK).setResponseCode(200))

            val collector =
                KrxCollector(
                    credentialsProvider = { CREDENTIALS },
                    rateLimiter = noSleepLimiter(),
                    client = client,
                )

            val outcome = collector.collect(LocalDate.parse("2026-08-01")..LocalDate.parse("2026-08-01"))

            assertEquals(CollectOutcome.Ok(emptyList()), outcome)
            // 3 login requests (test seed) + 1 KOSPI calendar call, nothing else.
            assertEquals(4, mockServer.requestCount)
        }

    // ====================================================
    // K-19 — empty response on a known trading day is a failure, not a silent holiday
    // ====================================================

    @Test
    fun `collect classifies an empty response on a known trading day as EmptyOnTradingDay`() =
        runTest {
            val client = loggedInClient()
            mockServer.enqueue(MockResponse().setBody(KOSPI_OHLCV_TWO_DAYS).setResponseCode(200))
            // KOSDAQ only reports one of the two known trading days.
            mockServer.enqueue(MockResponse().setBody(KOSDAQ_OHLCV_20260805).setResponseCode(200))
            mockServer.enqueue(MockResponse().setBody(INVESTOR_TRADING_TWO_DAYS).setResponseCode(200))
            mockServer.enqueue(MockResponse().setBody(VKOSPI_TWO_DAYS).setResponseCode(200))

            val collector =
                KrxCollector(
                    credentialsProvider = { CREDENTIALS },
                    rateLimiter = noSleepLimiter(),
                    client = client,
                )

            val outcome = collector.collect(LocalDate.parse("2026-08-04")..LocalDate.parse("2026-08-05"))

            check(outcome is CollectOutcome.Partial) { "expected Partial, got $outcome" }
            assertEquals(
                listOf(SeriesFailure("2001", FailureReason.EmptyOnTradingDay("20260804"))),
                outcome.failures,
            )
            // KOSDAQ's one present day still contributed its 6 OHLCV fields.
            assertTrue(outcome.rows.any { it.seriesId == "2001" })
        }

    // ====================================================
    // Session expiry mid-collection -> whole collect fails, not partial
    // ====================================================

    @Test
    fun `collect returns Failed AuthenticationRequired when session expires mid-collection`() =
        runTest {
            val client = loggedInClient()
            mockServer.enqueue(MockResponse().setBody(KOSPI_OHLCV_20260805).setResponseCode(200))
            mockServer.enqueue(MockResponse().setBody("LOGOUT").setResponseCode(200))

            val collector =
                KrxCollector(
                    credentialsProvider = { CREDENTIALS },
                    rateLimiter = noSleepLimiter(),
                    client = client,
                )

            val outcome = collector.collect(LocalDate.parse("2026-08-05")..LocalDate.parse("2026-08-05"))

            check(outcome is CollectOutcome.Failed) { "expected Failed, got $outcome" }
            assertTrue(outcome.reason is FailureReason.AuthenticationRequired)
        }

    // ====================================================
    // Credentials never configured -> Failed(NotConfigured), zero network calls
    // ====================================================

    @Test
    fun `collect returns Failed NotConfigured without any network call when credentials are missing`() =
        runTest {
            val collector =
                KrxCollector(
                    credentialsProvider = { error("krx credentials not set") },
                    rateLimiter = noSleepLimiter(),
                    client = KrxClient(baseUrl = mockServer.url("/").toString()),
                )

            val outcome = collector.collect(LocalDate.parse("2026-08-05")..LocalDate.parse("2026-08-05"))

            assertEquals(CollectOutcome.Failed(FailureReason.NotConfigured), outcome)
            assertEquals(0, mockServer.requestCount)
        }

    // ====================================================
    // Login rejected -> Failed(AuthenticationRequired)
    // ====================================================

    @Test
    fun `collect returns Failed AuthenticationRequired when login is rejected`() =
        runTest {
            mockServer.enqueue(MockResponse().setBody("<html></html>").setResponseCode(200)) // login page
            mockServer.enqueue(MockResponse().setBody("<html></html>").setResponseCode(200)) // login jsp
            mockServer.enqueue(MockResponse().setBody("""{"_error_code": "CD002"}""").setResponseCode(200)) // login

            val client =
                KrxClient(
                    baseUrl = mockServer.url("/base").toString(),
                    loginPageUrl = mockServer.url("/page").toString(),
                    loginJspUrl = mockServer.url("/jsp").toString(),
                    loginUrl = mockServer.url("/login").toString(),
                )
            val collector =
                KrxCollector(
                    credentialsProvider = { CREDENTIALS },
                    rateLimiter = noSleepLimiter(),
                    client = client,
                )

            val outcome = collector.collect(LocalDate.parse("2026-08-05")..LocalDate.parse("2026-08-05"))

            assertEquals(CollectOutcome.Failed(FailureReason.AuthenticationRequired()), outcome)
        }

    // ====================================================
    // K-03 — the SSOT rate limiter is actually wired between calls
    // ====================================================

    @Test
    fun `collect throttles between each of the four krx calls`() =
        runTest {
            val client = loggedInClient()
            mockServer.enqueue(MockResponse().setBody(KOSPI_OHLCV_20260805).setResponseCode(200))
            mockServer.enqueue(MockResponse().setBody(KOSDAQ_OHLCV_20260805).setResponseCode(200))
            mockServer.enqueue(MockResponse().setBody(INVESTOR_TRADING_KOSPI_20260805).setResponseCode(200))
            mockServer.enqueue(MockResponse().setBody(VKOSPI_20260805).setResponseCode(200))

            val sleeps = mutableListOf<Long>()
            val fixedClockLimiter = KrxRateLimiter(minIntervalMs = 1000, clock = { 0L }, sleep = { sleeps += it })
            val collector =
                KrxCollector(
                    credentialsProvider = { CREDENTIALS },
                    rateLimiter = fixedClockLimiter,
                    client = client,
                )

            collector.collect(LocalDate.parse("2026-08-05")..LocalDate.parse("2026-08-05"))

            // 4 krx calls at a clock frozen at t=0: 1st never sleeps, the other 3 always do.
            // (Login itself is not throttled — it runs once via the public login() handshake.)
            assertEquals(listOf(1000L, 1000L, 1000L), sleeps)
        }
}

private const val EMPTY_OUTBLOCK = """{"OutBlock_1": []}"""

private const val KOSPI_OHLCV_20260805 =
    """
    {"OutBlock_1": [
        {"TRD_DD":"2026/08/05","OPNPRC_IDX":"6,603.48","HGPRC_IDX":"6,674.66","LWPRC_IDX":"6,540.27",
         "CLSPRC_IDX":"6,598.26","ACC_TRDVOL":"338,499,583","ACC_TRDVAL":"25,657,753,879,758",
         "FLUC_TP_CD":"1","PRV_DD_CMPR":"239.31"}
    ]}
    """

private const val KOSPI_OHLCV_TWO_DAYS =
    """
    {"OutBlock_1": [
        {"TRD_DD":"2026/08/04","OPNPRC_IDX":"6,500.00","HGPRC_IDX":"6,550.00","LWPRC_IDX":"6,480.00",
         "CLSPRC_IDX":"6,520.00","ACC_TRDVOL":"300,000,000","ACC_TRDVAL":"20,000,000,000,000",
         "FLUC_TP_CD":"1","PRV_DD_CMPR":"10.00"},
        {"TRD_DD":"2026/08/05","OPNPRC_IDX":"6,603.48","HGPRC_IDX":"6,674.66","LWPRC_IDX":"6,540.27",
         "CLSPRC_IDX":"6,598.26","ACC_TRDVOL":"338,499,583","ACC_TRDVAL":"25,657,753,879,758",
         "FLUC_TP_CD":"1","PRV_DD_CMPR":"239.31"}
    ]}
    """

private const val KOSDAQ_OHLCV_20260805 =
    """
    {"OutBlock_1": [
        {"TRD_DD":"2026/08/05","OPNPRC_IDX":"800.00","HGPRC_IDX":"812.00","LWPRC_IDX":"795.00",
         "CLSPRC_IDX":"805.00","ACC_TRDVOL":"1,200,000,000","ACC_TRDVAL":"9,000,000,000,000",
         "FLUC_TP_CD":"1","PRV_DD_CMPR":"5.00"}
    ]}
    """

private const val INVESTOR_TRADING_KOSPI_20260805 =
    """
    {"output": [
        {"TRD_DD":"2026/08/05",
         "TRDVAL1":"135,740,424,004","TRDVAL2":"-10,517,391,740","TRDVAL3":"110,045,917,508",
         "TRDVAL4":"-519,354,783,643","TRDVAL5":"1,968,808,079","TRDVAL6":"25,181,552,015",
         "TRDVAL7":"-26,821,033,593","TRDVAL8":"21,784,531,051","TRDVAL9":"-1,184,395,808,956",
         "TRDVAL10":"1,451,333,652,408","TRDVAL11":"-4,965,867,133","TRDVAL_TOT":"0"}
    ]}
    """

private const val INVESTOR_TRADING_TWO_DAYS =
    """
    {"output": [
        {"TRD_DD":"2026/08/04",
         "TRDVAL1":"1,000","TRDVAL2":"1,000","TRDVAL3":"1,000","TRDVAL4":"1,000","TRDVAL5":"1,000",
         "TRDVAL6":"1,000","TRDVAL7":"1,000","TRDVAL8":"1,000","TRDVAL9":"1,000","TRDVAL10":"1,000",
         "TRDVAL11":"1,000","TRDVAL_TOT":"0"},
        {"TRD_DD":"2026/08/05",
         "TRDVAL1":"135,740,424,004","TRDVAL2":"-10,517,391,740","TRDVAL3":"110,045,917,508",
         "TRDVAL4":"-519,354,783,643","TRDVAL5":"1,968,808,079","TRDVAL6":"25,181,552,015",
         "TRDVAL7":"-26,821,033,593","TRDVAL8":"21,784,531,051","TRDVAL9":"-1,184,395,808,956",
         "TRDVAL10":"1,451,333,652,408","TRDVAL11":"-4,965,867,133","TRDVAL_TOT":"0"}
    ]}
    """

private const val VKOSPI_20260805 = """{"output": [{"TRD_DD":"2026/08/05","CLSPRC_IDX":"78.55"}]}"""

private const val VKOSPI_TWO_DAYS =
    """
    {"output": [
        {"TRD_DD":"2026/08/04","CLSPRC_IDX":"80.78"},
        {"TRD_DD":"2026/08/05","CLSPRC_IDX":"78.55"}
    ]}
    """
