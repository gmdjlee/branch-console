package com.krxkt.model

import com.google.gson.JsonObject
import com.krxkt.parser.KrxJsonParser

/**
 * 지수 구성종목 (Index Portfolio Deposit File) 데이터
 *
 * KRX API 응답 필드 매핑 (MDCSTAT00601):
 * - ISU_SRT_CD → ticker (종목코드)
 * - ISU_ABBRV → name (종목명)
 * - TDD_CLSPRC → close (종가)
 * - FLUC_TP_CD → changeType (등락구분)
 * - STR_CMP_PRC → change (전일대비)
 * - FLUC_RT → changeRate (등락률, %)
 * - MKTCAP → marketCap (시가총액)
 *
 * @property ticker 종목코드 (예: "005930")
 * @property name 종목명 (예: "삼성전자")
 * @property close 종가 (원)
 * @property changeType 등락구분 (1: 상승, 2: 하락, 3: 보합)
 * @property change 전일대비 (원)
 * @property changeRate 등락률 (%)
 * @property marketCap 시가총액 (원)
 */
data class IndexPortfolio(
    val ticker: String,
    val name: String,
    val close: Long,
    val changeType: Int?,
    val change: Long,
    val changeRate: Double?,
    val marketCap: Long,
) {
    companion object {
        fun fromJson(json: JsonObject): IndexPortfolio? {
            return try {
                val ticker =
                    json.get("ISU_SRT_CD")?.asString?.trim()
                        ?: return null
                if (ticker.isEmpty()) return null

                IndexPortfolio(
                    ticker = ticker,
                    name = json.get("ISU_ABBRV")?.asString ?: "",
                    close = KrxJsonParser.parseLong(json.get("TDD_CLSPRC")?.asString) ?: 0L,
                    changeType = json.get("FLUC_TP_CD")?.asString?.toIntOrNull(),
                    change = KrxJsonParser.parseLong(json.get("STR_CMP_PRC")?.asString) ?: 0L,
                    changeRate = KrxJsonParser.parseDouble(json.get("FLUC_RT")?.asString),
                    marketCap = KrxJsonParser.parseLong(json.get("MKTCAP")?.asString) ?: 0L,
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
