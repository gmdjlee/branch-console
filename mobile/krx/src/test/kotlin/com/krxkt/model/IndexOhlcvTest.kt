package com.krxkt.model

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndexOhlcvTest {
    @Test
    fun `fromJson should parse valid index OHLCV response`() {
        // 실제 KRX API 응답 형식 (KOSPI 200 예시)
        val json =
            """
            {
                "TRD_DD": "2021/01/22",
                "OPNPRC_IDX": "3,031.68",
                "HGPRC_IDX": "3,062.48",
                "LWPRC_IDX": "3,031.68",
                "CLSPRC_IDX": "3,055.28",
                "ACC_TRDVOL": "637,384",
                "ACC_TRDVAL": "12,534,397",
                "FLUC_TP_CD": "1",
                "PRV_DD_CMPR": "52.90"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = IndexOhlcv.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("20210122", result.date) // slash 제거됨
        assertEquals(3031.68, result.open, 0.01)
        assertEquals(3062.48, result.high, 0.01)
        assertEquals(3031.68, result.low, 0.01)
        assertEquals(3055.28, result.close, 0.01)
        assertEquals(637384L, result.volume)
        assertEquals(12534397L, result.tradingValue)
        assertEquals(1, result.changeType)
        assertEquals(52.90, result.change!!, 0.01)
        assertTrue(result.isUp)
        assertFalse(result.isDown)
    }

    @Test
    fun `fromJson should handle down market`() {
        val json =
            """
            {
                "TRD_DD": "2021/01/04",
                "OPNPRC_IDX": "2,900.00",
                "HGPRC_IDX": "2,920.00",
                "LWPRC_IDX": "2,850.00",
                "CLSPRC_IDX": "2,860.00",
                "ACC_TRDVOL": "500,000",
                "ACC_TRDVAL": "10,000,000",
                "FLUC_TP_CD": "2",
                "PRV_DD_CMPR": "-40.00"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = IndexOhlcv.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals(2, result.changeType)
        assertEquals(-40.00, result.change!!, 0.01)
        assertTrue(result.isDown)
        assertFalse(result.isUp)
    }

    @Test
    fun `fromJson should handle unchanged market`() {
        val json =
            """
            {
                "TRD_DD": "2021/01/04",
                "OPNPRC_IDX": "2,900.00",
                "HGPRC_IDX": "2,900.00",
                "LWPRC_IDX": "2,900.00",
                "CLSPRC_IDX": "2,900.00",
                "ACC_TRDVOL": "100",
                "ACC_TRDVAL": "290,000",
                "FLUC_TP_CD": "3",
                "PRV_DD_CMPR": "0.00"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = IndexOhlcv.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals(3, result.changeType)
        assertTrue(result.isUnchanged)
    }

    @Test
    fun `fromJson should return null for missing date`() {
        val json =
            """
            {
                "OPNPRC_IDX": "3,031.68",
                "CLSPRC_IDX": "3,055.28"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = IndexOhlcv.fromJson(jsonObject)

        assertNull(result)
    }

    @Test
    fun `fromJson should handle missing optional fields`() {
        val json =
            """
            {
                "TRD_DD": "2021/01/22",
                "OPNPRC_IDX": "3,031.68",
                "HGPRC_IDX": "3,062.48",
                "LWPRC_IDX": "3,031.68",
                "CLSPRC_IDX": "3,055.28",
                "ACC_TRDVOL": "637,384",
                "ACC_TRDVAL": "12,534,397"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = IndexOhlcv.fromJson(jsonObject)

        assertNotNull(result)
        assertNull(result.changeType)
        assertNull(result.change)
        assertEquals(3055.28, result.close, 0.01)
    }

    @Test
    fun `fromJson should handle empty and dash values`() {
        val json =
            """
            {
                "TRD_DD": "2021/01/22",
                "OPNPRC_IDX": "-",
                "HGPRC_IDX": "",
                "LWPRC_IDX": "0",
                "CLSPRC_IDX": "0",
                "ACC_TRDVOL": "-",
                "ACC_TRDVAL": "",
                "FLUC_TP_CD": "",
                "PRV_DD_CMPR": "-"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = IndexOhlcv.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals(0.0, result.open, 0.01)
        assertEquals(0.0, result.high, 0.01)
        assertEquals(0L, result.volume)
        assertNull(result.changeType)
    }
}
