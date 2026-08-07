package com.branchconsole.app.collectors.yahoo

import android.content.Context
import com.branchconsole.app.collectors.CollectOutcome
import com.branchconsole.app.collectors.Collector
import com.branchconsole.app.collectors.CollectorResult
import com.branchconsole.app.collectors.FailureReason
import com.branchconsole.app.collectors.Observation
import com.branchconsole.app.collectors.SeriesFailure
import com.branchconsole.app.collectors.toCollectFailureReason
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * 야후 chart 심볼 6종을 [Collector] 공통 계약으로 감싼다(MT1-04g — [YahooChartCollector] 자체는
 * 심볼 하나·API `range` 문자열 하나짜리 저수준 어댑터라 그대로는 [Collector]가 아니다).
 *
 * 날짜 → API `range` 버킷 변환은 [yahooRangeCovering]이 담당한다 — 00g §7 실측(pykrx·yfinance
 * 둘 다 요청 범위를 그대로 반환, 관측된 상한 없음)에 따라 **분할 호출을 하지 않고** 요청 구간을
 * 덮는 가장 작은 버킷 하나로 단일 호출한다.
 *
 * `asOf`는 야후가 준 원시 타임스탬프의 UTC 날짜로 정규화한다(그날의 UTC 자정) — 미국 시장 종가는
 * 항상 같은 UTC 달력일 안에서 발생하므로(장 마감 ET ≈ 20~21시 UTC, 자정 이전) 거래소 표준시간대
 * 변환 없이도 올바른 거래일을 얻는다.
 * ponytail: 아시아 세션 심볼이 추가되면 이 가정이 깨질 수 있다 — 이 어댑터가 다루는 6개 심볼은
 * 전부 미주 세션이라 지금은 안전하다.
 */
class YahooCollector(
    private val chart: YahooChartCollector,
    private val symbols: List<String> = DEFAULT_SYMBOLS,
    private val nowProvider: () -> Instant = Instant::now,
) : Collector {
    override val id: String = "yfinance"

    override val expectedSeriesIds: List<String> = symbols

    override suspend fun collect(range: ClosedRange<LocalDate>): CollectOutcome {
        val yahooRange = yahooRangeCovering(ChronoUnit.DAYS.between(range.start, range.endInclusive).toInt())
        val rows = mutableListOf<Observation>()
        val failures = mutableListOf<SeriesFailure>()
        for (symbol in symbols) {
            when (val result = collectSymbol(symbol, yahooRange)) {
                is CollectorResult.Success -> rows += barsToObservations(symbol, result.value.bars, range)
                is CollectorResult.Failed -> failures += SeriesFailure(symbol, result.toCollectFailureReason())
            }
        }
        return if (failures.isEmpty()) CollectOutcome.Ok(rows) else CollectOutcome.Partial(rows, failures)
    }

    // TooGenericExceptionCaught: YahooChartCollector already absorbs IOException/HTTP/parse errors
    // into CollectorResult.Failed — this is a last-resort net so an unexpected throw doesn't crash
    // the whole warmup backfill run for the other 5 symbols (K-01/K-18 partial-failure absorption).
    @Suppress("TooGenericExceptionCaught")
    private suspend fun collectSymbol(
        symbol: String,
        yahooRange: String,
    ): CollectorResult<YahooChartSeries> =
        try {
            chart.fetchDailyChart(symbol, range = yahooRange)
        } catch (e: Exception) {
            CollectorResult.Failed(FailureReason.NETWORK, e.message ?: "yahoo collector threw for $symbol", e)
        }

    private fun barsToObservations(
        symbol: String,
        bars: List<YahooBar>,
        range: ClosedRange<LocalDate>,
    ): List<Observation> {
        val observedAt = nowProvider()
        return bars.flatMap { bar ->
            val day = bar.asOf.atZone(ZoneOffset.UTC).toLocalDate()
            if (day < range.start || day > range.endInclusive) return@flatMap emptyList()
            val asOfMidnight = day.atStartOfDay(ZoneOffset.UTC).toInstant()
            listOfNotNull(
                bar.open?.let { Observation(symbol, "open", asOfMidnight, observedAt, SOURCE, it) },
                bar.high?.let { Observation(symbol, "high", asOfMidnight, observedAt, SOURCE, it) },
                bar.low?.let { Observation(symbol, "low", asOfMidnight, observedAt, SOURCE, it) },
                bar.close?.let { Observation(symbol, "close", asOfMidnight, observedAt, SOURCE, it) },
            )
        }
    }

    companion object {
        private const val SOURCE = "yahoo"

        /** `configs/indicators.yaml`의 yfinance 심볼 6종(00a 저널 §1 실측 계약). */
        val DEFAULT_SYMBOLS = listOf("^VIX", "^VIX3M", "^MOVE", "^GSPC", "DX-Y.NYB", "KRW=X")

        // 00a 저널 §1 meta.validRanges 실측 그대로 — 야후 API 자체의 버킷 열거이며 임계값/가중치가
        // 아니다(CLAUDE.md §1 SSOT 규율 대상 아님).
        private val RANGE_BUCKETS =
            listOf(
                5 to "5d",
                30 to "1mo",
                90 to "3mo",
                180 to "6mo",
                365 to "1y",
                730 to "2y",
                1825 to "5y",
                3650 to "10y",
            )

        /** [days]를 덮는 가장 작은 야후 API `range` 버킷. 전부 초과하면 "max". */
        internal fun yahooRangeCovering(days: Int): String {
            return RANGE_BUCKETS.firstOrNull { days <= it.first }?.second ?: "max"
        }

        fun create(context: Context): YahooCollector = YahooCollector(YahooChartCollector.create(context))
    }
}
