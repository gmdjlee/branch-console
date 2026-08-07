package com.krxkt.model

import com.google.gson.JsonObject
import com.krxkt.parser.KrxJsonParser

/**
 * 전종목 지수 OHLCV 데이터 (특정일 기준)
 *
 * pykrx: get_index_ohlcv_by_ticker(date, market)
 *
 * KRX API 응답 필드 매핑 (MDCSTAT00101):
 * - IDX_NM → name (지수명)
 * - CLSPRC_IDX → close (종가지수)
 * - FLUC_TP_CD → changeType (등락구분)
 * - CMPPREVDD_IDX → change (전일대비)
 * - FLUC_RT → changeRate (등락률, %)
 * - OPNPRC_IDX → open (시가지수)
 * - HGPRC_IDX → high (고가지수)
 * - LWPRC_IDX → low (저가지수)
 * - ACC_TRDVOL → volume (거래량)
 * - ACC_TRDVAL → tradingValue (거래대금)
 * - MKTCAP → marketCap (시가총액)
 *
 * @property name 지수명 (예: "코스피", "코스피 200")
 * @property open 시가지수
 * @property high 고가지수
 * @property low 저가지수
 * @property close 종가지수
 * @property volume 거래량 (주)
 * @property tradingValue 거래대금 (백만원)
 * @property marketCap 시가총액 (백만원)
 * @property changeType 등락구분 (1: 상승, 2: 하락, 3: 보합)
 * @property change 전일대비 포인트
 * @property changeRate 등락률 (%)
 */
data class IndexOhlcvByTicker(
    val name: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
    val tradingValue: Long,
    val marketCap: Long,
    val changeType: Int?,
    val change: Double?,
    val changeRate: Double?,
) {
    companion object {
        fun fromJson(json: JsonObject): IndexOhlcvByTicker? {
            return try {
                val name = json.get("IDX_NM")?.asString ?: return null
                if (name.isEmpty()) return null

                IndexOhlcvByTicker(
                    name = name,
                    open = KrxJsonParser.parseDouble(json.get("OPNPRC_IDX")?.asString) ?: 0.0,
                    high = KrxJsonParser.parseDouble(json.get("HGPRC_IDX")?.asString) ?: 0.0,
                    low = KrxJsonParser.parseDouble(json.get("LWPRC_IDX")?.asString) ?: 0.0,
                    close = KrxJsonParser.parseDouble(json.get("CLSPRC_IDX")?.asString) ?: 0.0,
                    volume = KrxJsonParser.parseLong(json.get("ACC_TRDVOL")?.asString) ?: 0L,
                    tradingValue = KrxJsonParser.parseLong(json.get("ACC_TRDVAL")?.asString) ?: 0L,
                    marketCap = KrxJsonParser.parseLong(json.get("MKTCAP")?.asString) ?: 0L,
                    changeType = json.get("FLUC_TP_CD")?.asString?.toIntOrNull(),
                    change = KrxJsonParser.parseDouble(json.get("CMPPREVDD_IDX")?.asString),
                    changeRate = KrxJsonParser.parseDouble(json.get("FLUC_RT")?.asString),
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    val isUp: Boolean get() = changeType == 1
    val isDown: Boolean get() = changeType == 2
    val isUnchanged: Boolean get() = changeType == 3
}
