package com.branchconsole.engine.pit

import java.time.LocalDate

/**
 * 거래일 그리드 이진탐색 — `run_replay._first_grid_day_on_or_after`/`_first_grid_day_after`
 * 1:1 이식(docs/plans/M1_PLAN_A.md §2.5.1 증명: 두 형태 모두 "정렬 배열에서 T보다 큰(또는
 * 이상인) 첫 원소"). `grid`는 오름차순 정렬·중복 없는 거래일 목록이어야 한다(호출자 책임).
 */
object TradingDayGrid {
    fun firstOnOrAfter(
        grid: List<LocalDate>,
        day: LocalDate,
    ): LocalDate? = grid.getOrNull(bisectLeft(grid, day))

    fun firstAfter(
        grid: List<LocalDate>,
        day: LocalDate,
    ): LocalDate? = grid.getOrNull(bisectRight(grid, day))

    private fun bisectLeft(
        grid: List<LocalDate>,
        day: LocalDate,
    ): Int {
        var lo = 0
        var hi = grid.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (grid[mid] < day) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun bisectRight(
        grid: List<LocalDate>,
        day: LocalDate,
    ): Int {
        var lo = 0
        var hi = grid.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (grid[mid] <= day) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
