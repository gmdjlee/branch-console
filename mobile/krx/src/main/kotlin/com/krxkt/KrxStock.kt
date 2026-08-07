package com.krxkt

import com.krxkt.api.KrxClient
import com.krxkt.api.KrxEndpoints
import com.krxkt.cache.TickerCache
import com.krxkt.model.AskBidType
import com.krxkt.model.InvestorTrading
import com.krxkt.model.Market
import com.krxkt.model.MarketCap
import com.krxkt.model.MarketOhlcv
import com.krxkt.model.ShortBalance
import com.krxkt.model.ShortBalanceHistory
import com.krxkt.model.ShortSelling
import com.krxkt.model.ShortSellingHistory
import com.krxkt.model.StockFundamental
import com.krxkt.model.StockFundamentalHistory
import com.krxkt.model.StockOhlcvHistory
import com.krxkt.model.TickerInfo
import com.krxkt.model.TradingValueType
import com.krxkt.parser.KrxJsonParser
import com.krxkt.util.DateUtils

/**
 * KRX 주식 데이터 API
 *
 * pykrx의 stock 모듈과 호환되는 Kotlin 구현
 *
 * 사용 예:
 * ```
 * val krxStock = KrxStock()
 *
 * // 전종목 OHLCV
 * val ohlcvList = krxStock.getMarketOhlcv("20210122")
 *
 * // 개별종목 기간 조회
 * val history = krxStock.getOhlcvByTicker("20210101", "20210131", "005930")
 *
 * // 시가총액
 * val marketCaps = krxStock.getMarketCap("20210122")
 * ```
 *
 * @param client HTTP 클라이언트 (테스트용 주입 가능)
 * @param tickerCache ISIN 코드 캐시 (공유 가능)
 */
class KrxStock(
    private val client: KrxClient = KrxClient(),
    private val tickerCache: TickerCache = TickerCache(),
) {
    companion object {
        /** KRX API 기간 조회 최대 허용 일수 (INVALIDPERIOD2 방지) */
        private const val MAX_PERIOD_DAYS = 365L
        private val DATE_FMT = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")
    }

    /**
     * 전종목 OHLCV 조회
     *
     * pykrx: stock.get_market_ohlcv("20210122")
     *
     * @param date 조회 날짜 (yyyyMMdd)
     * @param market 시장 구분 (기본: ALL)
     * @return 전종목 OHLCV 리스트 (공휴일/휴장일은 빈 리스트)
     */
    suspend fun getMarketOhlcv(
        date: String,
        market: Market = Market.ALL,
    ): List<MarketOhlcv> {
        DateUtils.validateDate(date)

        val params =
            mapOf(
                "bld" to KrxEndpoints.Bld.STOCK_OHLCV_ALL,
                "mktId" to market.code,
                "trdDd" to date,
            )

        val response = client.post(params)
        val jsonArray = KrxJsonParser.parseOutBlock(response)

        return jsonArray.mapNotNull { MarketOhlcv.fromJson(it) }
    }

    /**
     * 개별종목 OHLCV 기간 조회
     *
     * pykrx: stock.get_market_ohlcv("20210101", "20210131", "005930")
     *
     * @param startDate 시작 날짜 (yyyyMMdd)
     * @param endDate 종료 날짜 (yyyyMMdd)
     * @param ticker 종목코드 (예: "005930")
     * @return 날짜별 OHLCV 리스트 (최신순 정렬)
     */
    suspend fun getOhlcvByTicker(
        startDate: String,
        endDate: String,
        ticker: String,
    ): List<StockOhlcvHistory> {
        DateUtils.validateDateRange(startDate, endDate)

        // ISIN 코드 조회 필요 (KRX API는 ISIN 사용)
        val isinCode =
            getIsinCode(ticker, endDate)
                ?: return emptyList()

        return fetchByDateChunks(startDate, endDate) { chunkStart, chunkEnd ->
            val params =
                mapOf(
                    "bld" to KrxEndpoints.Bld.STOCK_OHLCV_BY_TICKER,
                    "isuCd" to isinCode,
                    "strtDd" to chunkStart,
                    "endDd" to chunkEnd,
                    // 수정주가 적용 (pykrx 동일)
                    "adjStkPrc" to "2",
                )

            val response = client.post(params)
            val jsonArray = KrxJsonParser.parseOutBlock(response)
            jsonArray.mapNotNull { StockOhlcvHistory.fromJson(it) }
        }
    }

    /**
     * 전종목 시가총액 조회
     *
     * pykrx: stock.get_market_cap("20210122")
     *
     * @param date 조회 날짜 (yyyyMMdd)
     * @param market 시장 구분 (기본: ALL)
     * @return 전종목 시가총액 리스트
     */
    suspend fun getMarketCap(
        date: String,
        market: Market = Market.ALL,
    ): List<MarketCap> {
        DateUtils.validateDate(date)

        val params =
            mapOf(
                "bld" to KrxEndpoints.Bld.MARKET_CAP,
                "mktId" to market.code,
                "trdDd" to date,
            )

        val response = client.post(params)
        val jsonArray = KrxJsonParser.parseOutBlock(response)

        return jsonArray.mapNotNull { MarketCap.fromJson(it) }
    }

    /**
     * 전종목 투자지표 조회
     *
     * pykrx: stock.get_market_fundamental("20210122")
     *
     * @param date 조회 날짜 (yyyyMMdd)
     * @param market 시장 구분 (기본: ALL)
     * @return 전종목 펀더멘탈 리스트 (PER, PBR, EPS, BPS, DPS, 배당수익률)
     */
    suspend fun getMarketFundamental(
        date: String,
        market: Market = Market.ALL,
    ): List<StockFundamental> {
        DateUtils.validateDate(date)

        val params =
            mapOf(
                "bld" to KrxEndpoints.Bld.FUNDAMENTAL,
                "mktId" to market.code,
                "trdDd" to date,
            )

        val response = client.post(params)
        val jsonArray = KrxJsonParser.parseOutBlock(response)

        return jsonArray.mapNotNull { StockFundamental.fromJson(it) }
    }

    /**
     * 개별종목 투자지표 기간 조회
     *
     * pykrx: stock.get_market_fundamental_by_ticker()
     *
     * @param startDate 시작 날짜 (yyyyMMdd)
     * @param endDate 종료 날짜 (yyyyMMdd)
     * @param ticker 종목코드 (예: "005930")
     * @return 날짜별 투자지표 리스트 (PER, PBR, EPS, BPS, DPS, 배당수익률)
     */
    suspend fun getFundamentalByTicker(
        startDate: String,
        endDate: String,
        ticker: String,
    ): List<StockFundamentalHistory> {
        DateUtils.validateDateRange(startDate, endDate)

        val isinCode = getIsinCode(ticker, endDate)
        if (isinCode == null) {
            println("KrxStock.getFundamentalByTicker: ISIN 코드 조회 실패 (ticker=$ticker)")
            return emptyList()
        }
        println("KrxStock.getFundamentalByTicker: ticker=$ticker, isinCode=$isinCode, range=$startDate~$endDate")

        return fetchByDateChunks(startDate, endDate) { chunkStart, chunkEnd ->
            val params =
                mapOf(
                    "bld" to KrxEndpoints.Bld.FUNDAMENTAL_BY_TICKER,
                    "isuCd" to isinCode,
                    "strtDd" to chunkStart,
                    "endDd" to chunkEnd,
                    "mktId" to "ALL",
                )

            val response = client.post(params)
            println("KrxStock.getFundamentalByTicker: raw response length=${response.length}, first 500 chars=${response.take(500)}")
            val jsonArray = KrxJsonParser.parseOutBlock(response)
            println("KrxStock.getFundamentalByTicker: parsed jsonArray size=${jsonArray.size}")
            if (jsonArray.isNotEmpty()) {
                println("KrxStock.getFundamentalByTicker: first item keys=${jsonArray[0].keySet()}")
                println("KrxStock.getFundamentalByTicker: first item=${jsonArray[0]}")
            }
            val result = jsonArray.mapNotNull { StockFundamentalHistory.fromJson(it) }
            println("KrxStock.getFundamentalByTicker: parsed result size=${result.size}")
            result
        }
    }

    /**
     * 종목 리스트 조회
     *
     * pykrx: stock.get_market_ticker_list("20210122")
     *
     * @param date 조회 날짜 (yyyyMMdd)
     * @param market 시장 구분 (기본: ALL)
     * @return 종목 정보 리스트
     */
    suspend fun getTickerList(
        date: String,
        market: Market = Market.ALL,
    ): List<TickerInfo> {
        DateUtils.validateDate(date)

        val params =
            mapOf(
                "bld" to KrxEndpoints.Bld.TICKER_LIST,
                "mktId" to market.code,
                "trdDd" to date,
            )

        val response = client.post(params)
        val jsonArray = KrxJsonParser.parseOutBlock(response)

        return jsonArray.mapNotNull { TickerInfo.fromJson(it) }
    }

    /**
     * 종목코드로 ISIN 코드 조회 (캐시 사용)
     *
     * 캐시에 ISIN이 있으면 즉시 반환, 없으면 전체 티커 리스트를
     * 조회하여 캐시에 일괄 저장 후 반환.
     *
     * @param ticker 종목코드 (예: "005930")
     * @param date 기준 날짜
     * @return ISIN 코드 (예: "KR7005930003"), 없으면 null
     */
    internal suspend fun getIsinCode(
        ticker: String,
        date: String,
    ): String? {
        // 캐시 히트
        tickerCache.getStockIsin(ticker)?.let { return it }

        // 캐시 미스: 전체 티커 리스트 조회 후 일괄 캐시
        val tickerList = getTickerList(date, Market.ALL)
        val tickerToIsin =
            tickerList
                .filter { it.isinCode.isNotEmpty() }
                .associate { it.ticker to it.isinCode }
        tickerCache.putAllStockIsins(tickerToIsin)

        return tickerToIsin[ticker]
    }

    // ============================================================
    // 투자자별 거래실적 (Investor Trading)
    // ============================================================

    /**
     * 전체시장 투자자별 거래실적 (일별 추이)
     *
     * pykrx: stock.get_market_trading_value_and_volume_on_market_by_date()
     *
     * @param startDate 시작 날짜 (yyyyMMdd)
     * @param endDate 종료 날짜 (yyyyMMdd)
     * @param market 시장 구분 (기본: ALL)
     * @param valueType 거래량/거래대금 (기본: VALUE)
     * @param askBidType 매수/매도/순매수 (기본: NET_BUY)
     * @return 일별 투자자별 거래실적 리스트
     */
    suspend fun getMarketTradingByInvestor(
        startDate: String,
        endDate: String,
        market: Market = Market.ALL,
        valueType: TradingValueType = TradingValueType.VALUE,
        askBidType: AskBidType = AskBidType.NET_BUY,
    ): List<InvestorTrading> {
        DateUtils.validateDateRange(startDate, endDate)

        val params =
            mapOf(
                "bld" to KrxEndpoints.Bld.INVESTOR_TRADING_MARKET_DAILY,
                "strtDd" to startDate,
                "endDd" to endDate,
                "mktId" to market.code,
                "trdVolVal" to valueType.code,
                "askBid" to askBidType.code,
            )

        val response = client.post(params)
        val jsonArray = KrxJsonParser.parseOutBlock(response)

        return jsonArray.mapNotNull { InvestorTrading.fromJson(it) }
    }

    /**
     * 개별종목 투자자별 거래실적 (일별 추이)
     *
     * pykrx: stock.get_market_trading_value_and_volume_on_ticker_by_date()
     *
     * @param startDate 시작 날짜 (yyyyMMdd)
     * @param endDate 종료 날짜 (yyyyMMdd)
     * @param ticker 종목코드 (예: "005930")
     * @param valueType 거래량/거래대금 (기본: VALUE)
     * @param askBidType 매수/매도/순매수 (기본: NET_BUY)
     * @return 일별 투자자별 거래실적 리스트
     */
    suspend fun getTradingByInvestor(
        startDate: String,
        endDate: String,
        ticker: String,
        valueType: TradingValueType = TradingValueType.VALUE,
        askBidType: AskBidType = AskBidType.NET_BUY,
    ): List<InvestorTrading> {
        DateUtils.validateDateRange(startDate, endDate)

        val isinCode =
            getIsinCode(ticker, endDate)
                ?: return emptyList()

        return fetchByDateChunks(startDate, endDate) { chunkStart, chunkEnd ->
            val params =
                mapOf(
                    "bld" to KrxEndpoints.Bld.INVESTOR_TRADING_TICKER_DAILY,
                    "strtDd" to chunkStart,
                    "endDd" to chunkEnd,
                    "isuCd" to isinCode,
                    "trdVolVal" to valueType.code,
                    "askBid" to askBidType.code,
                    "inqTpCd" to "2",
                    "detailView" to "1",
                )

            val response = client.post(params)
            val jsonArray = KrxJsonParser.parseOutBlock(response)
            jsonArray.mapNotNull { InvestorTrading.fromTickerJson(it) }
        }
    }

    // ============================================================
    // 공매도 (Short Selling)
    // ============================================================

    /**
     * 전종목 공매도 거래 현황 (특정일)
     *
     * pykrx: stock.get_shorting_volume_by_ticker()
     *
     * @param date 조회 날짜 (yyyyMMdd)
     * @param market 시장 구분 (기본: KOSPI)
     * @return 전종목 공매도 거래 리스트
     */
    suspend fun getShortSellingAll(
        date: String,
        market: Market = Market.KOSPI,
    ): List<ShortSelling> {
        DateUtils.validateDate(date)

        val params =
            mapOf(
                "bld" to KrxEndpoints.Bld.SHORT_SELLING_ALL,
                "trdDd" to date,
                "mktId" to market.code,
            )

        val response = client.post(params)
        val jsonArray = KrxJsonParser.parseOutBlock(response)

        return jsonArray.mapNotNull { ShortSelling.fromJson(it) }
    }

    /**
     * 개별종목 공매도 거래 일별 추이
     *
     * pykrx: stock.get_shorting_volume_by_date()
     *
     * @param startDate 시작 날짜 (yyyyMMdd)
     * @param endDate 종료 날짜 (yyyyMMdd)
     * @param ticker 종목코드 (예: "005930")
     * @return 일별 공매도 거래 리스트
     */
    suspend fun getShortSellingByTicker(
        startDate: String,
        endDate: String,
        ticker: String,
    ): List<ShortSellingHistory> {
        DateUtils.validateDateRange(startDate, endDate)

        val isinCode =
            getIsinCode(ticker, endDate)
                ?: return emptyList()

        val params =
            mapOf(
                "bld" to KrxEndpoints.Bld.SHORT_SELLING_BY_TICKER,
                "strtDd" to startDate,
                "endDd" to endDate,
                "isuCd" to isinCode,
            )

        val response = client.post(params)
        val jsonArray = KrxJsonParser.parseOutBlock(response)

        return jsonArray.mapNotNull { ShortSellingHistory.fromJson(it) }
    }

    /**
     * 전종목 공매도 잔고 현황 (특정일)
     *
     * pykrx: stock.get_shorting_balance_by_ticker()
     *
     * @param date 조회 날짜 (yyyyMMdd)
     * @param market 시장 구분 (기본: KOSPI)
     * @return 전종목 공매도 잔고 리스트
     */
    suspend fun getShortBalanceAll(
        date: String,
        market: Market = Market.KOSPI,
    ): List<ShortBalance> {
        DateUtils.validateDate(date)

        val params =
            mapOf(
                "bld" to KrxEndpoints.Bld.SHORT_BALANCE_ALL,
                "trdDd" to date,
                "mktId" to market.code,
            )

        val response = client.post(params)
        val jsonArray = KrxJsonParser.parseOutBlock(response)

        return jsonArray.mapNotNull { ShortBalance.fromJson(it) }
    }

    /**
     * 개별종목 공매도 잔고 일별 추이
     *
     * pykrx: stock.get_shorting_balance_by_date()
     *
     * @param startDate 시작 날짜 (yyyyMMdd)
     * @param endDate 종료 날짜 (yyyyMMdd)
     * @param ticker 종목코드 (예: "005930")
     * @return 일별 공매도 잔고 리스트
     */
    suspend fun getShortBalanceByTicker(
        startDate: String,
        endDate: String,
        ticker: String,
    ): List<ShortBalanceHistory> {
        DateUtils.validateDateRange(startDate, endDate)

        val isinCode =
            getIsinCode(ticker, endDate)
                ?: return emptyList()

        val params =
            mapOf(
                "bld" to KrxEndpoints.Bld.SHORT_BALANCE_BY_TICKER,
                "strtDd" to startDate,
                "endDd" to endDate,
                "isuCd" to isinCode,
            )

        val response = client.post(params)
        val jsonArray = KrxJsonParser.parseOutBlock(response)

        return jsonArray.mapNotNull { ShortBalanceHistory.fromJson(it) }
    }

    /**
     * 큰 날짜 범위를 MAX_PERIOD_DAYS 단위로 분할하여 조회
     *
     * KRX API는 기간 조회 시 최대 허용 일수가 있으며 (약 1년),
     * 초과 시 INVALIDPERIOD2 에러(HTTP 400)를 반환한다.
     * 이 메서드는 큰 범위를 자동으로 분할하여 결과를 합친다.
     */
    private suspend fun <T> fetchByDateChunks(
        startDate: String,
        endDate: String,
        fetcher: suspend (chunkStart: String, chunkEnd: String) -> List<T>,
    ): List<T> {
        val start = java.time.LocalDate.parse(startDate, DATE_FMT)
        val end = java.time.LocalDate.parse(endDate, DATE_FMT)
        val totalDays = java.time.temporal.ChronoUnit.DAYS.between(start, end)

        if (totalDays <= MAX_PERIOD_DAYS) {
            return fetcher(startDate, endDate)
        }

        val results = mutableListOf<T>()
        var chunkStart = start
        while (!chunkStart.isAfter(end)) {
            val chunkEnd = minOf(chunkStart.plusDays(MAX_PERIOD_DAYS), end)
            results.addAll(fetcher(chunkStart.format(DATE_FMT), chunkEnd.format(DATE_FMT)))
            chunkStart = chunkEnd.plusDays(1)
        }
        return results
    }

    /**
     * 리소스 정리
     */
    fun close() {
        client.close()
    }
}
