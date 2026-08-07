package com.krxkt.model

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StockFundamentalHistoryTest {
    @Test
    fun `fromJson should parse valid KRX response with slash date format`() {
        val json =
            """
            {
                "TRD_DD": "2021/01/22",
                "TDD_CLSPRC": "84,400",
                "EPS": "3,166",
                "PER": "26.67",
                "BPS": "39,406",
                "PBR": "2.14",
                "DPS": "1,416",
                "DVD_YLD": "1.68"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = StockFundamentalHistory.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("20210122", result.date)
        assertEquals(84400L, result.close)
        assertEquals(3166L, result.eps)
        assertEquals(26.67, result.per, 0.001)
        assertEquals(39406L, result.bps)
        assertEquals(2.14, result.pbr, 0.001)
        assertEquals(1416L, result.dps)
        assertEquals(1.68, result.dividendYield, 0.001)
    }

    @Test
    fun `fromJson should handle yyyyMMdd date format`() {
        val json =
            """
            {
                "TRD_DD": "20210122",
                "TDD_CLSPRC": "84,400",
                "EPS": "3,166",
                "PER": "26.67",
                "BPS": "39,406",
                "PBR": "2.14",
                "DPS": "1,416",
                "DVD_YLD": "1.68"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = StockFundamentalHistory.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("20210122", result.date)
    }

    @Test
    fun `fromJson should return null for missing date`() {
        val json =
            """
            {
                "TDD_CLSPRC": "10000"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = StockFundamentalHistory.fromJson(jsonObject)

        assertNull(result)
    }

    @Test
    fun `fromJson should return null for empty date`() {
        val json =
            """
            {
                "TRD_DD": "",
                "TDD_CLSPRC": "10000"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = StockFundamentalHistory.fromJson(jsonObject)

        assertNull(result)
    }

    @Test
    fun `fromJson should handle missing numeric fields with defaults`() {
        val json =
            """
            {
                "TRD_DD": "2021/01/22",
                "TDD_CLSPRC": "84,400"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = StockFundamentalHistory.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals(84400L, result.close)
        assertEquals(0L, result.eps)
        assertEquals(0.0, result.per, 0.001)
        assertEquals(0L, result.bps)
        assertEquals(0.0, result.pbr, 0.001)
        assertEquals(0L, result.dps)
        assertEquals(0.0, result.dividendYield, 0.001)
    }
}
