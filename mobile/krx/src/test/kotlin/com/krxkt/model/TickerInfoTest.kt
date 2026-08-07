package com.krxkt.model

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TickerInfoTest {
    @Test
    fun `fromJson should parse valid KRX response`() {
        val json =
            """
            {
                "ISU_SRT_CD": "005930",
                "ISU_ABBRV": "삼성전자",
                "MKT_TP_NM": "KOSPI",
                "ISU_CD": "KR7005930003"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = TickerInfo.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("005930", result.ticker)
        assertEquals("삼성전자", result.name)
        assertEquals("KOSPI", result.marketName)
        assertEquals("KR7005930003", result.isinCode)
    }

    @Test
    fun `fromJson should handle KOSDAQ stocks`() {
        val json =
            """
            {
                "ISU_SRT_CD": "035720",
                "ISU_ABBRV": "카카오",
                "MKT_TP_NM": "KOSDAQ",
                "ISU_CD": "KR7035720002"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = TickerInfo.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("KOSDAQ", result.marketName)
    }

    @Test
    fun `fromJson should return null for empty ticker`() {
        val json =
            """
            {
                "ISU_SRT_CD": "",
                "ISU_ABBRV": "테스트"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = TickerInfo.fromJson(jsonObject)

        assertNull(result)
    }
}
