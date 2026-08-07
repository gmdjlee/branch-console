package com.branchconsole.app.notif

/**
 * MT1-08a — `provisional_alert` 트리거 판정(D §3.9.1: "프리뷰 결과가 crit 수준 AND 억제 아님").
 * 순수 함수. [suppressed]는 [com.branchconsole.app.preview.PreviewResult.suppressed](raw
 * coverage < 임계 — D-23 §23.3-3)이고, 억제 상태면 무조건 미발신이다(브리프 "coverage 억제 시
 * 미발신"). "crit 수준"은 병합 지표(관측 + carry-forward, 이미 raw coverage와 별개로 존재하는
 * [com.branchconsole.app.preview.PreviewIndicatorState.severity] 필드) 중 하나라도
 * [com.branchconsole.engine.scoring.Scoring]의 crit 등급(severity>=3)에 도달했는지로 판정한다
 * — 확정 틱의 `any_crit`(엔진 상태기계 입력, `severities.values.any { it >= 3 }`)와 동일한
 * 정의를 프리뷰에 그대로 적용해, 이 모듈이 새로운 "composite 상 crit 눈금"을 발명하지 않는다.
 */
internal object ProvisionalAlertEvaluator {
    private const val SEVERITY_CRIT = 3

    fun shouldNotify(
        suppressed: Boolean,
        severities: Collection<Int?>,
    ): Boolean = !suppressed && severities.any { (it ?: 0) >= SEVERITY_CRIT }
}
