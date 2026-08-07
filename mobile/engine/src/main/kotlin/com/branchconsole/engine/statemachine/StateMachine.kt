package com.branchconsole.engine.statemachine

import com.branchconsole.engine.config.ProfileParams
import com.branchconsole.engine.config.StatemachineConfig

/**
 * `engine_ref/statemachine.py` 1:1 이식 — D-16 프로파일 주입형 국면 상태기계(D-25 실행 의미론
 * 확정판). 전이 구조(승격/강등 규칙)·composite 공식·distinct_axes 요건·skip_levels는 프로파일
 * 무관 동일. 프로파일별로 달라지는 것은 틱 카운트 파라미터([ProfileParams])뿐이다.
 *
 * D-25 확정 의미론(docs/P0_DESIGN_DECISIONS.md D-25):
 *  1. 승격 sustain은 레벨별 연속 충족이다 — 각 레벨은 독립 스트릭을 갖고, 그 틱에 자신의
 *     조건이 충족되면 +1, 아니면 0으로 리셋된다. `skip_levels=true`면 sustain 충족 레벨 중
 *     최고 레벨로 직행한다. cooldown 중에는 모든 레벨 스트릭이 매 틱 0으로 정지·리셋되고
 *     승격 커밋도 없다.
 *  2. `min_dwell_ticks`는 명목=실효 체류다 — 전이가 커밋된 틱을 그 국면의 1틱째로 세고,
 *     강등은 `min_dwell_ticks`를 채운 뒤(+1틱째부터) 커밋 가능하다. 강등 스트릭 자체는 dwell
 *     충족 여부와 무관하게 누적되며, dwell 미달은 커밋만 지연시킨다.
 *  3. 전 지표 결측([Tick.composite]가 null)은 평가 불능이다 — 국면·모든 스트릭·dwell·
 *     cooldown을 그 틱에서 완전히 동결한다(전이 없음, 틱 미소비).
 *  4. D-26 이스케이프-이탈 짝지음(레벨-로컬, reset) — 레벨 L의 upgrade 규칙이 `or_any_*` 키를
 *     가지면, 그 입력이 참인 동안 `exit_L`은 자신의 조건과 무관하게 미충족으로 취급된다(다른
 *     레벨의 이탈에는 영향 없음). 이 미충급은 "이탈 조건이 거짓인 틱"과 동일한 경로(강등
 *     스트릭 리셋)를 탄다.
 */
data class Tick(
    /** null = 전 지표 결측("평가 불능", D-25 §3) — 이 틱은 완전 동결. */
    val composite: Double?,
    val distinctAxes: Int,
    val anyCrit: Boolean = false,
    /** AD-7 옵션 A: `or_any_extreme` 이스케이프 입력(원값 기반, severity 아님). */
    val anyExtreme: Boolean = false,
)

private val KNOWN_UPGRADE_KEYS = setOf("composite_gte", "distinct_axes_gte", "or_any_crit", "or_any_extreme")

object StateMachine {
    /** 실행 전체에서 불변인 값(phases/profile/config)을 한 번 계산해 묶어 파라미터 목록을
     * 줄인다 — `run()` 안에서만 만들어지는 실행 컨텍스트, 상태([RunState])는 담지 않는다. */
    private class RunContext(val order: List<String>, val profile: ProfileParams, val config: StatemachineConfig) {
        val idx: Map<String, Int> = order.withIndex().associate { (i, name) -> name to i }
        val levels: List<String> = order.drop(1)
    }

    private class RunState(
        var phase: String,
        val promoteStreaks: MutableMap<String, Int>,
        var ticksInPhase: Int = 1,
        var demoteStreak: Int = 0,
        var cooldown: Int = 0,
    )

    /**
     * 틱 시퀀스를 재생해 국면 타임라인(틱별 phase)을 산출한다. 상태를 저장·이어받지 않고
     * 매번 전량 재생한다(AD-A11 "전량 fold") — `run()`은 초기 상태 주입구가 없다(정본과 동일).
     */
    fun run(
        ticks: List<Tick>,
        profile: ProfileParams,
        config: StatemachineConfig,
    ): List<String> {
        val ctx = RunContext(config.phases, profile, config)
        val initialStreaks = ctx.levels.associateWith { 0 }.toMutableMap()
        val state = RunState(phase = config.initialPhase, promoteStreaks = initialStreaks)
        val timeline = mutableListOf<String>()

        for (tick in ticks) {
            val composite = tick.composite
            if (composite == null) {
                // D-25 §3: 평가 불능 — 국면·스트릭·dwell·cooldown 전부 동결, 틱 미소비.
                timeline.add(state.phase)
                continue
            }
            state.ticksInPhase++
            advance(state, tick, composite, ctx)
            timeline.add(state.phase)
        }
        return timeline
    }

    private fun advance(
        state: RunState,
        tick: Tick,
        composite: Double,
        ctx: RunContext,
    ) {
        updatePromoteStreaks(state, tick, composite, ctx)

        var transitioned = false
        if (state.cooldown == 0) {
            transitioned = tryPromote(state, ctx)
        }
        if (!transitioned && state.phase != ctx.order[0]) {
            transitioned = tryDemote(state, tick, composite, ctx)
        }
        if (state.cooldown > 0 && !transitioned) state.cooldown--
    }

    private fun updatePromoteStreaks(
        state: RunState,
        tick: Tick,
        composite: Double,
        ctx: RunContext,
    ) {
        if (state.cooldown > 0) {
            // 강등 직후 쿨다운: 모든 레벨 스트릭 정지·리셋, 승격 커밋 금지(D-25 §1).
            ctx.levels.forEach { state.promoteStreaks[it] = 0 }
            return
        }
        for (level in ctx.levels) {
            val rule = ctx.config.upgrade.getValue(level)
            val satisfied = ruleSatisfied(rule, composite, tick.distinctAxes, tick.anyCrit, tick.anyExtreme)
            state.promoteStreaks[level] = if (satisfied) state.promoteStreaks.getValue(level) + 1 else 0
        }
    }

    private fun tryPromote(
        state: RunState,
        ctx: RunContext,
    ): Boolean {
        val phaseIdx = ctx.idx.getValue(state.phase)
        val eligible =
            ctx.levels.filter {
                ctx.idx.getValue(it) > phaseIdx && state.promoteStreaks.getValue(it) >= ctx.profile.promoteSustainTicks
            }
        if (eligible.isEmpty()) return false
        state.phase =
            if (ctx.config.skipLevels) {
                eligible.maxBy { ctx.idx.getValue(it) }
            } else {
                eligible.minBy { ctx.idx.getValue(it) }
            }
        state.ticksInPhase = 1
        state.demoteStreak = 0
        return true
    }

    private fun tryDemote(
        state: RunState,
        tick: Tick,
        composite: Double,
        ctx: RunContext,
    ): Boolean {
        val exitRule = ctx.config.downgrade.getValue("exit_${state.phase}")
        // D-26: 현재 레벨 자신의 upgrade 규칙에 활성 or_any_* 이스케이프가 있으면 이탈은
        // 이번 틱에 미충족으로 취급된다(레벨-로컬 짝지음).
        val escapeBlocks = escapeBlocksExit(ctx.config.upgrade.getValue(state.phase), tick.anyCrit, tick.anyExtreme)
        if (!exitSatisfied(exitRule, composite) || escapeBlocks) {
            state.demoteStreak = 0
            return false
        }
        state.demoteStreak++
        val minTicks = ctx.profile.minDwellTicks + 1
        val canDemote = state.demoteStreak >= ctx.profile.demoteBelowTicks && state.ticksInPhase >= minTicks
        if (canDemote) {
            state.phase = ctx.order[ctx.idx.getValue(state.phase) - 1]
            state.ticksInPhase = 1
            state.demoteStreak = 0
            state.cooldown = ctx.profile.reentryCooldownTicks
        }
        return canDemote
    }

    private fun ruleSatisfied(
        rule: Map<String, Any?>,
        composite: Double,
        distinctAxes: Int,
        anyCrit: Boolean,
        anyExtreme: Boolean,
    ): Boolean {
        // O-1: 알려진 키가 하나도 없는 규칙은 설정 오류다 — 조용히 "항상 충족"으로 빠지지
        // 않고 즉시 실패한다(Fail Fast).
        check(rule.keys.any { it in KNOWN_UPGRADE_KEYS }) { "upgrade rule has no recognized keys: $rule" }
        val conditions = mutableListOf<Boolean>()
        (rule["composite_gte"] as? Number)?.toDouble()?.let { gte ->
            // AD-10: or_any_extreme은 composite_gte "만" 우회한다 — distinct_axes_gte는 면제되지
            // 않는다.
            var ok = composite >= gte
            if (rule["or_any_extreme"] == true) ok = ok || anyExtreme
            conditions.add(ok)
        }
        (rule["distinct_axes_gte"] as? Number)?.toInt()?.let { gte -> conditions.add(distinctAxes >= gte) }
        val base = conditions.isEmpty() || conditions.all { it }
        return if (rule["or_any_crit"] == true) base || anyCrit else base
    }

    private fun exitSatisfied(
        rule: Map<String, Any?>,
        composite: Double,
    ): Boolean {
        val lt = (rule["composite_lt"] as? Number)?.toDouble() ?: error("exit rule missing composite_lt: $rule")
        return composite < lt
    }

    /** D-26 짝지음(레벨-로컬): `rule`은 이탈 규칙이 아니라 **같은 레벨의 upgrade 규칙**이다 —
     * `or_any_crit`/`or_any_extreme`은 그 규칙에만 선언되므로 그것이 짝지어야 할 이탈의
     * 정본이다(RED는 이 키가 없어 자동으로 영향받지 않는다). */
    private fun escapeBlocksExit(
        rule: Map<String, Any?>,
        anyCrit: Boolean,
        anyExtreme: Boolean,
    ): Boolean = (rule["or_any_crit"] == true && anyCrit) || (rule["or_any_extreme"] == true && anyExtreme)
}
