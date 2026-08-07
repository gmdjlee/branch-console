package com.branchconsole.engine.config

import kotlin.test.Test
import kotlin.test.assertEquals

class ModifierRulesTest {
    @Test
    fun `parseHyLevelBoost extracts threshold, increment and cap from the rule sentence`() {
        val rule = "hy_oas_level > 4.5 -> hy_oas_delta.severity += 1 (max 3)"
        val boost = ModifierRules.parseHyLevelBoost(rule)
        assertEquals(4.5, boost.levelThreshold)
        assertEquals(1, boost.increment)
        assertEquals(3, boost.maxSeverity)
    }

    @Test
    fun `parseUsdkrwIntradayForce extracts warn and crit percentages in order`() {
        val rule = "usdkrw intraday_range >= 1.2% -> severity max(warn); >= 2.0% -> crit"
        val fx = ModifierRules.parseUsdkrwIntradayForce(rule)
        assertEquals(1.2, fx.warnThreshold)
        assertEquals(2.0, fx.critThreshold)
    }

    @Test
    fun `loadModifiers reads both rules from the real configs indicators yaml`() {
        val (hy, fx) = ModifierRules.loadModifiers(RepoConfigSource)
        // configs/indicators.yaml engine.modifiers 현재값(SSOT — 변경되면 이 테스트가 먼저
        // 깨져 드리프트를 알린다).
        assertEquals(4.5, hy.levelThreshold)
        assertEquals(1, hy.increment)
        assertEquals(3, hy.maxSeverity)
        assertEquals(1.2, fx.warnThreshold)
        assertEquals(2.0, fx.critThreshold)
    }
}
