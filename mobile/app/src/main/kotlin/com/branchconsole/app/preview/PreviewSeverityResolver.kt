package com.branchconsole.app.preview

import com.branchconsole.app.tick.Resolved
import com.branchconsole.engine.config.HyLevelBoost
import com.branchconsole.engine.config.IndicatorSpec
import com.branchconsole.engine.config.UsdkrwIntradayForce
import com.branchconsole.engine.pit.KnownSeries
import com.branchconsole.engine.pit.Visibility
import com.branchconsole.engine.scoring.Modifiers
import com.branchconsole.engine.scoring.Scoring
import java.time.Duration
import java.time.Instant
import com.branchconsole.app.tick.Runtime as TickRuntime

/**
 * MT1-07a — 확정 틱의 (private, app.tick 파일 내부) resolve* 함수들은 가시 판정과 스테일
 * 판정에 **같은** `evaluatedAt` 하나를 쓴다(그 거래일의 확정 시각이라 항상 같은 값이면
 * 충분했다 — §5.4.1 지뢰 7, 매 확정 틱마다 정확히 그 등호에 걸린다).
 *
 * 프리뷰는 그 전제가 깨진다(B §5.4.2 규정 3줄): "가시 판정에만" 쓰는 `tickDay=today`와 "스테일
 * 판정에만" 쓰는 `evaluatedAt=now`가 **서로 다른 시각**이다 — 오전 프리뷰에서는 오늘의 확정
 * 시각이 아직 오지 않았다. 이 파일이 [com.branchconsole.app.tick]의 resolve* 함수들을 그대로
 * 쓰지 못하는 유일한 이유가 이 두 인자의 분리다. [TickRuntime]·[Resolved]·`buildIndicatorRuntime`
 * (13종 빌더, [com.branchconsole.app.tick.buildIndicatorRuntime])는 그대로 재사용한다 —
 * `internal`은 같은 `:app` 모듈 안에서 패키지 경계 없이 보이므로, app.tick 패키지 파일을 한 줄도
 * 고치지 않고도 그 파이프라인을 공유할 수 있다(병렬 워커가 MT1-06을 재작업 중이라는 브리프
 * 지시를 지킨다).
 */
private fun IndicatorSpec.flatThresholds(): Map<String, Double> {
    return thresholds.mapValues { (_, v) -> (v as Number).toDouble() }
}

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.nestedFlatThresholds(key: String): Map<String, Double> =
    (getValue(key) as Map<String, Any?>).mapValues { (_, v) -> (v as Number).toDouble() }

/**
 * 확정 틱은 가시 판정과 스테일 판정에 시각 하나면 충분했지만 프리뷰는 둘로 갈린다 — 이 한 쌍이
 * 이 파일 전체의 유일한 차이다(detekt `LongParameterList` 완화 목적도 겸한다, 6개 초과 함수를
 * 5개 이하로 되돌린다).
 *
 * [lookupAt] = `Visibility.kstToUtc(today, confirmTimeKst)` — "오늘 날짜라면 이미 알려졌는가"를
 * 날짜 단위로 판정한다(§5.4.2 "가시 판정을 날짜로 올린다"의 실행형: `confirmTimeKst`가 고정이면
 * `visDay <= today` ⇔ `visibleAt <= kstToUtc(today, confirmTimeKst)`이므로 등호 포함 bisect
 * 하나로 날짜 비교를 그대로 구현한다). [staleAt] = 실제 `now` — 여기서만 실경과(M-39)가
 * 나온다. `visibleAt`이 미래(`staleAt`보다 늦음)이면 경과가 음수가 되어 자동으로 fresh
 * 취급된다(클램프 불필요 — B §5.4.2 "부수 성질").
 */
internal data class PreviewInstants(val lookupAt: Instant, val staleAt: Instant)

private fun lookupFresh(
    known: KnownSeries?,
    instants: PreviewInstants,
    staleWindow: Duration,
): KnownSeries.LookupResult? {
    val looked = known?.lookup(instants.lookupAt) ?: return null
    return if (Visibility.isStale(instants.staleAt, looked.visibleAt, staleWindow)) null else looked
}

private fun resolveSimplePreview(
    known: KnownSeries,
    instants: PreviewInstants,
    staleWindow: Duration,
    spec: IndicatorSpec,
    direction: Scoring.Direction,
): Resolved {
    val looked = lookupFresh(known, instants, staleWindow) ?: return Resolved(null, false)
    val thresholds = spec.flatThresholds()
    return Resolved(
        Scoring.classifySeverity(looked.value, thresholds, direction, spec.maxSeverity),
        Scoring.isExtreme(looked.value, thresholds, direction),
    )
}

private fun resolveCombineMaxPreview(
    runtime: TickRuntime.CombineMax,
    instants: PreviewInstants,
    staleWindow: Duration,
    spec: IndicatorSpec,
    direction: Scoring.Direction,
): Resolved {
    val a = lookupFresh(runtime.knownA, instants, staleWindow)
    val b = lookupFresh(runtime.knownB, instants, staleWindow)
    val aVal = a?.value ?: Double.NaN
    val bVal = b?.value ?: Double.NaN
    return if (aVal.isNaN() && bVal.isNaN()) {
        Resolved(null, false)
    } else {
        val thrA = spec.thresholds.nestedFlatThresholds("drawdown")
        val thrB = spec.thresholds.nestedFlatThresholds("neg_z")
        Resolved(Scoring.combineMaxSeverity(aVal, thrA, bVal, thrB, direction), false)
    }
}

private fun resolveHyOasPreview(
    runtime: TickRuntime.HyOas,
    instants: PreviewInstants,
    staleWindow: Duration,
    spec: IndicatorSpec,
    hyRule: HyLevelBoost,
): Resolved {
    val direction = Scoring.Direction.from(spec.direction)
    val looked = lookupFresh(runtime.known, instants, staleWindow) ?: return Resolved(null, false)
    val thresholds = spec.flatThresholds()
    val severity = Scoring.classifySeverity(looked.value, thresholds, direction, spec.maxSeverity)
    val extreme = Scoring.isExtreme(looked.value, thresholds, direction)
    val boosted =
        runtime.levelByDate[looked.rowDate]?.let { level -> Modifiers.applyHyLevelBoost(severity, level, hyRule) }
            ?: severity
    return Resolved(boosted, extreme)
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

private fun resolveUsdkrwPreview(
    runtime: TickRuntime.Usdkrw,
    instants: PreviewInstants,
    staleWindow: Duration,
    spec: IndicatorSpec,
    fxRule: UsdkrwIntradayForce,
): Resolved {
    val direction = Scoring.Direction.from(spec.direction)
    val looked = lookupFresh(runtime.known, instants, staleWindow) ?: return Resolved(null, false)
    val thresholds = spec.flatThresholds()
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
    return Resolved(forced, extreme)
}

internal fun resolvePreviewSeverity(
    spec: IndicatorSpec,
    runtime: TickRuntime,
    instants: PreviewInstants,
    staleWindow: Duration,
    modifiers: Pair<HyLevelBoost, UsdkrwIntradayForce>,
): Resolved {
    val direction = Scoring.Direction.from(spec.direction)
    val (hyRule, fxRule) = modifiers
    return when (runtime) {
        is TickRuntime.AlwaysNone -> Resolved(null, false)
        is TickRuntime.Simple -> resolveSimplePreview(runtime.known, instants, staleWindow, spec, direction)
        is TickRuntime.CombineMax -> resolveCombineMaxPreview(runtime, instants, staleWindow, spec, direction)
        is TickRuntime.HyOas -> resolveHyOasPreview(runtime, instants, staleWindow, spec, hyRule)
        is TickRuntime.Usdkrw -> resolveUsdkrwPreview(runtime, instants, staleWindow, spec, fxRule)
    }
}
