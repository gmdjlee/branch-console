package com.branchconsole.app.collectors

/**
 * 수집 결과 3분류 (MT1-04g 승격). 이전에는 `collectors.krx.CollectOutcome` — KRX 전용 로컬
 * 타입이었고, 그 파일의 KDoc이 "04g가 실제로 착수되면 공통 인터페이스로 승격/치환 대상"이라고
 * 예고했다. 지금이 그 시점 — [KrxCollector][com.branchconsole.app.collectors.krx.KrxCollector],
 * [YahooCollector][com.branchconsole.app.collectors.yahoo.YahooCollector],
 * [FredCollector][com.branchconsole.app.collectors.fred.FredCollector] 3어댑터가 전부 이
 * 타입으로 [Collector.collect] 결과를 표현한다(3어댑터 정합).
 */
sealed interface CollectOutcome {
    data class Ok(val rows: List<Observation>) : CollectOutcome

    data class Partial(val rows: List<Observation>, val failures: List<SeriesFailure>) : CollectOutcome

    data class Failed(val reason: CollectFailureReason) : CollectOutcome
}

/** 시계열 하나의 수집 실패(부분 실패 흡수 단위). */
data class SeriesFailure(val seriesId: String, val reason: CollectFailureReason)

/**
 * [CollectOutcome] 레벨 수집 실패 사유 — 3어댑터 공통 어휘.
 *
 * 이름이 `FailureReason`이 아니라 `CollectFailureReason`인 이유: 같은 패키지의
 * `CollectorResult.kt`에 이미 HTTP 시도 단위 재시도 분류용 [FailureReason] enum이 있다(야후/FRED
 * 어댑터 내부, [RetryPolicy] 소비). 두 타입은 계층이 다르다 — 이쪽은 "시계열 하나를 못 받았다"는
 * 결과, 저쪽은 "이 HTTP 시도가 왜 실패했고 재시도할지"라는 판단. 승격 과정에서 억지로 하나로
 * 합치면(예: EmptyOnTradingDay를 HTTP enum에 끼워 넣기) 어느 쪽도 자기 계층에 맞지 않게 된다 —
 * 대신 [Http]로 감싸 양쪽을 다 보존한다.
 */
sealed class CollectFailureReason(val message: String) {
    /**
     * K-19: 사전 판정된 거래일(캘린더 성공 응답 기준)에 빈 응답이 온 경우(KRX 전용 — 휴장으로
     * 뭉개지 않고 재시도 대상으로 분류한다, A-8 반영).
     */
    data class EmptyOnTradingDay(val date: String) : CollectFailureReason("empty response on trading day $date")

    data class AuthenticationRequired(val cause: Throwable? = null) :
        CollectFailureReason("authentication required")

    data class Network(val cause: Throwable) : CollectFailureReason("network error: ${cause.message}")

    data object NotConfigured : CollectFailureReason("credentials not configured")

    /** 야후/FRED의 HTTP 레벨 [FailureReason] 분류를 그대로 감싼다(뭉개지 않고 보존). */
    data class Http(val httpReason: FailureReason, val detail: String) :
        CollectFailureReason("http failure ($httpReason): $detail")
}

/** [CollectorResult.Failed]를 공통 [CollectFailureReason] 어휘로 감싼다(야후·FRED 어댑터 공용). */
internal fun CollectorResult.Failed.toCollectFailureReason(): CollectFailureReason {
    return CollectFailureReason.Http(reason, message)
}
