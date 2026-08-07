package com.krxkt.model

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ShortSellingTest {
    @Test
    fun `ShortSelling fromJson should parse valid response`() {
        val json =
            """
            {
                "ISU_SRT_CD": "005930",
                "ISU_ABBRV": "삼성전자",
                "CVSRTSELL_TRDVOL": "1,234,567",
                "CVSRTSELL_TRDVAL": "101,234,567,890",
                "ACC_TRDVOL": "30,000,000",
                "ACC_TRDVAL": "2,500,000,000,000",
                "TRDVOL_WT": "4.12"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = ShortSelling.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("005930", result.ticker)
        assertEquals("삼성전자", result.name)
        assertEquals(1234567L, result.shortVolume)
        assertEquals(101234567890L, result.shortValue)
        assertEquals(30000000L, result.totalVolume)
        assertEquals(2500000000000L, result.totalValue)
        assertEquals(4.12, result.volumeRatio!!, 0.01)
    }

    @Test
    fun `ShortSelling should calculate ratios correctly`() {
        val shortSelling =
            ShortSelling(
                ticker = "005930",
                name = "삼성전자",
                shortVolume = 1000L,
                shortValue = 100000L,
                totalVolume = 10000L,
                totalValue = 1000000L,
                volumeRatio = null,
            )

        assertEquals(10.0, shortSelling.calculatedVolumeRatio, 0.01)
        assertEquals(10.0, shortSelling.calculatedValueRatio, 0.01)
    }

    @Test
    fun `ShortSelling should handle zero total volume`() {
        val shortSelling =
            ShortSelling(
                ticker = "005930",
                name = "삼성전자",
                shortVolume = 1000L,
                shortValue = 100000L,
                totalVolume = 0L,
                totalValue = 0L,
                volumeRatio = null,
            )

        assertEquals(0.0, shortSelling.calculatedVolumeRatio, 0.01)
        assertEquals(0.0, shortSelling.calculatedValueRatio, 0.01)
    }

    @Test
    fun `ShortSelling fromJson should return null for missing ticker`() {
        val json =
            """
            {
                "ISU_ABBRV": "삼성전자",
                "CVSRTSELL_TRDVOL": "1,234,567"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = ShortSelling.fromJson(jsonObject)

        assertNull(result)
    }

    @Test
    fun `ShortSellingHistory fromJson should parse valid response`() {
        val json =
            """
            {
                "TRD_DD": "2021/01/22",
                "CVSRTSELL_TRDVOL": "1,234,567",
                "CVSRTSELL_TRDVAL": "101,234,567,890",
                "ACC_TRDVOL": "30,000,000",
                "ACC_TRDVAL": "2,500,000,000,000"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = ShortSellingHistory.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("20210122", result.date)
        assertEquals(1234567L, result.shortVolume)
        assertEquals(101234567890L, result.shortValue)
        assertEquals(30000000L, result.totalVolume)
        assertEquals(2500000000000L, result.totalValue)
    }

    @Test
    fun `ShortSellingHistory should calculate ratios`() {
        val history =
            ShortSellingHistory(
                date = "20210122",
                shortVolume = 500L,
                shortValue = 50000L,
                totalVolume = 10000L,
                totalValue = 1000000L,
            )

        assertEquals(5.0, history.volumeRatio, 0.01)
        assertEquals(5.0, history.valueRatio, 0.01)
    }

    @Test
    fun `ShortSellingHistory fromJson should return null for missing date`() {
        val json =
            """
            {
                "CVSRTSELL_TRDVOL": "1,234,567"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = ShortSellingHistory.fromJson(jsonObject)

        assertNull(result)
    }

    @Test
    fun `ShortBalance fromJson should parse valid response`() {
        val json =
            """
            {
                "ISU_SRT_CD": "005930",
                "ISU_ABBRV": "삼성전자",
                "BAL_QTY": "12,345,678",
                "BAL_AMT": "1,012,345,678,900",
                "LIST_SHRS": "5,969,782,550",
                "BAL_RTO": "0.21"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = ShortBalance.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("005930", result.ticker)
        assertEquals("삼성전자", result.name)
        assertEquals(12345678L, result.balanceQuantity)
        assertEquals(1012345678900L, result.balanceAmount)
        assertEquals(5969782550L, result.listedShares)
        assertEquals(0.21, result.balanceRatio!!, 0.01)
    }

    @Test
    fun `ShortBalance should calculate ratio correctly`() {
        val balance =
            ShortBalance(
                ticker = "005930",
                name = "삼성전자",
                balanceQuantity = 1000000L,
                balanceAmount = 82000000000L,
                listedShares = 100000000L,
                balanceRatio = null,
            )

        assertEquals(1.0, balance.calculatedBalanceRatio, 0.01)
    }

    @Test
    fun `ShortBalanceHistory fromJson should parse valid response`() {
        val json =
            """
            {
                "TRD_DD": "2021/01/22",
                "BAL_QTY": "12,345,678",
                "BAL_AMT": "1,012,345,678,900",
                "LIST_SHRS": "5,969,782,550",
                "BAL_RTO": "0.21"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = ShortBalanceHistory.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("20210122", result.date)
        assertEquals(12345678L, result.balanceQuantity)
        assertEquals(1012345678900L, result.balanceAmount)
        assertEquals(5969782550L, result.listedShares)
        assertEquals(0.21, result.balanceRatio!!, 0.01)
    }

    @Test
    fun `ShortBalanceHistory fromJson should return null for missing date`() {
        val json =
            """
            {
                "BAL_QTY": "12,345,678"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = ShortBalanceHistory.fromJson(jsonObject)

        assertNull(result)
    }

    @Test
    fun `ShortBalance and ShortBalanceHistory should handle empty values`() {
        val json =
            """
            {
                "ISU_SRT_CD": "005930",
                "ISU_ABBRV": "삼성전자",
                "BAL_QTY": "",
                "BAL_AMT": "-",
                "LIST_SHRS": "0",
                "BAL_RTO": ""
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = ShortBalance.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals(0L, result.balanceQuantity)
        assertEquals(0L, result.balanceAmount)
        assertNull(result.balanceRatio)
    }
}
