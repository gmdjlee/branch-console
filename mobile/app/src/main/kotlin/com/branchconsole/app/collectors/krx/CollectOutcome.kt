package com.branchconsole.app.collectors.krx

/**
 * KRX 수집 결과 3분류. `docs/plans/M1_PLAN_A.md`의 MT1-04 공통 아키텍처(04g, 이 시점 미착수)가
 * 정의하는 `Collector`/`CollectOutcome` 어휘를 미리 채택한다 — 04g가 실제로 착수되면 이 타입은
 * 공통 인터페이스로 승격/치환 대상이다. 지금은 `:app` 로컬 타입으로 [KrxCollector] 전용이다.
 */
sealed interface CollectOutcome {
    data class Ok(val rows: List<Observation>) : CollectOutcome

    data class Partial(val rows: List<Observation>, val failures: List<SeriesFailure>) : CollectOutcome

    data class Failed(val reason: FailureReason) : CollectOutcome
}

/** 시계열 하나의 수집 실패(부분 실패 흡수 단위). */
data class SeriesFailure(val seriesId: String, val reason: FailureReason)

/** 수집 실패 사유. */
sealed class FailureReason(val message: String) {
    /**
     * K-19: 사전 판정된 거래일(캘린더 성공 응답 기준)에 빈 응답이 온 경우.
     * 휴장으로 뭉개지 않고 재시도 대상으로 분류한다(A-8 반영).
     */
    data class EmptyOnTradingDay(val date: String) : FailureReason("empty response on trading day $date")

    data class AuthenticationRequired(val cause: Throwable? = null) : FailureReason("krx authentication required")

    data class Network(val cause: Throwable) : FailureReason("krx network error: ${cause.message}")

    data object NotConfigured : FailureReason("krx credentials not configured")
}
