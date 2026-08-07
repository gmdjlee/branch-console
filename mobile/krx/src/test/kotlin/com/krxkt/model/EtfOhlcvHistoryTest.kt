package com.krxkt.model

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EtfOhlcvHistoryTest {
    @Test
    fun `fromJson should parse valid ETF history response`() {
        // 실제 KRX API 응답 형식
        val json =
            """
            {
                "TRD_DD": "2021/01/22",
                "LST_NAV": "42,150.33",
                "TDD_OPNPRC": "42,000",
                "TDD_HGPRC": "42,250",
                "TDD_LWPRC": "41,950",
                "TDD_CLSPRC": "42,100",
                "ACC_TRDVOL": "8,234,567",
                "ACC_TRDVAL": "346,789,012,345",
                "OBJ_STKPRC_IDX": "2,987.34"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = EtfOhlcvHistory.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("20210122", result.date) // slash 제거됨
        assertEquals(42150.33, result.nav!!, 0.01)
        assertEquals(42000L, result.open)
        assertEquals(42250L, result.high)
        assertEquals(41950L, result.low)
        assertEquals(42100L, result.close)
        assertEquals(8234567L, result.volume)
        assertEquals(346789012345L, result.tradingValue)
        assertEquals(2987.34, result.underlyingIndex!!, 0.01)
    }

    @Test
    fun `fromJson should normalize date format`() {
        val json =
            """
            {
                "TRD_DD": "2021/01/04",
                "LST_NAV": "42,000",
                "TDD_OPNPRC": "42,000",
                "TDD_HGPRC": "42,000",
                "TDD_LWPRC": "42,000",
                "TDD_CLSPRC": "42,000",
                "ACC_TRDVOL": "1,000",
                "ACC_TRDVAL": "42,000,000"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = EtfOhlcvHistory.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("20210104", result.date)
    }

    @Test
    fun `fromJson should return null for missing date`() {
        val json =
            """
            {
                "LST_NAV": "42,000",
                "TDD_CLSPRC": "42,000"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = EtfOhlcvHistory.fromJson(jsonObject)

        assertNull(result)
    }

    @Test
    fun `fromJson should handle missing optional fields`() {
        val json =
            """
            {
                "TRD_DD": "2021/01/22",
                "TDD_OPNPRC": "42,000",
                "TDD_HGPRC": "42,250",
                "TDD_LWPRC": "41,950",
                "TDD_CLSPRC": "42,100",
                "ACC_TRDVOL": "8,234,567",
                "ACC_TRDVAL": "346,789,012,345"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = EtfOhlcvHistory.fromJson(jsonObject)

        assertNotNull(result)
        assertNull(result.nav)
        assertNull(result.underlyingIndex)
        assertEquals(42100L, result.close)
    }

    @Test
    fun `fromJson should handle empty and dash values`() {
        val json =
            """
            {
                "TRD_DD": "2021/01/22",
                "LST_NAV": "-",
                "TDD_OPNPRC": "",
                "TDD_HGPRC": "0",
                "TDD_LWPRC": "0",
                "TDD_CLSPRC": "0",
                "ACC_TRDVOL": "-",
                "ACC_TRDVAL": "",
                "OBJ_STKPRC_IDX": ""
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = EtfOhlcvHistory.fromJson(jsonObject)

        assertNotNull(result)
        assertNull(result.nav)
        assertEquals(0L, result.open)
        assertEquals(0L, result.volume)
    }
}
