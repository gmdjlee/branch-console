package com.krxkt.model

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IndexPortfolioTest {
    @Test
    fun `fromJson should parse valid index portfolio response`() {
        val json =
            """
            {
                "ISU_SRT_CD": "005930",
                "ISU_ABBRV": "삼성전자",
                "TDD_CLSPRC": "84,400",
                "FLUC_TP_CD": "2",
                "STR_CMP_PRC": "-2,200",
                "FLUC_RT": "-2.54",
                "MKTCAP": "503,825,840,000,000"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = IndexPortfolio.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("005930", result.ticker)
        assertEquals("삼성전자", result.name)
        assertEquals(84400L, result.close)
        assertEquals(2, result.changeType)
        assertEquals(-2200L, result.change)
        assertEquals(-2.54, result.changeRate!!, 0.01)
        assertEquals(503825840000000L, result.marketCap)
    }

    @Test
    fun `fromJson should return null for empty ticker`() {
        val json =
            """
            {
                "ISU_SRT_CD": "",
                "ISU_ABBRV": "빈종목",
                "TDD_CLSPRC": "0"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = IndexPortfolio.fromJson(jsonObject)

        assertNull(result)
    }

    @Test
    fun `fromJson should return null for missing ticker`() {
        val json =
            """
            {
                "ISU_ABBRV": "이름만있음",
                "TDD_CLSPRC": "100"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = IndexPortfolio.fromJson(jsonObject)

        assertNull(result)
    }

    @Test
    fun `fromJson should handle missing optional fields`() {
        val json =
            """
            {
                "ISU_SRT_CD": "005930",
                "ISU_ABBRV": "삼성전자",
                "TDD_CLSPRC": "84,400",
                "STR_CMP_PRC": "0",
                "MKTCAP": "100"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = IndexPortfolio.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("005930", result.ticker)
        assertNull(result.changeType)
        assertNull(result.changeRate)
    }

    @Test
    fun `fromJson should handle dash and empty numeric values`() {
        val json =
            """
            {
                "ISU_SRT_CD": "005930",
                "ISU_ABBRV": "삼성전자",
                "TDD_CLSPRC": "-",
                "STR_CMP_PRC": "",
                "FLUC_RT": "-",
                "MKTCAP": ""
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = IndexPortfolio.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals(0L, result.close)
        assertEquals(0L, result.change)
        assertNull(result.changeRate)
        assertEquals(0L, result.marketCap)
    }
}
