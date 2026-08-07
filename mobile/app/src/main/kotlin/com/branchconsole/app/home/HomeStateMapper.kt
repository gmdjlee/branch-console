package com.branchconsole.app.home

import com.branchconsole.lake.RunLogEntity
import com.branchconsole.lake.TickInputEntity

private const val STATUS_FAILED = "failed"
private const val STATUS_CONFIG_ERROR = "config_error"
private const val STATUS_WARMUP_INSUFFICIENT = "WARMUP_INSUFFICIENT"
private const val FULL_COVERAGE = 1.0

/**
 * MT1-08b — 7상태 매핑의 유일한 결정 지점(순수 함수, IO 없음). 표시(렌더링)는 이 결과를
 * 소비만 한다(M1_PLAN_C.md §4.1 규칙 5 "degraded는 배지가 아니라 값이다"와 동일한 원칙 —
 * 판정은 도메인 계층, UI는 그 값을 그릴 뿐).
 *
 * 우선순위(위에서부터 먼저 성립하는 것을 채택):
 * 1. 최근 실행 자체가 실패 → [HomeState.ERROR]
 * 2. 확정 틱이 아직 하나도 없음 → 웜업 시도 흔적이 있으면 [HomeState.WARMUP], 없으면
 *    [HomeState.EMPTY]
 * 3. 최근 확정 틱이 캐치업 상한 절단 공백 행(`gap_reason` 존재) → [HomeState.GAP]
 * 4. 최근 확정 틱이 평가 불능(`composite=null`이고 gap도 아님, D-25 §3) → [HomeState.ERROR]
 * 5. 최근 프리뷰가 커버리지 미달로 억제됨 → [HomeState.SUPPRESSED]
 * 6. 최근 확정 틱의 커버리지가 100% 미만(부분 결측) → [HomeState.PARTIAL]
 * 7. 그 외 → [HomeState.NORMAL]
 */
internal object HomeStateMapper {
    // Guard-clause style (priority list above IS the control flow) -- same judgment as
    // KrxCollector.ensureLoggedIn/WarmupGate.rowCountFor elsewhere in this codebase: each branch
    // reads as one priority rule, restructuring into a single expression would obscure the order.
    @Suppress("ReturnCount")
    fun compute(
        lastTick: TickInputEntity?,
        lastRunLog: RunLogEntity?,
        previewSuppressed: Boolean?,
    ): HomeState {
        if (lastRunLog?.status == STATUS_FAILED || lastRunLog?.status == STATUS_CONFIG_ERROR) {
            return HomeState.ERROR
        }
        if (lastTick == null) {
            return if (lastRunLog?.status == STATUS_WARMUP_INSUFFICIENT) HomeState.WARMUP else HomeState.EMPTY
        }
        if (lastTick.gapReason != null) return HomeState.GAP
        if (lastTick.composite == null) return HomeState.ERROR
        if (previewSuppressed == true) return HomeState.SUPPRESSED
        if (lastTick.coverage < FULL_COVERAGE) return HomeState.PARTIAL
        return HomeState.NORMAL
    }
}
