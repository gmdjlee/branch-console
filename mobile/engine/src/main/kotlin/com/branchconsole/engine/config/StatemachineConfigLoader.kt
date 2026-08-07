package com.branchconsole.engine.config

import java.time.LocalTime

private const val DEFAULT_REENTRY_COOLDOWN = 0

/** `engine_ref.registry.ProfileParams` 1:1 이식 — statemachine 틱 카운트 파라미터(D-16, 프로파일
 * 무관 전이 구조와 달리 이것만 프로파일별로 다르다).
 *
 * `confirmTimeKst`/`catchupMaxTicks`는 `mobile_daily` 프로파일 전용 확장(M1_PLAN_FINAL.md
 * M-05/06·M-17b) — `server_intraday`에는 없으므로 둘 다 nullable이다. `confirmTimeKst`는
 * MT1-00g 실측이 아직 완료되지 않아 SSOT(`statemachine.yaml`)에 값이 없다(2026-08-07 현재,
 * PROGRESS.md) — 이 로더는 그 부재를 조용한 기본값(예: 17:00 리터럴)으로 메우지 않는다.
 * **호출부가 확정 틱 파이프라인을 조립할 때 null을 명시적으로 실패시켜야 한다**
 * (CLAUDE.md §1 SSOT 규율 — 코드 리터럴 금지, MT1-06 브리프 aaa 요건 1).
 */
data class ProfileParams(
    val promoteSustainTicks: Int,
    val demoteBelowTicks: Int,
    val minDwellTicks: Int,
    /** 미정의 시 0(Advisor 지정 해석 — mobile_daily는 statemachine.yaml에 2로 명시). */
    val reentryCooldownTicks: Int = DEFAULT_REENTRY_COOLDOWN,
    /** `profiles.mobile_daily.confirm_time_kst` — 미기입이면 null(측정 대기, 기본값 없음). */
    val confirmTimeKst: LocalTime? = null,
    /** `profiles.mobile_daily.catchup_max_ticks` — M-17b, 20으로 SSOT 기입됨. */
    val catchupMaxTicks: Int? = null,
)

/** `engine_ref.registry.StatemachineConfig` 1:1 이식. */
data class StatemachineConfig(
    val phases: List<String>,
    val initialPhase: String,
    val upgrade: Map<String, Map<String, Any?>>,
    val downgrade: Map<String, Map<String, Any?>>,
    val skipLevels: Boolean,
    val profiles: Map<String, ProfileParams>,
)

/** `configs/statemachine.yaml` 로더 — `engine_ref.registry.load_statemachine` 1:1 이식. */
object StatemachineConfigLoader {
    @Suppress("UNCHECKED_CAST")
    fun load(source: ConfigSource): StatemachineConfig {
        val root = YamlLoader.loadMap(source, "statemachine.yaml")
        val profiles =
            root.asMap("profiles").mapValues { (_, raw) ->
                val p = raw as Map<String, Any?>
                ProfileParams(
                    promoteSustainTicks = p.asInt("promote_sustain_ticks"),
                    demoteBelowTicks = p.asInt("demote_below_ticks"),
                    minDwellTicks = p.asInt("min_dwell_ticks"),
                    reentryCooldownTicks =
                        (p["reentry_cooldown_ticks"] as? Number)?.toInt() ?: DEFAULT_REENTRY_COOLDOWN,
                    confirmTimeKst = (p["confirm_time_kst"] as? String)?.let { LocalTime.parse(it) },
                    catchupMaxTicks = (p["catchup_max_ticks"] as? Number)?.toInt(),
                )
            }
        return StatemachineConfig(
            phases = (root["phases"] as List<*>).map { it as String },
            initialPhase = root.asString("initial_phase"),
            upgrade = root.asMap("upgrade").asMap("rules") as Map<String, Map<String, Any?>>,
            downgrade = root.asMap("downgrade").asMap("rules") as Map<String, Map<String, Any?>>,
            skipLevels = root["skip_levels"] as Boolean,
            profiles = profiles,
        )
    }
}
