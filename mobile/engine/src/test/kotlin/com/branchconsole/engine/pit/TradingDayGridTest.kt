package com.branchconsole.engine.pit

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TradingDayGridTest {
    private fun d(s: String) = LocalDate.parse(s)

    private val grid =
        listOf(d("2026-01-05"), d("2026-01-06"), d("2026-01-07"), d("2026-01-08"), d("2026-01-09"))

    @Test
    fun `firstOnOrAfter returns the same day when it is on the grid`() {
        assertEquals(d("2026-01-06"), TradingDayGrid.firstOnOrAfter(grid, d("2026-01-06")))
    }

    @Test
    fun `firstOnOrAfter pulls a non-grid day forward to the next grid day, never backward`() {
        // KRX 휴장일에 관측이 찍힌 극단 케이스(run_replay.py 모듈 docstring) — 항상 미래
        // 쪽으로만 당긴다. 2026-01-07(수)을 휴장일로 뺀 그리드에서 그 날짜를 조회하면
        // 다음 그리드일(01-08)로 당겨진다(과거로 당기지 않는다).
        val gridWithHoliday = listOf(d("2026-01-05"), d("2026-01-06"), d("2026-01-08"), d("2026-01-09"))
        assertEquals(d("2026-01-08"), TradingDayGrid.firstOnOrAfter(gridWithHoliday, d("2026-01-07")))
    }

    @Test
    fun `firstOnOrAfter beyond the grid end returns null`() {
        assertNull(TradingDayGrid.firstOnOrAfter(grid, d("2026-01-10")))
    }

    @Test
    fun `firstAfter skips the exact day itself (strictly greater)`() {
        assertEquals(d("2026-01-07"), TradingDayGrid.firstAfter(grid, d("2026-01-06")))
    }

    @Test
    fun `firstAfter of the last grid day returns null`() {
        assertNull(TradingDayGrid.firstAfter(grid, d("2026-01-09")))
    }

    @Test
    fun `firstOnOrAfter and firstAfter agree when queried one day apart (M1_PLAN_D 2-5-1 proof)`() {
        // _first_grid_day_after(g,T) == _first_grid_day_on_or_after(g,T+1일) — bisect 수준 증명.
        for (day in grid) {
            assertEquals(TradingDayGrid.firstAfter(grid, day), TradingDayGrid.firstOnOrAfter(grid, day.plusDays(1)))
        }
    }
}
