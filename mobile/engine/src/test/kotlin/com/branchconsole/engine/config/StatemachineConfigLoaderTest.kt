package com.branchconsole.engine.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatemachineConfigLoaderTest {
    private val config by lazy { StatemachineConfigLoader.load(RepoConfigSource) }

    @Test
    fun `phases and initial phase match the SSOT`() {
        assertEquals(listOf("GREEN", "AMBER", "ORANGE", "RED"), config.phases)
        assertEquals("GREEN", config.initialPhase)
        assertTrue(config.skipLevels)
    }

    @Test
    fun `mobile_daily profile params match the BT-03 sweep selection`() {
        val profile = config.profiles.getValue("mobile_daily")
        assertEquals(1, profile.promoteSustainTicks)
        assertEquals(3, profile.demoteBelowTicks)
        assertEquals(5, profile.minDwellTicks)
        assertEquals(2, profile.reentryCooldownTicks)
    }

    @Test
    fun `server_intraday profile reentry_cooldown_ticks defaults are read, not the missing-key fallback`() {
        val profile = config.profiles.getValue("server_intraday")
        assertEquals(2, profile.promoteSustainTicks)
        assertEquals(6, profile.demoteBelowTicks)
        assertEquals(4, profile.minDwellTicks)
        assertEquals(6, profile.reentryCooldownTicks)
    }

    @Test
    fun `upgrade and downgrade rules carry the or_any escape keys exactly where the SSOT declares them`() {
        val amber = config.upgrade.getValue("AMBER")
        val orange = config.upgrade.getValue("ORANGE")
        val red = config.upgrade.getValue("RED")

        assertEquals(20, (amber["composite_gte"] as Number).toInt())
        assertEquals(true, amber["or_any_crit"])
        assertEquals(null, amber["or_any_extreme"])

        assertEquals(40, (orange["composite_gte"] as Number).toInt())
        assertEquals(2, (orange["distinct_axes_gte"] as Number).toInt())
        assertEquals(true, orange["or_any_extreme"])
        assertEquals(null, orange["or_any_crit"])

        assertEquals(60, (red["composite_gte"] as Number).toInt())
        assertEquals(3, (red["distinct_axes_gte"] as Number).toInt())
        assertEquals(null, red["or_any_crit"])
        assertEquals(null, red["or_any_extreme"])

        assertEquals(50.0, (config.downgrade.getValue("exit_RED")["composite_lt"] as Number).toDouble())
        assertEquals(32.0, (config.downgrade.getValue("exit_ORANGE")["composite_lt"] as Number).toDouble())
        assertEquals(14.0, (config.downgrade.getValue("exit_AMBER")["composite_lt"] as Number).toDouble())
    }
}
