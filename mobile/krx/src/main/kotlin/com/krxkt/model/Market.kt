package com.krxkt.model

/**
 * KRX 시장 구분 코드
 *
 * KRX API의 mktId 파라미터에 사용
 */
enum class Market(val code: String) {
    /** 코스피 */
    KOSPI("STK"),

    /** 코스닥 */
    KOSDAQ("KSQ"),

    /** 코넥스 */
    KONEX("KNX"),

    /** 전체 시장 */
    ALL("ALL"),
    ;

    companion object {
        /**
         * 코드로 Market enum 찾기
         * @param code KRX API 마켓 코드 (STK, KSQ, KNX, ALL)
         * @return 해당 Market, 없으면 null
         */
        fun fromCode(code: String): Market? = entries.find { it.code == code }
    }
}
