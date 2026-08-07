package com.krxkt.parser

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.krxkt.error.KrxError

/**
 * KRX API JSON 응답 파서
 *
 * KRX 응답 특징:
 * - 데이터는 OutBlock_1 또는 output 배열에 포함
 * - 숫자에 쉼표 포함 ("82,200")
 * - 빈 값은 "-" 또는 ""로 표현
 */
object KrxJsonParser {
    /**
     * JSON 응답에서 데이터 배열 추출
     *
     * KRX API는 엔드포인트에 따라 다른 키를 사용:
     * - OutBlock_1: 대부분의 STAT 엔드포인트
     * - block1: 파생상품 통계 (MDCSTAT13102, MDCSTAT01201 등)
     * - output: ETF 목록 등 일부 엔드포인트
     *
     * @param json KRX API JSON 응답 문자열
     * @return JsonObject 리스트 (빈 응답 시 빈 리스트)
     * @throws KrxError.ParseError JSON 파싱 실패 시
     */
    fun parseOutBlock(json: String): List<JsonObject> {
        return try {
            val root = JsonParser.parseString(json).asJsonObject

            // OutBlock_1, block1, 또는 output 키 시도
            val outBlock =
                root.getAsJsonArray("OutBlock_1")
                    ?: root.getAsJsonArray("block1")
                    ?: root.getAsJsonArray("output")
                    ?: return emptyList()

            outBlock.mapNotNull { element ->
                if (element.isJsonObject) element.asJsonObject else null
            }
        } catch (e: Exception) {
            throw KrxError.ParseError("Failed to parse JSON response: ${e.message}", e)
        }
    }

    /**
     * 총 건수 추출 (currentDatetime과 함께 응답에 포함)
     */
    fun parseTotalCount(json: String): Int {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            root.get("totCnt")?.asInt ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * KRX 숫자 문자열을 Long으로 변환 (nullable 버전)
     *
     * @param value KRX 형식 숫자 문자열 (쉼표 포함 가능)
     * @return Long 값, null이거나 빈 값이면 null 반환
     */
    fun parseLong(value: String?): Long? {
        if (value == null) return null
        val cleaned = value.trim()
        if (cleaned.isEmpty() || cleaned == "-") return null
        return try {
            cleaned.replace(",", "").toLong()
        } catch (e: NumberFormatException) {
            null
        }
    }

    /**
     * KRX 숫자 문자열을 Double로 변환 (nullable 버전)
     *
     * @param value KRX 형식 숫자 문자열 (쉼표 포함 가능)
     * @return Double 값, null이거나 빈 값이면 null 반환
     */
    fun parseDouble(value: String?): Double? {
        if (value == null) return null
        val cleaned = value.trim()
        if (cleaned.isEmpty() || cleaned == "-") return null
        return try {
            cleaned.replace(",", "").toDouble()
        } catch (e: NumberFormatException) {
            null
        }
    }
}

/**
 * KRX 숫자 문자열을 Long으로 변환
 *
 * - 쉼표 제거: "82,200" → 82200
 * - 음수 처리: "-1,500" → -1500
 * - 빈 값 처리: "", "-" → 0
 */
fun String.parseKrxLong(): Long {
    val cleaned = this.trim()
    if (cleaned.isEmpty() || cleaned == "-") return 0L

    return try {
        cleaned.replace(",", "").toLong()
    } catch (e: NumberFormatException) {
        0L
    }
}

/**
 * KRX 숫자 문자열을 Double로 변환
 *
 * - 쉼표 제거: "1,234.56" → 1234.56
 * - 음수 처리: "-0.50" → -0.50
 * - 빈 값 처리: "", "-" → 0.0
 */
fun String.parseKrxDouble(): Double {
    val cleaned = this.trim()
    if (cleaned.isEmpty() || cleaned == "-") return 0.0

    return try {
        cleaned.replace(",", "").toDouble()
    } catch (e: NumberFormatException) {
        0.0
    }
}

/**
 * JsonObject에서 문자열 값 안전하게 추출
 *
 * @param key JSON 키
 * @return 문자열 값, 없거나 null이면 빈 문자열
 */
fun JsonObject.getStringOrEmpty(key: String): String {
    val element = this.get(key) ?: return ""
    if (element.isJsonNull) return ""
    return element.asString ?: ""
}

/**
 * JsonObject에서 Long 값 안전하게 추출
 * KRX 쉼표 형식 자동 처리
 */
fun JsonObject.getKrxLong(key: String): Long {
    return getStringOrEmpty(key).parseKrxLong()
}

/**
 * JsonObject에서 Double 값 안전하게 추출
 * KRX 쉼표 형식 자동 처리
 */
fun JsonObject.getKrxDouble(key: String): Double {
    return getStringOrEmpty(key).parseKrxDouble()
}
