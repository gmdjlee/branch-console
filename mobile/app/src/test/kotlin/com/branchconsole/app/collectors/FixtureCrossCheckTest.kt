package com.branchconsole.app.collectors

import com.branchconsole.app.collectors.fred.FredCollector
import com.branchconsole.app.collectors.fred.FredCredentialsProvider
import com.branchconsole.app.collectors.fred.FredObservationsCollector
import com.branchconsole.app.collectors.krx.KrxCollector
import com.branchconsole.app.collectors.krx.KrxCredentials
import com.branchconsole.app.collectors.krx.KrxRateLimiter
import com.branchconsole.app.collectors.yahoo.YahooChartCollector
import com.branchconsole.app.collectors.yahoo.YahooCollector
import com.branchconsole.app.tick.ConfirmSeriesIds
import com.krxkt.api.KrxClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * MT1-04h (docs/plans/M1_PLAN_B.md §162 "PIT 대조 하니스" / M1_PLAN_D.md §145-146
 * "MT1-04h와 BT-05 L0가 같은 자료구조 위에서 성립한다"): PIT cross-check harness.
 *
 * BT-05 ([com.branchconsole.engine.parity.ParityRunnerTest]) proves the *engine* reproduces
 * `engine_ref` given a fixed input timeline (`backtest/parity/<window>/raw.jsonl`). It never
 * asks whether that premise -- "the mobile collectors' parsed output equals the same
 * timeline" -- actually holds. This test closes that gap: it feeds each of the three
 * production collectors (Yahoo/FRED/KRX) a response shaped exactly like the real API
 * (per the 00a/00c journal + the schema already reverse-engineered by
 * YahooCollectorTest/FredCollectorTest/KrxCollectorTest) but populated with the *real*
 * values already frozen in a backtest fixture, drives it through the real parsing pipeline
 * (`YahooChartCollector`/`FredObservationsCollector`/`KrxCollector` -> [Observation]), and
 * asserts the resulting (series_id, field, as_of, value) rows equal the fixture rows they
 * were built from, within 1e-6 relative tolerance.
 *
 * Why the envelope is hand-built rather than a literal captured HTTP transcript: the
 * 00a/00c journal probes ran on 2026-08-07, but every `backtest/fixtures` window's
 * `collected_range` ends by 2026-08-02 (`w2026_structural` is the newest and still ends
 * there) -- no historical HTTP capture in this repo has dates overlapping a frozen fixture
 * window. Embedding the fixture's own real values in the documented real wire schema is the
 * strongest available proof that the parser -> [Observation] mapping (field slot, date
 * parsing, sign, comma/decimal handling, epoch/timezone) does not corrupt the exact values
 * BT-05 assumes as input.
 *
 * Sample window: [WINDOW_ID], 3 consecutive KRX+US trading days ([SAMPLE_DATES]).
 * `KRX:VKOSPI/close` is the one 14-pair series (docs/plans/M1_PLAN_C.md §9-C "14쌍") with
 * **zero** rows in every `backtest/fixtures` window without exception (verified against all
 * 10 windows before writing this test) -- v1 never wires VKOSPI into the Python scoring
 * pipeline (M-19(c), docs/plans/M1_PLAN_FINAL.md §1.3: "수집·저장하되 v1 판정 입력은 실현변동성
 * 폴백 유지") -- so it is excluded here with this recorded reason, as the brief allows.
 *
 * Series-id translation (3 of the 14 pairs, KRX-sourced only -- already documented in
 * [ConfirmSeriesIds]'s file header): the mobile collector's own seriesId differs from the
 * backtest-fixture convention for the pykrx-sourced pairs ("1001" vs "KRX:1001",
 * "kospi_investor_trading"/"foreign_net_buy_value" vs
 * "KRX:investor_foreign_kospi"/"net_buy_value"). [fixtureKeyFor] is the single place that
 * translation is applied when looking up the expected value.
 */
class FixtureCrossCheckTest {
    private val expected: Map<Triple<String, String, String>, Double> by lazy { loadExpected() }

    // ---------------------------------------------------------------------
    // Yahoo: 6 symbols, 1 HTTP request each, enqueued in collect() call order.
    // ---------------------------------------------------------------------

    @Test
    fun `yahoo collector output matches the frozen fixture for the sample window`() =
        runTest {
            val server = MockWebServer()
            server.start()
            try {
                YAHOO_SYMBOLS.forEach { symbol ->
                    server.enqueue(MockResponse().setBody(yahooBody(symbol)).setResponseCode(HTTP_OK))
                }
                val chart =
                    YahooChartCollector(
                        baseUrl = server.url("/v8/finance/chart").toString(),
                        userAgent = "fixture-cross-check",
                        retryPolicy = RetryPolicy(attempts = 1, backoffMs = emptyList()),
                    )
                val outcome = YahooCollector(chart, symbols = YAHOO_SYMBOLS).collect(SAMPLE_START..SAMPLE_END)

                assertRowsMatchExpected(rowsOf(outcome), YAHOO_SYMBOLS)
            } finally {
                server.shutdown()
            }
        }

    // ---------------------------------------------------------------------
    // FRED: 2 series, 1 HTTP request each.
    // ---------------------------------------------------------------------

    @Test
    fun `fred collector output matches the frozen fixture for the sample window`() =
        runTest {
            val server = MockWebServer()
            server.start()
            try {
                FRED_SERIES.forEach { seriesId ->
                    server.enqueue(MockResponse().setBody(fredBody(seriesId)).setResponseCode(HTTP_OK))
                }
                val fred =
                    FredObservationsCollector(
                        credentials = FredCredentialsProvider { "fixture-cross-check-key" },
                        baseUrl = server.url("/fred").toString(),
                        retryPolicy = RetryPolicy(attempts = 1, backoffMs = emptyList()),
                    )
                val outcome = FredCollector(fred, seriesIds = FRED_SERIES).collect(SAMPLE_START..SAMPLE_END)

                assertRowsMatchExpected(rowsOf(outcome), FRED_SERIES)
            } finally {
                server.shutdown()
            }
        }

    // ---------------------------------------------------------------------
    // KRX: login handshake (3 requests, KrxCollectorTest.loggedInClient() pattern) + kospi
    // + kosdaq (untested, only kept trading-day-complete so it doesn't register as a
    // failure) + investor + vkospi (untested -- no fixture data, see class doc).
    // ---------------------------------------------------------------------

    @Test
    fun `krx collector output matches the frozen fixture for the sample window`() =
        runTest {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(MockResponse().setBody("<html></html>").setResponseCode(HTTP_OK)) // login page
                server.enqueue(MockResponse().setBody("<html></html>").setResponseCode(HTTP_OK)) // login jsp
                server.enqueue(MockResponse().setBody("""{"_error_code": "CD001"}""").setResponseCode(HTTP_OK))
                val client =
                    KrxClient(
                        baseUrl = server.url("/base").toString(),
                        loginPageUrl = server.url("/page").toString(),
                        loginJspUrl = server.url("/jsp").toString(),
                        loginUrl = server.url("/login").toString(),
                    )
                check(client.login("seed-id", "seed-pw")) { "test setup: seed login failed" }

                server.enqueue(MockResponse().setBody(kospiBody()).setResponseCode(HTTP_OK))
                server.enqueue(MockResponse().setBody(dummyIndexBody()).setResponseCode(HTTP_OK)) // kosdaq
                server.enqueue(MockResponse().setBody(investorBody()).setResponseCode(HTTP_OK))
                server.enqueue(MockResponse().setBody(dummyVkospiBody()).setResponseCode(HTTP_OK))

                val collector =
                    KrxCollector(
                        credentialsProvider = { KrxCredentials(id = "test-id", password = "test-pw") },
                        rateLimiter = KrxRateLimiter(minIntervalMs = 0),
                        client = client,
                    )
                val outcome = collector.collect(SAMPLE_START..SAMPLE_END)

                assertRowsMatchExpected(rowsOf(outcome), KRX_SERIES)
            } finally {
                server.shutdown()
            }
        }

    // ---------------------------------------------------------------------
    // shared assertion
    // ---------------------------------------------------------------------

    private fun rowsOf(outcome: CollectOutcome): List<Observation> =
        when (outcome) {
            is CollectOutcome.Ok -> outcome.rows
            is CollectOutcome.Partial -> outcome.rows
            is CollectOutcome.Failed -> error("collector failed: ${outcome.reason}")
        }

    private fun assertRowsMatchExpected(
        rows: List<Observation>,
        seriesIds: Collection<String>,
    ) {
        val checkedPairs = CHECKED_SERIES_FIELDS.filter { (seriesId, _) -> seriesId in seriesIds }
        check(checkedPairs.isNotEmpty()) { "no checked (seriesId, field) pairs for $seriesIds - test setup bug" }
        for ((seriesId, field) in checkedPairs) {
            for (date in SAMPLE_DATES) {
                val key = Triple(seriesId, field, date)
                val expectedValue = expected.getValue(key)
                val actualRow =
                    rows.singleOrNull { it.seriesId == seriesId && it.field == field && it.asOf == instantOf(date) }
                        ?: error("missing produced row for $seriesId/$field@$date among ${rows.size} rows")
                assertRelativeEquals(expectedValue, actualRow.value, key)
            }
        }
    }

    private fun assertRelativeEquals(
        expectedValue: Double,
        actualValue: Double,
        key: Triple<String, String, String>,
    ) {
        val tolerance = RELATIVE_TOLERANCE * maxOf(abs(expectedValue), MIN_ABS_FOR_TOLERANCE)
        assertTrue(
            "$key: expected=$expectedValue actual=$actualValue (relative tolerance=$RELATIVE_TOLERANCE)",
            abs(expectedValue - actualValue) <= tolerance,
        )
    }

    // ---------------------------------------------------------------------
    // fixture loading (backtest/parity/$WINDOW_ID/raw.jsonl, read from its original
    // repo-root location -- never copied into this module, SnapshotContractsTest precedent)
    // ---------------------------------------------------------------------

    private fun loadExpected(): Map<Triple<String, String, String>, Double> {
        val json = Json { ignoreUnknownKeys = true }
        val bySourceKey = mutableMapOf<Triple<String, String, String>, Double>()
        rawFixtureFile().readLines(Charsets.UTF_8).forEach { line ->
            if (line.isBlank()) return@forEach
            val row = json.decodeFromString(RawFixtureRow.serializer(), line)
            bySourceKey[Triple(row.seriesId, row.field, row.asOf)] = row.value
        }
        val result = mutableMapOf<Triple<String, String, String>, Double>()
        for ((mobileSeriesId, field) in CHECKED_SERIES_FIELDS) {
            val (fixtureSeriesId, fixtureField) = fixtureKeyFor(mobileSeriesId, field)
            for (date in SAMPLE_DATES) {
                val value =
                    bySourceKey[Triple(fixtureSeriesId, fixtureField, date)]
                        ?: error(
                            "backtest/parity/$WINDOW_ID/raw.jsonl has no row for " +
                                "$fixtureSeriesId/$fixtureField@$date - sample window/dates need updating",
                        )
                result[Triple(mobileSeriesId, field, date)] = value
            }
        }
        return result
    }

    private fun rawFixtureFile(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(MAX_PARENT_HOPS) {
            val candidate = dir?.let { File(it, "backtest/parity/$WINDOW_ID/raw.jsonl") }
            if (candidate != null && candidate.isFile) return candidate
            dir = dir?.parentFile
        }
        error("backtest/parity/$WINDOW_ID/raw.jsonl not found by walking up from ${System.getProperty("user.dir")}")
    }

    @Serializable
    private data class RawFixtureRow(
        @SerialName("series_id") val seriesId: String,
        val field: String,
        @SerialName("as_of") val asOf: String,
        val value: Double,
    )

    /**
     * Translates a mobile (seriesId, field) pair into the backtest-fixture convention.
     * Identity for 11 of the 14 pairs (yfinance/FRED symbols already agree by construction,
     * `ConfirmSeriesIds`'s file header) -- only the 3 pykrx-sourced pairs differ.
     */
    private fun fixtureKeyFor(
        mobileSeriesId: String,
        field: String,
    ): Pair<String, String> =
        when (mobileSeriesId) {
            ConfirmSeriesIds.KOSPI -> FIXTURE_KOSPI to field
            ConfirmSeriesIds.KOSPI_INVESTOR -> FIXTURE_KOSPI_INVESTOR to FIXTURE_NET_BUY_VALUE_FIELD
            else -> mobileSeriesId to field
        }

    // ---------------------------------------------------------------------
    // synthetic (but real-value) upstream response bodies
    // ---------------------------------------------------------------------

    private fun epochSecondsOf(date: String): Long = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toEpochSecond()

    private fun instantOf(date: String): Instant = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant()

    private fun expectedValue(
        seriesId: String,
        field: String,
        date: String,
    ): Double = expected.getValue(Triple(seriesId, field, date))

    private fun yahooBody(symbol: String): String {
        fun csvOf(field: String) = SAMPLE_DATES.joinToString(",") { expectedValue(symbol, field, it).toString() }

        val timestamps = SAMPLE_DATES.joinToString(",") { epochSecondsOf(it).toString() }
        val closes = csvOf(ConfirmSeriesIds.FIELD_CLOSE)
        val opens = SAMPLE_DATES.joinToString(",") { "null" }
        val highs = if (symbol == ConfirmSeriesIds.USDKRW) csvOf(ConfirmSeriesIds.FIELD_HIGH) else opens
        val lows = if (symbol == ConfirmSeriesIds.USDKRW) csvOf(ConfirmSeriesIds.FIELD_LOW) else opens
        val volumes = SAMPLE_DATES.joinToString(",") { DUMMY_VOLUME }
        return """
            {"chart":{"result":[{
              "meta":{"symbol":"$symbol","currency":"USD"},
              "timestamp":[$timestamps],
              "indicators":{"quote":[{
                "open":[$opens],"high":[$highs],"low":[$lows],"close":[$closes],"volume":[$volumes]
              }]}
            }],"error":null}}
            """.trimIndent()
    }

    private fun fredBody(seriesId: String): String {
        val entries =
            SAMPLE_DATES.joinToString(",") { date ->
                val value = expected.getValue(Triple(seriesId, ConfirmSeriesIds.FIELD_VALUE, date))
                """{"date":"$date","value":"$value"}"""
            }
        return """{"count":${SAMPLE_DATES.size},"observations":[$entries]}"""
    }

    private fun kospiBody(): String {
        val rows =
            SAMPLE_DATES.joinToString(",") { date ->
                val close = expectedValue(ConfirmSeriesIds.KOSPI, ConfirmSeriesIds.FIELD_CLOSE, date)
                val tradingValueField = ConfirmSeriesIds.FIELD_TRADING_VALUE
                val tradingValue = expectedValue(ConfirmSeriesIds.KOSPI, tradingValueField, date).toLong()
                indexOhlcvRow(date, close = close.toString(), tradingValue = tradingValue.toString())
            }
        return """{"OutBlock_1": [$rows]}"""
    }

    /** KOSDAQ is not part of the 14-pair contract -- only kept complete over [SAMPLE_DATES]
     * so [KrxCollector] doesn't register it as an `EmptyOnTradingDay` failure. */
    private fun dummyIndexBody(): String {
        val rows =
            SAMPLE_DATES.joinToString(
                ",",
            ) { date -> indexOhlcvRow(date, close = DUMMY_INDEX_CLOSE, tradingValue = DUMMY_TRADING_VALUE) }
        return """{"OutBlock_1": [$rows]}"""
    }

    private fun indexOhlcvRow(
        date: String,
        close: String,
        tradingValue: String,
    ): String {
        val trdDd = date.replace("-", "/")
        return """{"TRD_DD":"$trdDd","OPNPRC_IDX":"$close","HGPRC_IDX":"$close","LWPRC_IDX":"$close",
            "CLSPRC_IDX":"$close","ACC_TRDVOL":"$DUMMY_VOLUME","ACC_TRDVAL":"$tradingValue",
            "FLUC_TP_CD":"1","PRV_DD_CMPR":"0.0"}"""
    }

    private fun investorBody(): String {
        val rows =
            SAMPLE_DATES.joinToString(",") { date ->
                val netBuyField = ConfirmSeriesIds.FIELD_FOREIGN_NET_BUY_VALUE
                val netBuy = expectedValue(ConfirmSeriesIds.KOSPI_INVESTOR, netBuyField, date).toLong()
                val trdDd = date.replace("-", "/")
                """{"TRD_DD":"$trdDd","TRDVAL1":"0","TRDVAL2":"0","TRDVAL3":"0","TRDVAL4":"0","TRDVAL5":"0",
                    "TRDVAL6":"0","TRDVAL7":"0","TRDVAL8":"0","TRDVAL9":"0",
                    "TRDVAL10":"$netBuy","TRDVAL11":"0","TRDVAL_TOT":"0"}"""
            }
        return """{"output": [$rows]}"""
    }

    /** No `KRX:VKOSPI` row exists in any backtest fixture window (class doc) -- these values
     * are never asserted on, they only need to exist so KrxCollector doesn't fail the day. */
    private fun dummyVkospiBody(): String {
        val rows =
            SAMPLE_DATES.joinToString(",") { date ->
                val trdDd = date.replace("-", "/")
                """{"TRD_DD":"$trdDd","CLSPRC_IDX":"$DUMMY_VKOSPI_CLOSE"}"""
            }
        return """{"output": [$rows]}"""
    }

    private companion object {
        const val HTTP_OK = 200
        const val MAX_PARENT_HOPS = 8
        const val RELATIVE_TOLERANCE = 1e-6
        const val MIN_ABS_FOR_TOLERANCE = 1.0
        const val DUMMY_VOLUME = "100000000"
        const val DUMMY_TRADING_VALUE = "1000000000"
        const val DUMMY_INDEX_CLOSE = "800.0"
        const val DUMMY_VKOSPI_CLOSE = "20.0"

        const val WINDOW_ID = "w2026_structural"
        val SAMPLE_DATES = listOf("2026-07-14", "2026-07-15", "2026-07-16")
        val SAMPLE_START: LocalDate = LocalDate.parse(SAMPLE_DATES.first())
        val SAMPLE_END: LocalDate = LocalDate.parse(SAMPLE_DATES.last())

        const val FIXTURE_KOSPI = "KRX:1001"
        const val FIXTURE_KOSPI_INVESTOR = "KRX:investor_foreign_kospi"
        const val FIXTURE_NET_BUY_VALUE_FIELD = "net_buy_value"

        val YAHOO_SYMBOLS =
            listOf(
                ConfirmSeriesIds.VIX,
                ConfirmSeriesIds.VIX3M,
                ConfirmSeriesIds.MOVE,
                ConfirmSeriesIds.GSPC,
                ConfirmSeriesIds.DXY,
                ConfirmSeriesIds.USDKRW,
            )
        val FRED_SERIES = listOf(ConfirmSeriesIds.HY_OAS, ConfirmSeriesIds.UST_2S10S)
        val KRX_SERIES = listOf(ConfirmSeriesIds.KOSPI, ConfirmSeriesIds.KOSPI_INVESTOR)

        /** The 14-pair contract (docs/plans/M1_PLAN_C.md §9-C) minus `KRX:VKOSPI/close`
         * (class doc: zero rows in every backtest fixture window, excluded with reason). */
        val CHECKED_SERIES_FIELDS: List<Pair<String, String>> =
            ConfirmSeriesIds.REQUIRED_SERIES_FIELDS.filterNot { (seriesId, _) -> seriesId == ConfirmSeriesIds.VKOSPI }
    }
}
