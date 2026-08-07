package com.krxkt.model

import com.google.gson.JsonObject
import com.krxkt.parser.KrxJsonParser

/**
 * ETF 구성종목 (Portfolio Deposit File) 데이터
 *
 * KRX API 응답 필드 매핑 (MDCSTAT05001):
 * - COMPST_ISU_CD → ticker (구성종목 코드, ISIN 또는 축약형)
 * - COMPST_ISU_NM → name (구성종목명)
 * - COMPST_ISU_CU1_SHRS → shares (주식수/계약수)
 * - VALU_AMT → valuationAmount (평가금액)
 * - COMPST_AMT → amount (시가총액)
 * - COMPST_RTO → weight (비중, %)
 *
 * @property ticker 구성종목 코드 (6자리로 정규화됨)
 * @property name 구성종목명 (예: "삼성전자")
 * @property shares 주식수/계약수
 * @property valuationAmount 평가금액 (원)
 * @property amount 구성금액 (원)
 * @property weight 구성비중 (%)
 */
data class EtfPortfolio(
    val ticker: String,
    val name: String,
    val shares: Long,
    val valuationAmount: Long,
    val amount: Long,
    val weight: Double?,
) {
    companion object {
        /**
         * KRX JSON 응답에서 EtfPortfolio 객체 생성
         *
         * @param json OutBlock_1 배열의 개별 JSON 객체
         * @return EtfPortfolio 또는 null (파싱 실패 시)
         */
        fun fromJson(json: JsonObject): EtfPortfolio? {
            return try {
                val rawTicker = json.get("COMPST_ISU_CD")?.asString ?: return null
                val name = json.get("COMPST_ISU_NM")?.asString ?: ""

                // 티커 정규화: ISIN(12자리) 또는 축약형에서 6자리 티커 추출
                // 예: "KR7005930003" → "005930", "005930" → "005930"
                val ticker = normalizeTicker(rawTicker)

                EtfPortfolio(
                    ticker = ticker,
                    name = name,
                    shares = KrxJsonParser.parseLong(json.get("COMPST_ISU_CU1_SHRS")?.asString) ?: 0L,
                    valuationAmount = KrxJsonParser.parseLong(json.get("VALU_AMT")?.asString) ?: 0L,
                    amount = KrxJsonParser.parseLong(json.get("COMPST_AMT")?.asString) ?: 0L,
                    weight = KrxJsonParser.parseDouble(json.get("COMPST_RTO")?.asString),
                )
            } catch (e: Exception) {
                null
            }
        }

        /**
         * 티커 정규화
         * - ISIN 코드(12자리): "KR7005930003" → "005930"
         * - 축약형(6자리 이상): 앞에서 6자리 추출
         */
        private fun normalizeTicker(raw: String): String {
            return when {
                // ISIN 형식: KR7XXXXXX000
                raw.startsWith("KR") && raw.length == 12 -> raw.substring(3, 9)
                // 이미 6자리 이하면 그대로
                raw.length <= 6 -> raw
                // 그 외: 앞 6자리 추출
                else -> raw.take(6)
            }
        }
    }
}
