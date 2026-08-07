package com.krxkt.model

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class InvestorTradingTest {
    @Test
    fun `fromJson should parse valid investor trading response`() {
        // 실제 KRX API 응답 형식 (일별 추이)
        val json =
            """
            {
                "TRD_DD": "2021/01/22",
                "TRDVAL1": "1,234,567,890",
                "TRDVAL2": "234,567,890",
                "TRDVAL3": "345,678,901",
                "TRDVAL4": "456,789,012",
                "TRDVAL5": "567,890,123",
                "TRDVAL6": "678,901,234",
                "TRDVAL7": "789,012,345",
                "TRDVAL8": "4,307,407,395",
                "TRDVAL9": "890,123,456",
                "TRDVAL10": "-5,678,901,234",
                "TRDVAL11": "987,654,321",
                "TRDVAL_TOT": "506,283,938"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = InvestorTrading.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals("20210122", result.date)
        assertEquals(1234567890L, result.financialInvestment)
        assertEquals(234567890L, result.insurance)
        assertEquals(345678901L, result.investmentTrust)
        assertEquals(456789012L, result.privateEquity)
        assertEquals(567890123L, result.bank)
        assertEquals(678901234L, result.otherFinance)
        assertEquals(789012345L, result.pensionFund)
        // institutionalTotal = TRDVAL1~7 직접 합산 (D-1 정정 후에도 4,307,407,395로 값 불변 —
        // 이 픽스처에서는 TRDVAL8이 우연히 그 합과 같은 값이었을 뿐, 필드 자체는 이제 TRDVAL8을 읽지 않는다)
        assertEquals(4307407395L, result.institutionalTotal)
        assertEquals(4307407395L, result.otherCorporation) // TRDVAL8 = 기타법인 (D-1 정정)
        assertEquals(890123456L, result.individual) // TRDVAL9 = 개인 (D-1 정정)
        assertEquals(-4691246913L, result.foreigner) // TRDVAL10+11 = 외국인합계 (D-1 정정)
        assertEquals(506283938L, result.total)
    }

    @Test
    fun `fromJson should handle negative values`() {
        val json =
            """
            {
                "TRD_DD": "2021/01/22",
                "TRDVAL1": "-1,000,000",
                "TRDVAL2": "-500,000",
                "TRDVAL3": "0",
                "TRDVAL4": "0",
                "TRDVAL5": "0",
                "TRDVAL6": "0",
                "TRDVAL7": "0",
                "TRDVAL8": "-1,500,000",
                "TRDVAL9": "0",
                "TRDVAL10": "2,000,000",
                "TRDVAL11": "-500,000",
                "TRDVAL_TOT": "0"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = InvestorTrading.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals(-1000000L, result.financialInvestment)
        assertEquals(-1500000L, result.institutionalTotal) // TRDVAL1~7 합산, 값은 D-1 정정 전후 불변
        assertEquals(0L, result.individual) // TRDVAL9 = 개인 (D-1 정정)
        assertEquals(1500000L, result.foreigner) // TRDVAL10+11 = 2,000,000 + (-500,000) (D-1 정정)
    }

    @Test
    fun `fromJson should return null for missing date`() {
        val json =
            """
            {
                "TRDVAL1": "1,000,000",
                "TRDVAL10": "2,000,000"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = InvestorTrading.fromJson(jsonObject)

        assertNull(result)
    }

    @Test
    fun `fromJson should handle empty and missing fields`() {
        val json =
            """
            {
                "TRD_DD": "2021/01/22",
                "TRDVAL1": "",
                "TRDVAL2": "-",
                "TRDVAL10": "1,000,000"
            }
            """.trimIndent()

        val jsonObject = JsonParser.parseString(json).asJsonObject
        val result = InvestorTrading.fromJson(jsonObject)

        assertNotNull(result)
        assertEquals(0L, result.financialInvestment)
        assertEquals(0L, result.insurance)
        assertEquals(0L, result.individual) // TRDVAL9 결측 (D-1 정정: TRDVAL10이 아니라 TRDVAL9가 개인)
        assertEquals(1000000L, result.foreigner) // TRDVAL10=1,000,000(외국인 주성분)+TRDVAL11 결측(0)
    }

    @Test
    fun `fromJson maps TRDVAL8-11 per 2026-08-05 real capture, foreigner sum matches pykrx (D-1 witness)`() {
        // Raw JSON captured 2026-08-05, mktId=ALL (branch-console
        // docs/journal/2026-08-07_MT1-00c_kotlin_krx.md §3.4). pykrx 독립 재조회로 대조된 값:
        // 외국인합계(TRDVAL10+11)=+1,148,492,588,611, 기관합계(TRDVAL1~7 직접합산)=-393,829,630,819.
        val json =
            """
            {"TRD_DD":"2026/08/05",
             "TRDVAL1":"-28,799,818,552","TRDVAL2":"-7,009,938,372","TRDVAL3":"165,150,622,478",
             "TRDVAL4":"-511,707,108,428","TRDVAL5":"2,405,915,443","TRDVAL6":"21,332,394,103",
             "TRDVAL7":"-35,201,697,491","TRDVAL8":"35,576,102,556","TRDVAL9":"-790,239,060,348",
             "TRDVAL10":"1,154,143,165,996","TRDVAL11":"-5,650,577,385","TRDVAL_TOT":"0"}
            """.trimIndent()

        val result = InvestorTrading.fromJson(JsonParser.parseString(json).asJsonObject)

        assertNotNull(result)
        assertEquals(35576102556L, result.otherCorporation) // TRDVAL8 = 기타법인 (NOT 기관합계)
        assertEquals(-790239060348L, result.individual) // TRDVAL9 = 개인
        assertEquals(1148492588611L, result.foreigner) // TRDVAL10+11, pykrx 대조 재현
        assertEquals(-393829630819L, result.institutionalTotal) // TRDVAL1~7 직접합산, pykrx 대조 재현
    }

    @Test
    fun `fromJson 11-way classification identity sums to zero (D-2 witness)`() {
        // 같은 2026-08-05 캡처: 11분류 배타 순매수의 합은 항등적으로 0이다(§3.4). TRDVAL_TOT=0은
        // 결측이 아니라 이 항등식의 참값이며, pykrx `전체` 컬럼도 동일 3일치 전부 0으로 대조됐다.
        val json =
            """
            {"TRD_DD":"2026/08/05",
             "TRDVAL1":"-28,799,818,552","TRDVAL2":"-7,009,938,372","TRDVAL3":"165,150,622,478",
             "TRDVAL4":"-511,707,108,428","TRDVAL5":"2,405,915,443","TRDVAL6":"21,332,394,103",
             "TRDVAL7":"-35,201,697,491","TRDVAL8":"35,576,102,556","TRDVAL9":"-790,239,060,348",
             "TRDVAL10":"1,154,143,165,996","TRDVAL11":"-5,650,577,385","TRDVAL_TOT":"0"}
            """.trimIndent()

        val result = InvestorTrading.fromJson(JsonParser.parseString(json).asJsonObject)

        assertNotNull(result)
        val elevenWaySum = result.institutionalTotal + result.otherCorporation + result.individual + result.foreigner
        assertEquals(0L, elevenWaySum)
        assertEquals(0L, result.total)
    }

    @Test
    fun `InvestorType should map correctly`() {
        assertEquals(InvestorType.FINANCIAL_INVESTMENT, InvestorType.fromCode(1000))
        assertEquals(InvestorType.INDIVIDUAL, InvestorType.fromCode(8000))
        assertEquals(InvestorType.FOREIGNER, InvestorType.fromCode(9000))
        assertEquals(InvestorType.INSTITUTIONAL_TOTAL, InvestorType.fromCode(7050))
        assertNull(InvestorType.fromCode(9999999))
    }

    @Test
    fun `InvestorType fromName should work`() {
        assertEquals(InvestorType.FINANCIAL_INVESTMENT, InvestorType.fromName("금융투자"))
        assertEquals(InvestorType.INDIVIDUAL, InvestorType.fromName("개인"))
        assertEquals(InvestorType.FOREIGNER, InvestorType.fromName("외국인"))
    }

    @Test
    fun `TradingValueType should have correct codes`() {
        assertEquals("1", TradingValueType.VOLUME.code)
        assertEquals("2", TradingValueType.VALUE.code)
    }

    @Test
    fun `AskBidType should have correct codes`() {
        assertEquals("1", AskBidType.SELL.code)
        assertEquals("2", AskBidType.BUY.code)
        assertEquals("3", AskBidType.NET_BUY.code)
    }
}
