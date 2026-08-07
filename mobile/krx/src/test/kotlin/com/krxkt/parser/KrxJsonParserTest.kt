package com.krxkt.parser

import com.krxkt.error.KrxError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KrxJsonParserTest {
    @Test
    fun `parseKrxLong should handle comma-separated numbers`() {
        assertEquals(82200L, "82,200".parseKrxLong())
        assertEquals(1234567890L, "1,234,567,890".parseKrxLong())
    }

    @Test
    fun `parseKrxLong should handle negative numbers`() {
        assertEquals(-1500L, "-1,500".parseKrxLong())
        assertEquals(-82200L, "-82,200".parseKrxLong())
    }

    @Test
    fun `parseKrxLong should handle empty and dash values`() {
        assertEquals(0L, "".parseKrxLong())
        assertEquals(0L, "-".parseKrxLong())
        assertEquals(0L, "  ".parseKrxLong())
    }

    @Test
    fun `parseKrxLong should handle plain numbers`() {
        assertEquals(82200L, "82200".parseKrxLong())
        assertEquals(0L, "0".parseKrxLong())
    }

    @Test
    fun `parseKrxDouble should handle decimal numbers with commas`() {
        assertEquals(1234.56, "1,234.56".parseKrxDouble(), 0.001)
        assertEquals(-0.50, "-0.50".parseKrxDouble(), 0.001)
    }

    @Test
    fun `parseKrxDouble should handle empty and dash values`() {
        assertEquals(0.0, "".parseKrxDouble(), 0.001)
        assertEquals(0.0, "-".parseKrxDouble(), 0.001)
    }

    @Test
    fun `parseOutBlock should extract data from OutBlock_1`() {
        val json =
            """
            {
                "OutBlock_1": [
                    {"ISU_SRT_CD": "005930", "ISU_ABBRV": "삼성전자"},
                    {"ISU_SRT_CD": "000660", "ISU_ABBRV": "SK하이닉스"}
                ],
                "totCnt": 2
            }
            """.trimIndent()

        val result = KrxJsonParser.parseOutBlock(json)

        assertEquals(2, result.size)
        assertEquals("005930", result[0].get("ISU_SRT_CD").asString)
        assertEquals("삼성전자", result[0].get("ISU_ABBRV").asString)
    }

    @Test
    fun `parseOutBlock should return empty list for empty OutBlock_1`() {
        val json = """{"OutBlock_1": [], "totCnt": 0}"""

        val result = KrxJsonParser.parseOutBlock(json)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseOutBlock should return empty list for missing OutBlock_1`() {
        val json = """{"totCnt": 0}"""

        val result = KrxJsonParser.parseOutBlock(json)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseOutBlock should throw ParseError for invalid JSON`() {
        assertFailsWith<KrxError.ParseError> {
            KrxJsonParser.parseOutBlock("not valid json")
        }
    }

    @Test
    fun `parseOutBlock should extract data from block1`() {
        val json =
            """
            {
                "block1": [
                    {"TRD_DD": "2021/01/22", "AMT_OR_QTY": "1,234,567"},
                    {"TRD_DD": "2021/01/21", "AMT_OR_QTY": "987,654"}
                ]
            }
            """.trimIndent()

        val result = KrxJsonParser.parseOutBlock(json)

        assertEquals(2, result.size)
        assertEquals("2021/01/22", result[0].get("TRD_DD").asString)
        assertEquals("1,234,567", result[0].get("AMT_OR_QTY").asString)
    }

    @Test
    fun `parseOutBlock should prefer OutBlock_1 over block1`() {
        val json =
            """
            {
                "OutBlock_1": [
                    {"ISU_SRT_CD": "005930"}
                ],
                "block1": [
                    {"TRD_DD": "2021/01/22"}
                ]
            }
            """.trimIndent()

        val result = KrxJsonParser.parseOutBlock(json)

        assertEquals(1, result.size)
        assertEquals("005930", result[0].get("ISU_SRT_CD").asString)
    }

    @Test
    fun `parseOutBlock should fall back from block1 to output`() {
        val json =
            """
            {
                "output": [
                    {"ETF_NM": "KODEX 200"}
                ]
            }
            """.trimIndent()

        val result = KrxJsonParser.parseOutBlock(json)

        assertEquals(1, result.size)
        assertEquals("KODEX 200", result[0].get("ETF_NM").asString)
    }

    @Test
    fun `parseTotalCount should extract totCnt`() {
        val json = """{"OutBlock_1": [], "totCnt": 2500}"""

        assertEquals(2500, KrxJsonParser.parseTotalCount(json))
    }

    @Test
    fun `parseTotalCount should return 0 for missing totCnt`() {
        val json = """{"OutBlock_1": []}"""

        assertEquals(0, KrxJsonParser.parseTotalCount(json))
    }
}
