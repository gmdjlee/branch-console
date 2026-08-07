package com.branchconsole.engine.indicators

import com.branchconsole.engine.transforms.RollingTransforms
import com.branchconsole.engine.transforms.Transforms
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * K-02 폴백 체인 — 분기는 데이터로만 판정한다(빈 VKOSPI 배열이면 폴백, 하드코딩 금지). 브리프
 * 지시: "실측 VKOSPI 계열을 스코어링에 배선 금지"의 유일한 결정 지점.
 */
class VkospiTest {
    @Test
    fun `real VKOSPI series is used directly (zscore of the actual series) when observations exist`() {
        val vkospi = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val kospi = doubleArrayOf(100.0, 200.0, 300.0, 400.0, 500.0) // must be ignored
        val out = Vkospi.vkospiZ(vkospi, kospi, zscoreWindow = 5, fallbackWindow = 3)
        assertEquals(Transforms.zscore(vkospi, window = 5).toList(), out.toList())
    }

    @Test
    fun `empty VKOSPI series falls back to KOSPI-derived realized volatility (K-02)`() {
        val kospi = doubleArrayOf(100.0, 101.0, 99.0, 100.0, 101.0, 99.0, 100.0)
        val out = Vkospi.vkospiZ(DoubleArray(0), kospi, zscoreWindow = 4, fallbackWindow = 3)

        val expectedRealizedVol = RollingTransforms.realizedVolKospi20d(Transforms.pctChange1d(kospi), window = 3)
        val expected = Transforms.zscore(expectedRealizedVol, window = 4)
        assertEquals(expected.toList(), out.toList())
        // the fallback path never touches an empty vkospi array as input to zscore directly.
        assertTrue(out.isNotEmpty())
    }
}
