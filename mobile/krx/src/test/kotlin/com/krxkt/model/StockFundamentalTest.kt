package com.krxkt.model

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StockFundamentalTest {
    @Test
    fun `fromJson should parse valid KRX response`() {
        val json =
            """
            {
                "ISU_SRT_CD": "005930",
                "ISU_ABBRV": "삼성전자",
                "TDD_CLSPRC": "84,400",
                "EPS": "3,841",
                "PER": "21.97",
                "BPS": "43,612",
                "PBR": "1.94",
                "DPS": "1,416",
                "DVD_YLD": "1.68"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = StockFundamental.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("005930", result.ticker)
        assertEquals("삼성전자", result.name)
        assertEquals(84400L, result.close)
        assertEquals(3841L, result.eps)
        assertEquals(21.97, result.per, 0.001)
        assertEquals(43612L, result.bps)
        assertEquals(1.94, result.pbr, 0.001)
        assertEquals(1416L, result.dps)
        assertEquals(1.68, result.dividendYield, 0.001)
    }

    @Test
    fun `fromJson should handle zero and empty values`() {
        val json =
            """
            {
                "ISU_SRT_CD": "123456",
                "ISU_ABBRV": "테스트",
                "TDD_CLSPRC": "0",
                "EPS": "-",
                "PER": "",
                "BPS": "0",
                "PBR": "-",
                "DPS": "0",
                "DVD_YLD": "0.00"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = StockFundamental.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals(0L, result.eps)
        assertEquals(0.0, result.per, 0.001)
        assertEquals(0.0, result.pbr, 0.001)
    }

    @Test
    fun `fromJson should return null for missing ticker`() {
        val json =
            """
            {
                "ISU_ABBRV": "테스트",
                "TDD_CLSPRC": "10000"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = StockFundamental.fromJson(jsonObject)

        assertNull(result)
    }
}
