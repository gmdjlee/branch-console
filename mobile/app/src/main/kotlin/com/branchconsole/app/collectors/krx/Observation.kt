package com.branchconsole.app.collectors.krx

import java.time.Instant

/**
 * KRX 수집 원시 관측치 (MT1-04c 자체 DTO — `:lake` 의존 없음, 배선은 MT1-06 소관).
 *
 * `configs/sources.yaml` 헤더 규칙("observed_at = 수집 시각, as_of = 데이터 기준 시점")을
 * 그대로 따른다. [asOf]는 이 서브태스크 시점에는 거래일 UTC 자정으로 근사한다 — MT1-06이
 * `pit/Visibility.kt`(§2.8 PIT 계약)와 대조해 확정할 때까지의 잠정치다.
 *
 * @property seriesId 시계열 식별자 (예: KRX 지수 티커 "1001", 또는 "kospi_investor_trading")
 * @property field 필드명 (예: "close", "trading_value")
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
