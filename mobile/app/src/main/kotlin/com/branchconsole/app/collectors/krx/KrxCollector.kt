package com.branchconsole.app.collectors.krx

import android.content.Context
import com.branchconsole.app.collectors.CollectFailureReason
import com.branchconsole.app.collectors.CollectOutcome
import com.branchconsole.app.collectors.Collector
import com.branchconsole.app.collectors.Observation
import com.branchconsole.app.collectors.SeriesFailure
import com.krxkt.KrxIndex
import com.krxkt.KrxStock
import com.krxkt.api.KrxClient
import com.krxkt.error.KrxError
import com.krxkt.model.IndexOhlcv
import com.krxkt.model.Market
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private const val SOURCE = "krx_mobile"
private const val FIELD_OPEN = "open"
private const val FIELD_HIGH = "high"
private const val FIELD_LOW = "low"
private const val FIELD_CLOSE = "close"
private const val FIELD_VOLUME = "volume"
private const val FIELD_TRADING_VALUE = "trading_value"
private const val FIELD_FOREIGN_NET_BUY_VALUE = "foreign_net_buy_value"
private const val SERIES_KOSPI_INVESTOR = "kospi_investor_trading"

/**
 * VKOSPI seriesId — **수집·저장 전용**(M-19(c)). v1 `vkospi_z` 지표는
 * `realized_vol_kospi_20d` 폴백을 그대로 쓰며, 이 시리즈를 스코어링 입력으로 배선하지 않는다
 * (`configs/sources.yaml` `providers.pykrx.notes`, `configs/indicators.yaml` `vkospi_z.source`).
 * 전환은 C1 재평가 대상 — 이 상수를 엔진 지표 id로 재사용하지 말 것.
 */
private const val SERIES_VKOSPI = "vkospi"

private val KRX_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")

private data class SeriesResult(val rows: List<Observation>, val failures: List<SeriesFailure>)

/**
 * KRX 지수·투자자 순매수·VKOSPI 수집 어댑터 (MT1-04c).
 *
 * 벤더 `:krx`(mobile/krx)는 무수정 원칙(`verifyKrxProvenance`)이라 rate limit·휴장 판정·K-19
 * 분류는 전부 이 계층의 책임이다(00c 저널 §6 #2, `PROVENANCE.md`).
 *
 * 거래일 캘린더는 별도로 `getBusinessDays`를 호출하지 않고 KOSPI OHLCV 응답(그 자체가
 * `getBusinessDays`의 내부 구현과 동일 호출)의 날짜 집합을 그대로 재사용한다 — 동일 엔드포인트
 * (MDCSTAT00301)를 두 번 두드리지 않기 위한 의도적 선택(K-03 호출 예산 절약). 성공 응답에서만
 * 캘린더를 구성하므로 "경험적 거래일 달력은 성공 응답에서만 만든다"(A-8) 원칙은 그대로 지켜진다.
 */
class KrxCollector(
    private val credentialsProvider: KrxCredentialsProvider,
    private val rateLimiter: KrxRateLimiter,
    private val client: KrxClient = KrxClient(),
    private val index: KrxIndex = KrxIndex(client),
    private val stock: KrxStock = KrxStock(client),
    private val nowProvider: () -> Instant = Instant::now,
) : Collector {
    override val id: String = "pykrx"

    override val expectedSeriesIds: List<String> =
        listOf(KrxIndex.TICKER_KOSPI, KrxIndex.TICKER_KOSDAQ, SERIES_KOSPI_INVESTOR, SERIES_VKOSPI)

    override suspend fun collect(range: ClosedRange<LocalDate>): CollectOutcome {
        ensureLoggedIn()?.let { return it }

        val startStr = range.start.format(KRX_DATE_FMT)
        val endStr = range.endInclusive.format(KRX_DATE_FMT)

        return try {
            collectAuthenticated(startStr, endStr)
        } catch (e: KrxError.AuthenticationError) {
            CollectOutcome.Failed(CollectFailureReason.AuthenticationRequired(e))
        }
    }

    // Early-return guard clauses (not-configured / login-failed / already-logged-in) read clearer
    // than accumulating a single result variable through three independent failure branches.
    @Suppress("ReturnCount")
    private suspend fun ensureLoggedIn(): CollectOutcome? {
        if (client.isLoggedIn()) return null
        val creds =
            runCatching { credentialsProvider.get() }
                .getOrElse { return CollectOutcome.Failed(CollectFailureReason.NotConfigured) }
        val loggedIn =
            runCatching { client.login(creds.id, creds.password) }
                .getOrElse { return CollectOutcome.Failed(toFailureReason(it)) }
        return if (loggedIn) null else CollectOutcome.Failed(CollectFailureReason.AuthenticationRequired())
    }

    // ReturnCount: the empty-calendar short-circuit is a guard clause, not a design smell.
    // TooGenericExceptionCaught: intentional — a KOSPI calendar-call surprise (not auth-related)
    // must still surface as Failed(Network) rather than crash the whole collect() (K-01/K-18).
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private suspend fun collectAuthenticated(
        startStr: String,
        endStr: String,
    ): CollectOutcome {
        rateLimiter.throttle()
        val kospiOhlcv =
            try {
                index.getKospi(startStr, endStr)
            } catch (e: KrxError.AuthenticationError) {
                throw e
            } catch (e: Exception) {
                return CollectOutcome.Failed(toFailureReason(e))
            }

        val tradingDays = kospiOhlcv.map { it.date }.toSortedSet()
        if (tradingDays.isEmpty()) return CollectOutcome.Ok(emptyList())

        val rows = mutableListOf<Observation>()
        val failures = mutableListOf<SeriesFailure>()
        rows += kospiOhlcv.flatMap { ohlcvObservations(KrxIndex.TICKER_KOSPI, it) }

        fetchOhlcvSeries(KrxIndex.TICKER_KOSDAQ, tradingDays) { index.getKosdaq(startStr, endStr) }.also {
            rows += it.rows
            failures += it.failures
        }
        fetchInvestorSeries(tradingDays, startStr, endStr).also {
            rows += it.rows
            failures += it.failures
        }
        fetchVkospiSeries(tradingDays, startStr, endStr).also {
            rows += it.rows
            failures += it.failures
        }

        return if (failures.isEmpty()) CollectOutcome.Ok(rows) else CollectOutcome.Partial(rows, failures)
    }

    // TooGenericExceptionCaught: a single series' unexpected failure must be absorbed as a
    // SeriesFailure (K-01/K-18 "부분 실패는 지표별 결측으로 흡수"), not crash the whole collect().
    @Suppress("TooGenericExceptionCaught")
    private suspend fun fetchOhlcvSeries(
        seriesId: String,
        tradingDays: Set<String>,
        fetch: suspend () -> List<IndexOhlcv>,
    ): SeriesResult {
        rateLimiter.throttle()
        val ohlcv =
            try {
                fetch()
            } catch (e: KrxError.AuthenticationError) {
                throw e
            } catch (e: Exception) {
                return SeriesResult(emptyList(), listOf(SeriesFailure(seriesId, toFailureReason(e))))
            }
        val rows = ohlcv.flatMap { ohlcvObservations(seriesId, it) }
        return SeriesResult(rows, emptyDayFailures(seriesId, tradingDays, ohlcv.map { it.date }.toSet()))
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun fetchInvestorSeries(
        tradingDays: Set<String>,
        startStr: String,
        endStr: String,
    ): SeriesResult {
        rateLimiter.throttle()
        val trading =
            try {
                stock.getMarketTradingByInvestor(startStr, endStr, market = Market.KOSPI)
            } catch (e: KrxError.AuthenticationError) {
                throw e
            } catch (e: Exception) {
                return SeriesResult(emptyList(), listOf(SeriesFailure(SERIES_KOSPI_INVESTOR, toFailureReason(e))))
            }
        val observedAt = nowProvider()
        val rows =
            trading.map {
                Observation(
                    seriesId = SERIES_KOSPI_INVESTOR,
                    field = FIELD_FOREIGN_NET_BUY_VALUE,
                    asOf = tradingDateInstant(it.date),
                    observedAt = observedAt,
                    source = SOURCE,
                    value = it.foreigner.toDouble(),
                )
            }
        return SeriesResult(rows, emptyDayFailures(SERIES_KOSPI_INVESTOR, tradingDays, trading.map { it.date }.toSet()))
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun fetchVkospiSeries(
        tradingDays: Set<String>,
        startStr: String,
        endStr: String,
    ): SeriesResult {
        rateLimiter.throttle()
        val vkospi =
            try {
                index.getVkospi(startStr, endStr)
            } catch (e: KrxError.AuthenticationError) {
                throw e
            } catch (e: Exception) {
                return SeriesResult(emptyList(), listOf(SeriesFailure(SERIES_VKOSPI, toFailureReason(e))))
            }
        val observedAt = nowProvider()
        val rows =
            vkospi.map {
                Observation(
                    seriesId = SERIES_VKOSPI,
                    field = FIELD_CLOSE,
                    asOf = tradingDateInstant(it.date),
                    observedAt = observedAt,
                    source = SOURCE,
                    value = it.close,
                )
            }
        return SeriesResult(rows, emptyDayFailures(SERIES_VKOSPI, tradingDays, vkospi.map { it.date }.toSet()))
    }

    private fun emptyDayFailures(
        seriesId: String,
        tradingDays: Set<String>,
        seenDates: Set<String>,
    ): List<SeriesFailure> =
        tradingDays.filter { it !in seenDates }
            .map { SeriesFailure(seriesId, CollectFailureReason.EmptyOnTradingDay(it)) }

    private fun ohlcvObservations(
        seriesId: String,
        bar: IndexOhlcv,
    ): List<Observation> {
        val asOf = tradingDateInstant(bar.date)
        val observedAt = nowProvider()
        return listOf(
            Observation(seriesId, FIELD_OPEN, asOf, observedAt, SOURCE, bar.open),
            Observation(seriesId, FIELD_HIGH, asOf, observedAt, SOURCE, bar.high),
            Observation(seriesId, FIELD_LOW, asOf, observedAt, SOURCE, bar.low),
            Observation(seriesId, FIELD_CLOSE, asOf, observedAt, SOURCE, bar.close),
            Observation(seriesId, FIELD_VOLUME, asOf, observedAt, SOURCE, bar.volume.toDouble()),
            Observation(seriesId, FIELD_TRADING_VALUE, asOf, observedAt, SOURCE, bar.tradingValue.toDouble()),
        )
    }

    private fun toFailureReason(e: Throwable): CollectFailureReason =
        if (e is KrxError.AuthenticationError) {
            CollectFailureReason.AuthenticationRequired(e)
        } else {
            CollectFailureReason.Network(e)
        }

    companion object {
        private fun tradingDateInstant(date: String): Instant {
            val localDate = LocalDate.parse(date, KRX_DATE_FMT)
            return localDate.atStartOfDay(ZoneOffset.UTC).toInstant()
        }

        /** K-03 SSOT를 assets에서 로드해 스로틀에 주입하는 조립 지점. */
        fun create(
            context: Context,
            credentialsProvider: KrxCredentialsProvider,
        ): KrxCollector {
            val minIntervalMs = KrxRateLimitConfig.loadMinIntervalMs(context)
            return KrxCollector(credentialsProvider, KrxRateLimiter(minIntervalMs))
        }
    }
}
