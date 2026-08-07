package com.krxkt.cache

import java.util.concurrent.ConcurrentHashMap

/**
 * ISIN 코드 인메모리 캐시
 *
 * KRX API에서 개별종목 조회 시 ISIN 코드가 필요하지만,
 * 매번 전체 티커 리스트를 조회하는 것은 비효율적.
 * 이 캐시는 ticker -> ISIN 매핑을 저장하여 중복 API 호출 방지.
 *
 * 특징:
 * - Thread-safe (ConcurrentHashMap 사용)
 * - 시간 기반 만료 (기본 1시간)
 * - Stock/ETF 별도 캐시 공간
 *
 * @param ttlMillis 캐시 유효 시간 (밀리초, 기본: 1시간)
 * @param clock 현재 시간 제공자 (테스트용)
 */
class TickerCache(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        /** 기본 TTL: 1시간 (티커 데이터는 장중 거의 변하지 않음) */
        const val DEFAULT_TTL_MILLIS = 3_600_000L
    }

    private data class CacheEntry(
        val isinCode: String,
        val timestamp: Long,
    )

    /** Stock ticker -> ISIN 캐시 */
    private val stockCache = ConcurrentHashMap<String, CacheEntry>()

    /** ETF ticker -> ISIN 캐시 */
    private val etfCache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * Stock ISIN 코드 조회
     *
     * @param ticker 종목코드 (예: "005930")
     * @return 캐시된 ISIN 코드, 없거나 만료되면 null
     */
    fun getStockIsin(ticker: String): String? {
        return getFromCache(stockCache, ticker)
    }

    /**
     * Stock ISIN 코드 저장
     *
     * @param ticker 종목코드
     * @param isinCode ISIN 코드
     */
    fun putStockIsin(
        ticker: String,
        isinCode: String,
    ) {
        stockCache[ticker] = CacheEntry(isinCode, clock())
    }

    /**
     * Stock 티커 리스트 일괄 저장
     *
     * getTickerList() 결과를 캐시에 일괄 저장하여
     * 한 번의 API 호출로 모든 종목의 ISIN을 캐시.
     *
     * @param tickerToIsin ticker -> ISIN 매핑
     */
    fun putAllStockIsins(tickerToIsin: Map<String, String>) {
        val now = clock()
        tickerToIsin.forEach { (ticker, isin) ->
            stockCache[ticker] = CacheEntry(isin, now)
        }
    }

    /**
     * ETF ISIN 코드 조회
     *
     * @param ticker ETF 종목코드 (예: "069500")
     * @return 캐시된 ISIN 코드, 없거나 만료되면 null
     */
    fun getEtfIsin(ticker: String): String? {
        return getFromCache(etfCache, ticker)
    }

    /**
     * ETF ISIN 코드 저장
     *
     * @param ticker ETF 종목코드
     * @param isinCode ISIN 코드
     */
    fun putEtfIsin(
        ticker: String,
        isinCode: String,
    ) {
        etfCache[ticker] = CacheEntry(isinCode, clock())
    }

    /**
     * ETF 티커 리스트 일괄 저장
     *
     * @param tickerToIsin ticker -> ISIN 매핑
     */
    fun putAllEtfIsins(tickerToIsin: Map<String, String>) {
        val now = clock()
        tickerToIsin.forEach { (ticker, isin) ->
            etfCache[ticker] = CacheEntry(isin, now)
        }
    }

    /**
     * 전체 캐시 초기화
     */
    fun clear() {
        stockCache.clear()
        etfCache.clear()
    }

    /**
     * Stock 캐시 크기
     */
    fun stockCacheSize(): Int = stockCache.size

    /**
     * ETF 캐시 크기
     */
    fun etfCacheSize(): Int = etfCache.size

    private fun getFromCache(
        cache: ConcurrentHashMap<String, CacheEntry>,
        key: String,
    ): String? {
        val entry = cache[key] ?: return null
        val elapsed = clock() - entry.timestamp
        if (elapsed > ttlMillis) {
            cache.remove(key)
            return null
        }
        return entry.isinCode
    }
}
