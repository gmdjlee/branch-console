package com.branchconsole.app.collectors.yahoo

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * 야후 chart v8 REST 응답 DTO (00a 저널 §1 실측 계약 그대로).
 *
 * `query1.finance.yahoo.com/v8/finance/chart/{symbol}` 응답 구조를 그대로 미러링한다.
 * `null` 배열 원소는 결측 봉을 의미하며 스킵하지 않는다(PIT 원칙 — 00a §1 "파서가 길이로
 * 정렬 가능, null 스킵 금지").
 */
@Serializable
internal data class YahooChartEnvelope(
    val chart: YahooChart,
)

@Serializable
internal data class YahooChart(
    val result: List<YahooChartResult>? = null,
    val error: YahooChartError? = null,
)

@Serializable
internal data class YahooChartError(
    val code: String,
    val description: String,
)

@Serializable
internal data class YahooChartResult(
    val meta: YahooChartMeta,
    val timestamp: List<Long> = emptyList(),
    val indicators: YahooIndicators,
)

@Serializable
internal data class YahooChartMeta(
    val symbol: String,
    val currency: String? = null,
)

@Serializable
internal data class YahooIndicators(
    val quote: List<YahooQuote> = emptyList(),
)

@Serializable
internal data class YahooQuote(
    val open: List<Double?> = emptyList(),
    val high: List<Double?> = emptyList(),
    val low: List<Double?> = emptyList(),
    val close: List<Double?> = emptyList(),
    val volume: List<Long?> = emptyList(),
)

/**
 * 어댑터가 반환하는 봉 하나 — `timestamp[i]`와 `indicators.quote[0].{field}[i]`를 인덱스로
 * zip한 결과. 각 필드는 야후가 결측(`null`)으로 보낸 그대로 nullable이다(00a §1·§2 — 절단
 * 구간을 예외 없이 명시적으로 표현).
 */
data class YahooBar(
    val asOf: Instant,
    val open: Double?,
    val high: Double?,
    val low: Double?,
    val close: Double?,
    val volume: Long?,
)

data class YahooChartSeries(
    val symbol: String,
    val bars: List<YahooBar>,
)
