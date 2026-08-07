package com.branchconsole.app.collectors

import android.content.Context
import kotlinx.coroutines.delay
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/**
 * MT1-04a/04b 공통 수집 결과 타입.
 *
 * 브리프 규율: 어댑터는 예외를 전파하지 않는다 — 전체 틱 실패를 막기 위해 지표별 결측/실패를
 * 이 sealed interface로 명시한다. `:lake`의 정식 observation 레코드
 * (observed_at/as_of/source/raw, `configs/sources.yaml` 공통 규칙)로의 매핑은 MT1-06 배선
 * 소관이며, 이 타입은 어댑터 자체 DTO다(`:lake`에 직접 의존하지 않는다 — 병렬 작업 중인 모듈).
 */
sealed interface CollectorResult<out T> {
    data class Success<T>(val value: T) : CollectorResult<T>

    data class Failed(
        val reason: FailureReason,
        val message: String,
        val cause: Throwable? = null,
    ) : CollectorResult<Nothing>
}

/**
 * 실패 사유 분류. [retriable]인 사유만 [RetryPolicy] 루프가 재시도한다.
 *
 * - [NOT_FOUND]: 야후 HTTP 404 + `chart.error.code == "Not Found"`(00a 저널 §1) — 심볼
 *   상장폐지 등, 재시도 무의미.
 * - [RATE_LIMITED]: HTTP 429.
 * - [SERVER_ERROR]: HTTP 5xx.
 * - [CLIENT_ERROR]: 위 셋에 해당하지 않는 그 외 비성공 HTTP 코드(예: FRED의 잘못된
 *   api_key/series_id — 00a 저널이 정확한 오류 바디 형태를 실측하지 않아 코드로만 분류,
 *   추측 금지 원칙).
 * - [NETWORK]: `IOException`(타임아웃 포함 — `SocketTimeoutException`은 `IOException`
 *   하위 타입이라 별도 분류 불필요).
 * - [PARSE_ERROR]: 응답 본문이 예상 스키마와 다름(JSON 파싱 실패, 필수 블록 부재 등).
 * - [DISABLED]: 폴백 경로가 설계상 비활성 상태(Stooq, 00a §3~4).
 */
enum class FailureReason(val retriable: Boolean) {
    NOT_FOUND(retriable = false),
    RATE_LIMITED(retriable = true),
    SERVER_ERROR(retriable = true),
    CLIENT_ERROR(retriable = false),
    NETWORK(retriable = true),
    PARSE_ERROR(retriable = false),
    DISABLED(retriable = false),
}

/**
 * 재시도 정책. 값은 assets(SSOT)에서만 온다 — 코드에 하드코딩하지 않는다(CLAUDE.md §1).
 * `attempts`가 `backoffMs.size`보다 많으면 마지막 시도는 대기 없이 종료한다.
 */
data class RetryPolicy(
    val attempts: Int,
    val backoffMs: List<Long>,
) {
    init {
        require(attempts >= 1) { "attempts must be >= 1, was $attempts" }
        require(backoffMs.size >= attempts - 1) {
            "backoffMs must have at least attempts-1 entries, was ${backoffMs.size} for attempts=$attempts"
        }
    }

    companion object {
        private const val ASSET_PATH = "configs/sources.yaml"
        private const val MILLIS_PER_SECOND = 1000L

        /**
         * `providers.yfinance.retry`(`attempts`/`backoff_s`, 초 단위)를 assets에서 읽는다
         * (`:krx` `KrxRateLimitConfig`와 동일한 로딩 경로 — `syncConfigs` 산출물,
         * `ConfigsManifestJvmTest`와 동일). FRED provider 블록에는 아직 `retry` 키가 없어
         * ([FredObservationsCollector]) 이 값을 명시적으로 빌려 쓴다(주석이 아니라 코드로 —
         * `sources.yaml`에 `fred.retry`가 생기면 이 호출부만 바꾸면 된다). 값 부재 시 조용한
         * 기본값 없이 예외로 실패한다.
         */
        fun fromYfinance(context: Context): RetryPolicy {
            val root =
                context.assets.open(ASSET_PATH).use {
                    Load(LoadSettings.builder().build()).loadFromInputStream(it)
                }
            val providers = (root as? Map<*, *>)?.get("providers") as? Map<*, *>
            val retry = (providers?.get("yfinance") as? Map<*, *>)?.get("retry") as? Map<*, *>
            val attempts =
                (retry?.get("attempts") as? Number)?.toInt()
                    ?: error("providers.yfinance.retry.attempts missing from $ASSET_PATH")
            val backoffS =
                (retry["backoff_s"] as? List<*>)
                    ?: error("providers.yfinance.retry.backoff_s missing from $ASSET_PATH")
            val backoffMs = backoffS.map { (it as Number).toLong() * MILLIS_PER_SECOND }
            return RetryPolicy(attempts, backoffMs)
        }
    }
}

/**
 * 야후·FRED 어댑터 공통 재시도 루프. `:krx` `KrxClient.post()`와 동일한 인덱싱 규약을 쓴다 —
 * `attempt`(0-based)마다 [block]을 실행하고, 실패가 [FailureReason.retriable]이면서 마지막
 * 시도가 아닐 때만 `backoffMs[attempt]`만큼 대기 후 재시도한다(마지막 backoff 값은 정책상
 * 항상 미사용 — sources.yaml의 `attempts`/`backoff_s` 배열 길이를 그대로 반영하기 위해 남겨둔
 * 선례를 따른다). 재시도 불가 사유는 즉시 반환한다.
 */
@Suppress("ReturnCount") // 조기 성공 반환 + 재시도 불가 조기 반환 + 최종 반환 — 가드 절 스타일.
internal suspend fun <T> withRetry(
    policy: RetryPolicy,
    block: suspend (attempt: Int) -> CollectorResult<T>,
): CollectorResult<T> {
    var last: CollectorResult.Failed = CollectorResult.Failed(FailureReason.NETWORK, "no attempt executed")
    repeat(policy.attempts) { attempt ->
        when (val outcome = block(attempt)) {
            is CollectorResult.Success -> return outcome
            is CollectorResult.Failed -> {
                last = outcome
                val hasMoreAttempts = attempt < policy.attempts - 1
                if (outcome.reason.retriable && hasMoreAttempts) {
                    delay(policy.backoffMs[attempt])
                } else {
                    return outcome
                }
            }
        }
    }
    return last
}
