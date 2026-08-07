package com.krxkt.model

import com.google.gson.JsonObject
import com.krxkt.parser.getKrxDouble
import com.krxkt.parser.getKrxLong
import com.krxkt.parser.getStringOrEmpty

/**
 * 개별 종목 OHLCV 히스토리 (기간 조회용)
 *
 * 특정 종목의 날짜별 시세 데이터
 *
 * KRX API 응답 필드 매핑:
 * - TRD_DD: 거래일자 (yyyyMMdd → yyyy/MM/dd 형식으로 응답)
 * - TDD_OPNPRC: 시가
 * - TDD_HGPRC: 고가
 * - TDD_LWPRC: 저가
 * - TDD_CLSPRC: 종가
 * - ACC_TRDVOL: 거래량
 * - ACC_TRDVAL: 거래대금
 * - FLUC_RT: 등락률 (%)
 */
data class StockOhlcvHistory(
    /** 거래일자 (yyyyMMdd 형식, 예: "20210122") */
    val date: String,
    /** 시가 */
    val open: Long,
    /** 고가 */
    val high: Long,
    /** 저가 */
    val low: Long,
    /** 종가 */
    val close: Long,
    /** 거래량 (주) */
    val volume: Long,
    /** 거래대금 (원) */
    val tradingValue: Long,
    /** 등락률 (%, 예: -0.50) */
    val changeRate: Double,
) {
    companion object {
        /**
         * KRX JSON 응답에서 StockOhlcvHistory 객체 생성
         *
         * @param json OutBlock_1 배열의 개별 항목
         * @return StockOhlcvHistory 객체, 필수 필드 누락 시 null
         */
        fun fromJson(json: JsonObject): StockOhlcvHistory? {
            // 날짜 필드 파싱 (yyyy/MM/dd → yyyyMMdd 변환)
            val rawDate = json.getStringOrEmpty("TRD_DD")
            if (rawDate.isEmpty()) return null

            val date = rawDate.replace("/", "")

            return StockOhlcvHistory(
                date = date,
                open = json.getKrxLong("TDD_OPNPRC"),
                high = json.getKrxLong("TDD_HGPRC"),
                low = json.getKrxLong("TDD_LWPRC"),
                close = json.getKrxLong("TDD_CLSPRC"),
                volume = json.getKrxLong("ACC_TRDVOL"),
                tradingValue = json.getKrxLong("ACC_TRDVAL"),
                changeRate = json.getKrxDouble("FLUC_RT"),
            )
        }
    }
}
