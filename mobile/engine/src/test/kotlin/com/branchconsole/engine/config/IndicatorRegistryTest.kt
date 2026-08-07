package com.branchconsole.engine.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IndicatorRegistryTest {
    @Test
    fun `loadIndicatorSpecs enabled-only returns exactly the 15 D-01 active indicators`() {
        val specs = IndicatorRegistry.loadIndicatorSpecs(RepoConfigSource, enabledOnly = true)
        assertEquals(15, specs.size)
        assertTrue("krx_halt_events" !in specs.map { it.id }, "enabled:false must be excluded")
        assertTrue("news_volume_z" !in specs.map { it.id })
    }

    @Test
    fun `loadIndicatorSpecs enabledOnly=false includes the disabled P2 indicators too`() {
        val specs = IndicatorRegistry.loadIndicatorSpecs(RepoConfigSource, enabledOnly = false)
        assertTrue(specs.size > 15)
        assertTrue("krx_halt_events" in specs.map { it.id })
    }

    @Test
    fun `weightMap axisMap and maxSeverityMap preserve declaration order and values`() {
        val specs = IndicatorRegistry.loadIndicatorSpecs(RepoConfigSource, enabledOnly = true)
        val weights = IndicatorRegistry.weightMap(specs)
        val axes = IndicatorRegistry.axisMap(specs)
        val maxSeverities = IndicatorRegistry.maxSeverityMap(specs)

        assertEquals(3.0, weights.getValue("vix_level_z"))
        assertEquals("vol_global", axes.getValue("vix_level_z"))
        assertEquals(3, maxSeverities.getValue("vix_level_z"), "no max_severity key in yaml -> default 3")
        // 합계 31.0 — D-01/D-25 §3 coverage 분모(enabled 15지표 가중 합).
        assertEquals(31.0, weights.values.sum(), 1e-9)
        // LinkedHashMap이므로 iteration 순서가 곧 삽입 순서 = yaml 선언 순서(파리티 지뢰 3).
        assertEquals("vix_level_z", weights.keys.first())
    }

    @Test
    fun `vkospi_z spec carries the K-02 fallback identifier verbatim`() {
        val spec =
            IndicatorRegistry.loadIndicatorSpecs(RepoConfigSource, enabledOnly = true).first { it.id == "vkospi_z" }
        assertEquals("realized_vol_kospi_20d", spec.source["fallback"])
    }

    @Test
    fun `spx_drawdown_momentum thresholds are a nested map, not flat`() {
        val spec =
            IndicatorRegistry.loadIndicatorSpecs(RepoConfigSource, enabledOnly = true)
                .first { it.id == "spx_drawdown_momentum" }

        @Suppress("UNCHECKED_CAST")
        val drawdown = spec.thresholds["drawdown"] as Map<String, Any?>
        assertEquals(8.0, (drawdown["crit"] as Number).toDouble())
    }
}
