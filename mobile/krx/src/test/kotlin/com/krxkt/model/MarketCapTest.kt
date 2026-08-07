package com.krxkt.model

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MarketCapTest {
    @Test
    fun `fromJson should parse valid KRX response`() {
        val json =
            """
            {
                "ISU_SRT_CD": "005930",
                "ISU_ABBRV": "삼성전자",
                "TDD_CLSPRC": "84,400",
                "FLUC_RT": "-2.54",
                "MKTCAP": "503,825,840,000,000",
                "LIST_SHRS": "5,969,782,550"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = MarketCap.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("005930", result.ticker)
        assertEquals("삼성전자", result.name)
        assertEquals(84400L, result.close)
        assertEquals(-2.54, result.changeRate, 0.001)
        assertEquals(503825840000000L, result.marketCap)
        assertEquals(5969782550L, result.sharesOutstanding)
    }

    @Test
    fun `fromJson should handle zero values`() {
        val json =
            """
            {
                "ISU_SRT_CD": "123456",
                "ISU_ABBRV": "테스트종목",
                "TDD_CLSPRC": "0",
                "FLUC_RT": "0.00",
                "MKTCAP": "0",
                "LIST_SHRS": "0"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = MarketCap.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals(0L, result.close)
        assertEquals(0.0, result.changeRate, 0.001)
        assertEquals(0L, result.marketCap)
        assertEquals(0L, result.sharesOutstanding)
    }

    @Test
    fun `fromJson should handle empty and dash values as zero`() {
        val json =
            """
            {
                "ISU_SRT_CD": "123456",
                "ISU_ABBRV": "테스트종목",
                "TDD_CLSPRC": "",
                "FLUC_RT": "-",
                "MKTCAP": "",
                "LIST_SHRS": "-"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = MarketCap.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals(0L, result.close)
        assertEquals(0.0, result.changeRate, 0.001)
        assertEquals(0L, result.marketCap)
        assertEquals(0L, result.sharesOutstanding)
    }

    @Test
    fun `fromJson should handle missing fields with defaults`() {
        val json =
            """
            {
                "ISU_SRT_CD": "005930",
                "ISU_ABBRV": "삼성전자"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = MarketCap.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("005930", result.ticker)
        assertEquals("삼성전자", result.name)
        assertEquals(0L, result.close)
        assertEquals(0.0, result.changeRate, 0.001)
        assertEquals(0L, result.marketCap)
        assertEquals(0L, result.sharesOutstanding)
    }

    @Test
    fun `fromJson should return null for missing ticker`() {
        val json =
            """
            {
                "ISU_ABBRV": "테스트종목",
                "TDD_CLSPRC": "10000"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = MarketCap.fromJson(jsonObject)

        assertNull(result)
    }

    @Test
    fun `fromJson should return null for empty ticker`() {
        val json =
            """
            {
                "ISU_SRT_CD": "",
                "ISU_ABBRV": "테스트종목",
                "TDD_CLSPRC": "10000"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = MarketCap.fromJson(jsonObject)

        assertNull(result)
    }

    @Test
    fun `fromJson should handle large market cap values`() {
        val json =
            """
            {
                "ISU_SRT_CD": "005930",
                "ISU_ABBRV": "삼성전자",
                "TDD_CLSPRC": "84,400",
                "FLUC_RT": "1.23",
                "MKTCAP": "999,999,999,999,999",
                "LIST_SHRS": "9,999,999,999"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = MarketCap.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals(999999999999999L, result.marketCap)
        assertEquals(9999999999L, result.sharesOutstanding)
    }
}
