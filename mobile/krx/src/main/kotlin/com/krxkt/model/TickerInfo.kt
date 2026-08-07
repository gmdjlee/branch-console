package com.krxkt.model

import com.google.gson.JsonObject
import com.krxkt.parser.getStringOrEmpty

/**
 * 종목 기본 정보 (티커 리스트용)
 *
 * KRX API 응답 필드 매핑:
 * - ISU_SRT_CD: 종목코드 (6자리)
 * - ISU_ABBRV: 종목명 (약칭)
 * - MKT_TP_NM: 시장구분명 (KOSPI, KOSDAQ 등)
 * - ISU_CD: ISIN 코드
 */
data class TickerInfo(
    /** 종목코드 (예: "005930") */
    val ticker: String,
    /** 종목명 (예: "삼성전자") */
    val name: String,
    /** 시장구분 (예: "KOSPI", "KOSDAQ") */
    val marketName: String,
    /** ISIN 코드 (예: "KR7005930003") */
    val isinCode: String,
) {
    companion object {
        /**
         * KRX JSON 응답에서 TickerInfo 객체 생성
         *
         * @param json OutBlock_1 배열의 개별 항목
         * @return TickerInfo 객체, 필수 필드 누락 시 null
         */
        fun fromJson(json: JsonObject): TickerInfo? {
            val ticker = json.getStringOrEmpty("ISU_SRT_CD")
            if (ticker.isEmpty()) return null

            return TickerInfo(
                ticker = ticker,
                name = json.getStringOrEmpty("ISU_ABBRV"),
                marketName = json.getStringOrEmpty("MKT_TP_NM"),
                isinCode = json.getStringOrEmpty("ISU_CD"),
            )
        }
    }
}
