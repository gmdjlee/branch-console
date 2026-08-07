package com.krxkt.model

import com.google.gson.JsonObject

/**
 * 지수 기본 정보
 *
 * KRX API 응답 필드 매핑:
 * - IDX_NM → name (지수명)
 * - IDX_IND_CD → code (지수 코드)
 * - IND_TP_CD → typeCode (지수 타입 코드: 1=KOSPI, 2=KOSDAQ, 3=파생, 4=테마)
 * - BAS_TM_CONTN → baseDate (기준일)
 *
 * 티커 구조:
 * - ticker = typeCode + code
 * - 예: "1028" = typeCode "1" + code "028" (KOSPI 200)
 *
 * @property ticker 지수 티커 (예: "1001" = KOSPI, "1028" = KOSPI 200)
 * @property code 지수 코드 (예: "001", "028")
 * @property name 지수명 (예: "코스피", "코스피 200")
 * @property typeCode 지수 타입 코드 (1: KOSPI, 2: KOSDAQ, 3: 파생, 4: 테마)
 * @property baseDate 기준일 (예: "1980.01.04")
 */
data class IndexInfo(
    val ticker: String,
    val code: String,
    val name: String,
    val typeCode: String,
    val baseDate: String?,
) {
    companion object {
        /**
         * KRX JSON 응답에서 IndexInfo 객체 생성
         *
         * @param json OutBlock_1 배열의 개별 JSON 객체
         * @return IndexInfo 또는 null (파싱 실패 시)
         */
        fun fromJson(json: JsonObject): IndexInfo? {
            return try {
                val code = json.get("IDX_IND_CD")?.asString ?: return null
                val typeCode = json.get("IND_TP_CD")?.asString ?: return null
                val name = json.get("IDX_NM")?.asString ?: return null

                // ticker = typeCode + code (예: "1" + "028" = "1028")
                val ticker = typeCode + code

                IndexInfo(
                    ticker = ticker,
                    code = code,
                    name = name,
                    typeCode = typeCode,
                    baseDate = json.get("BAS_TM_CONTN")?.asString?.takeIf { it.isNotBlank() },
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * KOSPI 계열 지수 여부
     */
    val isKospi: Boolean get() = typeCode == "1"

    /**
     * KOSDAQ 계열 지수 여부
     */
    val isKosdaq: Boolean get() = typeCode == "2"

    /**
     * 파생상품 지수 여부
     */
    val isDerivatives: Boolean get() = typeCode == "3"

    /**
     * 테마 지수 여부
     */
    val isTheme: Boolean get() = typeCode == "4"
}

/**
 * 지수 시장 타입
 */
enum class IndexMarket(
    val code: String,
    /** MDCSTAT00101 전종목 시세 조회용 코드 (idxIndMidclssCd) */
    val krxCode: String,
) {
    /** 전체 */
    ALL("", "01"),

    /** KOSPI 계열 */
    KOSPI("1", "02"),

    /** KOSDAQ 계열 */
    KOSDAQ("2", "03"),

    /** 파생상품 지수 */
    DERIVATIVES("3", "04"),

    /** 테마 지수 */
    THEME("4", "04"),
}
