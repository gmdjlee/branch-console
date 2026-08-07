package com.krxkt.model

import com.google.gson.JsonObject
import com.krxkt.parser.getKrxDouble
import com.krxkt.parser.getKrxLong
import com.krxkt.parser.getStringOrEmpty

/**
 * 시가총액 데이터
 *
 * KRX API 응답 필드 매핑:
 * - ISU_SRT_CD: 종목코드 (6자리)
 * - ISU_ABBRV: 종목명 (약칭)
 * - TDD_CLSPRC: 종가
 * - FLUC_RT: 등락률 (%)
 * - MKTCAP: 시가총액 (원)
 * - LIST_SHRS: 상장주식수
 */
data class MarketCap(
    /** 종목코드 (예: "005930") */
    val ticker: String,
    /** 종목명 (예: "삼성전자") */
    val name: String,
    /** 종가 */
    val close: Long,
    /** 등락률 (%, 예: -0.50) */
    val changeRate: Double,
    /** 시가총액 (원) */
    val marketCap: Long,
    /** 상장주식수 */
    val sharesOutstanding: Long,
) {
    companion object {
        /**
         * KRX JSON 응답에서 MarketCap 객체 생성
         *
         * @param json OutBlock_1 배열의 개별 항목
         * @return MarketCap 객체, 필수 필드 누락 시 null
         */
        fun fromJson(json: JsonObject): MarketCap? {
            val ticker = json.getStringOrEmpty("ISU_SRT_CD")
            if (ticker.isEmpty()) return null

            return MarketCap(
                ticker = ticker,
                name = json.getStringOrEmpty("ISU_ABBRV"),
                close = json.getKrxLong("TDD_CLSPRC"),
                changeRate = json.getKrxDouble("FLUC_RT"),
                marketCap = json.getKrxLong("MKTCAP"),
                sharesOutstanding = json.getKrxLong("LIST_SHRS"),
            )
        }
    }
}
