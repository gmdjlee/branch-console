package com.branchconsole.engine.pit

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `run_replay.py`의 `raw_visibility_grid_day`/`visibility_tick_utc`/`combined_visibility_utc`
 * 대조 표본(Python 대조, 2026-08-07 산출) — docs/plans/M1_PLAN_A.md §2.8 이식 대상.
 *
 * ```
 * from datetime import date, time
 * from backtest.run_replay import raw_visibility_grid_day, visibility_tick_utc, combined_visibility_utc
 * grid = [date(2026,1,5), date(2026,1,6), date(2026,1,7), date(2026,1,8), date(2026,1,9)]
 * confirm = time(17, 0)
 * raw_visibility_grid_day('^VIX', date(2026,1,6), grid, {'BAMLH0A0HYM2':1})   # 2026-01-07 (us_market: T 다음 거래일)
 * visibility_tick_utc('^VIX', date(2026,1,6), grid, {}, {}, 'mobile_daily', confirm)  # 2026-01-07 08:00:00+00:00
 * raw_visibility_grid_day('BAMLH0A0HYM2', date(2026,1,6), grid, {'BAMLH0A0HYM2':1})   # 2026-01-07 (fred, lag=1)
 * raw_visibility_grid_day('KRX:1001', date(2026,1,6), grid, {})                       # 2026-01-06 (krx: 당일)
 * raw_visibility_grid_day('KRW=X', date(2026,1,6), grid, {})                          # 2026-01-06 (fx: 당일)
 * raw_visibility_grid_day('KRX:1001', date(2026,1,10), grid, {})                      # None (그리드 밖)
 * combined_visibility_utc(('^VIX','KRX:1001'), date(2026,1,6), grid, {}, {}, 'mobile_daily', confirm)
 *   # 2026-01-07 08:00:00+00:00 (worst-of-inputs: max(us_market, krx))
 * ```
 */
class VisibilityTest {
    private fun d(s: String) = LocalDate.parse(s)

    private val grid = listOf(d("2026-01-05"), d("2026-01-06"), d("2026-01-07"), d("2026-01-08"), d("2026-01-09"))
    private val confirmTime: LocalTime = LocalTime.of(17, 0)

    private fun visAt(
        kind: CalendarKind,
        asOf: LocalDate,
        fredLagDays: Long = 0,
    ): Instant? = Visibility.visibleAt(kind, asOf, grid, fredLagDays, confirmTime)

    @Test
    fun `US_MARKET is visible on the next grid day, never the same day (KST next-morning reveal)`() {
        assertEquals(Instant.parse("2026-01-07T08:00:00Z"), visAt(CalendarKind.US_MARKET, d("2026-01-06")))
    }

    @Test
    fun `FRED is visible on or after as_of plus lag_days`() {
        val visT1 = visAt(CalendarKind.FRED, d("2026-01-06"), fredLagDays = 1)
        assertEquals(Instant.parse("2026-01-07T08:00:00Z"), visT1)
    }

    @Test
    fun `KRX and FX are visible the same day (close-of-market reveal)`() {
        assertEquals(Instant.parse("2026-01-06T08:00:00Z"), visAt(CalendarKind.KRX, d("2026-01-06")))
        assertEquals(Instant.parse("2026-01-06T08:00:00Z"), visAt(CalendarKind.FX, d("2026-01-06")))
    }

    @Test
    fun `visibleAt is null when as_of falls beyond the end of the grid`() {
        assertNull(visAt(CalendarKind.KRX, d("2026-01-10")))
    }

    @Test
    fun `combinedVisibleAt is the max of a us_market and a krx input (worst-of-inputs)`() {
        val inputs =
            listOf(Visibility.VisibilityInput(CalendarKind.US_MARKET), Visibility.VisibilityInput(CalendarKind.KRX))
        val combined = Visibility.combinedVisibleAt(inputs, d("2026-01-06"), grid, confirmTime)
        assertEquals(Instant.parse("2026-01-07T08:00:00Z"), combined, "global_corr_break: ^GSPC's L=1 rule dominates")
    }

    @Test
    fun `combinedVisibleAt is null if any single input is not yet visible (both must be known)`() {
        val inputs =
            listOf(Visibility.VisibilityInput(CalendarKind.KRX), Visibility.VisibilityInput(CalendarKind.US_MARKET))
        // as_of beyond the grid -> US_MARKET leg resolves to null -> combined must be null too.
        val combined = Visibility.combinedVisibleAt(inputs, d("2026-01-09"), grid, confirmTime)
        assertNull(combined)
    }

    // ---- W-V4: 스테일 등호 규약 — 초과만 stale, 등호는 fresh ----

    @Test
    fun `isStale is false exactly at the window boundary and true one instant past it`() {
        val visibleAt = Instant.parse("2026-01-06T08:00:00Z")
        val window = Duration.ofHours(30)
        assertFalse(Visibility.isStale(visibleAt.plus(window), visibleAt, window), "exactly at window: fresh")
        assertTrue(Visibility.isStale(visibleAt.plus(window).plusMillis(1), visibleAt, window), "one ms past: stale")
    }

    @Test
    fun `kstToUtc converts 17-00 KST to 08-00 UTC (fixed plus9 offset, no DST)`() {
        assertEquals(Instant.parse("2026-01-06T08:00:00Z"), Visibility.kstToUtc(d("2026-01-06"), confirmTime))
    }
}
