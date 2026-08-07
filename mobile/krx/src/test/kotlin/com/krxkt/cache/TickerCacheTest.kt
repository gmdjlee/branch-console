package com.krxkt.cache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TickerCacheTest {
    @Test
    fun `stock ISIN cache hit returns cached value`() {
        val cache = TickerCache()
        cache.putStockIsin("005930", "KR7005930003")

        assertEquals("KR7005930003", cache.getStockIsin("005930"))
    }

    @Test
    fun `stock ISIN cache miss returns null`() {
        val cache = TickerCache()

        assertNull(cache.getStockIsin("005930"))
    }

    @Test
    fun `etf ISIN cache hit returns cached value`() {
        val cache = TickerCache()
        cache.putEtfIsin("069500", "KR7069500007")

        assertEquals("KR7069500007", cache.getEtfIsin("069500"))
    }

    @Test
    fun `etf ISIN cache miss returns null`() {
        val cache = TickerCache()

        assertNull(cache.getEtfIsin("069500"))
    }

    @Test
    fun `stock and etf caches are independent`() {
        val cache = TickerCache()
        cache.putStockIsin("005930", "KR7005930003")

        assertNull(cache.getEtfIsin("005930"))
    }

    @Test
    fun `putAllStockIsins stores multiple entries`() {
        val cache = TickerCache()
        val tickers =
            mapOf(
                "005930" to "KR7005930003",
                "000660" to "KR7000660001",
                "035420" to "KR7035420009",
            )
        cache.putAllStockIsins(tickers)

        assertEquals("KR7005930003", cache.getStockIsin("005930"))
        assertEquals("KR7000660001", cache.getStockIsin("000660"))
        assertEquals("KR7035420009", cache.getStockIsin("035420"))
        assertEquals(3, cache.stockCacheSize())
    }

    @Test
    fun `putAllEtfIsins stores multiple entries`() {
        val cache = TickerCache()
        val tickers =
            mapOf(
                "069500" to "KR7069500007",
                "114800" to "KR7114800004",
            )
        cache.putAllEtfIsins(tickers)

        assertEquals("KR7069500007", cache.getEtfIsin("069500"))
        assertEquals("KR7114800004", cache.getEtfIsin("114800"))
        assertEquals(2, cache.etfCacheSize())
    }

    @Test
    fun `expired entry returns null`() {
        var currentTime = 1000L
        val cache =
            TickerCache(
                ttlMillis = 500L,
                clock = { currentTime },
            )

        cache.putStockIsin("005930", "KR7005930003")
        assertEquals("KR7005930003", cache.getStockIsin("005930"))

        // Advance time past TTL
        currentTime = 1600L
        assertNull(cache.getStockIsin("005930"))
    }

    @Test
    fun `entry within TTL returns value`() {
        var currentTime = 1000L
        val cache =
            TickerCache(
                ttlMillis = 500L,
                clock = { currentTime },
            )

        cache.putStockIsin("005930", "KR7005930003")

        // Still within TTL
        currentTime = 1400L
        assertEquals("KR7005930003", cache.getStockIsin("005930"))
    }

    @Test
    fun `clear removes all entries`() {
        val cache = TickerCache()
        cache.putStockIsin("005930", "KR7005930003")
        cache.putEtfIsin("069500", "KR7069500007")

        cache.clear()

        assertNull(cache.getStockIsin("005930"))
        assertNull(cache.getEtfIsin("069500"))
        assertEquals(0, cache.stockCacheSize())
        assertEquals(0, cache.etfCacheSize())
    }

    @Test
    fun `default TTL is 1 hour`() {
        assertEquals(3_600_000L, TickerCache.DEFAULT_TTL_MILLIS)
    }

    @Test
    fun `overwriting existing entry updates value`() {
        val cache = TickerCache()
        cache.putStockIsin("005930", "OLD_ISIN")
        cache.putStockIsin("005930", "KR7005930003")

        assertEquals("KR7005930003", cache.getStockIsin("005930"))
    }

    @Test
    fun `expired entry is removed from cache`() {
        var currentTime = 1000L
        val cache =
            TickerCache(
                ttlMillis = 500L,
                clock = { currentTime },
            )

        cache.putStockIsin("005930", "KR7005930003")
        assertEquals(1, cache.stockCacheSize())

        // Expire and access
        currentTime = 1600L
        assertNull(cache.getStockIsin("005930"))
        assertEquals(0, cache.stockCacheSize())
    }
}
