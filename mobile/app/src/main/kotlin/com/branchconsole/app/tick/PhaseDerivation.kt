package com.branchconsole.app.tick

import com.branchconsole.engine.config.StatemachineConfig
import com.branchconsole.engine.statemachine.StateMachine
import com.branchconsole.engine.statemachine.Tick
import com.branchconsole.lake.TickInputDao
import com.branchconsole.lake.TickInputEntity

/**
 * 국면 산출 = 전량 fold (docs/plans/M1_PLAN_A.md AD-A11, M1_PLAN_D.md §2.7). `tick_input`은
 * fold 입력 4열(`composite`·`distinct_axes`·`any_crit`·`any_extreme`)만 갖고 phase 열 자체는
 * 저장하지 않는다 — 오늘의 국면은 매번 `StateMachine.run`으로 **재도출**한다(상태를 이어받지
 * 않는다, W-S1~W-S4는 :engine `StateMachineTest`가 이미 증명). 이 파일은 그 fold를
 * `tick_input` 원장에 실제로 연결하는 최소 지점 — MT1-08(홈·노티)이 "오늘 국면"을 물을 때
 * 재사용한다.
 */
internal fun TickInputEntity.toTick(): Tick =
    Tick(composite = composite, distinctAxes = distinctAxes, anyCrit = anyCrit, anyExtreme = anyExtreme)

internal object PhaseDerivation {
    /** `tick_input` 전량을 fold해 오늘(마지막 원소)의 국면을 반환. 0행이면 null(설치 직후). */
    suspend fun currentPhase(
        tickInputDao: TickInputDao,
        profileName: String,
        statemachineConfig: StatemachineConfig,
    ): String? {
        val rows = tickInputDao.allOrderedByDate()
        if (rows.isEmpty()) return null
        val profile = statemachineConfig.profiles.getValue(profileName)
        return StateMachine.run(rows.map { it.toTick() }, profile, statemachineConfig).last()
    }
}
