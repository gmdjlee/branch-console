package com.krxkt.model

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndexInfoTest {
    @Test
    fun `fromJson should parse KOSPI index info`() {
        val json =
            """
            {
                "IDX_IND_CD": "001",
                "IDX_NM": "코스피",
                "IND_TP_CD": "1",
                "BAS_TM_CONTN": "1980.01.04"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = IndexInfo.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("1001", result.ticker) // typeCode + code
        assertEquals("001", result.code)
        assertEquals("코스피", result.name)
        assertEquals("1", result.typeCode)
        assertEquals("1980.01.04", result.baseDate)
        assertTrue(result.isKospi)
        assertFalse(result.isKosdaq)
    }

    @Test
    fun `fromJson should parse KOSPI 200 index info`() {
        val json =
            """
            {
                "IDX_IND_CD": "028",
                "IDX_NM": "코스피 200",
                "IND_TP_CD": "1",
                "BAS_TM_CONTN": "1990.01.03"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = IndexInfo.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("1028", result.ticker)
        assertEquals("028", result.code)
        assertEquals("코스피 200", result.name)
        assertTrue(result.isKospi)
    }

    @Test
    fun `fromJson should parse KOSDAQ index info`() {
        val json =
            """
            {
                "IDX_IND_CD": "001",
                "IDX_NM": "코스닥",
                "IND_TP_CD": "2",
                "BAS_TM_CONTN": "1996.07.01"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = IndexInfo.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("2001", result.ticker)
        assertEquals("001", result.code)
        assertEquals("코스닥", result.name)
        assertEquals("2", result.typeCode)
        assertTrue(result.isKosdaq)
        assertFalse(result.isKospi)
    }

    @Test
    fun `fromJson should parse KOSDAQ 150 index info`() {
        val json =
            """
            {
                "IDX_IND_CD": "203",
                "IDX_NM": "코스닥 150",
                "IND_TP_CD": "2",
                "BAS_TM_CONTN": "2010.01.04"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = IndexInfo.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("2203", result.ticker)
        assertEquals("코스닥 150", result.name)
        assertTrue(result.isKosdaq)
    }

    @Test
    fun `fromJson should parse derivatives index info`() {
        val json =
            """
            {
                "IDX_IND_CD": "001",
                "IDX_NM": "KOSPI200 선물",
                "IND_TP_CD": "3",
                "BAS_TM_CONTN": ""
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = IndexInfo.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("3001", result.ticker)
        assertTrue(result.isDerivatives)
    }

    @Test
    fun `fromJson should parse theme index info`() {
        val json =
            """
            {
                "IDX_IND_CD": "001",
                "IDX_NM": "KRX 반도체",
                "IND_TP_CD": "4",
                "BAS_TM_CONTN": "2015.01.02"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = IndexInfo.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("4001", result.ticker)
        assertTrue(result.isTheme)
    }

    @Test
    fun `fromJson should return null for missing code`() {
        val json =
            """
            {
                "IDX_NM": "코스피",
                "IND_TP_CD": "1"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = IndexInfo.fromJson(jsonObject)

        assertNull(result)
    }

    @Test
    fun `fromJson should return null for missing type code`() {
        val json =
            """
            {
                "IDX_IND_CD": "001",
                "IDX_NM": "코스피"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = IndexInfo.fromJson(jsonObject)

        assertNull(result)
    }

    @Test
    fun `fromJson should return null for missing name`() {
        val json =
            """
            {
                "IDX_IND_CD": "001",
                "IND_TP_CD": "1"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = IndexInfo.fromJson(jsonObject)

        assertNull(result)
    }

    @Test
    fun `fromJson should handle empty base date`() {
        val json =
            """
            {
                "IDX_IND_CD": "001",
                "IDX_NM": "테스트 지수",
                "IND_TP_CD": "1",
                "BAS_TM_CONTN": ""
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = IndexInfo.fromJson(jsonObject)

        assertNotNull(result)
        assertNull(result.baseDate)
    }
}
