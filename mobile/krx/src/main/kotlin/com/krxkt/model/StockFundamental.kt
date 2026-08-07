package com.krxkt.model

import com.google.gson.JsonObject
import com.krxkt.parser.getKrxDouble
import com.krxkt.parser.getKrxLong
import com.krxkt.parser.getStringOrEmpty

/**
 * 주식 투자지표 (펀더멘탈) 데이터
 *
 * KRX API 응답 필드 매핑:
 * - ISU_SRT_CD: 종목코드 (6자리)
 * - ISU_ABBRV: 종목명 (약칭)
 * - TDD_CLSPRC: 종가
 * - EPS: 주당순이익
 * - PER: 주가수익비율
 * - BPS: 주당순자산
 * - PBR: 주가순자산비율
 * - DPS: 주당배당금
 * - DVD_YLD: 배당수익률 (%)
 */
data class StockFundamental(
    /** 종목코드 (예: "005930") */
    val ticker: String,
    /** 종목명 (예: "삼성전자") */
    val name: String,
    /** 종가 */
    val close: Long,
    /** 주당순이익 (EPS) */
    val eps: Long,
    /** 주가수익비율 (PER) */
    val per: Double,
    /** 주당순자산 (BPS) */
    val bps: Long,
    /** 주가순자산비율 (PBR) */
    val pbr: Double,
    /** 주당배당금 (DPS) */
    val dps: Long,
    /** 배당수익률 (%) */
    val dividendYield: Double,
) {
    companion object {
        /**
         * KRX JSON 응답에서 StockFundamental 객체 생성
         *
         * @param json OutBlock_1 배열의 개별 항목
         * @return StockFundamental 객체, 필수 필드 누락 시 null
         */
        fun fromJson(json: JsonObject): StockFundamental? {
            val ticker = json.getStringOrEmpty("ISU_SRT_CD")
            if (ticker.isEmpty()) return null

            return StockFundamental(
                ticker = ticker,
                name = json.getStringOrEmpty("ISU_ABBRV"),
                close = json.getKrxLong("TDD_CLSPRC"),
                eps = json.getKrxLong("EPS"),
                per = json.getKrxDouble("PER"),
                bps = json.getKrxLong("BPS"),
                pbr = json.getKrxDouble("PBR"),
                dps = json.getKrxLong("DPS"),
                dividendYield = json.getKrxDouble("DVD_YLD"),
            )
        }
    }
}
