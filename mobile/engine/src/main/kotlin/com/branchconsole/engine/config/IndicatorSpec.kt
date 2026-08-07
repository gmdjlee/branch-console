package com.branchconsole.engine.config

/**
 * `engine_ref.registry.IndicatorSpec` 1:1 이식. `thresholds`는 평평한
 * `{watch,warn,crit[,extreme]}`이거나 `spx_drawdown_momentum`류 중첩 맵(`drawdown`/`neg_z`
 * 각각 하위 맵)이다 — 타입을 `Any?`로 열어두고 [asFlatThresholds]로 필요한 층만 꺼낸다.
 */
data class IndicatorSpec(
    val id: String,
    val axis: String,
    val weight: Double,
    val direction: String,
    val thresholds: Map<String, Any?>,
    val transform: String,
    val source: Map<String, Any?>,
    val optional: Boolean = false,
    val maxSeverity: Int = DEFAULT_MAX_SEVERITY,
) {
    companion object {
        const val DEFAULT_MAX_SEVERITY = 3
    }
}

/** `configs/indicators.yaml` 로더 + 파생 맵(weight/axis/maxSeverity) — 순서는 항상
 * `LinkedHashMap`(YAML `indicators:` 선언 순서)으로 보존한다(부동소수 합산 순서, 파리티 지뢰 3). */
object IndicatorRegistry {
    fun loadIndicatorSpecs(
        source: ConfigSource,
        enabledOnly: Boolean = true,
    ): List<IndicatorSpec> {
        val root = YamlLoader.loadMap(source, "indicators.yaml")
        return root
            .asListOfMaps("indicators")
            .filter { !enabledOnly || it.asBoolOrDefault("enabled", true) }
            .map(::toSpec)
    }

    private fun toSpec(item: Map<String, Any?>): IndicatorSpec =
        IndicatorSpec(
            id = item.asString("id"),
            axis = item.asString("axis"),
            weight = item.asDouble("weight"),
            direction = item.asString("direction"),
            thresholds = item.asMap("thresholds"),
            transform = item.asString("transform"),
            source = item.asMap("source"),
            optional = item.asBoolOrDefault("optional", false),
            maxSeverity = (item["max_severity"] as? Number)?.toInt() ?: IndicatorSpec.DEFAULT_MAX_SEVERITY,
        )

    fun weightMap(specs: List<IndicatorSpec>): Map<String, Double> =
        LinkedHashMap<String, Double>(specs.size).apply { specs.forEach { put(it.id, it.weight) } }

    fun axisMap(specs: List<IndicatorSpec>): Map<String, String> =
        LinkedHashMap<String, String>(specs.size).apply { specs.forEach { put(it.id, it.axis) } }

    fun maxSeverityMap(specs: List<IndicatorSpec>): Map<String, Int> =
        LinkedHashMap<String, Int>(specs.size).apply { specs.forEach { put(it.id, it.maxSeverity) } }
}
