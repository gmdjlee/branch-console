package com.krxkt

import com.krxkt.api.KrxClient
import com.krxkt.api.KrxEndpoints
import com.krxkt.error.KrxError
import com.krxkt.model.IndexMarket
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KrxIndexTest {
    private lateinit var mockServer: MockWebServer
    private lateinit var krxIndex: KrxIndex

    @BeforeTest
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()
        val mockUrl = mockServer.url("/").toString()
        val client = KrxClient(baseUrl = mockUrl, sessionInitUrl = mockUrl)
        client.setLoggedInForTest(true)
        krxIndex = KrxIndex(client)
    }

    @AfterTest
    fun teardown() {
        mockServer.shutdown()
        krxIndex.close()
    }

    // ====================================================
    // getIndexOhlcv (전종목 지수 OHLCV - 특정일)
    // ====================================================

    @Test
    fun `getIndexOhlcv should return parsed data`() =
        runTest {
            val responseJson =
                """
                {
                    "OutBlock_1": [
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
                        },
                        {
                            "IDX_NM": "코스피 200",
                            "CLSPRC_IDX": "412.50",
                            "FLUC_TP_CD": "1",
                            "CMPPREVDD_IDX": "7.50",
                            "FLUC_RT": "1.85",
                            "OPNPRC_IDX": "408.00",
                            "HGPRC_IDX": "413.00",
                            "LWPRC_IDX": "408.00",
                            "ACC_TRDVOL": "200,000",
                            "ACC_TRDVAL": "5,000,000",
                            "MKTCAP": "1,800,000,000"
                        }
                    ],
                    "totCnt": 2
                }
                """.trimIndent()

            mockServer.enqueue(MockResponse().setBody(responseJson).setResponseCode(200))

            val result = krxIndex.getIndexOhlcv("20210122")

            assertEquals(2, result.size)
            assertEquals("코스피", result[0].name)
            assertEquals(3055.28, result[0].close, 0.01)
            assertEquals(3031.68, result[0].open, 0.01)
            assertEquals(3062.48, result[0].high, 0.01)
            assertEquals(3031.68, result[0].low, 0.01)
            assertEquals(637384L, result[0].volume)
            assertEquals(12534397L, result[0].tradingValue)
            assertEquals(2100000000L, result[0].marketCap)
            assertEquals(1, result[0].changeType)
            assertEquals(52.90, result[0].change!!, 0.01)
            assertEquals(1.76, result[0].changeRate!!, 0.01)
            assertTrue(result[0].isUp)

            assertEquals("코스피 200", result[1].name)
        }

    @Test
    fun `getIndexOhlcv on holiday should return empty list`() =
        runTest {
            mockServer.enqueue(MockResponse().setBody("""{"OutBlock_1": [], "totCnt": 0}""").setResponseCode(200))

            val result = krxIndex.getIndexOhlcv("20210101")
            assertTrue(result.isEmpty())
        }

    @Test
    fun `getIndexOhlcv should send correct parameters`() =
        runTest {
            mockServer.enqueue(MockResponse().setBody("""{"OutBlock_1": []}""").setResponseCode(200))

            krxIndex.getIndexOhlcv("20210122", IndexMarket.KOSPI)

            val request = mockServer.takeRequest()
            val body = java.net.URLDecoder.decode(request.body.readUtf8(), "UTF-8")
            assertTrue(body.contains("bld=${KrxEndpoints.Bld.INDEX_LIST}"))
            assertTrue(body.contains("trdDd=20210122"))
            assertTrue(body.contains("idxIndMidclssCd=02"))
        }

    @Test
    fun `getIndexOhlcv with invalid date should throw`() =
        runTest {
            assertFailsWith<KrxError.InvalidDateError> {
                krxIndex.getIndexOhlcv("2021-01-22")
            }
        }

    // ====================================================
    // getIndexPortfolio (지수 구성종목)
    // ====================================================

    @Test
    fun `getIndexPortfolio should return parsed data`() =
        runTest {
            val responseJson =
                """
                {
                    "OutBlock_1": [
                        {
                            "ISU_SRT_CD": "005930",
                            "ISU_ABBRV": "삼성전자",
                            "TDD_CLSPRC": "84,400",
                            "FLUC_TP_CD": "2",
                            "STR_CMP_PRC": "-2,200",
                            "FLUC_RT": "-2.54",
                            "MKTCAP": "503,825,840,000,000"
                        },
                        {
                            "ISU_SRT_CD": "000660",
                            "ISU_ABBRV": "SK하이닉스",
                            "TDD_CLSPRC": "127,000",
                            "FLUC_TP_CD": "2",
                            "STR_CMP_PRC": "-1,000",
                            "FLUC_RT": "-0.78",
                            "MKTCAP": "92,430,000,000,000"
                        }
                    ],
                    "totCnt": 2
                }
                """.trimIndent()

            mockServer.enqueue(MockResponse().setBody(responseJson).setResponseCode(200))

            val result = krxIndex.getIndexPortfolio("20210122", "1028")

            assertEquals(2, result.size)
            assertEquals("005930", result[0].ticker)
            assertEquals("삼성전자", result[0].name)
            assertEquals(84400L, result[0].close)
            assertEquals(2, result[0].changeType)
            assertEquals(-2200L, result[0].change)
            assertEquals(-2.54, result[0].changeRate!!, 0.01)
            assertEquals(503825840000000L, result[0].marketCap)

            assertEquals("000660", result[1].ticker)
        }

    @Test
    fun `getIndexPortfolio should send correct bld and ticker params`() =
        runTest {
            mockServer.enqueue(MockResponse().setBody("""{"OutBlock_1": []}""").setResponseCode(200))

            krxIndex.getIndexPortfolio("20210122", "1028")

            val request = mockServer.takeRequest()
            val body = java.net.URLDecoder.decode(request.body.readUtf8(), "UTF-8")
            assertTrue(body.contains("bld=${KrxEndpoints.Bld.INDEX_PORTFOLIO}"))
            assertTrue(body.contains("trdDd=20210122"))
            assertTrue(body.contains("indIdx=1"))
            assertTrue(body.contains("indIdx2=028"))
        }

    @Test
    fun `getIndexPortfolio with invalid ticker should throw`() =
        runTest {
            assertFailsWith<IllegalArgumentException> {
                krxIndex.getIndexPortfolio("20210122", "1")
            }
        }

    @Test
    fun `getIndexPortfolioTickers should return ticker list`() =
        runTest {
            val responseJson =
                """
                {
                    "OutBlock_1": [
                        {"ISU_SRT_CD": "005930", "ISU_ABBRV": "삼성전자", "TDD_CLSPRC": "84,400", "STR_CMP_PRC": "0", "MKTCAP": "100"},
                        {"ISU_SRT_CD": "000660", "ISU_ABBRV": "SK하이닉스", "TDD_CLSPRC": "127,000", "STR_CMP_PRC": "0", "MKTCAP": "50"}
                    ]
                }
                """.trimIndent()

            mockServer.enqueue(MockResponse().setBody(responseJson).setResponseCode(200))

            val tickers = krxIndex.getIndexPortfolioTickers("20210122", "1028")

            assertEquals(listOf("005930", "000660"), tickers)
        }

    // ====================================================
    // getNearestBusinessDay (최근 영업일)
    // Uses getOhlcvByTicker (MDCSTAT00301) - returns only actual trading dates
    // ====================================================

    @Test
    fun `getNearestBusinessDay should return same date if business day`() =
        runTest {
            // prev=true → queries range [date-7, date] via getOhlcvByTicker
            // Response contains 20210122 as a trading day
            val responseJson =
                """
                {
                    "OutBlock_1": [
                        {"TRD_DD": "2021/01/21", "OPNPRC_IDX": "3,100.00", "HGPRC_IDX": "3,120.00", "LWPRC_IDX": "3,080.00", "CLSPRC_IDX": "3,110.00", "ACC_TRDVOL": "500,000", "ACC_TRDVAL": "10,000,000"},
                        {"TRD_DD": "2021/01/22", "OPNPRC_IDX": "3,031.68", "HGPRC_IDX": "3,062.48", "LWPRC_IDX": "3,031.68", "CLSPRC_IDX": "3,055.28", "ACC_TRDVOL": "637,384", "ACC_TRDVAL": "12,534,397"}
                    ]
                }
                """.trimIndent()

            mockServer.enqueue(MockResponse().setBody(responseJson).setResponseCode(200))

            val result = krxIndex.getNearestBusinessDay("20210122")
            assertEquals("20210122", result)
        }

    @Test
    fun `getNearestBusinessDay should find previous business day on holiday`() =
        runTest {
            // prev=true, date=20210123 (Saturday) → queries [20210116, 20210123]
            // Response: only weekday trading dates (no Sat/Sun entries)
            val responseJson =
                """
                {
                    "OutBlock_1": [
                        {"TRD_DD": "2021/01/18", "OPNPRC_IDX": "3,000.00", "HGPRC_IDX": "3,020.00", "LWPRC_IDX": "2,990.00", "CLSPRC_IDX": "3,010.00", "ACC_TRDVOL": "400,000", "ACC_TRDVAL": "8,000,000"},
                        {"TRD_DD": "2021/01/19", "OPNPRC_IDX": "3,010.00", "HGPRC_IDX": "3,030.00", "LWPRC_IDX": "3,000.00", "CLSPRC_IDX": "3,020.00", "ACC_TRDVOL": "420,000", "ACC_TRDVAL": "8,500,000"},
                        {"TRD_DD": "2021/01/20", "OPNPRC_IDX": "3,020.00", "HGPRC_IDX": "3,050.00", "LWPRC_IDX": "3,010.00", "CLSPRC_IDX": "3,040.00", "ACC_TRDVOL": "450,000", "ACC_TRDVAL": "9,000,000"},
                        {"TRD_DD": "2021/01/21", "OPNPRC_IDX": "3,040.00", "HGPRC_IDX": "3,060.00", "LWPRC_IDX": "3,030.00", "CLSPRC_IDX": "3,050.00", "ACC_TRDVOL": "430,000", "ACC_TRDVAL": "8,700,000"},
                        {"TRD_DD": "2021/01/22", "OPNPRC_IDX": "3,031.68", "HGPRC_IDX": "3,062.48", "LWPRC_IDX": "3,031.68", "CLSPRC_IDX": "3,055.28", "ACC_TRDVOL": "637,384", "ACC_TRDVAL": "12,534,397"}
                    ]
                }
                """.trimIndent()

            mockServer.enqueue(MockResponse().setBody(responseJson).setResponseCode(200))

            val result = krxIndex.getNearestBusinessDay("20210123", prev = true)
            assertEquals("20210122", result)
        }

    @Test
    fun `getNearestBusinessDay should find next business day when prev is false`() =
        runTest {
            // prev=false, date=20210123 (Saturday) → queries [20210123, 20210130]
            // Response: Monday 20210125 is the first trading day
            val responseJson =
                """
                {
                    "OutBlock_1": [
                        {"TRD_DD": "2021/01/25", "OPNPRC_IDX": "3,080.00", "HGPRC_IDX": "3,120.00", "LWPRC_IDX": "3,075.00", "CLSPRC_IDX": "3,100.00", "ACC_TRDVOL": "500,000", "ACC_TRDVAL": "10,000,000"},
                        {"TRD_DD": "2021/01/26", "OPNPRC_IDX": "3,100.00", "HGPRC_IDX": "3,130.00", "LWPRC_IDX": "3,090.00", "CLSPRC_IDX": "3,120.00", "ACC_TRDVOL": "480,000", "ACC_TRDVAL": "9,500,000"}
                    ]
                }
                """.trimIndent()

            mockServer.enqueue(MockResponse().setBody(responseJson).setResponseCode(200))

            val result = krxIndex.getNearestBusinessDay("20210123", prev = false)
            assertEquals("20210125", result)
        }

    @Test
    fun `getNearestBusinessDay should throw when no business day in range`() =
        runTest {
            mockServer.enqueue(MockResponse().setBody("""{"OutBlock_1": []}""").setResponseCode(200))

            assertFailsWith<IllegalStateException> {
                krxIndex.getNearestBusinessDay("20210123", prev = true)
            }
        }

    @Test
    fun `getNearestBusinessDay with invalid date should throw`() =
        runTest {
            assertFailsWith<KrxError.InvalidDateError> {
                krxIndex.getNearestBusinessDay("bad-date")
            }
        }

    // ====================================================
    // getBusinessDays (기간 내 영업일 목록)
    // ====================================================

    @Test
    fun `getBusinessDays should return trading dates from OHLCV`() =
        runTest {
            val responseJson =
                """
                {
                    "OutBlock_1": [
                        {"TRD_DD": "2021/01/04", "OPNPRC_IDX": "2,900.00", "HGPRC_IDX": "2,920.00", "LWPRC_IDX": "2,850.00", "CLSPRC_IDX": "2,860.00", "ACC_TRDVOL": "500,000", "ACC_TRDVAL": "10,000,000"},
                        {"TRD_DD": "2021/01/05", "OPNPRC_IDX": "2,870.00", "HGPRC_IDX": "2,890.00", "LWPRC_IDX": "2,860.00", "CLSPRC_IDX": "2,880.00", "ACC_TRDVOL": "480,000", "ACC_TRDVAL": "9,500,000"},
                        {"TRD_DD": "2021/01/06", "OPNPRC_IDX": "2,880.00", "HGPRC_IDX": "2,910.00", "LWPRC_IDX": "2,875.00", "CLSPRC_IDX": "2,905.00", "ACC_TRDVOL": "510,000", "ACC_TRDVAL": "9,800,000"}
                    ]
                }
                """.trimIndent()

            mockServer.enqueue(MockResponse().setBody(responseJson).setResponseCode(200))

            val result = krxIndex.getBusinessDays("20210104", "20210108")

            assertEquals(3, result.size)
            assertEquals("20210104", result[0])
            assertEquals("20210105", result[1])
            assertEquals("20210106", result[2])
        }

    @Test
    fun `getBusinessDays with no trading days should return empty list`() =
        runTest {
            mockServer.enqueue(MockResponse().setBody("""{"OutBlock_1": []}""").setResponseCode(200))

            val result = krxIndex.getBusinessDays("20210101", "20210103")
            assertTrue(result.isEmpty())
        }

    @Test
    fun `getBusinessDays with invalid date range should throw`() =
        runTest {
            assertFailsWith<KrxError.InvalidDateError> {
                krxIndex.getBusinessDays("20210131", "20210101")
            }
        }

    // ====================================================
    // getBusinessDaysByMonth (월별 영업일)
    // ====================================================

    @Test
    fun `getBusinessDaysByMonth should return month trading dates`() =
        runTest {
            val responseJson =
                """
                {
                    "OutBlock_1": [
                        {"TRD_DD": "2021/01/04", "OPNPRC_IDX": "2,900.00", "HGPRC_IDX": "2,920.00", "LWPRC_IDX": "2,850.00", "CLSPRC_IDX": "2,860.00", "ACC_TRDVOL": "500,000", "ACC_TRDVAL": "10,000,000"},
                        {"TRD_DD": "2021/01/05", "OPNPRC_IDX": "2,870.00", "HGPRC_IDX": "2,890.00", "LWPRC_IDX": "2,860.00", "CLSPRC_IDX": "2,880.00", "ACC_TRDVOL": "480,000", "ACC_TRDVAL": "9,500,000"}
                    ]
                }
                """.trimIndent()

            mockServer.enqueue(MockResponse().setBody(responseJson).setResponseCode(200))

            val result = krxIndex.getBusinessDaysByMonth(2021, 1)

            assertEquals(2, result.size)
            assertTrue(result[0].startsWith("2021"))
        }

    @Test
    fun `getBusinessDaysByMonth with invalid year should throw`() =
        runTest {
            assertFailsWith<IllegalArgumentException> {
                krxIndex.getBusinessDaysByMonth(1800, 1)
            }
        }

    @Test
    fun `getBusinessDaysByMonth with invalid month should throw`() =
        runTest {
            assertFailsWith<IllegalArgumentException> {
                krxIndex.getBusinessDaysByMonth(2021, 13)
            }
        }

    // ====================================================
    // getDerivativeIndex (파생상품 지수)
    // ====================================================

    @Test
    fun `getDerivativeIndex should return parsed VKOSPI data`() =
        runTest {
            val responseJson =
                """
                {
                    "output": [
                        {"TRD_DD": "2021/01/21", "CLSPRC_IDX": "28.15"},
                        {"TRD_DD": "2021/01/22", "CLSPRC_IDX": "27.45"}
                    ]
                }
                """.trimIndent()

            mockServer.enqueue(MockResponse().setBody(responseJson).setResponseCode(200))

            val result = krxIndex.getVkospi("20210121", "20210122")

            assertEquals(2, result.size)
            assertEquals("20210121", result[0].date)
            assertEquals(28.15, result[0].close, 0.01)
            assertEquals("20210122", result[1].date)
            assertEquals(27.45, result[1].close, 0.01)
        }

    @Test
    fun `getDerivativeIndex should send correct parameters`() =
        runTest {
            mockServer.enqueue(MockResponse().setBody("""{"output": []}""").setResponseCode(200))

            krxIndex.getDerivativeIndex("20210101", "20210131", "1", "300")

            val request = mockServer.takeRequest()
            val body = java.net.URLDecoder.decode(request.body.readUtf8(), "UTF-8")
            assertTrue(body.contains("bld=${KrxEndpoints.Bld.DERIVATIVE_INDEX}"))
            assertTrue(body.contains("indTpCd=1"))
            assertTrue(body.contains("idxIndCd=300"))
            assertTrue(body.contains("strtDd=20210101"))
            assertTrue(body.contains("endDd=20210131"))
        }

    @Test
    fun `getBond5y should return bond index data`() =
        runTest {
            val responseJson =
                """
                {
                    "output": [
                        {"TRD_DD": "2021/01/22", "CLSPRC_IDX": "1.85"}
                    ]
                }
                """.trimIndent()

            mockServer.enqueue(MockResponse().setBody(responseJson).setResponseCode(200))

            val result = krxIndex.getBond5y("20210122", "20210122")

            assertEquals(1, result.size)
            assertEquals(1.85, result[0].close, 0.01)
        }

    @Test
    fun `getBond10y should return bond index data`() =
        runTest {
            val responseJson =
                """
                {
                    "output": [
                        {"TRD_DD": "2021/01/22", "CLSPRC_IDX": "2.15"}
                    ]
                }
                """.trimIndent()

            mockServer.enqueue(MockResponse().setBody(responseJson).setResponseCode(200))

            val result = krxIndex.getBond10y("20210122", "20210122")

            assertEquals(1, result.size)
            assertEquals(2.15, result[0].close, 0.01)
        }

    @Test
    fun `getDerivativeIndex on holiday should return empty list`() =
        runTest {
            mockServer.enqueue(MockResponse().setBody("""{"output": []}""").setResponseCode(200))

            val result = krxIndex.getVkospi("20210101", "20210101")
            assertTrue(result.isEmpty())
        }

    // ====================================================
    // getOptionVolume (옵션 거래량)
    // ====================================================

    @Test
    fun `getCallOptionVolume should return parsed data`() =
        runTest {
            val responseJson =
                """
                {
                    "output": [
                        {"TRD_DD": "2021/01/21", "AMT_OR_QTY": "1,500,000"},
                        {"TRD_DD": "2021/01/22", "AMT_OR_QTY": "1,234,567"}
                    ]
                }
                """.trimIndent()

            mockServer.enqueue(MockResponse().setBody(responseJson).setResponseCode(200))

            val result = krxIndex.getCallOptionVolume("20210121", "20210122")

            assertEquals(2, result.size)
            assertEquals("20210121", result[0].date)
            assertEquals(1500000L, result[0].totalVolume)
            assertEquals("20210122", result[1].date)
            assertEquals(1234567L, result[1].totalVolume)
        }

    @Test
    fun `getPutOptionVolume should return parsed data`() =
        runTest {
            val responseJson =
                """
                {
                    "output": [
                        {"TRD_DD": "2021/01/22", "AMT_OR_QTY": "987,654"}
                    ]
                }
                """.trimIndent()

            mockServer.enqueue(MockResponse().setBody(responseJson).setResponseCode(200))

            val result = krxIndex.getPutOptionVolume("20210122", "20210122")

            assertEquals(1, result.size)
            assertEquals(987654L, result[0].totalVolume)
        }

    @Test
    fun `getOptionVolume should send correct parameters for call`() =
        runTest {
            mockServer.enqueue(MockResponse().setBody("""{"output": []}""").setResponseCode(200))

            krxIndex.getOptionVolume("20210101", "20210131", "C")

            val request = mockServer.takeRequest()
            val body = java.net.URLDecoder.decode(request.body.readUtf8(), "UTF-8")
            assertTrue(body.contains("bld=${KrxEndpoints.Bld.OPTION_TRADING}"))
            assertTrue(body.contains("isuOpt=C"))
            assertTrue(body.contains("prodId=KR___OPK2I"))
            assertTrue(body.contains("strtDd=20210101"))
            assertTrue(body.contains("endDd=20210131"))
        }

    @Test
    fun `getOptionVolume with invalid type should throw`() =
        runTest {
            assertFailsWith<IllegalArgumentException> {
                krxIndex.getOptionVolume("20210101", "20210131", "X")
            }
        }

    @Test
    fun `getOptionVolume on holiday should return empty list`() =
        runTest {
            mockServer.enqueue(MockResponse().setBody("""{"output": []}""").setResponseCode(200))

            val result = krxIndex.getCallOptionVolume("20210101", "20210101")
            assertTrue(result.isEmpty())
        }

    // ====================================================
    // Existing functions (regression tests)
    // ====================================================

    @Test
    fun `getOhlcvByTicker should still work correctly`() =
        runTest {
            val responseJson =
                """
                {
                    "OutBlock_1": [
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
                    ]
                }
                """.trimIndent()

            mockServer.enqueue(MockResponse().setBody(responseJson).setResponseCode(200))

            val result = krxIndex.getOhlcvByTicker("20210101", "20210131", "1028")

            assertEquals(1, result.size)
            assertEquals("20210122", result[0].date)
            assertEquals(3055.28, result[0].close, 0.01)
        }

    @Test
    fun `getIndexList should still work correctly`() =
        runTest {
            val responseJson =
                """
                {
                    "OutBlock_1": [
                        {
                            "IDX_NM": "코스피",
                            "IDX_IND_CD": "001",
                            "IND_TP_CD": "1",
                            "BAS_TM_CONTN": "1980.01.04"
                        }
                    ]
                }
                """.trimIndent()

            mockServer.enqueue(MockResponse().setBody(responseJson).setResponseCode(200))

            val result = krxIndex.getIndexList("20210122")

            assertEquals(1, result.size)
            assertEquals("1001", result[0].ticker)
            assertEquals("코스피", result[0].name)
        }
}
