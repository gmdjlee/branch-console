package com.krxkt.model

import com.google.gson.JsonObject
import com.krxkt.parser.KrxJsonParser
import com.krxkt.parser.getStringOrEmpty

/**
 * 지수 PER/PBR/배당수익률 데이터
 *
 * KRX API 응답 필드 매핑 (MDCSTAT00702):
 * - TRD_DD: 거래일 (yyyy/MM/dd → yyyyMMdd)
 * - CLSPRC_IDX: 종가지수
 * - WT_PER: 가중 PER (당일은 "-")
 * - WT_STKPRC_NETASST_RTO: PBR (가중 주가순자산비율)
 * - DIV_YD: 배당수익률 (%)
 *
 * @property date 거래일 (yyyyMMdd)
 * @property close 종가지수
 * @property per 가중 PER
 * @property forwardPer 12개월 선행 PER (FWD_PER, 없으면 0.0)
 * @property pbr PBR (가중 주가순자산비율)
 * @property dividendYield 배당수익률 (%)
 */
data class IndexFundamentalHistory(
    val date: String,
    val close: Double,
    val per: Double,
    val forwardPer: Double = 0.0,
    val pbr: Double,
    val dividendYield: Double,
) {
    companion object {
        /**
         * KRX JSON 응답에서 IndexFundamentalHistory 객체 생성
         *
         * @param json OutBlock_1 배열의 개별 JSON 객체
         * @return IndexFundamentalHistory 또는 null (파싱 실패 시)
         */
        fun fromJson(json: JsonObject): IndexFundamentalHistory? {
            return try {
                val dateRaw = json.getStringOrEmpty("TRD_DD")
                if (dateRaw.isEmpty()) return null
                val date = dateRaw.replace("/", "")

                val close = KrxJsonParser.parseDouble(json.get("CLSPRC_IDX")?.asString) ?: return null
                // WT_PER: 가중 PER (당일 미확정 시 "-" → null → 0.0)
                val per = KrxJsonParser.parseDouble(json.get("WT_PER")?.asString) ?: 0.0
                // FWD_PER: 12개월 선행 PER (필드 없거나 "-" → 0.0)
                val forwardPer = KrxJsonParser.parseDouble(json.get("FWD_PER")?.asString) ?: 0.0
                val pbr = KrxJsonParser.parseDouble(json.get("WT_STKPRC_NETASST_RTO")?.asString) ?: 0.0
                val dividendYield = KrxJsonParser.parseDouble(json.get("DIV_YD")?.asString) ?: 0.0

                IndexFundamentalHistory(
                    date = date,
                    close = close,
                    per = per,
                    forwardPer = forwardPer,
                    pbr = pbr,
                    dividendYield = dividendYield,
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
