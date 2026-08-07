package com.branchconsole.engine.pit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * docs/plans/M1_PLAN_A.md §2.5.1 `L` 표 — 프로덕션 매핑은 `source.provider`(+`symbol`)에서
 * 파생하고, 픽스처 전용 `fixture_schema.calendar_kind`의 "그 외 전부 us_market" 폴백을
 * 그대로 쓰지 않는다(ECOS 오분류 방지, "발견 사항").
 */
class CalendarKindResolverTest {
    @Test
    fun `KRW-X is FX regardless of provider`() {
        assertEquals(CalendarKind.FX, CalendarKindResolver.resolve("yfinance", "KRW=X"))
    }

    @Test
    fun `yfinance non-KRW symbols are US_MARKET`() {
        assertEquals(CalendarKind.US_MARKET, CalendarKindResolver.resolve("yfinance", "^VIX"))
        assertEquals(CalendarKind.US_MARKET, CalendarKindResolver.resolve("yfinance", "^GSPC"))
    }

    @Test
    fun `pykrx and krx-family providers are KRX`() {
        assertEquals(CalendarKind.KRX, CalendarKindResolver.resolve("pykrx"))
        assertEquals(CalendarKind.KRX, CalendarKindResolver.resolve("krx_notice"))
        assertEquals(CalendarKind.KRX, CalendarKindResolver.resolve("krx_margin"))
    }

    @Test
    fun `fred and ecos both resolve to FRED (same lag_days-based rule), not the us_market fixture fallback`() {
        assertEquals(CalendarKind.FRED, CalendarKindResolver.resolve("fred"))
        assertEquals(CalendarKind.FRED, CalendarKindResolver.resolve("ecos"))
    }

    @Test
    fun `scrape_wgb (KR CDS, G-4) is KRX-like`() {
        assertEquals(CalendarKind.KRX, CalendarKindResolver.resolve("scrape_wgb"))
    }

    @Test
    fun `unknown provider fails fast instead of silently defaulting`() {
        assertFailsWith<IllegalStateException> { CalendarKindResolver.resolve("derived") }
    }
}
