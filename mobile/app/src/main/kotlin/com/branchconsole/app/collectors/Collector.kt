package com.branchconsole.app.collectors

import java.time.LocalDate

/**
 * 수집기 공통 계약 (MT1-04g). `docs/plans/M1_PLAN_A.md` MT1-04 "공통 아키텍처" 절이 예고한
 * 어휘를, 웜업 백필 오케스트레이터([WarmupBackfillOrchestrator])가 실제로 3어댑터를 균일하게
 * 다뤄야 하는 시점에 승격한다.
 */
interface Collector {
    /** `configs/sources.yaml` provider 키와 동일 (예: "pykrx", "yfinance", "fred"). */
    val id: String

    /**
     * 이 수집기가 다루는 시계열 id 전체(정적, `collect()` 결과와 무관). [CollectOutcome.Failed]처럼
     * 개별 시계열 정보가 전혀 없는 결과에서도 웜업 리포트가 계열별 상태를 채울 수 있게 한다.
     */
    val expectedSeriesIds: List<String>

    /** [range]를 조회해 관측치를 반환한다. range 상한은 병목이 아니므로(00g §7 실측) 분할 호출 없이 단일 호출로 처리한다. */
    suspend fun collect(range: ClosedRange<LocalDate>): CollectOutcome
}
