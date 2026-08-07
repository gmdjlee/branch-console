package com.krxkt.model

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MarketOhlcvTest {
    @Test
    fun `fromJson should parse valid KRX response`() {
        // 실제 KRX API 응답 형식 (2021-01-22 삼성전자 예시)
        val json =
            """
            {
                "ISU_SRT_CD": "005930",
                "ISU_ABBRV": "삼성전자",
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
        val result = MarketOhlcv.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("005930", result.ticker)
        assertEquals("삼성전자", result.name)
        assertEquals(87000L, result.open)
        assertEquals(87300L, result.high)
        assertEquals(84200L, result.low)
        assertEquals(84400L, result.close)
        assertEquals(30587917L, result.volume)
        assertEquals(2611762949200L, result.tradingValue)
        assertEquals(-2.54, result.changeRate, 0.001)
    }

    @Test
    fun `fromJson should handle zero values`() {
        val json =
            """
            {
                "ISU_SRT_CD": "123456",
                "ISU_ABBRV": "테스트종목",
                "TDD_OPNPRC": "0",
                "TDD_HGPRC": "0",
                "TDD_LWPRC": "0",
                "TDD_CLSPRC": "0",
                "ACC_TRDVOL": "0",
                "ACC_TRDVAL": "0",
                "FLUC_RT": "0.00"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = MarketOhlcv.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals(0L, result.open)
        assertEquals(0L, result.volume)
        assertEquals(0.0, result.changeRate, 0.001)
    }

    @Test
    fun `fromJson should handle empty string values as zero`() {
        val json =
            """
            {
                "ISU_SRT_CD": "123456",
                "ISU_ABBRV": "테스트종목",
                "TDD_OPNPRC": "",
                "TDD_HGPRC": "-",
                "TDD_LWPRC": "",
                "TDD_CLSPRC": "",
                "ACC_TRDVOL": "-",
                "ACC_TRDVAL": "",
                "FLUC_RT": "-"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = MarketOhlcv.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals(0L, result.open)
        assertEquals(0L, result.high)
        assertEquals(0.0, result.changeRate, 0.001)
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
        val result = MarketOhlcv.fromJson(jsonObject)

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
        val result = MarketOhlcv.fromJson(jsonObject)

        assertNull(result)
    }
}
