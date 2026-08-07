package com.branchconsole.engine.scoring

import kotlin.math.abs

/**
 * `engine_ref/scoring.py` 1:1 이식 — severity 판정(D-01) + composite(D-02, D-25 §3) +
 * distinct_axes. severity: 0=none, 1=watch, 2=warn, 3=crit(등호 포함 — 값==임계 → 해당 등급
 * 발화). 결측(NaN)은 severity=null — composite 분모·분자 모두 제외(optional 지표 포함).
 * K-07: Double 고정, 반올림 없음(표시 계층 몫).
 *
 * D-25 §3: 전 지표 결측(유효 가중 0)은 GREEN이 아니라 "평가 불능"이다. [computeComposite]는
 * (score, coverage) 쌍을 반환하고, 유효 가중이 0이면 score=null — 상태기계는 이 틱에서
 * 국면·스트릭·카운터를 동결한다([com.branchconsole.engine.statemachine.StateMachine.run] 참조).
 */
object Scoring {
    private const val SEVERITY_NONE = 0
    private const val SEVERITY_WATCH = 1
    private const val SEVERITY_WARN = 2
    private const val SEVERITY_CRIT = 3
    private const val DEFAULT_MAX_SEVERITY = 3
    private const val EXTENDED_MAX_SEVERITY = 4 // AD-7 옵션 B(계량 전용) 4번째 tier
    private const val COMPOSITE_SCALE = 100.0

    // crit부터 내림차순 검사(engine_ref.scoring._LEVELS와 동일 순서).
    private val LEVELS = listOf("crit" to SEVERITY_CRIT, "warn" to SEVERITY_WARN, "watch" to SEVERITY_WATCH)

    enum class Direction {
        HIGHER_IS_RISK,
        ABS,
        ;

        companion object {
            fun from(raw: String): Direction =
                when (raw) {
                    "higher_is_risk" -> HIGHER_IS_RISK
                    "abs" -> ABS
                    else -> error("unknown direction: '$raw'")
                }
        }
    }

    private fun directed(
        value: Double,
        direction: Direction,
    ): Double = if (direction == Direction.ABS) abs(value) else value

    /**
     * 값 하나를 thresholds(watch/warn/crit[,extreme])에 대해 판정. 결측(NaN)이면 null.
     *
     * `maxSeverity>=4`(AD-7 옵션 B 전용, 계량 목적)일 때만 `thresholds["extreme"]`을 4번째
     * tier로 추가 판정한다(값 >= extreme -> 4). 기본값 3은 원래 3-tier 그대로이며 "extreme"
     * 키의 존재 여부와 무관하게 무시한다(옵션 A와의 격리 — engine_ref 모듈 docstring 참조).
     */
    fun classifySeverity(
        value: Double,
        thresholds: Map<String, Double>,
        direction: Direction = Direction.HIGHER_IS_RISK,
        maxSeverity: Int = DEFAULT_MAX_SEVERITY,
    ): Int? {
        if (value.isNaN()) return null
        val v = directed(value, direction)
        val extreme = thresholds["extreme"]
        return when {
            maxSeverity >= EXTENDED_MAX_SEVERITY && extreme != null && v >= extreme -> EXTENDED_MAX_SEVERITY
            else ->
                LEVELS.firstOrNull { (name, _) -> thresholds[name]?.let { v >= it } == true }?.second
                    ?: SEVERITY_NONE
        }
    }

    /**
     * AD-7 옵션 A 전용: 개별 지표의 원값(severity 아님)이 `thresholds["extreme"]`을 넘으면
     * true. extreme 키 미설정 또는 값 결측이면 항상 false(엔진 기본 거동 비영향 — extreme 키
     * 부재 시 이 함수는 어떤 지표에서도 true를 낼 수 없다). 등호 포함(">=" — classifySeverity와
     * 동일 규약).
     */
    fun isExtreme(
        value: Double,
        thresholds: Map<String, Double>,
        direction: Direction = Direction.HIGHER_IS_RISK,
    ): Boolean {
        val extreme = thresholds["extreme"] ?: return false
        return !value.isNaN() && directed(value, direction) >= extreme
    }

    /**
     * spx_drawdown_momentum: drawdown/neg_z 각 성분을 자체 임계로 판정 후 max. 한쪽만
     * 결측이면 있는 쪽으로 판정(결측 성분은 0 취급 아님 — 단순 배제). 둘 다 결측이면 전체 결측.
     */
    fun combineMaxSeverity(
        valueA: Double,
        thresholdsA: Map<String, Double>,
        valueB: Double,
        thresholdsB: Map<String, Double>,
        direction: Direction = Direction.HIGHER_IS_RISK,
    ): Int? {
        val sa = classifySeverity(valueA, thresholdsA, direction)
        val sb = classifySeverity(valueB, thresholdsB, direction)
        if (sa == null && sb == null) return null
        return maxOf(sa ?: SEVERITY_NONE, sb ?: SEVERITY_NONE)
    }

    data class CompositeResult(val score: Double?, val coverage: Double)

    /**
     * D-02: score = 100 * Σ(w_i·s_i) / Σ(w_i·3). 결측(null) 지표는 분모·분자 모두 제외(부분
     * 결측은 이 제외만 적용 — D-02 불변). coverage = 유효 가중 / 전체 가중.
     *
     * D-25 §3: 전체 가중이 0이거나 유효 가중이 0(전 지표 결측)이면 score=null — "평가 불능".
     *
     * `maxSeverities`(AD-7 옵션 B, 계량 전용)를 지정하면 분모의 상수 3.0이 지표별
     * `maxSeverities[id] ?: 3`으로 대체된다(생략 시 전 지표 3 — 원래 3-tier 산식과 완전히
     * 동일한 값). 순회 순서는 호출자가 넘긴 `severities`의 iteration 순서 그대로 누적한다 —
     * 부동소수 최종 비트까지 골든과 일치시키려면 호출자가 `LinkedHashMap`(=indicators.yaml
     * 선언 순서)을 넘겨야 한다(M1_PLAN_A.md §2.7 파리티 지뢰 3).
     */
    fun computeComposite(
        severities: Map<String, Int?>,
        weights: Map<String, Double>,
        maxSeverities: Map<String, Int>? = null,
    ): CompositeResult {
        fun maxSeverityOf(id: String): Double = (maxSeverities?.get(id) ?: DEFAULT_MAX_SEVERITY).toDouble()

        var num = 0.0
        var den = 0.0
        for ((id, s) in severities) {
            if (s == null) continue
            den += weights.getValue(id) * maxSeverityOf(id)
            num += weights.getValue(id) * s
        }
        val total = weights.entries.sumOf { (id, w) -> w * maxSeverityOf(id) }
        val coverage = if (total == 0.0) 0.0 else den / total
        val score = if (den == 0.0) null else COMPOSITE_SCALE * num / den
        return CompositeResult(score, coverage)
    }

    /** severity >= warn(2)인 지표가 존재하는 서로 다른 axis 개수(D-01). */
    fun distinctAxes(
        severities: Map<String, Int?>,
        axes: Map<String, String>,
    ): Int =
        severities.entries
            .filter { (_, s) -> s != null && s >= SEVERITY_WARN }
            .mapNotNull { (id, _) -> axes[id] }
            .toSet()
            .size
}
