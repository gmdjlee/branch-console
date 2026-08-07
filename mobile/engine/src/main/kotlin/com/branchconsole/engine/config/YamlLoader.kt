package com.branchconsole.engine.config

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/**
 * snakeyaml-engine으로 YAML을 (이름 -> Map) 형태로 파싱하는 최소 헬퍼 —
 * `engine_ref.registry._load_yaml`과 동형(dict 반환, 타입 접근은 호출부 몫).
 */
internal object YamlLoader {
    @Suppress("UNCHECKED_CAST")
    fun loadMap(
        source: ConfigSource,
        name: String,
    ): Map<String, Any?> {
        val loaded = source.open(name).use { Load(LoadSettings.builder().build()).loadFromInputStream(it) }
        return loaded as? Map<String, Any?> ?: error("$name: top-level YAML node is not a map")
    }
}

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.asMap(key: String): Map<String, Any?> =
    this[key] as? Map<String, Any?> ?: error("missing or malformed map key: '$key' in $this")

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.asListOfMaps(key: String): List<Map<String, Any?>> =
    (this[key] as? List<*>)?.map { it as Map<String, Any?> } ?: error("missing or malformed list key: '$key'")

internal fun Map<String, Any?>.asDouble(key: String): Double =
    (this[key] as? Number)?.toDouble() ?: error("missing or malformed numeric key: '$key' in $this")

internal fun Map<String, Any?>.asInt(key: String): Int =
    (this[key] as? Number)?.toInt() ?: error("missing or malformed int key: '$key' in $this")

internal fun Map<String, Any?>.asString(key: String): String =
    this[key] as? String ?: error("missing or malformed string key: '$key' in $this")

internal fun Map<String, Any?>.asBoolOrDefault(
    key: String,
    default: Boolean,
): Boolean = (this[key] as? Boolean) ?: default

/** 평평한 thresholds({watch,warn,crit[,extreme]}) 맵을 Double 맵으로. 중첩 구조
 * (spx_drawdown_momentum의 drawdown/neg_z)는 호출부가 하위 맵을 먼저 꺼내 넘긴다. */
@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.asFlatThresholds(): Map<String, Double> =
    mapValues { (_, v) ->
        (v as Number).toDouble()
    }
