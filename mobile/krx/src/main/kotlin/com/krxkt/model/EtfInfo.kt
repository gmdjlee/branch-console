package com.krxkt.model

import com.google.gson.JsonObject

/**
 * ETF 종목 기본 정보
 *
 * KRX API 응답 필드 매핑:
 * - ISU_SRT_CD → ticker (종목코드)
 * - ISU_ABBRV → name (종목약명)
 * - ISU_CD → isinCode (ISIN 코드)
 * - IDX_IND_NM → indexName (기초지수명)
 * - TAR_IDX_NM → targetIndexName (추적지수명)
 * - IDX_CALC_INST_NM1 → indexProvider (지수산출기관)
 * - CU → cu (설정단위, CU)
 * - TOT_FEE → totalFee (총보수율)
 *
 * @property ticker 종목코드 (예: "069500")
 * @property name 종목명 (예: "KODEX 200")
 * @property isinCode ISIN 코드 (예: "KR7069500007")
 * @property indexName 기초지수명
 * @property targetIndexName 추적지수명
 * @property indexProvider 지수산출기관
 * @property cu 설정단위 (Creation Unit)
 * @property totalFee 총보수율 (%)
 */
data class EtfInfo(
    val ticker: String,
    val name: String,
    val isinCode: String,
    val indexName: String?,
    val targetIndexName: String?,
    val indexProvider: String?,
    val cu: Long?,
    val totalFee: Double?,
) {
    companion object {
        /**
         * KRX JSON 응답에서 EtfInfo 객체 생성
         *
         * @param json OutBlock_1 배열의 개별 JSON 객체
         * @return EtfInfo 또는 null (파싱 실패 시)
         */
        fun fromJson(json: JsonObject): EtfInfo? {
            return try {
                val ticker = json.get("ISU_SRT_CD")?.asString ?: return null
                val isinCode = json.get("ISU_CD")?.asString ?: return null

                EtfInfo(
                    ticker = ticker,
                    name = json.get("ISU_ABBRV")?.asString ?: "",
                    isinCode = isinCode,
                    indexName = json.get("IDX_IND_NM")?.asString?.takeIf { it.isNotBlank() },
                    targetIndexName = json.get("TAR_IDX_NM")?.asString?.takeIf { it.isNotBlank() },
                    indexProvider = json.get("IDX_CALC_INST_NM1")?.asString?.takeIf { it.isNotBlank() },
                    cu = json.get("CU")?.asString?.replace(",", "")?.toLongOrNull(),
                    totalFee = json.get("TOT_FEE")?.asString?.toDoubleOrNull(),
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
