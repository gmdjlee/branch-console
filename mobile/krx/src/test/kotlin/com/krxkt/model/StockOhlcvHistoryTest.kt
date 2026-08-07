package com.krxkt.model

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StockOhlcvHistoryTest {
    @Test
    fun `fromJson should parse valid KRX response with slash date format`() {
        // KRX API returns dates in yyyy/MM/dd format
        val json =
            """
            {
                "TRD_DD": "2021/01/22",
                "TDD_OPNPRC": "87,000",
                "TDD_HGPRC": "87,300",
                "TDD_LWPRC": "84,200",
                "TDD_CLSPRC": "84,400",
                "ACC_TRDVOL": "30,587,917",
                "ACC_TRDVAL": "2,611,762,949,200",
                "FLUC_RT": "-2.54"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = StockOhlcvHistory.fromJson(jsonObject)

        assertNotNull(result)
        // Date should be converted to yyyyMMdd format
        assertEquals("20210122", result.date)
        assertEquals(87000L, result.open)
        assertEquals(87300L, result.high)
        assertEquals(84200L, result.low)
        assertEquals(84400L, result.close)
        assertEquals(30587917L, result.volume)
        assertEquals(2611762949200L, result.tradingValue)
        assertEquals(-2.54, result.changeRate, 0.001)
    }

    @Test
    fun `fromJson should handle yyyyMMdd date format`() {
        val json =
            """
            {
                "TRD_DD": "20210122",
                "TDD_OPNPRC": "1000",
                "TDD_HGPRC": "1100",
                "TDD_LWPRC": "900",
                "TDD_CLSPRC": "1050",
                "ACC_TRDVOL": "1000",
                "ACC_TRDVAL": "1050000",
                "FLUC_RT": "5.00"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = StockOhlcvHistory.fromJson(jsonObject)

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
        val result = StockOhlcvHistory.fromJson(jsonObject)

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
        val result = StockOhlcvHistory.fromJson(jsonObject)

        assertNull(result)
    }
}
