package com.branchconsole.app.collectors

import java.time.Instant

/**
 * 수집 원시 관측치 공통 DTO (MT1-04g 승격 — 이전에는 `collectors.krx.Observation`이었다,
 * `CollectOutcome.kt` KDoc이 예고한 대로 3어댑터 공통 타입으로 치환). `:lake` 의존 없음 —
 * `ObservationEntity`로의 매핑은 소비부([WarmupBackfillOrchestrator])가 담당한다.
 *
 * `configs/sources.yaml` 헤더 규칙("observed_at = 수집 시각, as_of = 데이터 기준 시점")을
 * 그대로 따른다.
 *
 * @property seriesId 시계열 식별자 (예: "^VIX", "KRW=X", KRX 지수 티커 "1001", 또는
 *   "kospi_investor_trading")
 * @property field 필드명 (예: "close", "trading_value", "value")
 * @property asOf 데이터 기준 시점 (UTC aware, K-05)
 * @property observedAt 수집 시각 (UTC aware, K-05)
 * @property source 수집 경로 라벨
 * @property value 관측값 (K-07: Double 고정)
 * @property lane 0=확정, 1=프리뷰 (D-17 §3 lane 개념, 기본은 확정)
 */
data class Observation(
    val seriesId: String,
    val field: String,
    val asOf: Instant,
    val observedAt: Instant,
    val source: String,
    val value: Double,
    val lane: Int = LANE_CONFIRMED,
) {
    companion object {
        const val LANE_CONFIRMED = 0
        const val LANE_PREVIEW = 1
    }
}
