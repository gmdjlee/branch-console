package com.branchconsole.app.collectors.yahoo

import android.content.Context
import com.branchconsole.app.collectors.CollectorResult
import com.branchconsole.app.collectors.FailureReason
import com.branchconsole.app.collectors.RetryPolicy
import com.branchconsole.app.collectors.withRetry
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * 야후 chart v8 REST 어댑터 (MT1-04a, 00a 저널 §1·§6 실측 계약 그대로).
 *
 * - `GET {baseUrl}/{symbol}?range=..&interval=..`, 헤더는 `User-Agent` 하나만 필요
 *   (crumb/쿠키 불요 — 00a §1 실측).
 * - 오류 분류(00a §6): HTTP 404 + `chart.error.code == "Not Found"` → [FailureReason.NOT_FOUND]
 *   (재시도 안 함). HTTP 429 → [FailureReason.RATE_LIMITED]. HTTP 5xx →
 *   [FailureReason.SERVER_ERROR]. `IOException`(타임아웃 포함) → [FailureReason.NETWORK].
 *   응답 스키마 불일치 → [FailureReason.PARSE_ERROR]. 전체 틱 실패를 막기 위해 예외를 던지지
 *   않고 전부 [CollectorResult.Failed]로 반환한다.
 * - `^MOVE`·`^VIX3M`의 결측/스냅샷-오염 구간(00a §2)은 이 계층에서 걸러내지 않는다 — 각 봉을
 *   있는 그대로([YahooBar], null 포함) 반환하고 stale 판정은 엔진(K-01, indicators.yaml
 *   engine.stale_profiles) 소관이다.
 */
class YahooChartCollector(
    private val retryPolicy: RetryPolicy,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val userAgent: String = DEFAULT_USER_AGENT,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchDailyChart(
        symbol: String,
        range: String = "5d",
        interval: String = "1d",
    ): CollectorResult<YahooChartSeries> = withRetry(retryPolicy) { attemptFetch(symbol, range, interval) }

    // 가드 절 스타일(네트워크 실패 + HTTP 오류 + 빈 바디 + 파싱 위임)이 ReturnCount(2) 기본
    // 상한보다 명확하다 — :krx KrxClient.kt의 @Suppress("LongParameterList") 선례와 동일 판단.
    @Suppress("ReturnCount")
    private fun attemptFetch(
        symbol: String,
        range: String,
        interval: String,
    ): CollectorResult<YahooChartSeries> {
        val url =
            baseUrl.toHttpUrl().newBuilder()
                .addPathSegment(symbol)
                .addQueryParameter("range", range)
                .addQueryParameter("interval", interval)
                .build()
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()

        val response =
            try {
                httpClient.newCall(request).execute()
            } catch (e: IOException) {
                return CollectorResult.Failed(
                    FailureReason.NETWORK,
                    "yahoo chart request failed for $symbol: ${e.message}",
                    e,
                )
            }

        response.use { resp ->
            val body = resp.body?.string()

            // HTTP 상태가 1차 분류 신호다(429/5xx는 body가 JSON이 아닐 수 있음 — 00a §1은
            // 404+chart.error 조합만 실측했고 429 body 형태는 미실측). body가 파싱되면 그
            // chart.error로 사유를 더 구체화한다(문서화된 404 케이스).
            if (!resp.isSuccessful) {
                return classifyHttpFailure(symbol, resp, body)
            }
            if (body.isNullOrBlank()) {
                return CollectorResult.Failed(
                    FailureReason.PARSE_ERROR,
                    "empty yahoo chart response body for $symbol (HTTP ${resp.code})",
                )
            }
            return parseChartBody(symbol, body)
        }
    }

    private fun classifyHttpFailure(
        symbol: String,
        resp: Response,
        body: String?,
    ): CollectorResult.Failed {
        val parsedError =
            body?.let {
                runCatching { json.decodeFromString(YahooChartEnvelope.serializer(), it).chart.error }.getOrNull()
            }
        val reason =
            when {
                resp.code == HTTP_NOT_FOUND && parsedError != null -> FailureReason.NOT_FOUND
                resp.code == HTTP_TOO_MANY_REQUESTS -> FailureReason.RATE_LIMITED
                resp.code >= HTTP_SERVER_ERROR_FLOOR -> FailureReason.SERVER_ERROR
                else -> FailureReason.CLIENT_ERROR
            }
        val detail = parsedError?.let { ": ${it.code} ${it.description}" }.orEmpty()
        return CollectorResult.Failed(reason, "yahoo chart HTTP ${resp.code} for $symbol$detail")
    }

    @Suppress("ReturnCount") // 가드 절 스타일(파싱 실패 + 방어적 chart.error + 스키마 결손 2건 + 성공).
    private fun parseChartBody(
        symbol: String,
        body: String,
    ): CollectorResult<YahooChartSeries> {
        val envelope =
            try {
                json.decodeFromString(YahooChartEnvelope.serializer(), body)
            } catch (e: SerializationException) {
                return CollectorResult.Failed(
                    FailureReason.PARSE_ERROR,
                    "failed to parse yahoo chart response for $symbol: ${e.message}",
                    e,
                )
            }

        envelope.chart.error?.let { error ->
            // HTTP 200인데 chart.error가 있는 것은 00a 실측에 없는 조합 — 방어적으로만 처리.
            return CollectorResult.Failed(
                FailureReason.PARSE_ERROR,
                "yahoo chart error with HTTP 200 for $symbol: ${error.code} ${error.description}",
            )
        }

        val result =
            envelope.chart.result?.firstOrNull()
                ?: return CollectorResult.Failed(
                    FailureReason.PARSE_ERROR,
                    "yahoo chart response missing result[0] for $symbol",
                )
        val quote =
            result.indicators.quote.firstOrNull()
                ?: return CollectorResult.Failed(
                    FailureReason.PARSE_ERROR,
                    "yahoo chart response missing indicators.quote[0] for $symbol",
                )

        val bars =
            result.timestamp.mapIndexed { i, epochSeconds ->
                YahooBar(
                    asOf = Instant.ofEpochSecond(epochSeconds),
                    open = quote.open.getOrNull(i),
                    high = quote.high.getOrNull(i),
                    low = quote.low.getOrNull(i),
                    close = quote.close.getOrNull(i),
                    volume = quote.volume.getOrNull(i),
                )
            }
        return CollectorResult.Success(YahooChartSeries(symbol = result.meta.symbol, bars = bars))
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://query1.finance.yahoo.com/v8/finance/chart"
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val CONNECT_TIMEOUT_S = 15L
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR_FLOOR = 500

        fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                .build()

        /** `providers.yfinance.retry`를 assets에서 로드해 주입하는 조립 지점(`:krx KrxCollector.create`와 동일 패턴). */
        fun create(context: Context): YahooChartCollector = YahooChartCollector(RetryPolicy.fromYfinance(context))
    }
}
