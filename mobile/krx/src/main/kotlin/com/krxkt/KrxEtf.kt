package com.krxkt

import com.krxkt.api.KrxClient
import com.krxkt.api.KrxEndpoints
import com.krxkt.cache.TickerCache
import com.krxkt.model.EtfInfo
import com.krxkt.model.EtfOhlcvHistory
import com.krxkt.model.EtfPortfolio
import com.krxkt.model.EtfPrice
import com.krxkt.parser.KrxJsonParser
import com.krxkt.util.DateUtils

/**
 * KRX ETF 데이터 API
 *
 * pykrx의 etf 모듈과 호환되는 Kotlin 구현
 *
 * 사용 예:
 * ```
 * val krxEtf = KrxEtf()
 *
 * // 전종목 ETF 시세
 * val etfList = krxEtf.getEtfPrice("20210122")
 *
 * // 개별 ETF 기간 조회
 * val history = krxEtf.getOhlcvByTicker("20210101", "20210131", "069500")
 *
 * // ETF 티커 목록
 * val tickers = krxEtf.getEtfTickerList("20210122")
 * ```
 *
 * @param client HTTP 클라이언트 (테스트용 주입 가능)
 * @param tickerCache ISIN 코드 캐시 (공유 가능)
 */
class KrxEtf(
    private val client: KrxClient = KrxClient(),
    private val tickerCache: TickerCache = TickerCache(),
) {
    /**
     * 전종목 ETF 시세 조회
     *
     * pykrx: etf.get_etf_ohlcv_by_ticker("20210122")
     *
     * @param date 조회 날짜 (yyyyMMdd)
     * @return 전종목 ETF 시세 리스트 (공휴일/휴장일은 빈 리스트)
     */
    suspend fun getEtfPrice(date: String): List<EtfPrice> {
        DateUtils.validateDate(date)

        val params =
            mapOf(
                "bld" to KrxEndpoints.Bld.ETF_PRICE,
                "trdDd" to date,
            )

        val response = client.post(params)
        val jsonArray = KrxJsonParser.parseOutBlock(response)

        return jsonArray.mapNotNull { EtfPrice.fromJson(it) }
    }

    /**
     * 개별 ETF OHLCV 기간 조회
     *
     * pykrx: etf.get_etf_ohlcv_by_date("20210101", "20210131", "069500")
     *
     * @param startDate 시작 날짜 (yyyyMMdd)
     * @param endDate 종료 날짜 (yyyyMMdd)
     * @param ticker ETF 종목코드 (예: "069500")
     * @return 날짜별 OHLCV 리스트 (최신순 정렬)
     */
    suspend fun getOhlcvByTicker(
        startDate: String,
        endDate: String,
        ticker: String,
    ): List<EtfOhlcvHistory> {
        DateUtils.validateDateRange(startDate, endDate)

        // ISIN 코드 조회 (KRX API는 ISIN 사용)
        val isinCode =
            getIsinCode(ticker, endDate)
                ?: return emptyList()

        val params =
            mapOf(
                "bld" to KrxEndpoints.Bld.ETF_OHLCV_BY_TICKER,
                "isuCd" to isinCode,
                "strtDd" to startDate,
                "endDd" to endDate,
            )

        val response = client.post(params)
        val jsonArray = KrxJsonParser.parseOutBlock(response)

        return jsonArray.mapNotNull { EtfOhlcvHistory.fromJson(it) }
    }

    /**
     * ETF 종목 리스트 조회
     *
     * @param date 조회 날짜 (yyyyMMdd)
     * @return ETF 종목 정보 리스트
     */
    suspend fun getEtfTickerList(date: String): List<EtfInfo> {
        DateUtils.validateDate(date)

        val params =
            mapOf(
                "bld" to KrxEndpoints.Bld.ETF_TICKER_LIST,
                "trdDd" to date,
            )

        val response = client.post(params)
        val jsonArray = KrxJsonParser.parseOutBlock(response)

        return jsonArray.mapNotNull { EtfInfo.fromJson(it) }
    }

    /**
     * 티커로 ETF 이름 조회
     *
     * @param ticker ETF 종목코드 (예: "069500")
     * @param date 기준 날짜
     * @return ETF 이름 (예: "KODEX 200"), 없으면 null
     */
    suspend fun getEtfName(
        ticker: String,
        date: String,
    ): String? {
        val tickerList = getEtfTickerList(date)
        return tickerList.find { it.ticker == ticker }?.name
    }

    /**
     * ETF 구성종목 조회 (Portfolio Deposit File)
     *
     * pykrx: etf.get_etf_portfolio_deposit_file("20210122", "069500")
     *
     * @param date 조회 날짜 (yyyyMMdd)
     * @param ticker ETF 종목코드 (예: "069500")
     * @return ETF 구성종목 리스트 (비중 순 정렬)
     */
    suspend fun getPortfolio(
        date: String,
        ticker: String,
    ): List<EtfPortfolio> {
        DateUtils.validateDate(date)

        val isinCode =
            getIsinCode(ticker, date)
                ?: return emptyList()

        val params =
            mapOf(
                "bld" to KrxEndpoints.Bld.ETF_PORTFOLIO,
                "trdDd" to date,
                "isuCd" to isinCode,
            )

        val response = client.post(params)
        val jsonArray = KrxJsonParser.parseOutBlock(response)

        return jsonArray.mapNotNull { EtfPortfolio.fromJson(it) }
    }

    /**
     * 종목코드로 ISIN 코드 조회 (캐시 사용)
     *
     * 캐시에 ISIN이 있으면 즉시 반환, 없으면 전체 ETF 티커 리스트를
     * 조회하여 캐시에 일괄 저장 후 반환.
     *
     * @param ticker ETF 종목코드 (예: "069500")
     * @param date 기준 날짜
     * @return ISIN 코드 (예: "KR7069500007"), 없으면 null
     */
    private suspend fun getIsinCode(
        ticker: String,
        date: String,
    ): String? {
        // 캐시 히트
        tickerCache.getEtfIsin(ticker)?.let { return it }

        // 캐시 미스: 전체 ETF 티커 리스트 조회 후 일괄 캐시
        val tickerList = getEtfTickerList(date)
        val tickerToIsin =
            tickerList
                .filter { it.isinCode.isNotEmpty() }
                .associate { it.ticker to it.isinCode }
        tickerCache.putAllEtfIsins(tickerToIsin)

        return tickerToIsin[ticker]
    }

    /**
     * 리소스 정리
     */
    fun close() {
        client.close()
    }
}
