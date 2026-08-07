package com.branchconsole.engine.scoring

import com.branchconsole.engine.config.HyLevelBoost
import com.branchconsole.engine.config.UsdkrwIntradayForce
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private const val EPS = 1e-9

/**
 * `engine_ref/modifiers.py` 대조 표본(Python 대조, 2026-08-07 산출).
 *
 * ```
 * from engine_ref import modifiers as M
 * from engine_ref.registry import HyLevelBoost, UsdkrwIntradayForce
 * hy = HyLevelBoost(level_threshold=4.5, increment=1, max_severity=3)
 * M.apply_hy_level_boost(None, 5.0, hy)   # None
 * M.apply_hy_level_boost(2, 5.0, hy)      # 3 (2+1)
 * M.apply_hy_level_boost(3, 5.0, hy)      # 3 (capped)
 * M.apply_hy_level_boost(2, 4.0, hy)      # 2 (below threshold, no boost)
 * fx = UsdkrwIntradayForce(warn_threshold=1.2, crit_threshold=2.0)
 * M.apply_usdkrw_intraday_force(None, 2.5, fx)  # 3 (crit from missing base)
 * M.apply_usdkrw_intraday_force(None, 1.5, fx)  # 2 (warn from missing base)
 * M.apply_usdkrw_intraday_force(1, 1.5, fx)     # 2 (watch -> warn forced)
 * M.apply_usdkrw_intraday_force(1, 0.5, fx)     # 1 (below both, unchanged)
 * M.usdkrw_intraday_range(1300.0, 1280.0, 1290.0)  # 1.550387596899225
 * ```
 */
class ModifiersTest {
    private val hy = HyLevelBoost(levelThreshold = 4.5, increment = 1, maxSeverity = 3)
    private val fx = UsdkrwIntradayForce(warnThreshold = 1.2, critThreshold = 2.0)

    @Test
    fun `hy level boost is null-safe and threshold is exclusive (strictly greater than)`() {
        assertNull(Modifiers.applyHyLevelBoost(null, 5.0, hy))
        assertEquals(3, Modifiers.applyHyLevelBoost(2, 5.0, hy))
        assertEquals(3, Modifiers.applyHyLevelBoost(3, 5.0, hy), "capped at maxSeverity")
        assertEquals(2, Modifiers.applyHyLevelBoost(2, 4.0, hy), "at or below threshold: no boost")
    }

    @Test
    fun `hy level boost threshold equality does not fire (exclusive, not inclusive)`() {
        assertEquals(2, Modifiers.applyHyLevelBoost(2, 4.5, hy), "exactly at threshold must not boost")
    }

    @Test
    fun `usdkrw intraday force promotes a missing base severity to a concrete value`() {
        // 결측 기저 승급 — 브리프 명시 요구.
        assertEquals(3, Modifiers.applyUsdkrwIntradayForce(null, 2.5, fx))
        assertEquals(2, Modifiers.applyUsdkrwIntradayForce(null, 1.5, fx))
    }

    @Test
    fun `usdkrw intraday force only raises severity, never lowers it`() {
        assertEquals(2, Modifiers.applyUsdkrwIntradayForce(1, 1.5, fx))
        assertEquals(1, Modifiers.applyUsdkrwIntradayForce(1, 0.5, fx), "below both thresholds: unchanged")
    }

    @Test
    fun `usdkrw intraday force thresholds are inclusive (equality fires)`() {
        assertEquals(3, Modifiers.applyUsdkrwIntradayForce(0, 2.0, fx))
        assertEquals(2, Modifiers.applyUsdkrwIntradayForce(0, 1.2, fx))
    }

    @Test
    fun `usdkrwIntradayRange computes the correct percentage`() {
        assertEquals(1.550387596899225, Modifiers.usdkrwIntradayRange(1300.0, 1280.0, 1290.0), EPS)
    }

    @Test
    fun `usdkrwIntradayRange fails fast on zero prevClose instead of returning Infinity or NaN`() {
        assertFailsWith<IllegalStateException> { Modifiers.usdkrwIntradayRange(1300.0, 1280.0, 0.0) }
    }
}
