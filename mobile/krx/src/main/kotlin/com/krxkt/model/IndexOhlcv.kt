package com.krxkt.model

import com.google.gson.JsonObject
import com.krxkt.parser.KrxJsonParser

/**
 * 지수 OHLCV 데이터
 *
 * KRX API 응답 필드 매핑:
 * - TRD_DD → date (거래일)
 * - OPNPRC_IDX → open (시가지수)
 * - HGPRC_IDX → high (고가지수)
 * - LWPRC_IDX → low (저가지수)
 * - CLSPRC_IDX → close (종가지수)
 * - ACC_TRDVOL → volume (누적거래량)
 * - ACC_TRDVAL → tradingValue (누적거래대금, **원 KRW** — MT1-01g 벤더링 시 정정. upstream KDoc은
 *   "백만원"이라 적었으나 2026-08-05 실측(KOSPI 25,657,753,879,758)이 원 단위와만 정합한다
 *   (branch-console docs/journal/2026-08-07_MT1-00c_kotlin_krx.md §3.2))
 * - FLUC_TP_CD → changeType (1: 상승, 2: 하락, 3: 보합)
 * - PRV_DD_CMPR → change (전일대비)
 *
 * @property date 거래일 (yyyyMMdd)
 * @property open 시가지수
 * @property high 고가지수
 * @property low 저가지수
 * @property close 종가지수
 * @property volume 거래량 (주)
 * @property tradingValue 거래대금 (원 KRW — 실측 정정, MDCSTAT00301 한정. IndexOhlcvByTicker의
 *   MKTCAP/tradingValue 단위는 별도 엔드포인트(MDCSTAT00101)라 이번 실측 범위 밖, 미변경)
 * @property changeType 등락구분 (1: 상승, 2: 하락, 3: 보합)
 * @property change 전일대비 포인트
 */
data class IndexOhlcv(
    val date: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
    val tradingValue: Long,
    val changeType: Int?,
    val change: Double?,
) {
    companion object {
        /**
         * KRX JSON 응답에서 IndexOhlcv 객체 생성
         *
         * @param json OutBlock_1 배열의 개별 JSON 객체
         * @return IndexOhlcv 또는 null (파싱 실패 시)
         */
        fun fromJson(json: JsonObject): IndexOhlcv? {
            return try {
                val dateRaw = json.get("TRD_DD")?.asString ?: return null
                // KRX는 "2021/01/22" 형식으로 반환 → "20210122"로 정규화
                val date = dateRaw.replace("/", "")

                IndexOhlcv(
                    date = date,
                    open = KrxJsonParser.parseDouble(json.get("OPNPRC_IDX")?.asString) ?: 0.0,
                    high = KrxJsonParser.parseDouble(json.get("HGPRC_IDX")?.asString) ?: 0.0,
                    low = KrxJsonParser.parseDouble(json.get("LWPRC_IDX")?.asString) ?: 0.0,
                    close = KrxJsonParser.parseDouble(json.get("CLSPRC_IDX")?.asString) ?: 0.0,
                    volume = KrxJsonParser.parseLong(json.get("ACC_TRDVOL")?.asString) ?: 0L,
                    tradingValue = KrxJsonParser.parseLong(json.get("ACC_TRDVAL")?.asString) ?: 0L,
                    changeType = json.get("FLUC_TP_CD")?.asString?.toIntOrNull(),
                    change = KrxJsonParser.parseDouble(json.get("PRV_DD_CMPR")?.asString),
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * 상승 여부
     */
    val isUp: Boolean get() = changeType == 1

    /**
     * 하락 여부
     */
    val isDown: Boolean get() = changeType == 2

    /**
     * 보합 여부
     */
    val isUnchanged: Boolean get() = changeType == 3
}
