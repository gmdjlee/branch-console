package com.krxkt.model

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EtfPortfolioTest {
    @Test
    fun `fromJson should parse valid portfolio data`() {
        val json =
            """
            {
                "COMPST_ISU_CD": "KR7005930003",
                "COMPST_ISU_NM": "삼성전자",
                "COMPST_ISU_CU1_SHRS": "1,234",
                "VALU_AMT": "104,149,600",
                "COMPST_AMT": "104,149,600",
                "COMPST_RTO": "30.52"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = EtfPortfolio.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("005930", result.ticker)
        assertEquals("삼성전자", result.name)
        assertEquals(1234L, result.shares)
        assertEquals(104149600L, result.valuationAmount)
        assertEquals(104149600L, result.amount)
        assertEquals(30.52, result.weight!!, 0.001)
    }

    @Test
    fun `fromJson should normalize ISIN ticker to 6 digits`() {
        val json =
            """
            {
                "COMPST_ISU_CD": "KR7005930003",
                "COMPST_ISU_NM": "삼성전자",
                "COMPST_ISU_CU1_SHRS": "100",
                "VALU_AMT": "8,440,000",
                "COMPST_AMT": "8,440,000",
                "COMPST_RTO": "10.00"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = EtfPortfolio.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("005930", result.ticker)
    }

    @Test
    fun `fromJson should keep short ticker as-is`() {
        val json =
            """
            {
                "COMPST_ISU_CD": "005930",
                "COMPST_ISU_NM": "삼성전자",
                "COMPST_ISU_CU1_SHRS": "100",
                "VALU_AMT": "8,440,000",
                "COMPST_AMT": "8,440,000",
                "COMPST_RTO": "5.00"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = EtfPortfolio.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("005930", result.ticker)
    }

    @Test
    fun `fromJson should handle empty values`() {
        val json =
            """
            {
                "COMPST_ISU_CD": "KR7005930003",
                "COMPST_ISU_NM": "삼성전자",
                "COMPST_ISU_CU1_SHRS": "",
                "VALU_AMT": "",
                "COMPST_AMT": "",
                "COMPST_RTO": ""
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = EtfPortfolio.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("005930", result.ticker)
        assertEquals(0L, result.shares)
        assertEquals(0L, result.valuationAmount)
        assertEquals(0L, result.amount)
        assertNull(result.weight)
    }

    @Test
    fun `fromJson should handle dash values`() {
        val json =
            """
            {
                "COMPST_ISU_CD": "KR7005930003",
                "COMPST_ISU_NM": "삼성전자",
                "COMPST_ISU_CU1_SHRS": "-",
                "VALU_AMT": "-",
                "COMPST_AMT": "-",
                "COMPST_RTO": "-"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = EtfPortfolio.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals(0L, result.shares)
        assertNull(result.weight)
    }

    @Test
    fun `fromJson should return null for missing ticker`() {
        val json =
            """
            {
                "COMPST_ISU_NM": "삼성전자",
                "COMPST_ISU_CU1_SHRS": "100"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = EtfPortfolio.fromJson(jsonObject)

        assertNull(result)
    }

    @Test
    fun `fromJson should handle missing optional fields`() {
        val json =
            """
            {
                "COMPST_ISU_CD": "KR7005930003",
                "COMPST_ISU_NM": "삼성전자"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = EtfPortfolio.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("005930", result.ticker)
        assertEquals("삼성전자", result.name)
        assertEquals(0L, result.shares)
        assertEquals(0L, result.valuationAmount)
        assertEquals(0L, result.amount)
        assertNull(result.weight)
    }

    @Test
    fun `fromJson should handle empty name`() {
        val json =
            """
            {
                "COMPST_ISU_CD": "KR7005930003",
                "COMPST_ISU_NM": "",
                "COMPST_ISU_CU1_SHRS": "50",
                "VALU_AMT": "4,220,000",
                "COMPST_AMT": "4,220,000",
                "COMPST_RTO": "2.50"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = EtfPortfolio.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("", result.name)
    }

    @Test
    fun `fromJson should handle large values`() {
        val json =
            """
            {
                "COMPST_ISU_CD": "KR7005930003",
                "COMPST_ISU_NM": "삼성전자",
                "COMPST_ISU_CU1_SHRS": "999,999,999",
                "VALU_AMT": "999,999,999,999",
                "COMPST_AMT": "999,999,999,999",
                "COMPST_RTO": "99.99"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = EtfPortfolio.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals(999999999L, result.shares)
        assertEquals(999999999999L, result.valuationAmount)
        assertEquals(99.99, result.weight!!, 0.001)
    }
}
