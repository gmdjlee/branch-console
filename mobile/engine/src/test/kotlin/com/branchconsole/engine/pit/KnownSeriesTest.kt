package com.branchconsole.engine.pit

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class KnownSeriesTest {
    private fun d(s: String) = LocalDate.parse(s)

    private fun visAt(day: LocalDate): Instant? =
        when (day) {
            d("2026-01-05") -> Instant.parse("2026-01-06T08:00:00Z") // US_MARKET-style next-day reveal
            d("2026-01-06") -> Instant.parse("2026-01-07T08:00:00Z")
            d("2026-01-07") -> null // simulates a date that never becomes visible
            d("2026-01-08") -> Instant.parse("2026-01-09T08:00:00Z")
            else -> null
        }

    @Test
    fun `build excludes NaN rows and rows with no visibility, keeping the rest sorted by rowDate`() {
        val rowDates = listOf(d("2026-01-08"), d("2026-01-05"), d("2026-01-07"), d("2026-01-06"))
        val values = doubleArrayOf(4.0, 1.0, Double.NaN, 2.0)

        val ks = KnownSeries.build(rowDates, values, ::visAt)

        assertEquals(listOf(d("2026-01-05"), d("2026-01-06"), d("2026-01-08")), ks.rowDates)
        ks.assertMonotonicVisibility()
    }

    // ---- W-K1: bisect_right 등호 (파리티 지뢰 7) ----

    @Test
    fun `lookup includes the row whose visibleAt exactly equals evaluatedAt (equality is selected)`() {
        val rowDates = listOf(d("2026-01-05"), d("2026-01-06"))
        val values = doubleArrayOf(1.0, 2.0)
        val ks = KnownSeries.build(rowDates, values, ::visAt)

        val exact = ks.lookup(Instant.parse("2026-01-07T08:00:00Z"))
        checkNotNull(exact)
        assertEquals(d("2026-01-06"), exact.rowDate, "visibleAt == evaluatedAt must be selected, not skipped")
        assertEquals(2.0, exact.value)
    }

    @Test
    fun `lookup does not select a row whose visibleAt is one millisecond after evaluatedAt`() {
        val rowDates = listOf(d("2026-01-05"), d("2026-01-06"))
        val values = doubleArrayOf(1.0, 2.0)
        val ks = KnownSeries.build(rowDates, values, ::visAt)

        val justBefore = ks.lookup(Instant.parse("2026-01-07T08:00:00Z").minusMillis(1))
        checkNotNull(justBefore)
        assertEquals(d("2026-01-05"), justBefore.rowDate, "the 01-06 row is not yet visible by 1ms")
    }

    @Test
    fun `lookup returns null before anything has ever become visible`() {
        val rowDates = listOf(d("2026-01-05"))
        val values = doubleArrayOf(1.0)
        val ks = KnownSeries.build(rowDates, values, ::visAt)

        assertNull(ks.lookup(Instant.parse("2026-01-06T07:59:59Z")))
    }

    @Test
    fun `assertMonotonicVisibility fails fast when a hand-built series violates the monotonicity precondition`() {
        val laterFirst = Instant.parse("2026-01-09T00:00:00Z")
        val earlierSecond = Instant.parse("2026-01-01T00:00:00Z")
        val ks =
            KnownSeries.build(
                listOf(d("2026-01-05"), d("2026-01-06")),
                doubleArrayOf(1.0, 2.0),
            ) { day -> if (day == d("2026-01-05")) laterFirst else earlierSecond }
        assertFailsWith<IllegalStateException> { ks.assertMonotonicVisibility() }
    }
}
