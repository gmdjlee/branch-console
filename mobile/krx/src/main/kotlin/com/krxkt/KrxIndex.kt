package com.krxkt

import com.krxkt.api.KrxClient
import com.krxkt.api.KrxEndpoints
import com.krxkt.model.DerivativeIndex
import com.krxkt.model.IndexFundamentalHistory
import com.krxkt.model.IndexInfo
import com.krxkt.model.IndexMarket
import com.krxkt.model.IndexOhlcv
import com.krxkt.model.IndexOhlcvByTicker
import com.krxkt.model.IndexPortfolio
import com.krxkt.model.OptionVolume
import com.krxkt.parser.KrxJsonParser
import com.krxkt.util.DateUtils

/**
 * KRX 지수 데이터 API
 *
 * pykrx의 index 모듈과 호환되는 Kotlin 구현
 *
 * 주요 지수 티커:
 * - "1001" = KOSPI
 * - "1028" = KOSPI 200
 * - "2001" = KOSDAQ
 * - "2203" = KOSDAQ 150
 *
 * 티커 구조: [타입코드 1자리] + [지수코드 3자리]
 * - 타입코드: 1=KOSPI, 2=KOSDAQ, 3=파생, 4=테마
 *
 * 사용 예:
 * ```
 * val krxIndex = KrxIndex()
 *
 * // KOSPI 200 기간 조회
 * val history = krxIndex.getOhlcvByTicker("20210101", "20210131", "1028")
 *
 * // 지수 목록 조회
 * val indexList = krxIndex.getIndexList("20210122")
 * ```
 *
 * @param client HTTP 클라이언트 (테스트용 주입 가능)
 */
class KrxIndex(
    private val client: KrxClient = KrxClient(),
) {
    /**
     * 지수 OHLCV 기간 조회
     *
     * pykrx: index.get_index_ohlcv("20210101", "20210131", "1028")
     *
     * 주의: KRX API는 최대 2년 조회 제한이 있음
     *
     * @param startDate 시작 날짜 (yyyyMMdd)
     * @param endDate 종료 날짜 (yyyyMMdd)
     * @param ticker 지수 티커 (예: "1028" = KOSPI 200)
     * @return 날짜별 OHLCV 리스트
     */
    suspend fun getOhlcvByTicker(
        startDate: String,
        endDate: String,
        ticker: String,
    ): List<IndexOhlcv> {
        DateUtils.validateDateRange(startDate, endDate)
        require(ticker.length >= 2) { "Invalid index ticker: $ticker" }

        // 티커 파싱: "1028" → indIdx="1", indIdx2="028"
        // KRX MDCSTAT00301 파라미터: indIdx=시장구분, indIdx2=지수코드
        val indIdx = ticker.substring(0, 1)
        val indIdx2 = ticker.substring(1).padStart(3, '0')

        val params =
            mapOf(
                "bld" to KrxEndpoints.Bld.INDEX_OHLCV,
                "indIdx" to indIdx,
                "indIdx2" to indIdx2,
                "strtDd" to startDate,
                "endDd" to endDate,
            )

        val response = client.post(params)
        val jsonArray = KrxJsonParser.parseOutBlock(response)

        return jsonArray.mapNotNull { IndexOhlcv.fromJson(it) }
    }

    /**
     * 지수 PER/PBR/배당수익률 기간 조회
     *
     * @param startDate 시작 날짜 (yyyyMMdd)
     * @param endDate 종료 날짜 (yyyyMMdd)
     * @param ticker 지수 티커 (예: "1001" = KOSPI, "2001" = KOSDAQ)
     * @return 날짜별 PER/PBR/배당수익률 리스트
     */
    suspend fun getIndexFundamental(
        startDate: String,
        endDate: String,
        ticker: String,
    ): List<IndexFundamentalHistory> {
        DateUtils.validateDateRange(startDate, endDate)
        require(ticker.length >= 2) { "Invalid index ticker: $ticker" }

        // MDCSTAT00702 uses indTpCd/idxIndCd (not indIdx/indIdx2)
        // ticker "1001" → indTpCd="1", idxIndCd="001" (KOSPI)
        // ticker "2001" → indTpCd="2", idxIndCd="001" (KOSDAQ)
        val indTpCd = ticker.substring(0, 1)
        val idxIndCd = ticker.substring(1).padStart(3, '0')

        val params =
            mapOf(
                "bld" to KrxEndpoints.Bld.INDEX_FUNDAMENTAL,
                "indTpCd" to indTpCd,
                "idxIndCd" to idxIndCd,
                "strtDd" to startDate,
                "endDd" to endDate,
            )

        val response = client.post(params)
        val jsonArray = KrxJsonParser.parseOutBlock(response)

        return jsonArray.mapNotNull { IndexFundamentalHistory.fromJson(it) }
    }

    /**
     * 지수 목록 조회
     *
     * @param date 조회 날짜 (yyyyMMdd)
     * @param market 시장 구분 (기본: ALL)
     * @return 지수 정보 리스트
     */
    suspend fun getIndexList(
        date: String,
        market: IndexMarket = IndexMarket.ALL,
    ): List<IndexInfo> {
        DateUtils.validateDate(date)

        val params =
            buildMap {
                put("bld", KrxEndpoints.Bld.INDEX_LIST)
                put("trdDd", date)
                if (market != IndexMarket.ALL) {
                    put("indTpCd", market.code)
                }
            }

        val response = client.post(params)
        val jsonArray = KrxJsonParser.parseOutBlock(response)

        return jsonArray.mapNotNull { IndexInfo.fromJson(it) }
    }

    /**
     * 지수 이름 조회
     *
     * @param ticker 지수 티커 (예: "1028")
     * @param date 기준 날짜
     * @return 지수명 (예: "코스피 200"), 없으면 null
     */
    suspend fun getIndexName(
        ticker: String,
        date: String,
    ): String? {
        val indexList = getIndexList(date, IndexMarket.ALL)
        return indexList.find { it.ticker == ticker }?.name
    }

    /**
     * KOSPI 지수 조회
     *
     * @param startDate 시작 날짜 (yyyyMMdd)
     * @param endDate 종료 날짜 (yyyyMMdd)
     * @return KOSPI 지수 OHLCV 리스트
     */
    suspend fun getKospi(
        startDate: String,
        endDate: String,
    ): List<IndexOhlcv> {
        return getOhlcvByTicker(startDate, endDate, TICKER_KOSPI)
    }

    /**
     * KOSPI 200 지수 조회
     *
     * @param startDate 시작 날짜 (yyyyMMdd)
     * @param endDate 종료 날짜 (yyyyMMdd)
     * @return KOSPI 200 지수 OHLCV 리스트
     */
    suspend fun getKospi200(
        startDate: String,
        endDate: String,
    ): List<IndexOhlcv> {
        return getOhlcvByTicker(startDate, endDate, TICKER_KOSPI_200)
    }

    /**
     * KOSDAQ 지수 조회
     *
     * @param startDate 시작 날짜 (yyyyMMdd)
     * @param endDate 종료 날짜 (yyyyMMdd)
     * @return KOSDAQ 지수 OHLCV 리스트
     */
    suspend fun getKosdaq(
        startDate: String,
        endDate: String,
    ): List<IndexOhlcv> {
        return getOhlcvByTicker(startDate, endDate, TICKER_KOSDAQ)
    }

    /**
     * KOSDAQ 150 지수 조회
     *
     * @param startDate 시작 날짜 (yyyyMMdd)
     * @param endDate 종료 날짜 (yyyyMMdd)
     * @return KOSDAQ 150 지수 OHLCV 리스트
     */
    suspend fun getKosdaq150(
        startDate: String,
        endDate: String,
    ): List<IndexOhlcv> {
        return getOhlcvByTicker(startDate, endDate, TICKER_KOSDAQ_150)
    }

    // ============================================================
    // 전종목 지수 OHLCV (특정일)
    // ============================================================

    /**
     * 전종목 지수 OHLCV 조회 (특정일)
     *
     * pykrx: index.get_index_ohlcv_by_ticker(date, market)
     *
     * @param date 조회 날짜 (yyyyMMdd)
     * @param market 시장 구분 (기본: KOSPI)
     * @return 전종목 지수 OHLCV 리스트
     */
    suspend fun getIndexOhlcv(
        date: String,
        market: IndexMarket = IndexMarket.KOSPI,
    ): List<IndexOhlcvByTicker> {
        DateUtils.validateDate(date)

        val params =
            buildMap {
                put("bld", KrxEndpoints.Bld.INDEX_LIST)
                put("trdDd", date)
                if (market != IndexMarket.ALL) {
                    put("idxIndMidclssCd", market.krxCode)
                }
            }

        val response = client.post(params)
        val jsonArray = KrxJsonParser.parseOutBlock(response)

        return jsonArray.mapNotNull { IndexOhlcvByTicker.fromJson(it) }
    }

    // ============================================================
    // 지수 구성종목 (Portfolio Deposit File)
    // ============================================================

    /**
     * 지수 구성종목 조회
     *
     * pykrx: index.get_index_portfolio_deposit_file(ticker, date)
     *
     * @param date 조회 날짜 (yyyyMMdd)
     * @param ticker 지수 티커 (예: "1028" = KOSPI 200)
     * @return 지수 구성종목 리스트
     */
    suspend fun getIndexPortfolio(
        date: String,
        ticker: String,
    ): List<IndexPortfolio> {
        DateUtils.validateDate(date)
        require(ticker.length >= 2) { "Invalid index ticker: $ticker" }

        val indIdx = ticker.substring(0, 1)
        val indIdx2 = ticker.substring(1).padStart(3, '0')

        val params =
            mapOf(
                "bld" to KrxEndpoints.Bld.INDEX_PORTFOLIO,
                "trdDd" to date,
                "indIdx" to indIdx,
                "indIdx2" to indIdx2,
            )

        val response = client.post(params)
        val jsonArray = KrxJsonParser.parseOutBlock(response)

        return jsonArray.mapNotNull { IndexPortfolio.fromJson(it) }
    }

    /**
     * 지수 구성종목 티커 리스트 조회 (pykrx 호환)
     *
     * pykrx: index.get_index_portfolio_deposit_file() returns list of tickers
     *
     * @param date 조회 날짜 (yyyyMMdd)
     * @param ticker 지수 티커
     * @return 구성종목 코드 리스트 (예: ["005930", "000660", ...])
     */
    suspend fun getIndexPortfolioTickers(
        date: String,
        ticker: String,
    ): List<String> {
        return getIndexPortfolio(date, ticker).map { it.ticker }
    }

    // ============================================================
    // 파생상품 지수 (Derivative Index)
    // ============================================================

    /**
     * 파생상품 지수 조회 (MDCSTAT01201)
     *
     * feargreed.py의 KRXFetcher.get_index() (type="D") 대응:
     * - VKOSPI, 5년국채, 10년국채 등 파생상품 관련 지수
     *
     * @param startDate 시작 날짜 (yyyyMMdd)
     * @param endDate 종료 날짜 (yyyyMMdd)
     * @param indTpCd 지수 타입 코드 (예: "1", "D")
     * @param idxIndCd 지수 코드 (예: "300"=VKOSPI, "896"=5년국채, "309"=10년국채)
     * @return 날짜별 종가 리스트
     */
    suspend fun getDerivativeIndex(
        startDate: String,
        endDate: String,
        indTpCd: String,
        idxIndCd: String,
    ): List<DerivativeIndex> {
        DateUtils.validateDateRange(startDate, endDate)

        val params =
            mapOf(
                "bld" to KrxEndpoints.Bld.DERIVATIVE_INDEX,
                "locale" to "ko_KR",
                "indTpCd" to indTpCd,
                "idxIndCd" to idxIndCd,
                "idxCd" to indTpCd,
                "idxCd2" to idxIndCd,
                "strtDd" to startDate,
                "endDd" to endDate,
                "csvxls_isNo" to "false",
            )

        val response = client.post(params)
        val jsonArray = KrxJsonParser.parseOutBlock(response)

        return jsonArray.mapNotNull { DerivativeIndex.fromJson(it) }
    }

    /**
     * VKOSPI (변동성 지수) 조회
     *
     * @param startDate 시작 날짜 (yyyyMMdd)
     * @param endDate 종료 날짜 (yyyyMMdd)
     * @return VKOSPI 종가 리스트
     */
    suspend fun getVkospi(
        startDate: String,
        endDate: String,
    ): List<DerivativeIndex> {
        return getDerivativeIndex(startDate, endDate, VKOSPI_TYPE, VKOSPI_CODE)
    }

    /**
     * 5년 국채 지수 조회
     *
     * @param startDate 시작 날짜 (yyyyMMdd)
     * @param endDate 종료 날짜 (yyyyMMdd)
     * @return 5년국채 종가 리스트
     */
    suspend fun getBond5y(
        startDate: String,
        endDate: String,
    ): List<DerivativeIndex> {
        return getDerivativeIndex(startDate, endDate, BOND_5Y_TYPE, BOND_5Y_CODE)
    }

    /**
     * 10년 국채 지수 조회
     *
     * @param startDate 시작 날짜 (yyyyMMdd)
     * @param endDate 종료 날짜 (yyyyMMdd)
     * @return 10년국채 종가 리스트
     */
    suspend fun getBond10y(
        startDate: String,
        endDate: String,
    ): List<DerivativeIndex> {
        return getDerivativeIndex(startDate, endDate, BOND_10Y_TYPE, BOND_10Y_CODE)
    }

    // ============================================================
    // 옵션 거래량 (Option Trading Volume)
    // ============================================================

    /**
     * 옵션 거래량 조회 (MDCSTAT13102)
     *
     * feargreed.py의 KRXFetcher.get_option() 대응:
     * - KOSPI200 옵션 (콜/풋) 일별 거래량
     *
     * @param startDate 시작 날짜 (yyyyMMdd)
     * @param endDate 종료 날짜 (yyyyMMdd)
     * @param optionType 옵션 타입 ("C"=콜, "P"=풋)
     * @return 날짜별 거래량 리스트
     */
    suspend fun getOptionVolume(
        startDate: String,
        endDate: String,
        optionType: String,
    ): List<OptionVolume> {
        DateUtils.validateDateRange(startDate, endDate)
        require(optionType == "C" || optionType == "P") {
            "optionType must be 'C' (Call) or 'P' (Put), got: $optionType"
        }

        val params =
            mapOf(
                "bld" to KrxEndpoints.Bld.OPTION_TRADING,
                "inqTpCd" to "2",
                "prtType" to "QTY",
                "prtCheck" to "SU",
                "isuCd02" to "KR___OPK2I",
                "isuCd" to "KR___OPK2I",
                "prodId" to "KR___OPK2I",
                "aggBasTpCd" to "",
                "strtDd" to startDate,
                "endDd" to endDate,
                "isuOpt" to optionType,
            )

        val response = client.post(params)
        val jsonArray = KrxJsonParser.parseOutBlock(response)

        return jsonArray.mapNotNull { OptionVolume.fromJson(it) }
    }

    /**
     * 콜 옵션 거래량 조회
     *
     * @param startDate 시작 날짜 (yyyyMMdd)
     * @param endDate 종료 날짜 (yyyyMMdd)
     * @return 콜 옵션 일별 거래량 리스트
     */
    suspend fun getCallOptionVolume(
        startDate: String,
        endDate: String,
    ): List<OptionVolume> {
        return getOptionVolume(startDate, endDate, "C")
    }

    /**
     * 풋 옵션 거래량 조회
     *
     * @param startDate 시작 날짜 (yyyyMMdd)
     * @param endDate 종료 날짜 (yyyyMMdd)
     * @return 풋 옵션 일별 거래량 리스트
     */
    suspend fun getPutOptionVolume(
        startDate: String,
        endDate: String,
    ): List<OptionVolume> {
        return getOptionVolume(startDate, endDate, "P")
    }

    // ============================================================
    // 영업일 조회 (Business Days)
    // ============================================================

    /**
     * 최근 영업일 조회
     *
     * pykrx: get_nearest_business_day_in_a_week(date, prev)
     *
     * 지정일이 영업일이면 그대로 반환, 아니면 최대 7일 전/후로 탐색.
     * KOSPI 지수 OHLCV 기간 조회(MDCSTAT00301)를 사용하여
     * 실제 거래일만 포함된 결과에서 가장 가까운 날짜를 선택.
     *
     * @param date 기준 날짜 (yyyyMMdd)
     * @param prev true면 이전 영업일, false면 다음 영업일 탐색
     * @return 영업일 날짜 (yyyyMMdd)
     * @throws IllegalStateException 7일 내에 영업일이 없는 경우
     */
    suspend fun getNearestBusinessDay(
        date: String,
        prev: Boolean = true,
    ): String {
        DateUtils.validateDate(date)

        val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")
        val baseDate = java.time.LocalDate.parse(date, fmt)

        // 7일 범위를 한 번의 API 호출로 조회 (실제 거래일만 반환)
        val rangeStart: String
        val rangeEnd: String
        if (prev) {
            rangeStart = baseDate.minusDays(7).format(fmt)
            rangeEnd = date
        } else {
            rangeStart = date
            rangeEnd = baseDate.plusDays(7).format(fmt)
        }

        val tradingDays =
            getOhlcvByTicker(rangeStart, rangeEnd, TICKER_KOSPI)
                .map { it.date }
                .sorted()

        if (tradingDays.isEmpty()) {
            throw IllegalStateException("No business day found within 7 days of $date")
        }

        return if (prev) {
            // 기준일 이하 중 가장 최근 날짜
            tradingDays.lastOrNull { it <= date }
                ?: throw IllegalStateException("No previous business day found within 7 days of $date")
        } else {
            // 기준일 이상 중 가장 빠른 날짜
            tradingDays.firstOrNull { it >= date }
                ?: throw IllegalStateException("No next business day found within 7 days of $date")
        }
    }

    /**
     * 기간 내 영업일 목록 조회
     *
     * pykrx: get_previous_business_days(fromdate, todate)
     *
     * KOSPI 지수 OHLCV 기간 조회를 통해 실제 거래일 목록 추출.
     *
     * @param startDate 시작 날짜 (yyyyMMdd)
     * @param endDate 종료 날짜 (yyyyMMdd)
     * @return 영업일 목록 (yyyyMMdd, 오름차순)
     */
    suspend fun getBusinessDays(
        startDate: String,
        endDate: String,
    ): List<String> {
        DateUtils.validateDateRange(startDate, endDate)

        val ohlcvList = getOhlcvByTicker(startDate, endDate, TICKER_KOSPI)
        return ohlcvList.map { it.date }.sorted()
    }

    /**
     * 특정 월의 영업일 목록 조회
     *
     * pykrx: get_previous_business_days(year, month)
     *
     * @param year 연도 (예: 2021)
     * @param month 월 (1-12)
     * @return 영업일 목록 (yyyyMMdd, 오름차순)
     */
    suspend fun getBusinessDaysByMonth(
        year: Int,
        month: Int,
    ): List<String> {
        require(year in 1990..2100) { "Invalid year: $year" }
        require(month in 1..12) { "Invalid month: $month" }

        val startDate = String.format("%04d%02d01", year, month)
        val lastDay = java.time.YearMonth.of(year, month).lengthOfMonth()
        val endDate = String.format("%04d%02d%02d", year, month, lastDay)

        return getBusinessDays(startDate, endDate)
    }

    /**
     * 리소스 정리
     */
    fun close() {
        client.close()
    }

    companion object {
        /** KOSPI 티커 */
        const val TICKER_KOSPI = "1001"

        /** KOSPI 200 티커 */
        const val TICKER_KOSPI_200 = "1028"

        /** KOSPI 대형주 티커 */
        const val TICKER_KOSPI_LARGE = "1002"

        /** KOSPI 중형주 티커 */
        const val TICKER_KOSPI_MID = "1003"

        /** KOSPI 소형주 티커 */
        const val TICKER_KOSPI_SMALL = "1004"

        /** KOSDAQ 티커 */
        const val TICKER_KOSDAQ = "2001"

        /** KOSDAQ 150 티커 */
        const val TICKER_KOSDAQ_150 = "2203"

        // === 파생상품 지수 코드 (MDCSTAT01201 파라미터) ===

        /** VKOSPI 타입 코드 */
        const val VKOSPI_TYPE = "1"

        /** VKOSPI 지수 코드 */
        const val VKOSPI_CODE = "300"

        /** 5년국채 타입 코드 */
        const val BOND_5Y_TYPE = "D"

        /** 5년국채 지수 코드 */
        const val BOND_5Y_CODE = "896"

        /** 10년국채 타입 코드 */
        const val BOND_10Y_TYPE = "1"

        /** 10년국채 지수 코드 */
        const val BOND_10Y_CODE = "309"
    }
}
