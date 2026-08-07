package com.krxkt.model

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EtfInfoTest {
    @Test
    fun `fromJson should parse valid ETF info response`() {
        val json =
            """
            {
                "ISU_SRT_CD": "069500",
                "ISU_ABBRV": "KODEX 200",
                "ISU_CD": "KR7069500007",
                "IDX_IND_NM": "코스피 200",
                "TAR_IDX_NM": "KOSPI 200 지수",
                "IDX_CALC_INST_NM1": "한국거래소",
                "CU": "50,000",
                "TOT_FEE": "0.15"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = EtfInfo.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("069500", result.ticker)
        assertEquals("KODEX 200", result.name)
        assertEquals("KR7069500007", result.isinCode)
        assertEquals("코스피 200", result.indexName)
        assertEquals("KOSPI 200 지수", result.targetIndexName)
        assertEquals("한국거래소", result.indexProvider)
        assertEquals(50000L, result.cu)
        assertEquals(0.15, result.totalFee!!, 0.001)
    }

    @Test
    fun `fromJson should handle missing optional fields`() {
        val json =
            """
            {
                "ISU_SRT_CD": "069500",
                "ISU_ABBRV": "KODEX 200",
                "ISU_CD": "KR7069500007"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = EtfInfo.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("069500", result.ticker)
        assertEquals("KR7069500007", result.isinCode)
        assertNull(result.indexName)
        assertNull(result.targetIndexName)
        assertNull(result.cu)
        assertNull(result.totalFee)
    }

    @Test
    fun `fromJson should return null for missing ticker`() {
        val json =
            """
            {
                "ISU_ABBRV": "KODEX 200",
                "ISU_CD": "KR7069500007"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = EtfInfo.fromJson(jsonObject)

        assertNull(result)
    }

    @Test
    fun `fromJson should return null for missing ISIN`() {
        val json =
            """
            {
                "ISU_SRT_CD": "069500",
                "ISU_ABBRV": "KODEX 200"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = EtfInfo.fromJson(jsonObject)

        assertNull(result)
    }

    @Test
    fun `fromJson should handle empty string fields as null`() {
        val json =
            """
            {
                "ISU_SRT_CD": "069500",
                "ISU_ABBRV": "KODEX 200",
                "ISU_CD": "KR7069500007",
                "IDX_IND_NM": "",
                "TAR_IDX_NM": "   ",
                "IDX_CALC_INST_NM1": ""
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = EtfInfo.fromJson(jsonObject)

        assertNotNull(result)
        assertNull(result.indexName)
        assertNull(result.targetIndexName)
        assertNull(result.indexProvider)
    }

    @Test
    fun `fromJson should parse leveraged ETF`() {
        val json =
            """
            {
                "ISU_SRT_CD": "122630",
                "ISU_ABBRV": "KODEX 레버리지",
                "ISU_CD": "KR7122630007",
                "IDX_IND_NM": "코스피 200",
                "TAR_IDX_NM": "KOSPI 200 선물지수 (2X)",
                "IDX_CALC_INST_NM1": "한국거래소",
                "CU": "10,000",
                "TOT_FEE": "0.64"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = EtfInfo.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("122630", result.ticker)
        assertEquals("KODEX 레버리지", result.name)
        assertEquals(10000L, result.cu)
        assertEquals(0.64, result.totalFee!!, 0.001)
    }
}
