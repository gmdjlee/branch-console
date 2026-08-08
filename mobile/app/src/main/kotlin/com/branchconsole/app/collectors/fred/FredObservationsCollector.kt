package com.branchconsole.app.collectors.fred

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
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit

/**
 * FRED `series/observations` 어댑터 (MT1-04b, 00a 저널 §9~§13 실측 계약 그대로).
 *
 * 대상 계열: `BAMLH0A0HYM2`·`T10Y2Y`(본계열) + `VIXCLS`·`SP500`(야후 폴백 미러, 00a §9 판정 —
 * `configs/sources.yaml providers.fred.series`에 반영). 어느 계열을 실제로 호출할지는 이
 * 어댑터가 정하지 않는다(SSOT는 sources.yaml의 목록, 배선은 후속 통합 소관) — [fetchObservations]는
 * 임의의 [seriesId]를 받는 범용 함수다.
 *
 * 결측 규약(00a §12.3, §13):
 * - 응답 배열에 아예 없는 날짜(주말 등)는 결측이 아니라 "그 날짜의 관측치가 원래 없음" — 이
 *   어댑터는 반환된 [FredObservationDto]만 순회하고, 빠진 날짜를 채우는 보간을 하지 않는다.
 * - `value == "."`는 명시적 결측으로 `null`로 변환한다(문자열 그대로 저장하지 않는다).
 * - `BAMLH0A0HYM2`가 공휴일에 전일값을 반복하는 것(00a §12.3-2)은 결측이 아니라 유효한 값으로
 *   그대로 저장한다 — 계열별 분기 없이 API가 반환한 값을 그대로 신뢰한다(추측 금지 원칙,
 *   transform 로직은 이 계층에서 건드리지 않는다). `delta_bp(lookback=5)`가 이 반복값을 창에
 *   포함하면 스프레드 변화량이 소폭 과소평가될 수 있음을 후속 구현자가 인지할 것.
 *
 * `realtime_start`/`realtime_end`는 계열별로 분기하지 않고 항상 호출 시점 날짜로 명시한다
 * (§12.4 — 일부 인기 계열에서는 vintage 캐시 함정이 재현되지 않았지만, 어떤 계열이 안전한지
 * 미리 알 수 없어 전 계열 공통 방어가 더 단순하고 안전하다는 판정).
 *
 * 실기기 S-0 발견 결함 수정 — "호출 시점 날짜"는 §9.1이 실측한 **FRED의 실제 '오늘'**이지
 * UTC가 아니다. `LocalDate.now(clock)`(UTC 고정)로 계산하면 UTC 날짜가 FRED의 날짜 롤오버보다
 * 앞서는 창에서 아직 오지 않은 날짜를 요청해 FRED가 400을 반환한다.
 *
 * **시간대는 America/Chicago로 실측 확정**(aaa R6 D-6 재판정 — 최초 가설이던 America/New_York
 * (ET)은 아래 E2에서 반증됨). Advisor 판별 실측(동일 유효 키·동일 요청 형태, `series_id=VIXCLS
 * &file_type=json&sort_order=desc&limit=1&realtime_start=X&realtime_end=X`):
 *
 * | 실측 | 호출 시각(UTC) | 현지 시각 | realtime | 응답 | 판정 |
 * |------|----------------|-----------|----------|------|------|
 * | E1 | 2026-08-08T03:30:57Z | ET 08-07 23:30 | 2026-08-08 | 400 | UTC 반증 |
 * | E1 | 2026-08-08T03:30:57Z | ET 08-07 23:30 | 2026-08-07 | 200 | — |
 * | E2 | 2026-08-08T04:06:10Z | ET 08-08 00:06 | 2026-08-08 | 400 | **ET 반증**(ET 오늘도 거부) |
 * | E3 | 2026-08-08T05:06:09Z | CT 08-08 00:06 | 2026-08-08 | 200 | CT 수용 — 롤오버는 04:06~05:06Z 사이,
 * 오프셋 UTC−5(CDT) = America/Chicago(세인트루이스 연준 소재지와 정합) |
 *
 * 교훈: **단일 시각 재현은 복수 시간대 가설을 무판별한다**(맨 처음 2026-08-08 03:30 UTC 재현
 * 하나만으로 America/New_York을 채택했던 것이 바로 그 오류 — UTC보다 하루 늦은 시간대라면
 * 어느 것이든 그 시각에서는 결과가 같아 보인다. 반증 가능한 여러 시각으로 좁혀야 한다).
 *
 * 잔여 한계(정직 명시): 이 실측은 여름(CDT, UTC−5) 1회 관측이다. 겨울(CST, UTC−6) 전환 시
 * 롤오버 시각이 달라지는 것은 `ZoneId.of("America/Chicago")`의 DST 처리에 위임했을 뿐 별도
 * 실측은 없다.
 */
class FredObservationsCollector(
    private val credentials: FredCredentialsProvider,
    private val retryPolicy: RetryPolicy,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param limit 최근 관측치 개수 상한(desc 정렬 기준).
     * @param observationStart/observationEnd 백필 범위 지정(§13). `BAMLH0A0HYM2`는 호출 시점
     *   기준 최근 3년 이전 구간을 요청하면 항상 빈 배열이 온다(§12.2, 오류가 아니다 — 그 이전
     *   구간은 lake 적재분에만 의존해야 한다).
     */
    suspend fun fetchObservations(
        seriesId: String,
        limit: Int = DEFAULT_LIMIT,
        sortOrder: String = "desc",
        observationStart: LocalDate? = null,
        observationEnd: LocalDate? = null,
    ): CollectorResult<FredSeriesObservations> =
        withRetry(retryPolicy) {
            attemptFetch(seriesId, limit, sortOrder, observationStart, observationEnd)
        }

    private fun buildUrl(
        seriesId: String,
        limit: Int,
        sortOrder: String,
        observationStart: LocalDate?,
        observationEnd: LocalDate?,
    ): HttpUrl {
        val today = LocalDate.now(clock.withZone(FRED_REALTIME_ZONE))
        val urlBuilder =
            baseUrl.toHttpUrl().newBuilder()
                .addPathSegments("series/observations")
                .addQueryParameter("series_id", seriesId)
                .addQueryParameter("api_key", credentials.apiKey())
                .addQueryParameter("file_type", "json")
                .addQueryParameter("sort_order", sortOrder)
                .addQueryParameter("limit", limit.toString())
                .addQueryParameter("realtime_start", today.toString())
                .addQueryParameter("realtime_end", today.toString())
        observationStart?.let { urlBuilder.addQueryParameter("observation_start", it.toString()) }
        observationEnd?.let { urlBuilder.addQueryParameter("observation_end", it.toString()) }
        return urlBuilder.build()
    }

    // 가드 절 스타일(네트워크 실패 + HTTP 오류 + 빈 바디 + 파싱 위임)이 ReturnCount(2) 기본
    // 상한보다 명확하다 — :krx KrxClient.kt의 @Suppress("LongParameterList") 선례와 동일 판단.
    @Suppress("ReturnCount")
    private fun attemptFetch(
        seriesId: String,
        limit: Int,
        sortOrder: String,
        observationStart: LocalDate?,
        observationEnd: LocalDate?,
    ): CollectorResult<FredSeriesObservations> {
        val url = buildUrl(seriesId, limit, sortOrder, observationStart, observationEnd)
        val request = Request.Builder().url(url).build()

        val response =
            try {
                httpClient.newCall(request).execute()
            } catch (e: IOException) {
                return CollectorResult.Failed(
                    FailureReason.NETWORK,
                    "fred observations request failed for $seriesId: ${e.message}",
                    e,
                )
            }

        response.use { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful) {
                return classifyHttpFailure(seriesId, resp)
            }
            if (body.isNullOrBlank()) {
                return CollectorResult.Failed(
                    FailureReason.PARSE_ERROR,
                    "empty fred observations response body for $seriesId",
                )
            }
            return parseObservationsBody(seriesId, body)
        }
    }

    private fun classifyHttpFailure(
        seriesId: String,
        resp: Response,
    ): CollectorResult.Failed {
        val reason =
            when {
                resp.code == HTTP_TOO_MANY_REQUESTS -> FailureReason.RATE_LIMITED
                resp.code >= HTTP_SERVER_ERROR_FLOOR -> FailureReason.SERVER_ERROR
                else -> FailureReason.CLIENT_ERROR
            }
        return CollectorResult.Failed(reason, "fred observations HTTP ${resp.code} for $seriesId")
    }

    @Suppress("ReturnCount") // 가드 절 스타일(파싱 실패 + 하위 파싱 실패 위임 + 성공).
    private fun parseObservationsBody(
        seriesId: String,
        body: String,
    ): CollectorResult<FredSeriesObservations> {
        val envelope =
            try {
                json.decodeFromString(FredObservationsEnvelope.serializer(), body)
            } catch (e: SerializationException) {
                return CollectorResult.Failed(
                    FailureReason.PARSE_ERROR,
                    "failed to parse fred observations response for $seriesId: ${e.message}",
                    e,
                )
            }

        val observations =
            when (val parsed = parseObservations(seriesId, envelope.observations)) {
                is CollectorResult.Success -> parsed.value
                is CollectorResult.Failed -> return parsed
            }
        return CollectorResult.Success(FredSeriesObservations(seriesId = seriesId, observations = observations))
    }

    /** 관측치 배열 전체를 파싱한다 — 하나라도 스키마를 벗어나면 [FailureReason.PARSE_ERROR]로 전체 실패시킨다. */
    private fun parseObservations(
        seriesId: String,
        dtos: List<FredObservationDto>,
    ): CollectorResult<List<FredObservation>> {
        val parsed = mutableListOf<FredObservation>()
        for (dto in dtos) {
            when (val result = parseObservation(seriesId, dto)) {
                is CollectorResult.Success -> parsed += result.value
                is CollectorResult.Failed -> return result
            }
        }
        return CollectorResult.Success(parsed)
    }

    @Suppress("ReturnCount") // 가드 절 스타일(날짜 파싱 실패 + 값 파싱 실패 + 성공) — 위 attemptFetch와 동일 판단.
    private fun parseObservation(
        seriesId: String,
        dto: FredObservationDto,
    ): CollectorResult<FredObservation> {
        val asOf =
            try {
                LocalDate.parse(dto.date)
            } catch (e: DateTimeParseException) {
                return CollectorResult.Failed(
                    FailureReason.PARSE_ERROR,
                    "invalid fred observation date '${dto.date}' for $seriesId: ${e.message}",
                    e,
                )
            }
        val value =
            if (dto.value == MISSING_VALUE_MARKER) {
                null
            } else {
                dto.value.toDoubleOrNull()
                    ?: return CollectorResult.Failed(
                        FailureReason.PARSE_ERROR,
                        "unparseable fred observation value '${dto.value}' on ${dto.date} for $seriesId",
                    )
            }
        return CollectorResult.Success(FredObservation(asOf = asOf, value = value))
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.stlouisfed.org/fred"
        private const val DEFAULT_LIMIT = 10
        private const val MISSING_VALUE_MARKER = "."
        private const val CONNECT_TIMEOUT_S = 15L
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR_FLOOR = 500

        /** FRED realtime(빈티지) 달력의 기준 시간대 — America/Chicago로 실측 확정(위 클래스
         * KDoc E1~E3 판별 실측 참조, aaa R6 D-6). UTC도 America/New_York도 아니다. */
        private val FRED_REALTIME_ZONE = ZoneId.of("America/Chicago")

        fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                .build()

        /**
         * `sources.yaml`에 `providers.fred.retry`가 아직 없어(00a 저널 §9~15는 지연·폴백
         * 판정만 다룸) `providers.yfinance.retry` 값을 명시적으로 빌려 쓴다 — `fred.retry`가
         * 추가되면 이 한 줄만 바꾸면 된다(`:krx KrxCollector.create`와 동일 조립 지점 패턴).
         */
        fun create(
            context: Context,
            credentials: FredCredentialsProvider,
        ): FredObservationsCollector = FredObservationsCollector(credentials, RetryPolicy.fromYfinance(context))
    }
}
