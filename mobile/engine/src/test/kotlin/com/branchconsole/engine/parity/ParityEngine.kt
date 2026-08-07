package com.branchconsole.engine.parity

import com.branchconsole.engine.config.HyLevelBoost
import com.branchconsole.engine.config.IndicatorRegistry
import com.branchconsole.engine.config.IndicatorSpec
import com.branchconsole.engine.config.StatemachineConfig
import com.branchconsole.engine.config.TransformParser
import com.branchconsole.engine.config.UsdkrwIntradayForce
import com.branchconsole.engine.config.asFlatThresholds
import com.branchconsole.engine.indicators.Vkospi
import com.branchconsole.engine.pit.CalendarKind
import com.branchconsole.engine.pit.KnownSeries
import com.branchconsole.engine.pit.Visibility
import com.branchconsole.engine.scoring.Modifiers
import com.branchconsole.engine.scoring.Scoring
import com.branchconsole.engine.statemachine.StateMachine
import com.branchconsole.engine.transforms.RollingTransforms
import com.branchconsole.engine.transforms.SeriesAlign
import com.branchconsole.engine.transforms.Transforms
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import com.branchconsole.engine.statemachine.Tick as SmTick

/**
 * MT1-05j — BT-05 패리티 러너 "사슬 ③~⑦" 실행부. `backtest/run_replay.py`의
 * `build_indicator_runtime`(§`_BUILDERS`)/`resolve_severity`/`replay_window_profile` 1:1 이식.
 * D-01 활성 15지표 중 13종은 실제 빌더가 있고(`engine_ref.registry._BUILDERS`와 정확히 같은
 * id 집합), 2종(krx_credit_spread_delta·kr_cds_5y_delta)은 BT-01 수집 범위 밖이라 항상 결측
 * ([Runtime.AlwaysNone]) — 재구현 없음: transforms/pit/scoring/statemachine은 전부 이미 포팅된
 * 순수 함수([com.branchconsole.engine.transforms]·[com.branchconsole.engine.pit]·
 * [com.branchconsole.engine.scoring]·[StateMachine])를 그대로 호출한다. 이 파일은 그 함수들을
 * indicators.yaml의 transform 문자열이 지시하는 순서로 배선만 한다.
 */

private fun calendarKindOf(seriesId: String): CalendarKind =
    when {
        seriesId.startsWith("KRX:") -> CalendarKind.KRX
        seriesId == "KRW=X" -> CalendarKind.FX
        seriesId == "BAMLH0A0HYM2" || seriesId == "T10Y2Y" -> CalendarKind.FRED
        else -> CalendarKind.US_MARKET
    }

/** 창 1개에 고정인 원계열 테이블 + 거래일 그리드 + FRED lag — 빌더 인자 목록을 줄인다. */
class ParityContext(
    raw: List<RawRow>,
    val grid: List<LocalDate>,
    val confirmTimeKst: LocalTime,
    val fredLagDays: Map<String, Long>,
) {
    private val bySeries: Map<Pair<String, String>, List<Pair<LocalDate, Double>>> =
        raw
            .groupBy { it.seriesId to it.field }
            .mapValues { (_, rows) -> rows.map { it.asOf to it.value }.sortedBy { it.first } }

    fun series(
        seriesId: String,
        field: String,
    ): Pair<List<LocalDate>, DoubleArray> {
        val rows = bySeries[seriesId to field].orEmpty()
        return rows.map { it.first } to rows.map { it.second }.toDoubleArray()
    }

    fun byDate(
        seriesId: String,
        field: String,
    ): Map<LocalDate, Double> = bySeries[seriesId to field].orEmpty().toMap()

    fun known(
        dates: List<LocalDate>,
        values: DoubleArray,
        inputIds: List<String>,
    ): KnownSeries {
        val inputs = inputIds.map { Visibility.VisibilityInput(calendarKindOf(it), fredLagDays[it] ?: 0L) }
        return KnownSeries.build(dates, values) { d -> Visibility.combinedVisibleAt(inputs, d, grid, confirmTimeKst) }
    }
}

/** `run_replay._BUILDERS`의 dict "kind"에 대응하는 대수적 타입. */
sealed interface Runtime {
    /** L1~L3 지표 레이어의 값 출처(§9-C, `export_parity.py._indicator_layer`가
     * `runtime["known"]`이 없으면 value/as_of/visible_at을 전부 None으로 두는 것과 동일 —
     * combine_max/always_none은 null을 반환한다). */
    fun primaryKnown(): KnownSeries? = null

    data object AlwaysNone : Runtime

    data class Simple(val known: KnownSeries) : Runtime {
        override fun primaryKnown() = known
    }

    data class CombineMax(val knownA: KnownSeries, val knownB: KnownSeries) : Runtime

    data class HyOas(val known: KnownSeries, val levelByDate: Map<LocalDate, Double>) : Runtime {
        override fun primaryKnown() = known
    }

    data class Usdkrw(
        val known: KnownSeries,
        val highByDate: Map<LocalDate, Double>,
        val lowByDate: Map<LocalDate, Double>,
        val prevCloseByDate: Map<LocalDate, Double>,
    ) : Runtime {
        override fun primaryKnown() = known
    }
}

private fun kwInt(
    transform: String,
    callName: String,
    key: String,
): Int = (TransformParser.parseCallKwargs(callName, transform).getValue(key) as Number).toInt()

private val ALWAYS_MISSING_INDICATORS = setOf("krx_credit_spread_delta", "kr_cds_5y_delta")

// ---------------------------------------------------------------------------
// per-indicator builders — run_replay.py _build_* 1:1
// ---------------------------------------------------------------------------

private fun buildVixLevelZ(
    spec: IndicatorSpec,
    ctx: ParityContext,
): Runtime {
    val (dates, close) = ctx.series("^VIX", "close")
    val value = Transforms.zscore(close, kwInt(spec.transform, "zscore", "window"))
    return Runtime.Simple(ctx.known(dates, value, listOf("^VIX")))
}

private fun buildVixTermStructure(ctx: ParityContext): Runtime {
    val (datesA, a) = ctx.series("^VIX", "close")
    val (datesB, b) = ctx.series("^VIX3M", "close")
    val aligned = SeriesAlign.unionAlign(datesA, a, datesB, b)
    val value = Transforms.ratio(aligned.a, aligned.b)
    return Runtime.Simple(ctx.known(aligned.dates, value, listOf("^VIX", "^VIX3M")))
}

private fun buildMoveIndexZ(
    spec: IndicatorSpec,
    ctx: ParityContext,
): Runtime {
    val (dates, close) = ctx.series("^MOVE", "close")
    val value = Transforms.zscore(close, kwInt(spec.transform, "zscore", "window"))
    return Runtime.Simple(ctx.known(dates, value, listOf("^MOVE")))
}

private fun buildHyOasDelta(
    spec: IndicatorSpec,
    ctx: ParityContext,
): Runtime {
    val (dates, level) = ctx.series("BAMLH0A0HYM2", "value")
    val delta = Transforms.deltaBp(level, kwInt(spec.transform, "delta_bp", "lookback"))
    val levelByDate = dates.zip(level.toList()).toMap()
    return Runtime.HyOas(ctx.known(dates, delta, listOf("BAMLH0A0HYM2")), levelByDate)
}

private fun buildDxyZ(
    spec: IndicatorSpec,
    ctx: ParityContext,
): Runtime {
    val (dates, close) = ctx.series("DX-Y.NYB", "close")
    val kwargs = TransformParser.parseCallKwargs("zscore", spec.transform)
    val window = (kwargs.getValue("window") as Number).toInt()
    val absolute = kwargs["absolute"] as? Boolean ?: false
    val value = Transforms.zscore(Transforms.pctChange5d(close), window, absolute)
    return Runtime.Simple(ctx.known(dates, value, listOf("DX-Y.NYB")))
}

private fun buildUst2s10sMove(
    spec: IndicatorSpec,
    ctx: ParityContext,
): Runtime {
    val (dates, level) = ctx.series("T10Y2Y", "value")
    val value = Transforms.absValue(Transforms.deltaBp(level, kwInt(spec.transform, "delta_bp", "lookback")))
    return Runtime.Simple(ctx.known(dates, value, listOf("T10Y2Y")))
}

private fun buildSpxDrawdownMomentum(
    spec: IndicatorSpec,
    ctx: ParityContext,
): Runtime {
    val (dates, close) = ctx.series("^GSPC", "close")
    val dd = Transforms.drawdownFromHigh(close, kwInt(spec.transform, "drawdown_from_high", "window"))
    val nz = Transforms.negZscore(Transforms.pctChange5d(close), kwInt(spec.transform, "neg_zscore", "window"))
    return Runtime.CombineMax(ctx.known(dates, dd, listOf("^GSPC")), ctx.known(dates, nz, listOf("^GSPC")))
}

private fun buildGlobalCorrBreak(
    spec: IndicatorSpec,
    ctx: ParityContext,
): Runtime {
    val (spxDates, spxClose) = ctx.series("^GSPC", "close")
    val (kospiDates, kospiClose) = ctx.series("KRX:1001", "close")
    val retSpx = Transforms.pctChange1d(spxClose)
    val retKospi = Transforms.pctChange1d(kospiClose)
    val retSpxOnKr = SeriesAlign.alignToFfillCausal(spxDates, retSpx, kospiDates)
    val corr = RollingTransforms.rollingCorr(retKospi, retSpxOnKr, kwInt(spec.transform, "rolling_corr", "window"))
    val meanCorr = RollingTransforms.rollingMeanCorr(corr, kwInt(spec.transform, "rolling_mean_corr", "window"))
    val value = Transforms.absValue(DoubleArray(corr.size) { i -> corr[i] - meanCorr[i] })
    return Runtime.Simple(ctx.known(kospiDates, value, listOf("^GSPC", "KRX:1001")))
}

private fun buildVkospiZ(
    spec: IndicatorSpec,
    ctx: ParityContext,
): Runtime {
    val (vkDates, vkClose) = ctx.series("KRX:VKOSPI", "close")
    val (kospiDates, kospiClose) = ctx.series("KRX:1001", "close")
    val zWindow = kwInt(spec.transform, "zscore", "window")
    val fbWindow = TransformParser.parseFallbackWindow(spec.source["fallback"] as String)
    // 재사용: 프로덕션 K-02 폴백 분기 로직 자체를 [Vkospi.vkospiZ]에서 그대로 가져온다 —
    // 어느 계열을 실제로 썼는지(실측 VKOSPI vs KOSPI 유도)는 그 함수와 동일한 조건으로 재판정.
    val value = Vkospi.vkospiZ(vkClose, kospiClose, zWindow, fbWindow)
    val (dates, inputs) =
        if (vkClose.isNotEmpty()) vkDates to listOf("KRX:VKOSPI") else kospiDates to listOf("KRX:1001")
    return Runtime.Simple(ctx.known(dates, value, inputs))
}

private fun buildKospiDrawdown(
    spec: IndicatorSpec,
    ctx: ParityContext,
): Runtime {
    val (dates, close) = ctx.series("KRX:1001", "close")
    val value = Transforms.drawdownFromHigh(close, kwInt(spec.transform, "drawdown_from_high", "window"))
    return Runtime.Simple(ctx.known(dates, value, listOf("KRX:1001")))
}

private fun buildForeignNetSellKospi(
    spec: IndicatorSpec,
    ctx: ParityContext,
): Runtime {
    val (dates, netBuy) = ctx.series("KRX:investor_foreign_kospi", "net_buy_value")
    val rolled = RollingTransforms.rollingSum(netBuy, kwInt(spec.transform, "rolling_sum", "window"))
    val value = Transforms.negZscore(rolled, kwInt(spec.transform, "neg_zscore", "window"))
    return Runtime.Simple(ctx.known(dates, value, listOf("KRX:investor_foreign_kospi")))
}

private fun buildKospiVolumeDistribution(
    spec: IndicatorSpec,
    ctx: ParityContext,
): Runtime {
    val (dates, close) = ctx.series("KRX:1001", "close")
    val (_, tradingValue) = ctx.series("KRX:1001", "trading_value")
    val gatedKwargs = TransformParser.parseCallKwargs("gated", spec.transform)
    val (varName, op, threshold) = TransformParser.parseGate(gatedKwargs.getValue("gate") as String)
    check(varName == "daily_return") { "unexpected gate variable for kospi_volume_distribution: $varName" }
    val mask = RollingTransforms.gateMask(Transforms.pctChange1d(close), op, threshold)
    val z = Transforms.zscore(tradingValue, kwInt(spec.transform, "zscore", "window"))
    val value = RollingTransforms.gated(z, mask)
    return Runtime.Simple(ctx.known(dates, value, listOf("KRX:1001")))
}

private fun buildUsdkrwZ(
    spec: IndicatorSpec,
    ctx: ParityContext,
): Runtime {
    val (dates, close) = ctx.series("KRW=X", "close")
    val highByDate = ctx.byDate("KRW=X", "high")
    val lowByDate = ctx.byDate("KRW=X", "low")
    val prevCloseByDate = dates.zip(SeriesAlign.shift(close, 1).toList()).toMap()
    val value = Transforms.zscore(Transforms.pctChange1d(close), kwInt(spec.transform, "zscore", "window"))
    return Runtime.Usdkrw(ctx.known(dates, value, listOf("KRW=X")), highByDate, lowByDate, prevCloseByDate)
}

private val BUILDERS: Map<String, (IndicatorSpec, ParityContext) -> Runtime> =
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

fun buildIndicatorRuntime(
    spec: IndicatorSpec,
    ctx: ParityContext,
): Runtime {
    if (spec.id in ALWAYS_MISSING_INDICATORS) return Runtime.AlwaysNone
    val builder = BUILDERS.getValue(spec.id)
    return builder(spec, ctx)
}

// ---------------------------------------------------------------------------
// per-tick severity resolution — run_replay.resolve_severity 1:1
// ---------------------------------------------------------------------------

data class Resolved(val severity: Int?, val isExtreme: Boolean)

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

/** high/low/prevClose 셋 다 있고 prevClose가 NaN이 아닐 때만 강제를 적용, 그 외에는 원래
 * severity 그대로. [isMissingOrNaN] 호출 1항씩으로 쪼개 조건 하나당 항 수를 낮게 유지한다. */
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

/** `run_replay.resolve_severity` 1:1 디스패처. `modifiers` = (hy_level_boost, usdkrw_intraday_force). */
fun resolveSeverity(
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

/** `export_parity.py._indicator_layer` 1:1 — `runtime.primaryKnown()`이 없는 kind(combine_max·
 * always_none)는 value/as_of/visible_at/stale을 전부 결측으로 둔다(severity만 실제 값). */
fun indicatorLayer(
    runtime: Runtime,
    evaluatedAt: Instant,
    resolved: Resolved,
    staleWindow: Duration,
): IndicatorLayer {
    val looked = runtime.primaryKnown()?.lookup(evaluatedAt)
    return if (looked == null) {
        IndicatorLayer(severity = resolved.severity)
    } else {
        IndicatorLayer(
            value = looked.value,
            asOf = looked.rowDate.toString(),
            visibleAt = looked.visibleAt.toString(),
            stale = Visibility.isStale(evaluatedAt, looked.visibleAt, staleWindow),
            severity = resolved.severity,
        )
    }
}

/** 지표 스펙 전체에서 fred provider의 (series_id -> lag_days) 맵 — `run_replay.fred_lag_days` 1:1. */
fun fredLagDaysOf(specs: List<IndicatorSpec>): Map<String, Long> =
    buildMap {
        for (spec in specs) {
            if (spec.source["provider"] == "fred") {
                put(spec.source["series_id"] as String, (spec.source["lag_days"] as Number).toLong())
            }
        }
    }

// ---------------------------------------------------------------------------
// window orchestration — run_replay.replay_window_profile 1:1 (mobile_daily only)
// ---------------------------------------------------------------------------

/** `runWindow`가 필요로 하는, 창과 무관하게 고정인 설정 일체(1회 로드 후 9창 재사용 —
 * `run_replay.run_replay`의 "load once, reuse every tick" 원칙과 동일). */
data class ParityConfig(
    val specs: List<IndicatorSpec>,
    val weights: Map<String, Double>,
    val axes: Map<String, String>,
    val maxSeverities: Map<String, Int>,
    val fredLagDays: Map<String, Long>,
    /** cadence -> mobile_daily stale window (§9-C, `run_replay.load_stale_windows` 1:1). */
    val staleWindows: Map<String, Duration>,
    val statemachineConfig: StatemachineConfig,
    val modifiers: Pair<HyLevelBoost, UsdkrwIntradayForce>,
)

private fun cadenceOf(spec: IndicatorSpec): String = spec.source["cadence"] as? String ?: ""

private fun evaluateTick(
    day: LocalDate,
    grid: Grid,
    config: ParityConfig,
    runtimes: Map<String, Runtime>,
): Pair<SmTick, TickRecord> {
    val evaluatedAt = Visibility.kstToUtc(day, grid.confirmTimeKst)

    val resolved = LinkedHashMap<String, Resolved>()
    for (spec in config.specs) {
        val staleWindow = config.staleWindows.getValue(cadenceOf(spec))
        resolved[spec.id] =
            resolveSeverity(spec, runtimes.getValue(spec.id), evaluatedAt, staleWindow, config.modifiers)
    }
    val severities = LinkedHashMap<String, Int?>()
    resolved.forEach { (id, r) -> severities[id] = r.severity }

    val anyCrit = severities.values.any { it != null && it >= 3 }
    val anyExtreme = resolved.values.any { it.isExtreme }
    val distinctAxes = Scoring.distinctAxes(severities, config.axes)
    val composite = Scoring.computeComposite(severities, config.weights, config.maxSeverities)
    val firedAxes =
        severities.entries
            .filter { (_, s) -> s != null && s >= 2 }
            .mapNotNull { config.axes[it.key] }
            .toSortedSet()
            .toList()

    val indicators =
        config.specs.associate { spec ->
            val staleWindow = config.staleWindows.getValue(cadenceOf(spec))
            spec.id to indicatorLayer(runtimes.getValue(spec.id), evaluatedAt, resolved.getValue(spec.id), staleWindow)
        }

    val smTick =
        SmTick(composite = composite.score, distinctAxes = distinctAxes, anyCrit = anyCrit, anyExtreme = anyExtreme)
    val record =
        TickRecord(
            evaluatedAt = evaluatedAt.toString(),
            kstDate = day.toString(),
            indicators = indicators,
            composite = composite.score,
            coverage = composite.coverage,
            distinctAxes = distinctAxes,
            anyCrit = anyCrit,
            anyExtreme = anyExtreme,
            firedAxes = firedAxes,
            // StateMachine.run replays the whole timeline after all ticks are built.
            phase = "PENDING",
        )
    return smTick to record
}

/**
 * 창 1개를 평가해 틱 레코드 목록을 만든다. `config.specs`는 반드시 indicators.yaml 선언 순서
 * ([IndicatorRegistry.loadIndicatorSpecs]의 반환 순서)여야 한다 — [Scoring.computeComposite]의
 * 부동소수 누산 순서 파리티(정본 §2.7 파리티 지뢰 3).
 */
fun runWindow(
    raw: List<RawRow>,
    grid: Grid,
    config: ParityConfig,
): List<TickRecord> {
    val ctx = ParityContext(raw, grid.tradingDays, grid.confirmTimeKst, config.fredLagDays)
    val runtimes = config.specs.associate { it.id to buildIndicatorRuntime(it, ctx) }

    val evaluations = grid.tradingDays.map { day -> evaluateTick(day, grid, config, runtimes) }
    val smTicks = evaluations.map { it.first }
    val records = evaluations.map { it.second }

    val profileParams = config.statemachineConfig.profiles.getValue(grid.profile)
    val timeline = StateMachine.run(smTicks, profileParams, config.statemachineConfig)
    check(timeline.size == records.size) {
        "statemachine timeline size ${timeline.size} != tick count ${records.size}"
    }
    return records.zip(timeline) { rec, phase -> rec.copy(phase = phase) }
}
