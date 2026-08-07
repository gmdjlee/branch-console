package com.krxkt.model

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OptionVolumeTest {
    @Test
    fun `fromJson should parse valid option volume response`() {
        val json =
            """
            {
                "TRD_DD": "2021/01/22",
                "AMT_OR_QTY": "1,234,567"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = OptionVolume.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("20210122", result.date)
        assertEquals(1234567L, result.totalVolume)
    }

    @Test
    fun `fromJson should handle date without slashes`() {
        val json =
            """
            {
                "TRD_DD": "20210122",
                "AMT_OR_QTY": "500,000"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = OptionVolume.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("20210122", result.date)
        assertEquals(500000L, result.totalVolume)
    }

    @Test
    fun `fromJson should return null for missing date`() {
        val json =
            """
            {
                "AMT_OR_QTY": "1,234,567"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = OptionVolume.fromJson(jsonObject)

        assertNull(result)
    }

    @Test
    fun `fromJson should handle zero and dash values`() {
        val json =
            """
            {
                "TRD_DD": "2021/01/22",
                "AMT_OR_QTY": "-"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = OptionVolume.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals(0L, result.totalVolume)
    }

    @Test
    fun `fromJson should handle empty volume`() {
        val json =
            """
            {
                "TRD_DD": "2021/01/22",
                "AMT_OR_QTY": ""
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = OptionVolume.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals(0L, result.totalVolume)
    }

    @Test
    fun `fromJson should handle missing AMT_OR_QTY field`() {
        val json =
            """
            {
                "TRD_DD": "2021/01/22"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = OptionVolume.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals(0L, result.totalVolume)
    }
}
