package com.branchconsole.engine.transforms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val EPS = 1e-9

/**
 * `engine_ref/transforms.py` 대조 표본(Python 대조) + 경계 등호·결측 분모 증인.
 *
 * 표본 산출 근거(브리프 "Python 대조 표본" — engine_ref로 손 산출): 아래 커맨드를
 * `uv run python -c "..."`로 실행해 `engine_ref.transforms`의 실제 출력을 얻었다(2026-08-07,
 * 작업 디렉토리 저장소 루트). 값은 이 테스트에 하드코딩 픽스처로 고정한다.
 *
 * ```
 * from engine_ref import transforms as T
 * import pandas as pd
 * T.zscore(pd.Series([1.0,2,3,4,5,6,7,8,9,10]), window=5)
 * T.drawdown_from_high(pd.Series([10.0,12,11,15,9,8,20,19]), window=4)
 * T.zscore(pd.Series([1.0,2,float('nan'),4,5,6,7,8]), window=3)
 * T.gate_mask(pd.Series([0.0,-1,1,float('nan'),-2]), '<', 0.0)
 * T.gate_mask(pd.Series([0.0,-1,1,float('nan'),-2]), '>=', 0.0)
 * T.rolling_sum(pd.Series([1.0,2,3]), window=2)
 * T.neg_zscore(pd.Series([1.0,2,3,4,5]), window=3)
 * ```
 */
class TransformsTest {
    private fun assertArrayCloseWithNaN(
        expected: DoubleArray,
        actual: DoubleArray,
    ) {
        assertEquals(expected.size, actual.size, "size mismatch")
        for (i in expected.indices) {
            if (expected[i].isNaN()) {
                assertTrue(actual[i].isNaN(), "index $i: expected NaN, was ${actual[i]}")
            } else {
                assertEquals(expected[i], actual[i], EPS, "index $i")
            }
        }
    }

    // ---- Python 대조 표본 ----

    @Test
    fun `zscore matches engine_ref on ascending integers window 5`() {
        val x = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0)
        val expected =
            doubleArrayOf(
                Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                1.2649110640673518, 1.2649110640673518, 1.2649110640673518,
                1.2649110640673518, 1.2649110640673518, 1.2649110640673518,
            )
        assertArrayCloseWithNaN(expected, Transforms.zscore(x, window = 5))
    }

    @Test
    fun `drawdown_from_high matches engine_ref window 4`() {
        val x = doubleArrayOf(10.0, 12.0, 11.0, 15.0, 9.0, 8.0, 20.0, 19.0)
        val expected = doubleArrayOf(Double.NaN, Double.NaN, Double.NaN, 0.0, 40.0, 46.666666666666664, 0.0, 5.0)
        assertArrayCloseWithNaN(expected, Transforms.drawdownFromHigh(x, window = 4))
    }

    @Test
    fun `zscore with a mid-array NaN stays NaN until the gap clears the window`() {
        // pandas rolling(window, min_periods=window): 창 안에 NaN이 하나라도 있으면 그 행은
        // NaN — 앞쪽 window-1행만이 아니라 "gap을 포함한 모든 창"이 NaN이어야 한다.
        val x = doubleArrayOf(1.0, 2.0, Double.NaN, 4.0, 5.0, 6.0, 7.0, 8.0)
        val expected = doubleArrayOf(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 1.0, 1.0, 1.0)
        assertArrayCloseWithNaN(expected, Transforms.zscore(x, window = 3))
    }

    @Test
    fun `gateMask NaN comparisons are always false regardless of operator`() {
        val ret = doubleArrayOf(0.0, -1.0, 1.0, Double.NaN, -2.0)
        assertEquals(
            listOf(false, true, false, false, true),
            RollingTransforms.gateMask(ret, "<", 0.0).toList(),
        )
        assertEquals(
            listOf(true, false, true, false, false),
            RollingTransforms.gateMask(ret, ">=", 0.0).toList(),
        )
    }

    @Test
    fun `rollingSum matches engine_ref window 2`() {
        val expected = doubleArrayOf(Double.NaN, 3.0, 5.0)
        assertArrayCloseWithNaN(expected, RollingTransforms.rollingSum(doubleArrayOf(1.0, 2.0, 3.0), window = 2))
    }

    @Test
    fun `negZscore matches engine_ref window 3`() {
        val expected = doubleArrayOf(Double.NaN, Double.NaN, -1.0, -1.0, -1.0)
        assertArrayCloseWithNaN(expected, Transforms.negZscore(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0), window = 3))
    }

    // ---- 파리티 지뢰 1: ddof=1(표본표준편차) ----

    @Test
    fun `zscore uses ddof=1 sample standard deviation, not population`() {
        // [1,2,3] 표본표준편차(ddof=1) = 1.0 (분산 (1+0+1)/2=1). ddof=0(모표준편차)이면
        // sqrt(2/3)=0.8165 다른 값이 나온다 — 이 테스트는 ddof=1 고정을 고정한다.
        val z = Transforms.zscore(doubleArrayOf(1.0, 2.0, 3.0), window = 3)
        assertEquals(1.0, z[2], EPS)
    }

    // ---- 파리티 지뢰 2: min_periods=window 경계 ----

    @Test
    fun `rolling window minus one row is NaN, exactly window rows produces a value`() {
        val x = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        val z = Transforms.zscore(x, window = 4)
        assertTrue(z[2].isNaN(), "window-1 rows must be NaN")
        assertFalse(z[3].isNaN(), "exactly window rows must produce a value")
    }

    // ---- gated: NaN이 마스크 true를 만나도 0으로 승격되지 않는다 ----

    @Test
    fun `gated keeps NaN when mask is true but underlying value is still warming up`() {
        val z = doubleArrayOf(Double.NaN, 5.0)
        val mask = booleanArrayOf(true, true)
        val out = RollingTransforms.gated(z, mask)
        assertTrue(out[0].isNaN(), "mask=true with NaN input must stay NaN, not become 0.0")
        assertEquals(5.0, out[1], EPS)
    }

    @Test
    fun `gated maps mask false to exactly 0-0 not NaN`() {
        val z = doubleArrayOf(Double.NaN, 5.0)
        val mask = booleanArrayOf(false, false)
        val out = RollingTransforms.gated(z, mask)
        assertEquals(0.0, out[0], EPS)
        assertEquals(0.0, out[1], EPS)
    }

    // ---- pctChange / deltaBp / absValue 기초 ----

    @Test
    fun `pctChange1d and pctChange5d`() {
        val x = doubleArrayOf(100.0, 110.0, 121.0)
        assertArrayCloseWithNaN(doubleArrayOf(Double.NaN, 10.0, 10.0), Transforms.pctChange1d(x))
    }

    // ---- aaa D-1 (Kotlin 측) 증인: NaN 간극은 pad(forward-fill)되지 않고 전파된다.
    // engine_ref는 커밋 9563a85에서 pandas deprecated `fill_method='pad'` 의존을
    // `fill_method=None`으로 고정했다(발산 원인 해소, 골든·9창 불변 — 픽스처 내부 NaN 0건).
    // Kotlin은 애초에 위치 기반 뺄셈이라 pad 의미론을 가진 적이 없다(NaN 전파가 유일한 경로) —
    // 아래는 그 사실을 회귀로 고정하는 증인이다. 기대값 산출:
    // ```
    // uv run python -c "
    // import pandas as pd
    // from engine_ref import transforms as T
    // print(T.pct_change_1d(pd.Series([100.0, float('nan'), 100.0, 110.0])).tolist())
    // print(T.pct_change_5d(pd.Series([100.0,105,float('nan'),108,90,95,111,90])).tolist())
    // "
    // # pct1d -> [nan, nan, nan, 10.000000000000009]
    // # pct5d -> [nan, nan, nan, nan, nan, -5.000000000000004, 5.714285714285716, nan]
    // ```
    // 대조: pad(ffill) 의미론이었다면 pct5d의 마지막 원소는 NaN이 아니라 -14.28571428571429
    // (index2의 NaN을 직전값 105로 이월한 뒤 계산한 값)이 나온다 — pad로 되돌리면 이 테스트가 실패한다.
    @Test
    fun `pctChange1d propagates a NaN gap instead of pad-filling it (aaa D-1)`() {
        val x = doubleArrayOf(100.0, Double.NaN, 100.0, 110.0)
        val expected = doubleArrayOf(Double.NaN, Double.NaN, Double.NaN, 10.000000000000009)
        assertArrayCloseWithNaN(expected, Transforms.pctChange1d(x))
    }

    @Test
    fun `pctChange5d propagates a NaN gap instead of pad-filling it (aaa D-1)`() {
        val x = doubleArrayOf(100.0, 105.0, Double.NaN, 108.0, 90.0, 95.0, 111.0, 90.0)
        val expected =
            doubleArrayOf(
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                -5.000000000000004,
                5.714285714285716,
                Double.NaN,
            )
        assertArrayCloseWithNaN(expected, Transforms.pctChange5d(x))
    }

    @Test
    fun `deltaBp converts pct-points to bp over lookback`() {
        val x = doubleArrayOf(1.0, 1.2, 1.5)
        assertArrayCloseWithNaN(doubleArrayOf(Double.NaN, Double.NaN, 50.0), Transforms.deltaBp(x, lookback = 2))
    }

    @Test
    fun `absValue`() {
        assertEquals(listOf(1.0, 0.0, 3.5), Transforms.absValue(doubleArrayOf(-1.0, 0.0, -3.5)).toList())
    }

    @Test
    fun `realizedVolKospi20d annualizes sample std of return fractions`() {
        // window=3, r=[0.01,-0.01,0.01,-0.01] (pct 값 1,-1,1,-1을 /100). 표본표준편차(ddof=1)
        // of [0.01,-0.01,0.01] = 0.011547005..., *sqrt(252)*100.
        val dailyReturnPct = doubleArrayOf(1.0, -1.0, 1.0, -1.0)
        val out = RollingTransforms.realizedVolKospi20d(dailyReturnPct, window = 3)
        assertTrue(out[0].isNaN() && out[1].isNaN())
        assertEquals(18.33030278, out[2], 1e-6)
    }
}
