package com.branchconsole.engine.scoring

import com.branchconsole.engine.config.HyLevelBoost
import com.branchconsole.engine.config.UsdkrwIntradayForce

/**
 * `engine_ref/modifiers.py` 1:1 이식 — `configs/indicators.yaml` `engine.modifiers` 규칙
 * 적용. 수치(4.5, cap 3, 1.2%, 2.0%)는 전부
 * [com.branchconsole.engine.config.ModifierRules]가 rule 문자열에서 파싱한 값이다. 이 객체는
 * 그 값을 받아 적용만 한다(코드 리터럴 금지, CLAUDE.md §1).
 */
object Modifiers {
    private const val SEVERITY_NONE = 0
    private const val SEVERITY_WARN = 2
    private const val SEVERITY_CRIT = 3
    private const val PERCENT = 100.0

    /**
     * `hyOasLevel > rule.levelThreshold`(초과, 등호 아님) → `severity += rule.increment`,
     * `rule.maxSeverity`로 cap. severity가 결측(null)이면 레벨 부스트 대상이 없으므로 그대로
     * null.
     */
    fun applyHyLevelBoost(
        severity: Int?,
        hyOasLevel: Double,
        rule: HyLevelBoost,
    ): Int? {
        if (severity == null) return null
        return if (hyOasLevel > rule.levelThreshold) minOf(severity + rule.increment, rule.maxSeverity) else severity
    }

    /**
     * 일중 변동폭 = (high - low) / 전일 close * 100(%). O-2: `prevClose == 0`은 정의 불가(0으로
     * 나눔) — 조용히 Infinity/NaN을 반환하는 대신 즉시 실패한다(Fail Fast, Never Suppress
     * Silently).
     */
    fun usdkrwIntradayRange(
        high: Double,
        low: Double,
        prevClose: Double,
    ): Double {
        check(prevClose != 0.0) { "usdkrwIntradayRange: prevClose must be non-zero" }
        return (high - low) / prevClose * PERCENT
    }

    /**
     * 일중 변동폭 `>= rule.warnThreshold` → severity>=warn(2) 강제, `>= rule.critThreshold` →
     * crit(3). 등호 포함(rule 문자열 그대로). 강제이므로 결측(null) 기저도 게이트 충족 시
     * 승급된다 — 결측 지표가 강제로 유효 지표가 되어 coverage 분모에 들어온다는 뜻.
     */
    fun applyUsdkrwIntradayForce(
        severity: Int?,
        intradayRangePct: Double,
        rule: UsdkrwIntradayForce,
    ): Int? =
        when {
            intradayRangePct >= rule.critThreshold -> SEVERITY_CRIT
            intradayRangePct >= rule.warnThreshold -> maxOf(severity ?: SEVERITY_NONE, SEVERITY_WARN)
            else -> severity
        }
}
