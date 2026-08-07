package com.branchconsole.app.onboarding

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// android.content.Intent construction needs the Android framework shims Robolectric provides --
// the pure-string guidanceText() tests don't strictly need it, but sharing one runner is simpler.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BatteryOptimizationHelperTest {
    @Test
    fun `samsung gets the battery-and-device-care guidance`() {
        assertTrue(BatteryOptimizationHelper.guidanceText("Samsung").contains("디바이스 케어"))
    }

    @Test
    fun `case-insensitive samsung match`() {
        assertTrue(BatteryOptimizationHelper.guidanceText("SAMSUNG").contains("디바이스 케어"))
    }

    @Test
    fun `every other manufacturer gets the generic guidance`() {
        val text = BatteryOptimizationHelper.guidanceText("Xiaomi")
        assertTrue(text.contains("배터리 최적화"))
        assertTrue(!text.contains("디바이스 케어"))
    }

    @Test
    fun `settings intent targets the ignore-battery-optimizations list`() {
        val action = BatteryOptimizationHelper.openSettingsIntent().action
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, action)
    }
}
