package com.branchconsole.engine.config

private const val DEFAULT_REENTRY_COOLDOWN = 0

/** `engine_ref.registry.ProfileParams` 1:1 이식 — statemachine 틱 카운트 파라미터(D-16, 프로파일
 * 무관 전이 구조와 달리 이것만 프로파일별로 다르다). */
data class ProfileParams(
    val promoteSustainTicks: Int,
    val demoteBelowTicks: Int,
    val minDwellTicks: Int,
    /** 미정의 시 0(Advisor 지정 해석 — mobile_daily는 statemachine.yaml에 2로 명시). */
    val reentryCooldownTicks: Int = DEFAULT_REENTRY_COOLDOWN,
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
