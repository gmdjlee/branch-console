package com.krxkt.model

import com.google.gson.JsonObject
import com.krxkt.parser.KrxJsonParser

/**
 * 옵션 거래량 데이터
 *
 * KRX API MDCSTAT13102 응답 필드 매핑:
 * - TRD_DD → date (거래일)
 * - AMT_OR_QTY → totalVolume (전체 거래량)
 *
 * feargreed.py의 get_option() 응답과 동일:
 * ```python
 * df = df.rename(columns={"TRD_DD": "거래일", "AMT_OR_QTY": "전체"})
 * ```
 *
 * @property date 거래일 (yyyyMMdd)
 * @property totalVolume 전체 거래량 (주)
 */
data class OptionVolume(
    val date: String,
    val totalVolume: Long,
) {
    companion object {
        /**
         * KRX JSON 응답에서 OptionVolume 객체 생성
         *
         * @param json block1 배열의 개별 JSON 객체
         * @return OptionVolume 또는 null (파싱 실패 시)
         */
        fun fromJson(json: JsonObject): OptionVolume? {
            return try {
                val dateRaw = json.get("TRD_DD")?.asString ?: return null
                val date = dateRaw.replace("/", "")

                OptionVolume(
                    date = date,
                    totalVolume = KrxJsonParser.parseLong(json.get("AMT_OR_QTY")?.asString) ?: 0L,
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
