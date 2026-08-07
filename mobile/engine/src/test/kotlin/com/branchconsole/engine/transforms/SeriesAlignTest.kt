package com.branchconsole.engine.transforms

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val EPS = 1e-9

class SeriesAlignTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun `unionAlign fills missing dates with NaN on both sides (pandas auto index alignment)`() {
        val datesA = listOf(d("2026-01-05"), d("2026-01-06"), d("2026-01-08"))
        val a = doubleArrayOf(10.0, 20.0, 40.0)
        val datesB = listOf(d("2026-01-06"), d("2026-01-07"), d("2026-01-08"))
        val b = doubleArrayOf(2.0, 3.0, 4.0)

        val aligned = SeriesAlign.unionAlign(datesA, a, datesB, b)

        assertEquals(listOf(d("2026-01-05"), d("2026-01-06"), d("2026-01-07"), d("2026-01-08")), aligned.dates)
        assertTrue(aligned.a[0] == 10.0 && aligned.b[0].isNaN()) // 05: only A has it
        assertEquals(20.0, aligned.a[1], EPS)
        assertEquals(2.0, aligned.b[1], EPS)
        assertTrue(aligned.a[2].isNaN() && aligned.b[2] == 3.0) // 07: only B has it
        assertEquals(40.0, aligned.a[3], EPS)
        assertEquals(4.0, aligned.b[3], EPS)
    }

    @Test
    fun `ratio after unionAlign matches pandas Series division semantics`() {
        // engine_ref: T.ratio(pd.Series([10,20],index=[5,6]), pd.Series([2,4,5],index=[6,7,8]))
        // -> index union [5,6,7,8], values [NaN, 5.0, NaN, NaN] (index 5: only in a -> NaN
        // because b missing; index 6: 20/4=5.0; 7,8: only in b -> NaN because a missing).
        val datesA = listOf(d("2026-01-05"), d("2026-01-06"))
        val a = doubleArrayOf(10.0, 20.0)
        val datesB = listOf(d("2026-01-06"), d("2026-01-07"), d("2026-01-08"))
        val b = doubleArrayOf(4.0, 1.0, 1.0)

        val aligned = SeriesAlign.unionAlign(datesA, a, datesB, b)
        val ratio = Transforms.ratio(aligned.a, aligned.b)

        assertTrue(ratio[0].isNaN(), "2026-01-05: b missing -> NaN")
        assertEquals(5.0, ratio[1], EPS, "2026-01-06: 20/4")
        assertTrue(ratio[2].isNaN() && ratio[3].isNaN(), "2026-01-07/08: a missing -> NaN")
    }

    @Test
    fun `alignToFfillCausal carries the most recent source observation forward, never looks ahead`() {
        // global_corr_break 전용 정렬(run_replay._align_to_ffill) — 미래 참조 없이 causal.
        val sourceDates = listOf(d("2026-01-05"), d("2026-01-08")) // Mon, Thu (Tue/Wed missing)
        val sourceValues = doubleArrayOf(1.0, 2.0)
        val targetDates = listOf(d("2026-01-05"), d("2026-01-06"), d("2026-01-07"), d("2026-01-08"), d("2026-01-09"))

        val out = SeriesAlign.alignToFfillCausal(sourceDates, sourceValues, targetDates)

        assertEquals(1.0, out[0], EPS, "Mon: exact match")
        assertEquals(1.0, out[1], EPS, "Tue: ffill from Mon")
        assertEquals(1.0, out[2], EPS, "Wed: ffill from Mon")
        assertEquals(2.0, out[3], EPS, "Thu: exact match")
        assertEquals(2.0, out[4], EPS, "Fri: ffill from Thu")
    }

    @Test
    fun `alignToFfillCausal is NaN before the first source observation`() {
        val sourceDates = listOf(d("2026-01-08"))
        val sourceValues = doubleArrayOf(9.0)
        val targetDates = listOf(d("2026-01-05"), d("2026-01-08"))

        val out = SeriesAlign.alignToFfillCausal(sourceDates, sourceValues, targetDates)

        assertTrue(out[0].isNaN(), "before any source observation -> NaN, not extrapolated")
        assertEquals(9.0, out[1], EPS)
    }

    @Test
    fun `shift moves values forward positionally, not by calendar day (prev_close semantics)`() {
        // run_replay.py:578 close.shift(1) — 직전 관측 "행"이지 달력 전일이 아니다. 연휴로
        // 하루 빠진 시계열이어도 shift는 위치 기준으로 직전 값을 낸다.
        val values = doubleArrayOf(100.0, 101.0, 99.0)
        val shifted = SeriesAlign.shift(values, periods = 1)
        assertTrue(shifted[0].isNaN())
        assertEquals(100.0, shifted[1], EPS)
        assertEquals(101.0, shifted[2], EPS)
    }
}
