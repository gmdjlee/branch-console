package com.krxkt.model

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DerivativeIndexTest {
    @Test
    fun `fromJson should parse valid VKOSPI response`() {
        val json =
            """
            {
                "TRD_DD": "2021/01/22",
                "CLSPRC_IDX": "27.45"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = DerivativeIndex.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("20210122", result.date)
        assertEquals(27.45, result.close, 0.01)
    }

    @Test
    fun `fromJson should parse bond index with comma`() {
        val json =
            """
            {
                "TRD_DD": "2021/01/22",
                "CLSPRC_IDX": "1,234.56"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = DerivativeIndex.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals(1234.56, result.close, 0.01)
    }

    @Test
    fun `fromJson should handle date without slashes`() {
        val json =
            """
            {
                "TRD_DD": "20210122",
                "CLSPRC_IDX": "1.85"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = DerivativeIndex.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("20210122", result.date)
        assertEquals(1.85, result.close, 0.01)
    }

    @Test
    fun `fromJson should return null for missing date`() {
        val json =
            """
            {
                "CLSPRC_IDX": "27.45"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = DerivativeIndex.fromJson(jsonObject)

        assertNull(result)
    }

    @Test
    fun `fromJson should return null for missing close price`() {
        val json =
            """
            {
                "TRD_DD": "2021/01/22"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = DerivativeIndex.fromJson(jsonObject)

        assertNull(result)
    }

    @Test
    fun `fromJson should return null for dash close price`() {
        val json =
            """
            {
                "TRD_DD": "2021/01/22",
                "CLSPRC_IDX": "-"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = DerivativeIndex.fromJson(jsonObject)

        assertNull(result)
    }

    @Test
    fun `fromJson should return null for empty close price`() {
        val json =
            """
            {
                "TRD_DD": "2021/01/22",
                "CLSPRC_IDX": ""
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = DerivativeIndex.fromJson(jsonObject)

        assertNull(result)
    }
}
