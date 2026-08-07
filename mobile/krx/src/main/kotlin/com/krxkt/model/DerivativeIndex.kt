package com.krxkt.model

import com.google.gson.JsonObject
import com.krxkt.parser.KrxJsonParser

/**
 * 파생상품 지수 데이터
 *
 * KRX API MDCSTAT01201 응답 필드 매핑:
 * - TRD_DD → date (거래일)
 * - CLSPRC_IDX → close (종가지수)
 *
 * feargreed.py에서 사용하는 파생 지수:
 * - VKOSPI (변동성 지수): indTpCd="1", idxIndCd="300"
 * - 5년국채: indTpCd="D", idxIndCd="896"
 * - 10년국채: indTpCd="1", idxIndCd="309"
 *
 * @property date 거래일 (yyyyMMdd)
 * @property close 종가지수
 */
data class DerivativeIndex(
    val date: String,
    val close: Double,
) {
    companion object {
        /**
         * KRX JSON 응답에서 DerivativeIndex 객체 생성
         *
         * @param json block1 배열의 개별 JSON 객체
         * @return DerivativeIndex 또는 null (파싱 실패 시)
         */
        fun fromJson(json: JsonObject): DerivativeIndex? {
            return try {
                val dateRaw = json.get("TRD_DD")?.asString ?: return null
                val date = dateRaw.replace("/", "")

                DerivativeIndex(
                    date = date,
                    close = KrxJsonParser.parseDouble(json.get("CLSPRC_IDX")?.asString) ?: return null,
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
