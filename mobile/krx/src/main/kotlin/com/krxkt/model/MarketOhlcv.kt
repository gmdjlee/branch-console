package com.krxkt.model

import com.google.gson.JsonObject
import com.krxkt.parser.getKrxDouble
import com.krxkt.parser.getKrxLong
import com.krxkt.parser.getStringOrEmpty

/**
 * 시장 OHLCV (시가/고가/저가/종가/거래량) 데이터
 *
 * KRX API 응답 필드 매핑:
 * - ISU_SRT_CD: 종목코드 (6자리)
 * - ISU_ABBRV: 종목명 (약칭)
 * - TDD_OPNPRC: 시가
 * - TDD_HGPRC: 고가
 * - TDD_LWPRC: 저가
 * - TDD_CLSPRC: 종가
 * - ACC_TRDVOL: 거래량
 * - ACC_TRDVAL: 거래대금
 * - FLUC_RT: 등락률 (%)
 */
data class MarketOhlcv(
    /** 종목코드 (예: "005930") */
    val ticker: String,
    /** 종목명 (예: "삼성전자") */
    val name: String,
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
         * KRX JSON 응답에서 MarketOhlcv 객체 생성
         *
         * @param json OutBlock_1 배열의 개별 항목
         * @return MarketOhlcv 객체, 필수 필드 누락 시 null
         */
        fun fromJson(json: JsonObject): MarketOhlcv? {
            val ticker = json.getStringOrEmpty("ISU_SRT_CD")
            if (ticker.isEmpty()) return null

            return MarketOhlcv(
                ticker = ticker,
                name = json.getStringOrEmpty("ISU_ABBRV"),
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
