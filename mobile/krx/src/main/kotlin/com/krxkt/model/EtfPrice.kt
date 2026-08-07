package com.krxkt.model

import com.google.gson.JsonObject
import com.krxkt.parser.KrxJsonParser

/**
 * ETF 전종목 시세 데이터
 *
 * KRX API 응답 필드 매핑:
 * - ISU_SRT_CD → ticker
 * - ISU_ABBRV → name (종목명)
 * - NAV → nav (순자산가치)
 * - TDD_OPNPRC → open
 * - TDD_HGPRC → high
 * - TDD_LWPRC → low
 * - TDD_CLSPRC → close
 * - ACC_TRDVOL → volume
 * - ACC_TRDVAL → tradingValue
 * - OBJ_STKPRC_IDX → underlyingIndex (기초지수)
 * - FLUC_RT → changeRate (등락률)
 *
 * @property ticker 종목코드 (예: "069500")
 * @property name 종목명 (예: "KODEX 200")
 * @property nav 순자산가치 (원)
 * @property open 시가 (원)
 * @property high 고가 (원)
 * @property low 저가 (원)
 * @property close 종가 (원)
 * @property volume 거래량 (주)
 * @property tradingValue 거래대금 (원)
 * @property underlyingIndex 기초지수
 * @property changeRate 등락률 (%)
 */
data class EtfPrice(
    val ticker: String,
    val name: String,
    val nav: Double?,
    val open: Long,
    val high: Long,
    val low: Long,
    val close: Long,
    val volume: Long,
    val tradingValue: Long,
    val underlyingIndex: Double?,
    val changeRate: Double?,
) {
    companion object {
        /**
         * KRX JSON 응답에서 EtfPrice 객체 생성
         *
         * @param json OutBlock_1 배열의 개별 JSON 객체
         * @return EtfPrice 또는 null (파싱 실패 시)
         */
        fun fromJson(json: JsonObject): EtfPrice? {
            return try {
                val ticker = json.get("ISU_SRT_CD")?.asString ?: return null
                val name = json.get("ISU_ABBRV")?.asString ?: ""

                EtfPrice(
                    ticker = ticker,
                    name = name,
                    nav = KrxJsonParser.parseDouble(json.get("NAV")?.asString),
                    open = KrxJsonParser.parseLong(json.get("TDD_OPNPRC")?.asString) ?: 0L,
                    high = KrxJsonParser.parseLong(json.get("TDD_HGPRC")?.asString) ?: 0L,
                    low = KrxJsonParser.parseLong(json.get("TDD_LWPRC")?.asString) ?: 0L,
                    close = KrxJsonParser.parseLong(json.get("TDD_CLSPRC")?.asString) ?: 0L,
                    volume = KrxJsonParser.parseLong(json.get("ACC_TRDVOL")?.asString) ?: 0L,
                    tradingValue = KrxJsonParser.parseLong(json.get("ACC_TRDVAL")?.asString) ?: 0L,
                    underlyingIndex = KrxJsonParser.parseDouble(json.get("OBJ_STKPRC_IDX")?.asString),
                    changeRate = KrxJsonParser.parseDouble(json.get("FLUC_RT")?.asString),
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
