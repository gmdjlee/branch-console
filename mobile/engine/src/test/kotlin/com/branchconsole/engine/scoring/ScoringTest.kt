package com.branchconsole.engine.scoring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val EPS = 1e-9

/**
 * `engine_ref/scoring.py` 대조 표본(Python 대조, 2026-08-07 산출) + D-25 §3 결측 동결 증인.
 *
 * ```
 * from engine_ref import scoring as S
 * th = {'watch':1.5,'warn':2.0,'crit':3.0}
 * [S.classify_severity(v, th) for v in [1.0,1.5,2.0,3.0,3.5,None]]   # [0,1,2,3,3,None]
 * th_extreme = {'watch':3.0,'warn':4.5,'crit':7.0,'extreme':20.0}
 * [S.is_extreme(v, th_extreme) for v in [10.0,19.9,20.0,20.1,None]]  # [F,F,T,T,F]
 * sev = {'a':3,'b':2,'c':None,'d':0}; w = {'a':3.0,'b':2.5,'c':1.5,'d':1.0}
 * S.compute_composite(sev, w)   # CompositeResult(score=71.7948717948718, coverage=0.8125)
 * axes = {'a':'x','b':'y','c':'x','d':'z'}
 * S.distinct_axes(sev, axes)    # 2
 * ```
 */
class ScoringTest {
    private val flatThresholds = mapOf("watch" to 1.5, "warn" to 2.0, "crit" to 3.0)
    private val extremeThresholds = mapOf("watch" to 3.0, "warn" to 4.5, "crit" to 7.0, "extreme" to 20.0)

    @Test
    fun `classifySeverity ladder is inclusive at the boundary (equality fires the tier)`() {
        assertEquals(0, Scoring.classifySeverity(1.0, flatThresholds))
        assertEquals(1, Scoring.classifySeverity(1.5, flatThresholds))
        assertEquals(2, Scoring.classifySeverity(2.0, flatThresholds))
        assertEquals(3, Scoring.classifySeverity(3.0, flatThresholds))
        assertEquals(3, Scoring.classifySeverity(3.5, flatThresholds))
    }

    @Test
    fun `classifySeverity of NaN (missing) is null, not zero`() {
        assertNull(Scoring.classifySeverity(Double.NaN, flatThresholds))
    }

    @Test
    fun `classifySeverity default maxSeverity=3 ignores an extreme key entirely (option A isolation)`() {
        // AD-9(a)(i): extreme 키가 있어도 maxSeverity 기본값(3)에서는 절대 보지 않는다.
        assertEquals(3, Scoring.classifySeverity(25.0, extremeThresholds, maxSeverity = 3))
    }

    @Test
    fun `classifySeverity maxSeverity=4 promotes past the extreme threshold`() {
        assertEquals(3, Scoring.classifySeverity(19.9, extremeThresholds, maxSeverity = 4))
        assertEquals(4, Scoring.classifySeverity(20.0, extremeThresholds, maxSeverity = 4))
    }

    @Test
    fun `isExtreme boundary is inclusive and absent-key or missing value is always false`() {
        assertEquals(false, Scoring.isExtreme(10.0, extremeThresholds))
        assertEquals(false, Scoring.isExtreme(19.9, extremeThresholds))
        assertEquals(true, Scoring.isExtreme(20.0, extremeThresholds))
        assertEquals(true, Scoring.isExtreme(20.1, extremeThresholds))
        assertEquals(false, Scoring.isExtreme(Double.NaN, extremeThresholds))
        assertEquals(false, Scoring.isExtreme(999.0, flatThresholds), "no 'extreme' key -> always false")
    }

    @Test
    fun `combineMaxSeverity takes the surviving component when one side is missing`() {
        val dd = mapOf("watch" to 3.0, "warn" to 5.0, "crit" to 8.0)
        val negZ = mapOf("watch" to 1.5, "warn" to 2.0, "crit" to 3.0)
        assertEquals(2, Scoring.combineMaxSeverity(5.0, dd, Double.NaN, negZ)) // dd only -> warn(2)
        assertNull(Scoring.combineMaxSeverity(Double.NaN, dd, Double.NaN, negZ))
        assertEquals(3, Scoring.combineMaxSeverity(9.0, dd, 1.0, negZ), "max of the two component severities")
    }

    @Test
    fun `computeComposite excludes missing indicators from both numerator and denominator`() {
        val severities = linkedMapOf("a" to 3, "b" to 2, "c" to null, "d" to 0)
        val weights = mapOf("a" to 3.0, "b" to 2.5, "c" to 1.5, "d" to 1.0)

        val result = Scoring.computeComposite(severities, weights)

        assertEquals(71.7948717948718, result.score!!, EPS)
        assertEquals(0.8125, result.coverage, EPS)
    }

    @Test
    fun `computeComposite score is null when every indicator is missing (D-25 3 evaluation impossible)`() {
        val severities = linkedMapOf("a" to null, "b" to null)
        val weights = mapOf("a" to 3.0, "b" to 2.5)

        val result = Scoring.computeComposite(severities, weights)

        assertNull(result.score)
        assertEquals(0.0, result.coverage, EPS)
    }

    @Test
    fun `distinctAxes counts warn-or-above indicators only, deduplicated by axis`() {
        val severities = linkedMapOf("a" to 3, "b" to 2, "c" to null, "d" to 0)
        val axes = mapOf("a" to "x", "b" to "y", "c" to "x", "d" to "z")
        assertEquals(2, Scoring.distinctAxes(severities, axes))
    }

    @Test
    fun `computeComposite with per-indicator maxSeverities (AD-7 option B) changes the denominator`() {
        val severities = linkedMapOf("a" to 4, "b" to 2)
        val weights = mapOf("a" to 1.0, "b" to 1.0)
        val maxSeverities = mapOf("a" to 4, "b" to 3)

        val result = Scoring.computeComposite(severities, weights, maxSeverities)

        // num = 1*4 + 1*2 = 6; den = 1*4 + 1*3 = 7; total = 7 (both indicators covered).
        assertEquals(100.0 * 6.0 / 7.0, result.score!!, EPS)
        assertEquals(1.0, result.coverage, EPS)
    }
}
