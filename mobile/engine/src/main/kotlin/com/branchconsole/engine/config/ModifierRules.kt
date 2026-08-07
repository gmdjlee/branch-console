package com.branchconsole.engine.config

data class HyLevelBoost(
    /** rule 문자열의 "> X" 값(등호 미포함 — 초과만 발화). */
    val levelThreshold: Double,
    /** rule 문자열의 "+= N" 값(하드코딩 금지 — 여기서만 파싱). */
    val increment: Int,
    /** "(max N)" 캡. */
    val maxSeverity: Int,
)

data class UsdkrwIntradayForce(
    /** ">= X%" → severity >= warn 강제. */
    val warnThreshold: Double,
    /** ">= Y%" → severity crit 강제. */
    val critThreshold: Double,
)

private const val MOD_NUM_PATTERN = """[-+]?\d+(?:\.\d+)?"""

/** `configs/indicators.yaml` `engine.modifiers` 규칙 문자열 파서 — `engine_ref.registry`의
 * `_parse_hy_level_boost`/`_parse_usdkrw_intraday_force`/`load_modifiers` 1:1 이식. */
object ModifierRules {
    private val HY_LEVEL_RE = Regex(""">\s*($MOD_NUM_PATTERN)""")
    private val INCREMENT_RE = Regex("""\+=\s*(\d+)""")
    private val CAP_RE = Regex("""max\s+(\d+)""")

    /** 예: "hy_oas_level > 4.5 -> hy_oas_delta.severity += 1 (max 3)". */
    fun parseHyLevelBoost(rule: String): HyLevelBoost {
        val level = HY_LEVEL_RE.find(rule) ?: error("malformed hy_level_boost rule: '$rule'")
        val increment = INCREMENT_RE.find(rule) ?: error("malformed hy_level_boost rule: '$rule'")
        val cap = CAP_RE.find(rule) ?: error("malformed hy_level_boost rule: '$rule'")
        return HyLevelBoost(
            levelThreshold = level.groupValues[1].toDouble(),
            increment = increment.groupValues[1].toInt(),
            maxSeverity = cap.groupValues[1].toInt(),
        )
    }

    private val PERCENT_NUM_RE = Regex("""($MOD_NUM_PATTERN)%""")

    /** 예: "usdkrw intraday_range >= 1.2% -> severity max(warn); >= 2.0% -> crit". */
    fun parseUsdkrwIntradayForce(rule: String): UsdkrwIntradayForce {
        val nums = PERCENT_NUM_RE.findAll(rule).map { it.groupValues[1].toDouble() }.toList()
        check(nums.size >= 2) { "malformed usdkrw_intraday_force rule: '$rule'" }
        return UsdkrwIntradayForce(warnThreshold = nums[0], critThreshold = nums[1])
    }

    fun loadModifiers(source: ConfigSource): Pair<HyLevelBoost, UsdkrwIntradayForce> {
        val root = YamlLoader.loadMap(source, "indicators.yaml")
        val rules =
            root
                .asMap("engine")
                .asListOfMaps("modifiers")
                .associate { it.asString("id") to it.asString("rule") }
        return parseHyLevelBoost(rules.getValue("hy_level_boost")) to
            parseUsdkrwIntradayForce(rules.getValue("usdkrw_intraday_force"))
    }
}
