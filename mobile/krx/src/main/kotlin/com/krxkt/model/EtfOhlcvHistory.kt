package com.krxkt.model

import com.google.gson.JsonObject
import com.krxkt.parser.KrxJsonParser

/**
 * ETF 개별종목 OHLCV 히스토리 데이터
 *
 * KRX API 응답 필드 매핑:
 * - TRD_DD → date (거래일)
 * - LST_NAV → nav (순자산가치)
 * - TDD_OPNPRC → open
 * - TDD_HGPRC → high
 * - TDD_LWPRC → low
 * - TDD_CLSPRC → close
 * - ACC_TRDVOL → volume
 * - ACC_TRDVAL → tradingValue
 * - OBJ_STKPRC_IDX → underlyingIndex (기초지수)
 *
 * @property date 거래일 (yyyyMMdd 또는 yyyy/MM/dd)
 * @property nav 순자산가치 (원)
 * @property open 시가 (원)
 * @property high 고가 (원)
 * @property low 저가 (원)
 * @property close 종가 (원)
 * @property volume 거래량 (주)
 * @property tradingValue 거래대금 (원)
 * @property underlyingIndex 기초지수
 */
data class EtfOhlcvHistory(
    val date: String,
    val nav: Double?,
    val open: Long,
    val high: Long,
    val low: Long,
    val close: Long,
    val volume: Long,
    val tradingValue: Long,
    val underlyingIndex: Double?,
) {
    companion object {
        /**
         * KRX JSON 응답에서 EtfOhlcvHistory 객체 생성
         *
         * @param json OutBlock_1 배열의 개별 JSON 객체
         * @return EtfOhlcvHistory 또는 null (파싱 실패 시)
         */
        fun fromJson(json: JsonObject): EtfOhlcvHistory? {
            return try {
                val dateRaw = json.get("TRD_DD")?.asString ?: return null
                // KRX는 "2021/01/22" 형식으로 반환 → "20210122"로 정규화
                val date = dateRaw.replace("/", "")

                EtfOhlcvHistory(
                    date = date,
                    nav = KrxJsonParser.parseDouble(json.get("LST_NAV")?.asString),
                    open = KrxJsonParser.parseLong(json.get("TDD_OPNPRC")?.asString) ?: 0L,
                    high = KrxJsonParser.parseLong(json.get("TDD_HGPRC")?.asString) ?: 0L,
                    low = KrxJsonParser.parseLong(json.get("TDD_LWPRC")?.asString) ?: 0L,
                    close = KrxJsonParser.parseLong(json.get("TDD_CLSPRC")?.asString) ?: 0L,
                    volume = KrxJsonParser.parseLong(json.get("ACC_TRDVOL")?.asString) ?: 0L,
                    tradingValue = KrxJsonParser.parseLong(json.get("ACC_TRDVAL")?.asString) ?: 0L,
                    underlyingIndex = KrxJsonParser.parseDouble(json.get("OBJ_STKPRC_IDX")?.asString),
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
