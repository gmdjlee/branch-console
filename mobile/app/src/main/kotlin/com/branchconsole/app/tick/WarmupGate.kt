package com.branchconsole.app.tick

import com.branchconsole.app.collectors.WarmupReport
import com.branchconsole.app.collectors.WarmupSeriesStatus
import com.branchconsole.app.collectors.WarmupStatus
import com.branchconsole.engine.config.IndicatorSpec
import com.branchconsole.engine.config.TransformParser

/**
 * MT1-06h 부트스트랩 게이트 — D-D4/M-45·46 채택안: 활성·수집 가능 지표 전부가 원계열 행 수
 * 요건([TransformParser.requiredRows], 코드 리터럴 0)을 채우기 전에는 확정 틱을 아예 만들지
 * 않는다(`tick_input` 행 0, docs/plans/M1_PLAN_D.md §7 D-D4·W-W1). "행이 있으나 부족"
 * ([WarmupStatus.INSUFFICIENT])은 "그 지표를 아예 못 받음"([WarmupStatus.NOT_COLLECTED]/
 * `MISSING`)과 구분되는 별개 개념이다 — 후자는 [ConfirmIndicatorRuntime]의 severity=null
 * (스테일·미가시)로만 표현되고, 전자는 이 게이트가 원계열 **행 수**(transform 이전)로 직접
 * 판정한다.
 *
 * MT1-04g가 만든 [WarmupReport]/[WarmupSeriesStatus]를 그대로 재사용한다(브리프 지시 —
 * WarmupReport.kt 자체 KDoc이 "fold 배선은 MT1-06 소관"이라고 이 지점을 예고했다). 이 게이트는
 * 지표별 **원계열**(indicator → primary series) 행 수를 확인하므로 `WarmupSeriesStatus.seriesId`
 * 자리에는 그 지표의 대표 원계열 id를 채운다(`vkospi_z`는 실제 사용 중인 계열).
 *
 * [ConfirmSeriesIds.ALWAYS_MISSING_INDICATORS]는 게이트 판정 대상에서 제외한다 — 영원히 0행일
 * 지표를 포함하면 게이트가 구조적으로 열리지 않는다(D-D4 "활성·**수집 가능**" 요건).
 */
internal object WarmupGate {
    fun check(
        ctx: ConfirmTickContext,
        specs: List<IndicatorSpec>,
        asOfCutoffMillis: Long = 0L,
    ): WarmupReport {
        val relevant = specs.filter { it.id !in ConfirmSeriesIds.ALWAYS_MISSING_INDICATORS }
        val series =
            relevant.map { spec ->
                val required = TransformParser.requiredRows(spec)
                val (seriesId, available) = rowCountFor(spec, ctx)
                WarmupSeriesStatus(
                    seriesId = seriesId,
                    status = if (available >= required) WarmupStatus.OK else WarmupStatus.INSUFFICIENT,
                    rows = available,
                    reason = "indicator '${spec.id}' requires $required rows, has $available",
                )
            }
        return WarmupReport(windowStart = asOfCutoffMillis, windowEnd = asOfCutoffMillis, series = series)
    }

    fun WarmupReport.isReady(): Boolean = series.all { it.status == WarmupStatus.OK }

    /** `vkospi_z`는 데이터 기반 폴백(K-02)이라 실측 VKOSPI가 있으면 그 행 수, 없으면 KOSPI
     * 유도 폴백 체인의 행 수를 본다 — [ConfirmIndicatorRuntime.buildVkospiZ]의 분기 판정과 동일
     * 조건("vkClose.isNotEmpty()"). 가드 절 스타일(분기 2개) — KrxCollector.ensureLoggedIn과
     * 동일 판단. @return (대표 원계열 id, 그 계열의 행 수) */
    @Suppress("ReturnCount")
    private fun rowCountFor(
        spec: IndicatorSpec,
        ctx: ConfirmTickContext,
    ): Pair<String, Int> {
        if (spec.id == "vkospi_z") {
            val vkospiRows = ctx.rowCount(ConfirmSeriesIds.VKOSPI, ConfirmSeriesIds.FIELD_CLOSE)
            if (vkospiRows > 0) return ConfirmSeriesIds.VKOSPI to vkospiRows
            val (fallbackSeries, fallbackField) = ConfirmSeriesIds.VKOSPI_FALLBACK_SERIES_FIELD
            return fallbackSeries to ctx.rowCount(fallbackSeries, fallbackField)
        }
        val (seriesId, field) = ConfirmSeriesIds.PRIMARY_SERIES_FIELD.getValue(spec.id)
        return seriesId to ctx.rowCount(seriesId, field)
    }
}
