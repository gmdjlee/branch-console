package com.krxkt.model

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndexOhlcvByTickerTest {
    @Test
    fun `fromJson should parse valid all-index OHLCV response`() {
        val json =
            """
            {
                "IDX_NM": "코스피",
                "CLSPRC_IDX": "3,055.28",
                "FLUC_TP_CD": "1",
                "CMPPREVDD_IDX": "52.90",
                "FLUC_RT": "1.76",
                "OPNPRC_IDX": "3,031.68",
                "HGPRC_IDX": "3,062.48",
                "LWPRC_IDX": "3,031.68",
                "ACC_TRDVOL": "637,384",
                "ACC_TRDVAL": "12,534,397",
                "MKTCAP": "2,100,000,000"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = IndexOhlcvByTicker.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("코스피", result.name)
        assertEquals(3055.28, result.close, 0.01)
        assertEquals(3031.68, result.open, 0.01)
        assertEquals(3062.48, result.high, 0.01)
        assertEquals(3031.68, result.low, 0.01)
        assertEquals(637384L, result.volume)
        assertEquals(12534397L, result.tradingValue)
        assertEquals(2100000000L, result.marketCap)
        assertEquals(1, result.changeType)
        assertEquals(52.90, result.change!!, 0.01)
        assertEquals(1.76, result.changeRate!!, 0.01)
        assertTrue(result.isUp)
        assertFalse(result.isDown)
        assertFalse(result.isUnchanged)
    }

    @Test
    fun `fromJson should handle down index`() {
        val json =
            """
            {
                "IDX_NM": "코스닥",
                "CLSPRC_IDX": "940.50",
                "FLUC_TP_CD": "2",
                "CMPPREVDD_IDX": "-10.30",
                "FLUC_RT": "-1.08",
                "OPNPRC_IDX": "950.00",
                "HGPRC_IDX": "952.00",
                "LWPRC_IDX": "938.00",
                "ACC_TRDVOL": "300,000",
                "ACC_TRDVAL": "5,000,000",
                "MKTCAP": "350,000,000"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = IndexOhlcvByTicker.fromJson(jsonObject)

        assertNotNull(result)
        assertTrue(result.isDown)
        assertFalse(result.isUp)
        assertEquals(-10.30, result.change!!, 0.01)
    }

    @Test
    fun `fromJson should return null for empty name`() {
        val json =
            """
            {
                "IDX_NM": "",
                "CLSPRC_IDX": "100.00"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = IndexOhlcvByTicker.fromJson(jsonObject)

        assertNull(result)
    }

    @Test
    fun `fromJson should return null for missing name`() {
        val json =
            """
            {
                "CLSPRC_IDX": "100.00",
                "OPNPRC_IDX": "99.00"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = IndexOhlcvByTicker.fromJson(jsonObject)

        assertNull(result)
    }

    @Test
    fun `fromJson should handle missing optional fields`() {
        val json =
            """
            {
                "IDX_NM": "테스트지수",
                "CLSPRC_IDX": "100.00",
                "OPNPRC_IDX": "99.00",
                "HGPRC_IDX": "101.00",
                "LWPRC_IDX": "98.00",
                "ACC_TRDVOL": "1,000",
                "ACC_TRDVAL": "100,000",
                "MKTCAP": "50,000"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = IndexOhlcvByTicker.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("테스트지수", result.name)
        assertNull(result.changeType)
        assertNull(result.change)
        assertNull(result.changeRate)
    }

    @Test
    fun `fromJson should handle dash and empty numeric values`() {
        val json =
            """
            {
                "IDX_NM": "테스트지수",
                "CLSPRC_IDX": "-",
                "OPNPRC_IDX": "",
                "HGPRC_IDX": "0",
                "LWPRC_IDX": "0",
                "ACC_TRDVOL": "-",
                "ACC_TRDVAL": "",
                "MKTCAP": "0",
                "FLUC_TP_CD": "",
                "CMPPREVDD_IDX": "-",
                "FLUC_RT": ""
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = IndexOhlcvByTicker.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals(0.0, result.close, 0.01)
        assertEquals(0.0, result.open, 0.01)
        assertEquals(0L, result.volume)
        assertNull(result.changeType)
        assertNull(result.change)
        assertNull(result.changeRate)
    }
}
