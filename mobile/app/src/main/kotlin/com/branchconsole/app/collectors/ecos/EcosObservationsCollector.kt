package com.branchconsole.app.collectors.ecos

import android.content.Context
import com.branchconsole.app.collectors.CollectorResult
import com.branchconsole.app.collectors.FailureReason
import com.branchconsole.app.collectors.RetryPolicy
import com.branchconsole.app.collectors.withRetry
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

private val ECOS_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")

/**
 * ECOS `StatisticSearch` 어댑터 (MT1-04d, 00b 저널 §7.3~§7.5 실측 계약 그대로).
 *
 * 대상 통계표는 `817Y002`(시장금리, 일별) 고정 — 호출부([EcosCollector.create])가
 * [EcosSeriesConfig]로 SSOT(`configs/indicators.yaml`)에서 읽어 주입한다(item_code를 이 클래스가
 * 하드코딩하지 않는다). authkey는 쿼리 파라미터가 아니라 URL **경로** 세그먼트에 실린다(FRED와의
 * 의도적 차이, §7.5) — [attemptFetch]가 URL 전체를 로그·예외 메시지에 남기지 않는 이유다.
 *
 * 결측 규약(00b §7.3, FRED와 다름): 응답에 없는 날짜(주말 등)는 "그 날짜의 행 자체가 없음"이고,
 * 값 필드가 `"."` 같은 결측 마커로 오는 일이 없다 — 이 어댑터는 반환된 행만 순회하고 달력
 * 보간을 하지 않는다. `DATA_VALUE`가 빈 문자열/파싱 불가면(§7.5 "방어적 코딩, 실제 발생 사례
 * 아님") 그 **행 하나만** 결측으로 건너뛴다 — [com.branchconsole.app.collectors.fred.FredObservationsCollector]가
 * 값 하나라도 파싱 실패하면 전체 요청을 [FailureReason.PARSE_ERROR]로 실패시키는 것과 의도적으로
 * 다른 정책이다(브리프 지시, "결측 = 행 생략" 규약과 정합).
 */
class EcosObservationsCollector(
    private val credentials: EcosCredentialsProvider,
    private val retryPolicy: RetryPolicy,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** @param itemCode `configs/indicators.yaml`의 `item_codes.corp_aa3y`/`item_codes.ktb_3y` 값. */
    suspend fun fetchSeries(
        itemCode: String,
        statCode: String,
        start: LocalDate,
        end: LocalDate,
    ): CollectorResult<EcosSeriesObservations> = withRetry(retryPolicy) { attemptFetch(itemCode, statCode, start, end) }

    private fun buildUrl(
        itemCode: String,
        statCode: String,
        start: LocalDate,
        end: LocalDate,
    ): HttpUrl {
        // n(end_idx): 실제 존재하는 영업일 수의 상한이면 충분 — 달력일 수(+1 여유)를 쓴다.
        // ECOS가 요구하는 것은 "충분히 큰 페이지 크기"이지 SSOT 임계값이 아니다(CLAUDE.md §1
        // 범위 밖 — FredObservationsCollector.FETCH_LIMIT과 동일 분류).
        val n = ChronoUnit.DAYS.between(start, end) + 1
        return baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("StatisticSearch")
            .addPathSegment(credentials.apiKey())
            .addPathSegment("json")
            .addPathSegment("kr")
            .addPathSegment("1")
            .addPathSegment(n.toString())
            .addPathSegment(statCode)
            .addPathSegment("D")
            .addPathSegment(start.format(ECOS_DATE_FMT))
            .addPathSegment(end.format(ECOS_DATE_FMT))
            .addPathSegment(itemCode)
            .build()
    }

    // 가드 절 스타일 — FredObservationsCollector.attemptFetch와 동일 판단.
    @Suppress("ReturnCount")
    private fun attemptFetch(
        itemCode: String,
        statCode: String,
        start: LocalDate,
        end: LocalDate,
    ): CollectorResult<EcosSeriesObservations> {
        val url = buildUrl(itemCode, statCode, start, end)
        val request = Request.Builder().url(url).build()

        val response =
            try {
                httpClient.newCall(request).execute()
            } catch (e: IOException) {
                // authkey가 URL 경로에 있으므로(§7.5) 메시지에 URL을 넣지 않는다 — K-17.
                return CollectorResult.Failed(
                    FailureReason.NETWORK,
                    "ecos search request failed for $itemCode: ${e.message}",
                    e,
                )
            }

        response.use { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful) {
                return classifyHttpFailure(itemCode, resp)
            }
            if (body.isNullOrBlank()) {
                return CollectorResult.Failed(
                    FailureReason.PARSE_ERROR,
                    "empty ecos search response body for $itemCode",
                )
            }
            return parseSearchBody(itemCode, body)
        }
    }

    private fun classifyHttpFailure(
        itemCode: String,
        resp: Response,
    ): CollectorResult.Failed {
        val reason =
            when {
                resp.code == HTTP_TOO_MANY_REQUESTS -> FailureReason.RATE_LIMITED
                resp.code >= HTTP_SERVER_ERROR_FLOOR -> FailureReason.SERVER_ERROR
                else -> FailureReason.CLIENT_ERROR
            }
        return CollectorResult.Failed(reason, "ecos search HTTP ${resp.code} for $itemCode")
    }

    // 가드 절 스타일(파싱 실패 + RESULT 오류 봉투 + 성공) — FredObservationsCollector와 동일 판단.
    @Suppress("ReturnCount")
    private fun parseSearchBody(
        itemCode: String,
        body: String,
    ): CollectorResult<EcosSeriesObservations> {
        val envelope =
            try {
                json.decodeFromString(EcosSearchEnvelope.serializer(), body)
            } catch (e: SerializationException) {
                return CollectorResult.Failed(
                    FailureReason.PARSE_ERROR,
                    "failed to parse ecos search response for $itemCode: ${e.message}",
                    e,
                )
            }

        // ponytail: RESULT 오류 코드 세분화(예: "정상이나 데이터 없음" vs 실제 오류)는 실측으로
        // 재현하지 못했다(00b §7.5) — 지금은 전부 CLIENT_ERROR 한 버킷으로 묶는다. 실제 "무데이터"
        // 응답이 이 형태로 온다는 사실이 확인되면 그때 Ok(emptyList())로 세분화한다.
        val resultEnvelope = envelope.result
        if (resultEnvelope != null) {
            return CollectorResult.Failed(
                FailureReason.CLIENT_ERROR,
                "ecos RESULT ${resultEnvelope.code} for $itemCode: ${resultEnvelope.message}",
            )
        }
        val searchBody =
            envelope.statisticSearch ?: return CollectorResult.Failed(
                FailureReason.PARSE_ERROR,
                "ecos response has neither StatisticSearch nor RESULT for $itemCode",
            )

        val observations = searchBody.row.mapNotNull { parseRow(it) }
        return CollectorResult.Success(EcosSeriesObservations(itemCode = itemCode, observations = observations))
    }

    /** 파싱 불가 행은 결측으로 건너뛴다(null 반환) — 전체 실패로 만들지 않는다(§7.5, 클래스 KDoc).
     * `runCatching`으로 값을 다루므로 SwallowedException 대상이 아니다(ProductionConfirmTickWorker의
     * 동일 판단 — catch 절이 아니라 값으로 명시적으로 버린다). 가드 절 스타일(날짜 파싱 실패 +
     * 값 파싱 실패 + 성공) — FredObservationsCollector.parseObservation과 동일 판단. */
    @Suppress("ReturnCount")
    private fun parseRow(dto: EcosRowDto): EcosObservation? {
        val asOf = runCatching { LocalDate.parse(dto.time, ECOS_DATE_FMT) }.getOrNull() ?: return null
        val value = dto.dataValue.toDoubleOrNull() ?: return null
        return EcosObservation(asOf = asOf, value = value)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://ecos.bok.or.kr/api"
        private const val CONNECT_TIMEOUT_S = 15L
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR_FLOOR = 500

        fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                .build()

        /** `providers.yfinance.retry`를 빌려 쓴다 — `sources.yaml`에 `ecos.retry`가 없다
         * (`FredObservationsCollector.create`와 동일 판단·동일 근거). */
        fun create(
            context: Context,
            credentials: EcosCredentialsProvider,
        ): EcosObservationsCollector = EcosObservationsCollector(credentials, RetryPolicy.fromYfinance(context))
    }
}
