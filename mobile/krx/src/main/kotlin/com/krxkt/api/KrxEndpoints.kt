package com.krxkt.api

/**
 * KRX Open API 엔드포인트 상수
 *
 * 모든 요청은 BASE_URL로 POST, bld 파라미터로 데이터 유형 지정
 */
object KrxEndpoints {
    /**
     * KRX 데이터 API 기본 URL
     *
     * 참고: KRX API는 세션 기반 인증을 사용하며,
     * 일부 환경에서는 접근이 제한될 수 있음.
     * 프록시 또는 VPN 사용 시 우회 가능.
     */
    const val BASE_URL = "https://data.krx.co.kr/comm/bldAttendant/getJsonData.cmd"

    /** HTTP URL (레거시 호환용, 보안상 HTTPS 권장) */
    @Deprecated("Use BASE_URL (HTTPS) instead", replaceWith = ReplaceWith("BASE_URL"))
    const val BASE_URL_HTTP = "http://data.krx.co.kr/comm/bldAttendant/getJsonData.cmd"

    /** 기본 Referer 헤더 (outerLoader — 세션 불필요, 모든 엔드포인트에서 동작) */
    const val REFERER = "https://data.krx.co.kr/contents/MDC/MDI/outerLoader/index.cmd"

    /** 세션 초기화 URL (GET 요청으로 JSESSIONID 획득, 필요 시 사용) */
    const val SESSION_INIT_URL = "https://data.krx.co.kr/contents/MDC/MDI/mdiLoader/index.cmd?menuId=MDC0201"

    // === Login URLs ===

    /** 로그인 페이지 (초기 JSESSIONID 발급) */
    const val LOGIN_PAGE = "https://data.krx.co.kr/contents/MDC/COMS/client/MDCCOMS001.cmd"

    /** 로그인 JSP (iframe 세션 초기화) */
    const val LOGIN_JSP = "https://data.krx.co.kr/contents/MDC/COMS/client/view/login.jsp?site=mdc"

    /** 로그인 POST URL (실제 인증) */
    const val LOGIN_URL = "https://data.krx.co.kr/contents/MDC/COMS/client/MDCCOMS001D1.cmd"

    /** User-Agent 헤더 (브라우저 시뮬레이션) */
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /**
     * BLD (Business Logic Definition) 값
     * 각 API 기능별 엔드포인트 식별자
     */
    object Bld {
        // === Stock Data ===

        /** 전종목 시세 (일별) - OHLCV 전체 */
        const val STOCK_OHLCV_ALL = "dbms/MDC/STAT/standard/MDCSTAT01501"

        /** 개별종목 시세 추이 (기간 조회) */
        const val STOCK_OHLCV_BY_TICKER = "dbms/MDC/STAT/standard/MDCSTAT01701"

        /** 시가총액 (전종목시세 응답에 MKTCAP, LIST_SHRS 포함) */
        const val MARKET_CAP = "dbms/MDC/STAT/standard/MDCSTAT01501"

        /** 투자지표 (PER, PBR, EPS, BPS, DPS, 배당수익률) */
        const val FUNDAMENTAL = "dbms/MDC/STAT/standard/MDCSTAT03501"

        /** 개별종목 투자지표 추이 (PER, PBR, EPS, BPS, DPS, 배당수익률) */
        const val FUNDAMENTAL_BY_TICKER = "dbms/MDC/STAT/standard/MDCSTAT03502"

        /** 종목 리스트 (티커 목록) */
        const val TICKER_LIST = "dbms/MDC/STAT/standard/MDCSTAT01901"

        // === ETF Data ===

        /** ETF 전종목 시세 (일별) */
        const val ETF_PRICE = "dbms/MDC/STAT/standard/MDCSTAT04301"

        /** ETF 개별종목 시세 추이 (기간 조회) */
        const val ETF_OHLCV_BY_TICKER = "dbms/MDC/STAT/standard/MDCSTAT04501"

        /** ETF 종목 리스트 */
        const val ETF_TICKER_LIST = "dbms/MDC/STAT/standard/MDCSTAT04601"

        /** ETF 구성종목 (Portfolio Deposit File) */
        const val ETF_PORTFOLIO = "dbms/MDC/STAT/standard/MDCSTAT05001"

        // === Index Data ===

        /** 지수 시세 (OHLCV) */
        const val INDEX_OHLCV = "dbms/MDC/STAT/standard/MDCSTAT00301"

        /** 지수 리스트 / 전체지수 시세 (특정일 전종목) */
        const val INDEX_LIST = "dbms/MDC/STAT/standard/MDCSTAT00101"

        /** 지수 구성종목 (Portfolio Deposit File) */
        const val INDEX_PORTFOLIO = "dbms/MDC/STAT/standard/MDCSTAT00601"

        /** 지수 PER/PBR/배당수익률 (기간 조회) */
        const val INDEX_FUNDAMENTAL = "dbms/MDC/STAT/standard/MDCSTAT00702"

        // === Investor Trading ===

        /** 투자자별 거래실적 - 전체시장 기간합계 */
        const val INVESTOR_TRADING_MARKET_PERIOD = "dbms/MDC/STAT/standard/MDCSTAT02201"

        /** 투자자별 거래실적 - 전체시장 일별추이 (상세) */
        const val INVESTOR_TRADING_MARKET_DAILY = "dbms/MDC/STAT/standard/MDCSTAT02203"

        /** 투자자별 거래실적 - 개별종목 기간합계 */
        const val INVESTOR_TRADING_TICKER_PERIOD = "dbms/MDC/STAT/standard/MDCSTAT02301"

        /** 투자자별 거래실적 - 개별종목 일별추이 (상세) */
        const val INVESTOR_TRADING_TICKER_DAILY = "dbms/MDC/STAT/standard/MDCSTAT02303"

        // === Derivative Data ===

        /** 파생상품 지수 시세 (VKOSPI, 국채 등) */
        const val DERIVATIVE_INDEX = "dbms/MDC/STAT/standard/MDCSTAT01201"

        /** 옵션 거래량 (콜/풋 옵션, 일별 추이) */
        const val OPTION_TRADING = "dbms/MDC/STAT/standard/MDCSTAT13102"

        // === Short Selling ===

        /** 공매도 거래 - 전종목 (특정일) */
        const val SHORT_SELLING_ALL = "dbms/MDC/STAT/srt/MDCSTAT30101"

        /** 공매도 거래 - 개별종목 일별추이 */
        const val SHORT_SELLING_BY_TICKER = "dbms/MDC/STAT/srt/MDCSTAT30102"

        /** 공매도 잔고 - 전종목 (특정일) */
        const val SHORT_BALANCE_ALL = "dbms/MDC/STAT/srt/MDCSTAT30501"

        /** 공매도 잔고 - 개별종목 일별추이 */
        const val SHORT_BALANCE_BY_TICKER = "dbms/MDC/STAT/srt/MDCSTAT30502"
    }
}
