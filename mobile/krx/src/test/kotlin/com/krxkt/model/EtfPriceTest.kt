package com.krxkt.model

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EtfPriceTest {
    @Test
    fun `fromJson should parse valid ETF response`() {
        // 실제 KRX API 응답 형식 (KODEX 200 예시)
        val json =
            """
            {
                "ISU_SRT_CD": "069500",
                "ISU_ABBRV": "KODEX 200",
                "NAV": "42,350.52",
                "TDD_OPNPRC": "42,200",
                "TDD_HGPRC": "42,400",
                "TDD_LWPRC": "42,100",
                "TDD_CLSPRC": "42,300",
                "ACC_TRDVOL": "12,345,678",
                "ACC_TRDVAL": "521,234,567,890",
                "OBJ_STKPRC_IDX": "3,012.45",
                "FLUC_RT": "0.71"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = EtfPrice.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("069500", result.ticker)
        assertEquals("KODEX 200", result.name)
        assertEquals(42350.52, result.nav!!, 0.01)
        assertEquals(42200L, result.open)
        assertEquals(42400L, result.high)
        assertEquals(42100L, result.low)
        assertEquals(42300L, result.close)
        assertEquals(12345678L, result.volume)
        assertEquals(521234567890L, result.tradingValue)
        assertEquals(3012.45, result.underlyingIndex!!, 0.01)
        assertEquals(0.71, result.changeRate!!, 0.001)
    }

    @Test
    fun `fromJson should handle inverse ETF with negative change rate`() {
        val json =
            """
            {
                "ISU_SRT_CD": "114800",
                "ISU_ABBRV": "KODEX 인버스",
                "NAV": "4,125.33",
                "TDD_OPNPRC": "4,150",
                "TDD_HGPRC": "4,180",
                "TDD_LWPRC": "4,100",
                "TDD_CLSPRC": "4,120",
                "ACC_TRDVOL": "5,678,901",
                "ACC_TRDVAL": "23,456,789,012",
                "OBJ_STKPRC_IDX": "3,012.45",
                "FLUC_RT": "-1.23"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = EtfPrice.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("114800", result.ticker)
        assertEquals(-1.23, result.changeRate!!, 0.001)
    }

    @Test
    fun `fromJson should handle missing NAV`() {
        val json =
            """
            {
                "ISU_SRT_CD": "069500",
                "ISU_ABBRV": "KODEX 200",
                "TDD_OPNPRC": "42,200",
                "TDD_HGPRC": "42,400",
                "TDD_LWPRC": "42,100",
                "TDD_CLSPRC": "42,300",
                "ACC_TRDVOL": "12,345,678",
                "ACC_TRDVAL": "521,234,567,890"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = EtfPrice.fromJson(jsonObject)

        assertNotNull(result)
        assertNull(result.nav)
        assertEquals(42300L, result.close)
    }

    @Test
    fun `fromJson should return null for missing ticker`() {
        val json =
            """
            {
                "ISU_ABBRV": "KODEX 200",
                "TDD_CLSPRC": "42,300"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = EtfPrice.fromJson(jsonObject)

        assertNull(result)
    }

    @Test
    fun `fromJson should handle empty string values`() {
        val json =
            """
            {
                "ISU_SRT_CD": "069500",
                "ISU_ABBRV": "KODEX 200",
                "NAV": "",
                "TDD_OPNPRC": "-",
                "TDD_HGPRC": "",
                "TDD_LWPRC": "",
                "TDD_CLSPRC": "0",
                "ACC_TRDVOL": "0",
                "ACC_TRDVAL": "0",
                "OBJ_STKPRC_IDX": "-",
                "FLUC_RT": ""
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = EtfPrice.fromJson(jsonObject)

        assertNotNull(result)
        assertNull(result.nav)
        assertEquals(0L, result.open)
        assertEquals(0L, result.close)
        assertNull(result.underlyingIndex)
    }
}
