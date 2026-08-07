package com.branchconsole.app.tick

import com.branchconsole.engine.config.ConfigSource
import com.branchconsole.engine.config.HyLevelBoost
import com.branchconsole.engine.config.IndicatorRegistry
import com.branchconsole.engine.config.IndicatorSpec
import com.branchconsole.engine.config.ModifierRules
import com.branchconsole.engine.config.StaleWindows
import com.branchconsole.engine.config.StatemachineConfig
import com.branchconsole.engine.config.StatemachineConfigLoader
import com.branchconsole.engine.config.UsdkrwIntradayForce
import java.time.Duration
import java.time.LocalTime

private const val MOBILE_DAILY = "mobile_daily"

/** 확정 틱 파이프라인 1회 실행에 필요한, 창과 무관한 고정 설정 — 배치 전체에서 1회만 로드해
 * 재사용한다(ParityEngine.ParityConfig와 동형, docs/plans/M1_PLAN_D.md §2.10). */
internal data class ConfirmTickConfig(
    val specs: List<IndicatorSpec>,
    val weights: Map<String, Double>,
    val axes: Map<String, String>,
    val maxSeverities: Map<String, Int>,
    val fredLagDays: Map<String, Long>,
    val staleWindows: Map<String, Duration>,
    val statemachineConfig: StatemachineConfig,
    val modifiers: Pair<HyLevelBoost, UsdkrwIntradayForce>,
    val confirmTimeKst: LocalTime,
    val catchupMaxTicks: Int,
    val registryVersion: String,
    val profileName: String = MOBILE_DAILY,
)

/**
 * `configs/{indicators,statemachine}.yaml`(assets)에서 [ConfirmTickConfig]를 조립한다.
 *
 * `confirm_time_kst`/`catchup_max_ticks` 부재는 **여기서 즉시 명시 실패**한다(브리프 aaa 요건 1·
 * CLAUDE.md §1) — [com.branchconsole.engine.config.ProfileParams]는 조용히 null을 반환하도록
 * 두고(:engine은 SSOT 부재를 정책적으로 판단하지 않는다), 실패 여부 판단은 이 조립 지점(:app)의
 * 책임이다.
 */
internal object ConfirmTickConfigLoader {
    fun load(source: ConfigSource): ConfirmTickConfig {
        val specs = IndicatorRegistry.loadIndicatorSpecs(source, enabledOnly = true)
        val cadences = specs.mapNotNull { it.source["cadence"] as? String }.toSet()
        val statemachineConfig = StatemachineConfigLoader.load(source)
        val profile =
            statemachineConfig.profiles[MOBILE_DAILY]
                ?: error("profiles.$MOBILE_DAILY missing from statemachine.yaml")
        val confirmTimeKst =
            profile.confirmTimeKst ?: error(
                "profiles.$MOBILE_DAILY.confirm_time_kst missing from statemachine.yaml — " +
                    "AD-3b 확정 시각 실측(MT1-00g)이 아직 완료되지 않았다(PROGRESS.md). " +
                    "확정 틱 파이프라인은 이 키가 채워지기 전까지 실행할 수 없다(조용한 17:00 기본값 금지).",
            )
        val catchupMaxTicks =
            profile.catchupMaxTicks
                ?: error("profiles.$MOBILE_DAILY.catchup_max_ticks missing from statemachine.yaml (M-17b)")
        return ConfirmTickConfig(
            specs = specs,
            weights = IndicatorRegistry.weightMap(specs),
            axes = IndicatorRegistry.axisMap(specs),
            maxSeverities = IndicatorRegistry.maxSeverityMap(specs),
            fredLagDays = fredLagDaysOf(specs),
            staleWindows = cadences.associateWith { StaleWindows.staleWindow(source, MOBILE_DAILY, it) },
            statemachineConfig = statemachineConfig,
            modifiers = ModifierRules.loadModifiers(source),
            confirmTimeKst = confirmTimeKst,
            catchupMaxTicks = catchupMaxTicks,
            registryVersion = IndicatorRegistry.registryVersion(source),
        )
    }
}
