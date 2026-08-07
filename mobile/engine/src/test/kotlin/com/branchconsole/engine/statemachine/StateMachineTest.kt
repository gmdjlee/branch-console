package com.branchconsole.engine.statemachine

import com.branchconsole.engine.config.RepoConfigSource
import com.branchconsole.engine.config.StatemachineConfigLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `engine_ref/statemachine.py` 대조 표본 — 실제 `configs/statemachine.yaml`(mobile_daily
 * 프로파일)을 [RepoConfigSource]로 로드해, 그 위에서 Python `engine_ref.statemachine.run`을
 * 실행한 결과(2026-08-07 산출, docs/plans/M1_PLAN_A.md §2.10 W-S1 시나리오 그대로)와 대조한다.
 *
 * ```
 * from engine_ref import registry, statemachine as SM
 * cfg = registry.load_statemachine(); profile = cfg.profiles['mobile_daily']
 * ticks = [SM.Tick(22.0,1,False,False), SM.Tick(16.0,1,False,False)*5]
 * SM.run(ticks, profile, cfg)        # ['AMBER']*6
 * SM.run([ticks[5]], profile, cfg)   # ['GREEN']
 * ```
 */
class StateMachineTest {
    private val config by lazy { StatemachineConfigLoader.load(RepoConfigSource) }
    private val profile by lazy { config.profiles.getValue("mobile_daily") }

    private fun tick(
        composite: Double?,
        distinctAxes: Int = 1,
        anyCrit: Boolean = false,
        anyExtreme: Boolean = false,
    ) = Tick(composite, distinctAxes, anyCrit, anyExtreme)

    // ---- W-S1: 전량 fold vs 단일 틱 재산출 (AD-A11, 반려 A-9/A-12의 구분력 증인) ----

    @Test
    fun `W-S1 folding the whole history commits AMBER, replaying only the last tick alone gives GREEN`() {
        // D1: composite_gte(20) 충족 -> sustain=1(mobile_daily) 즉시 AMBER 진입.
        // D2..D6: 14 <= composite(16) < 20, any_crit=false, any_extreme=false ->
        // AMBER 유지 조건 충족(승격 미충족, exit_AMBER(14) 미충족) — "이어받았다"의 증인.
        val history = listOf(tick(22.0)) + List(5) { tick(16.0) }

        val folded = StateMachine.run(history, profile, config)
        assertEquals(List(6) { "AMBER" }, folded)

        // 구분력 증거: D6 단독으로 run()하면 GREEN에서 시작해 승격 조건(20 이상)을 못 만족한다.
        // fold를 run(listOf(오늘틱))으로 바꾸는 회귀가 있으면 이 단언이 잡는다.
        val singleTick = StateMachine.run(listOf(history.last()), profile, config)
        assertEquals(listOf("GREEN"), singleTick)
    }

    // ---- D-26 이스케이프-이탈 짝지음 ----

    @Test
    fun `D-26 any_extreme persisting blocks exit_ORANGE even while composite is well below the exit line`() {
        // ORANGE 진입: composite_gte 40, distinct_axes_gte 2 (or_any_extreme 미사용 경로).
        val enterOrange = tick(45.0, distinctAxes = 2)
        // composite=20 < exit_ORANGE(32) 이지만 any_extreme=true가 5틱 지속 -> 이탈 차단.
        val sustainedExtreme = List(5) { tick(20.0, distinctAxes = 1, anyExtreme = true) }

        val timeline = StateMachine.run(listOf(enterOrange) + sustainedExtreme, profile, config)

        assertEquals(List(6) { "ORANGE" }, timeline, "escape must block every exit check while any_extreme holds")
    }

    @Test
    fun `D-26 control -- without any_extreme the same composite sequence demotes back to AMBER`() {
        val enterOrange = tick(45.0, distinctAxes = 2)
        val noEscape = List(5) { tick(20.0, distinctAxes = 1, anyExtreme = false) }

        val timeline = StateMachine.run(listOf(enterOrange) + noEscape, profile, config)

        // demote_below_ticks=3, min_dwell_ticks=5 -> dwell(6번째 틱, ticksInPhase=6)에서 데못.
        assertEquals(listOf("ORANGE", "ORANGE", "ORANGE", "ORANGE", "ORANGE", "AMBER"), timeline)
    }

    // ---- D-25 §3: 전 지표 결측(composite=null)은 완전 동결 ----

    @Test
    fun `D-25-3 a null composite tick freezes phase and every counter, consuming no tick`() {
        val history = listOf(tick(22.0), tick(null), tick(16.0), tick(16.0))
        val timeline = StateMachine.run(history, profile, config)

        assertEquals("AMBER", timeline[0])
        assertEquals("AMBER", timeline[1], "frozen tick carries the prior phase forward unchanged")
        assertEquals("AMBER", timeline[2])
        assertEquals("AMBER", timeline[3])

        // 동결 틱이 없었을 때와 국면 진행이 동일해야 한다(카운터가 실제로 멈췄다는 증거) —
        // exit_AMBER 미충족 상태가 계속 유지되므로 두 실행 모두 AMBER를 유지한다.
        val withoutGap = StateMachine.run(listOf(tick(22.0), tick(16.0), tick(16.0)), profile, config)
        assertEquals(List(3) { "AMBER" }, withoutGap)
    }

    // ---- skip_levels: 조건 충족 시 단계를 건너뛴다 ----

    @Test
    fun `skip_levels jumps straight from GREEN to ORANGE when ORANGE conditions are met in one tick`() {
        // GREEN에서 AMBER(20)·ORANGE(40, distinct>=2) 조건을 동시에 만족하는 단일 틱.
        val timeline = StateMachine.run(listOf(tick(45.0, distinctAxes = 2)), profile, config)
        assertEquals(listOf("ORANGE"), timeline)
    }

    // ---- 멱등: 같은 입력을 두 번 재생해도 비트 동일 타임라인 (fold의 순수 함수 근거) ----

    @Test
    fun `run is a pure function -- replaying the same tick sequence twice yields identical timelines`() {
        val history = listOf(tick(22.0), tick(16.0), tick(45.0, distinctAxes = 2), tick(20.0, anyExtreme = true))
        assertEquals(StateMachine.run(history, profile, config), StateMachine.run(history, profile, config))
    }

    // ---- or_any_crit: AMBER 승격이 composite 미달이어도 anyCrit로 우회된다 ----

    @Test
    fun `or_any_crit promotes to AMBER even when composite is below composite_gte`() {
        val timeline = StateMachine.run(listOf(tick(5.0, anyCrit = true)), profile, config)
        assertEquals(listOf("AMBER"), timeline)
    }

    @Test
    fun `unrecognized upgrade rule keys fail fast instead of silently always-satisfying`() {
        val brokenConfig =
            config.copy(upgrade = config.upgrade + ("AMBER" to mapOf("unknown_key" to true)))
        assertFailsWith<IllegalStateException> { StateMachine.run(listOf(tick(1.0)), profile, brokenConfig) }
    }
}
