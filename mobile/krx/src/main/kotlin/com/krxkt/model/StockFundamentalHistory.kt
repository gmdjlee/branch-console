package com.krxkt.model

import com.google.gson.JsonObject
import com.krxkt.parser.getKrxDouble
import com.krxkt.parser.getKrxLong
import com.krxkt.parser.getStringOrEmpty

/**
 * 개별 종목 투자지표 히스토리 (기간 조회용)
 *
 * 특정 종목의 날짜별 투자지표 데이터
 *
 * KRX API 응답 필드 매핑:
 * - TRD_DD: 거래일자 (yyyyMMdd → yyyy/MM/dd 형식으로 응답)
 * - TDD_CLSPRC: 종가
 * - EPS: 주당순이익
 * - PER: 주가수익비율
 * - BPS: 주당순자산
 * - PBR: 주가순자산비율
 * - DPS: 주당배당금
 * - DVD_YLD: 배당수익률 (%)
 */
data class StockFundamentalHistory(
    /** 거래일자 (yyyyMMdd 형식, 예: "20210122") */
    val date: String,
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
    /** 배당수익률 (%, 예: 2.50) */
    val dividendYield: Double,
) {
    companion object {
        /**
         * KRX JSON 응답에서 StockFundamentalHistory 객체 생성
         *
         * @param json OutBlock_1 배열의 개별 항목
         * @return StockFundamentalHistory 객체, 필수 필드 누락 시 null
         */
        fun fromJson(json: JsonObject): StockFundamentalHistory? {
            val rawDate = json.getStringOrEmpty("TRD_DD")
            if (rawDate.isEmpty()) return null

            val date = rawDate.replace("/", "")

            return StockFundamentalHistory(
                date = date,
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
