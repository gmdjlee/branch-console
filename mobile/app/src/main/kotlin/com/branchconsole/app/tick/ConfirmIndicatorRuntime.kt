// TooManyFunctions: D-01 활성 지표 13종이 각자 정확히 1개의 빌더 함수를 갖는 고정 열거다
// (ParityEngine.kt의 13종 빌더와 1:1) — 파일을 인위적으로 쪼개면 "이 파일이 ParityEngine.kt를
// 그대로 미러링한다"는 대조 가능성만 해친다. 지표 수가 늘어 유지보수가 실제로 어려워지면 그때
// 지표군별 파일 분리를 재고한다.
@file:Suppress("TooManyFunctions")

package com.branchconsole.app.tick

import com.branchconsole.engine.config.HyLevelBoost
import com.branchconsole.engine.config.IndicatorSpec
import com.branchconsole.engine.config.TransformParser
import com.branchconsole.engine.config.UsdkrwIntradayForce
import com.branchconsole.engine.indicators.Vkospi
import com.branchconsole.engine.pit.KnownSeries
import com.branchconsole.engine.pit.Visibility
import com.branchconsole.engine.scoring.Modifiers
import com.branchconsole.engine.scoring.Scoring
import com.branchconsole.engine.transforms.RollingTransforms
import com.branchconsole.engine.transforms.SeriesAlign
import com.branchconsole.engine.transforms.Transforms
import java.time.Duration
import java.time.Instant

/**
 * MT1-06c — 확정 틱 지표 배선의 프로덕션 이식. 정본은
 * `mobile/engine/src/test/kotlin/.../parity/ParityEngine.kt`(BT-05 패리티 참조 구현) — 계산
 * 로직(가시성·transform 호출 순서·severity 판정)은 **문자 그대로 동일**하게 유지한다. 이 파일이
 * 그 파일과 다른 부분은 정확히 하나: 원계열 조회가 [ConfirmTickContext](DB 프리페치)를 쓰고,
 * 계열id가 프로덕션 collectors의 실제 적재 문자열([ConfirmSeriesIds])이라는 것뿐이다
 * (docs/plans/M1_PLAN_D.md §2.4.3 "_BUILDERS 이식 규율" — 계산 자체는 재구현하지 않는다).
 *
 * `:engine`은 이 파일이 아는 지표-계열 매핑을 모른다(무의존 원칙 유지, M1_PLAN_FINAL.md §2
 * "지표→계열 매핑... :engine 무의존 원칙 유지") — 두 사본(엔진 테스트의 픽스처 버전 + 여기의
 * 프로덕션 버전)의 동기화는 수동 대조로 유지한다(BT-05 패리티가 계산 결과의 등가성을 증명하는
 * 회귀 게이트다).
 */
internal sealed interface Runtime {
    fun primaryKnown(): KnownSeries? = null

    data object AlwaysNone : Runtime

    data class Simple(val known: KnownSeries) : Runtime {
        override fun primaryKnown() = known
    }

    data class CombineMax(val knownA: KnownSeries, val knownB: KnownSeries) : Runtime

    data class HyOas(val known: KnownSeries, val levelByDate: Map<java.time.LocalDate, Double>) : Runtime {
        override fun primaryKnown() = known
    }

    data class Usdkrw(
        val known: KnownSeries,
        val highByDate: Map<java.time.LocalDate, Double>,
        val lowByDate: Map<java.time.LocalDate, Double>,
        val prevCloseByDate: Map<java.time.LocalDate, Double>,
    ) : Runtime {
        override fun primaryKnown() = known
    }
}

internal data class Resolved(val severity: Int?, val isExtreme: Boolean)

/** `com.branchconsole.engine.config.asFlatThresholds`와 동형이지만 그 함수는 `:engine` 모듈
 * 내부(`internal`) 전용이라 모듈 경계를 넘어 여기서 쓸 수 없다 — 3줄짜리 순수 변환이라
 * `:engine`의 공개 API 표면을 넓히기보다 여기서 다시 쓰는 쪽을 택한다(ponytail: 재노출이
 * 필요해지는 두 번째 소비처가 생기면 그때 `:engine`에서 public으로 승격한다). */
private fun Map<String, Any?>.asFlatThresholds(): Map<String, Double> = mapValues { (_, v) -> (v as Number).toDouble() }

private fun kwInt(
    transform: String,
    callName: String,
    key: String,
): Int = (TransformParser.parseCallKwargs(callName, transform).getValue(key) as Number).toInt()

private fun buildVixLevelZ(
    spec: IndicatorSpec,
    ctx: ConfirmTickContext,
): Runtime {
    val (dates, close) = ctx.series(ConfirmSeriesIds.VIX, ConfirmSeriesIds.FIELD_CLOSE)
    val value = Transforms.zscore(close, kwInt(spec.transform, "zscore", "window"))
    return Runtime.Simple(ctx.known(dates, value, listOf(ConfirmSeriesIds.VIX)))
}

private fun buildVixTermStructure(ctx: ConfirmTickContext): Runtime {
    val (datesA, a) = ctx.series(ConfirmSeriesIds.VIX, ConfirmSeriesIds.FIELD_CLOSE)
    val (datesB, b) = ctx.series(ConfirmSeriesIds.VIX3M, ConfirmSeriesIds.FIELD_CLOSE)
    val aligned = SeriesAlign.unionAlign(datesA, a, datesB, b)
    val value = Transforms.ratio(aligned.a, aligned.b)
    return Runtime.Simple(ctx.known(aligned.dates, value, listOf(ConfirmSeriesIds.VIX, ConfirmSeriesIds.VIX3M)))
}

private fun buildMoveIndexZ(
    spec: IndicatorSpec,
    ctx: ConfirmTickContext,
): Runtime {
    val (dates, close) = ctx.series(ConfirmSeriesIds.MOVE, ConfirmSeriesIds.FIELD_CLOSE)
    val value = Transforms.zscore(close, kwInt(spec.transform, "zscore", "window"))
    return Runtime.Simple(ctx.known(dates, value, listOf(ConfirmSeriesIds.MOVE)))
}

private fun buildHyOasDelta(
    spec: IndicatorSpec,
    ctx: ConfirmTickContext,
): Runtime {
    val (dates, level) = ctx.series(ConfirmSeriesIds.HY_OAS, ConfirmSeriesIds.FIELD_VALUE)
    val delta = Transforms.deltaBp(level, kwInt(spec.transform, "delta_bp", "lookback"))
    val levelByDate = dates.zip(level.toList()).toMap()
    return Runtime.HyOas(ctx.known(dates, delta, listOf(ConfirmSeriesIds.HY_OAS)), levelByDate)
}

private fun buildDxyZ(
    spec: IndicatorSpec,
    ctx: ConfirmTickContext,
): Runtime {
    val (dates, close) = ctx.series(ConfirmSeriesIds.DXY, ConfirmSeriesIds.FIELD_CLOSE)
    val kwargs = TransformParser.parseCallKwargs("zscore", spec.transform)
    val window = (kwargs.getValue("window") as Number).toInt()
    val absolute = kwargs["absolute"] as? Boolean ?: false
    val value = Transforms.zscore(Transforms.pctChange5d(close), window, absolute)
    return Runtime.Simple(ctx.known(dates, value, listOf(ConfirmSeriesIds.DXY)))
}

private fun buildUst2s10sMove(
    spec: IndicatorSpec,
    ctx: ConfirmTickContext,
): Runtime {
    val (dates, level) = ctx.series(ConfirmSeriesIds.UST_2S10S, ConfirmSeriesIds.FIELD_VALUE)
    val value = Transforms.absValue(Transforms.deltaBp(level, kwInt(spec.transform, "delta_bp", "lookback")))
    return Runtime.Simple(ctx.known(dates, value, listOf(ConfirmSeriesIds.UST_2S10S)))
}

private fun buildSpxDrawdownMomentum(
    spec: IndicatorSpec,
    ctx: ConfirmTickContext,
): Runtime {
    val (dates, close) = ctx.series(ConfirmSeriesIds.GSPC, ConfirmSeriesIds.FIELD_CLOSE)
    val dd = Transforms.drawdownFromHigh(close, kwInt(spec.transform, "drawdown_from_high", "window"))
    val nz = Transforms.negZscore(Transforms.pctChange5d(close), kwInt(spec.transform, "neg_zscore", "window"))
    val knownDd = ctx.known(dates, dd, listOf(ConfirmSeriesIds.GSPC))
    val knownNz = ctx.known(dates, nz, listOf(ConfirmSeriesIds.GSPC))
    return Runtime.CombineMax(knownDd, knownNz)
}

private fun buildGlobalCorrBreak(
    spec: IndicatorSpec,
    ctx: ConfirmTickContext,
): Runtime {
    val (spxDates, spxClose) = ctx.series(ConfirmSeriesIds.GSPC, ConfirmSeriesIds.FIELD_CLOSE)
    val (kospiDates, kospiClose) = ctx.series(ConfirmSeriesIds.KOSPI, ConfirmSeriesIds.FIELD_CLOSE)
    val retSpx = Transforms.pctChange1d(spxClose)
    val retKospi = Transforms.pctChange1d(kospiClose)
    val retSpxOnKr = SeriesAlign.alignToFfillCausal(spxDates, retSpx, kospiDates)
    val corr = RollingTransforms.rollingCorr(retKospi, retSpxOnKr, kwInt(spec.transform, "rolling_corr", "window"))
    val meanCorr = RollingTransforms.rollingMeanCorr(corr, kwInt(spec.transform, "rolling_mean_corr", "window"))
    val value = Transforms.absValue(DoubleArray(corr.size) { i -> corr[i] - meanCorr[i] })
    return Runtime.Simple(ctx.known(kospiDates, value, listOf(ConfirmSeriesIds.GSPC, ConfirmSeriesIds.KOSPI)))
}

private fun buildVkospiZ(
    spec: IndicatorSpec,
    ctx: ConfirmTickContext,
): Runtime {
    val (vkDates, vkClose) = ctx.series(ConfirmSeriesIds.VKOSPI, ConfirmSeriesIds.FIELD_CLOSE)
    val (kospiDates, kospiClose) = ctx.series(ConfirmSeriesIds.KOSPI, ConfirmSeriesIds.FIELD_CLOSE)
    val zWindow = kwInt(spec.transform, "zscore", "window")
    val fbWindow = TransformParser.parseFallbackWindow(spec.source["fallback"] as String)
    val value = Vkospi.vkospiZ(vkClose, kospiClose, zWindow, fbWindow)
    val (dates, inputs) =
        if (vkClose.isNotEmpty()) {
            vkDates to listOf(ConfirmSeriesIds.VKOSPI)
        } else {
            kospiDates to listOf(ConfirmSeriesIds.KOSPI)
        }
    return Runtime.Simple(ctx.known(dates, value, inputs))
}

private fun buildKospiDrawdown(
    spec: IndicatorSpec,
    ctx: ConfirmTickContext,
): Runtime {
    val (dates, close) = ctx.series(ConfirmSeriesIds.KOSPI, ConfirmSeriesIds.FIELD_CLOSE)
    val value = Transforms.drawdownFromHigh(close, kwInt(spec.transform, "drawdown_from_high", "window"))
    return Runtime.Simple(ctx.known(dates, value, listOf(ConfirmSeriesIds.KOSPI)))
}

private fun buildForeignNetSellKospi(
    spec: IndicatorSpec,
    ctx: ConfirmTickContext,
): Runtime {
    val (dates, netBuy) = ctx.series(ConfirmSeriesIds.KOSPI_INVESTOR, ConfirmSeriesIds.FIELD_FOREIGN_NET_BUY_VALUE)
    val rolled = RollingTransforms.rollingSum(netBuy, kwInt(spec.transform, "rolling_sum", "window"))
    val value = Transforms.negZscore(rolled, kwInt(spec.transform, "neg_zscore", "window"))
    return Runtime.Simple(ctx.known(dates, value, listOf(ConfirmSeriesIds.KOSPI_INVESTOR)))
}

private fun buildKospiVolumeDistribution(
    spec: IndicatorSpec,
    ctx: ConfirmTickContext,
): Runtime {
    val (dates, close) = ctx.series(ConfirmSeriesIds.KOSPI, ConfirmSeriesIds.FIELD_CLOSE)
    val (_, tradingValue) = ctx.series(ConfirmSeriesIds.KOSPI, ConfirmSeriesIds.FIELD_TRADING_VALUE)
    val gatedKwargs = TransformParser.parseCallKwargs("gated", spec.transform)
    val (varName, op, threshold) = TransformParser.parseGate(gatedKwargs.getValue("gate") as String)
    check(varName == "daily_return") { "unexpected gate variable for kospi_volume_distribution: $varName" }
    val mask = RollingTransforms.gateMask(Transforms.pctChange1d(close), op, threshold)
    val z = Transforms.zscore(tradingValue, kwInt(spec.transform, "zscore", "window"))
    val value = RollingTransforms.gated(z, mask)
    return Runtime.Simple(ctx.known(dates, value, listOf(ConfirmSeriesIds.KOSPI)))
}

private fun buildUsdkrwZ(
    spec: IndicatorSpec,
    ctx: ConfirmTickContext,
): Runtime {
    val (dates, close) = ctx.series(ConfirmSeriesIds.USDKRW, ConfirmSeriesIds.FIELD_CLOSE)
    val highByDate = ctx.byDate(ConfirmSeriesIds.USDKRW, ConfirmSeriesIds.FIELD_HIGH)
    val lowByDate = ctx.byDate(ConfirmSeriesIds.USDKRW, ConfirmSeriesIds.FIELD_LOW)
    val prevCloseByDate = dates.zip(SeriesAlign.shift(close, 1).toList()).toMap()
    val value = Transforms.zscore(Transforms.pctChange1d(close), kwInt(spec.transform, "zscore", "window"))
    val known = ctx.known(dates, value, listOf(ConfirmSeriesIds.USDKRW))
    return Runtime.Usdkrw(known, highByDate, lowByDate, prevCloseByDate)
}

private val BUILDERS: Map<String, (IndicatorSpec, ConfirmTickContext) -> Runtime> =
    mapOf(
        "vix_level_z" to ::buildVixLevelZ,
        "vix_term_structure" to { _, ctx -> buildVixTermStructure(ctx) },
        "move_index_z" to ::buildMoveIndexZ,
        "hy_oas_delta" to ::buildHyOasDelta,
        "dxy_z" to ::buildDxyZ,
        "ust_2s10s_move" to ::buildUst2s10sMove,
        "spx_drawdown_momentum" to ::buildSpxDrawdownMomentum,
        "global_corr_break" to { spec, ctx -> buildGlobalCorrBreak(spec, ctx) },
        "vkospi_z" to ::buildVkospiZ,
        "kospi_drawdown" to ::buildKospiDrawdown,
        "foreign_net_sell_kospi" to ::buildForeignNetSellKospi,
        "kospi_volume_distribution" to ::buildKospiVolumeDistribution,
        "usdkrw_z" to ::buildUsdkrwZ,
    )

internal fun buildIndicatorRuntime(
    spec: IndicatorSpec,
    ctx: ConfirmTickContext,
): Runtime {
    if (spec.id in ConfirmSeriesIds.ALWAYS_MISSING_INDICATORS) return Runtime.AlwaysNone
    val builder = BUILDERS.getValue(spec.id)
    return builder(spec, ctx)
}

private fun resolveSimple(
    known: KnownSeries,
    evaluatedAt: Instant,
    staleWindow: Duration,
    spec: IndicatorSpec,
    direction: Scoring.Direction,
): Resolved {
    val looked = known.lookup(evaluatedAt)
    return if (looked == null || Visibility.isStale(evaluatedAt, looked.visibleAt, staleWindow)) {
        Resolved(null, false)
    } else {
        val thresholds = spec.thresholds.asFlatThresholds()
        Resolved(
            Scoring.classifySeverity(looked.value, thresholds, direction, spec.maxSeverity),
            Scoring.isExtreme(looked.value, thresholds, direction),
        )
    }
}

@Suppress("UNCHECKED_CAST")
private fun resolveCombineMax(
    runtime: Runtime.CombineMax,
    evaluatedAt: Instant,
    staleWindow: Duration,
    spec: IndicatorSpec,
    direction: Scoring.Direction,
): Resolved {
    val a = runtime.knownA.lookup(evaluatedAt)
    val b = runtime.knownB.lookup(evaluatedAt)
    val aVal = if (a != null && !Visibility.isStale(evaluatedAt, a.visibleAt, staleWindow)) a.value else Double.NaN
    val bVal = if (b != null && !Visibility.isStale(evaluatedAt, b.visibleAt, staleWindow)) b.value else Double.NaN
    return if (aVal.isNaN() && bVal.isNaN()) {
        Resolved(null, false)
    } else {
        val thrA = (spec.thresholds["drawdown"] as Map<String, Any?>).asFlatThresholds()
        val thrB = (spec.thresholds["neg_z"] as Map<String, Any?>).asFlatThresholds()
        Resolved(Scoring.combineMaxSeverity(aVal, thrA, bVal, thrB, direction), false)
    }
}

private fun resolveHyOas(
    runtime: Runtime.HyOas,
    evaluatedAt: Instant,
    staleWindow: Duration,
    spec: IndicatorSpec,
    hyRule: HyLevelBoost,
): Resolved {
    val direction = Scoring.Direction.from(spec.direction)
    val looked = runtime.known.lookup(evaluatedAt)
    return if (looked == null || Visibility.isStale(evaluatedAt, looked.visibleAt, staleWindow)) {
        Resolved(null, false)
    } else {
        val thresholds = spec.thresholds.asFlatThresholds()
        val severity = Scoring.classifySeverity(looked.value, thresholds, direction, spec.maxSeverity)
        val extreme = Scoring.isExtreme(looked.value, thresholds, direction)
        val boosted =
            runtime.levelByDate[looked.rowDate]?.let { level -> Modifiers.applyHyLevelBoost(severity, level, hyRule) }
                ?: severity
        Resolved(boosted, extreme)
    }
}

private fun isMissingOrNaN(v: Double?): Boolean = v == null || v.isNaN()

private fun usdkrwForcedSeverity(
    severity: Int?,
    high: Double?,
    low: Double?,
    prevClose: Double?,
    fxRule: UsdkrwIntradayForce,
): Int? {
    if (isMissingOrNaN(high) || isMissingOrNaN(low) || isMissingOrNaN(prevClose)) return severity
    val range = Modifiers.usdkrwIntradayRange(high!!, low!!, prevClose!!)
    return Modifiers.applyUsdkrwIntradayForce(severity, range, fxRule)
}

private fun resolveUsdkrw(
    runtime: Runtime.Usdkrw,
    evaluatedAt: Instant,
    staleWindow: Duration,
    spec: IndicatorSpec,
    fxRule: UsdkrwIntradayForce,
): Resolved {
    val direction = Scoring.Direction.from(spec.direction)
    val looked = runtime.known.lookup(evaluatedAt)
    return if (looked == null || Visibility.isStale(evaluatedAt, looked.visibleAt, staleWindow)) {
        Resolved(null, false)
    } else {
        val thresholds = spec.thresholds.asFlatThresholds()
        val severity = Scoring.classifySeverity(looked.value, thresholds, direction, spec.maxSeverity)
        val extreme = Scoring.isExtreme(looked.value, thresholds, direction)
        val forced =
            usdkrwForcedSeverity(
                severity,
                runtime.highByDate[looked.rowDate],
                runtime.lowByDate[looked.rowDate],
                runtime.prevCloseByDate[looked.rowDate],
                fxRule,
            )
        Resolved(forced, extreme)
    }
}

internal fun resolveSeverity(
    spec: IndicatorSpec,
    runtime: Runtime,
    evaluatedAt: Instant,
    staleWindow: Duration,
    modifiers: Pair<HyLevelBoost, UsdkrwIntradayForce>,
): Resolved {
    val direction = Scoring.Direction.from(spec.direction)
    val (hyRule, fxRule) = modifiers
    return when (runtime) {
        is Runtime.AlwaysNone -> Resolved(null, false)
        is Runtime.Simple -> resolveSimple(runtime.known, evaluatedAt, staleWindow, spec, direction)
        is Runtime.CombineMax -> resolveCombineMax(runtime, evaluatedAt, staleWindow, spec, direction)
        is Runtime.HyOas -> resolveHyOas(runtime, evaluatedAt, staleWindow, spec, hyRule)
        is Runtime.Usdkrw -> resolveUsdkrw(runtime, evaluatedAt, staleWindow, spec, fxRule)
    }
}

/** `fredLagDaysOf`(`run_replay.fred_lag_days` 1:1) — fred provider 지표의 (series_id -> lag_days). */
internal fun fredLagDaysOf(specs: List<IndicatorSpec>): Map<String, Long> =
    buildMap {
        for (spec in specs) {
            if (spec.source["provider"] == "fred") {
                put(spec.source["series_id"] as String, (spec.source["lag_days"] as Number).toLong())
            }
        }
    }
