package com.krxkt

import com.krxkt.api.KrxClient
import com.krxkt.api.KrxEndpoints
import com.krxkt.error.KrxError
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KrxStockTest {
    private lateinit var mockServer: MockWebServer
    private lateinit var krxStock: KrxStock

    @BeforeTest
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()
        val client = KrxClient(baseUrl = mockServer.url("/").toString())
        client.setLoggedInForTest(true)
        krxStock = KrxStock(client)
    }

    @AfterTest
    fun teardown() {
        mockServer.shutdown()
        krxStock.close()
    }

    // ====================================================
    // getMarketOhlcv
    // ====================================================

    @Test
    fun `getMarketOhlcv should return parsed data`() =
        runTest {
            val responseJson =
                """
                {
                    "OutBlock_1": [
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
                        },
                        {
                            "ISU_SRT_CD": "000660",
                            "ISU_ABBRV": "SK하이닉스",
                            "TDD_OPNPRC": "128,000",
                            "TDD_HGPRC": "129,000",
                            "TDD_LWPRC": "126,500",
                            "TDD_CLSPRC": "127,000",
                            "ACC_TRDVOL": "3,145,678",
                            "ACC_TRDVAL": "400,123,456,000",
                            "FLUC_RT": "-0.78"
                        }
                    ],
                    "totCnt": 2
                }
                """.trimIndent()

            mockServer.enqueue(MockResponse().setBody(responseJson).setResponseCode(200))

            val result = krxStock.getMarketOhlcv("20210122")

            assertEquals(2, result.size)
            assertEquals("005930", result[0].ticker)
            assertEquals("삼성전자", result[0].name)
            assertEquals(87000L, result[0].open)
            assertEquals(87300L, result[0].high)
            assertEquals(84200L, result[0].low)
            assertEquals(84400L, result[0].close)
            assertEquals(30587917L, result[0].volume)
            assertEquals(2611762949200L, result[0].tradingValue)
            assertEquals(-2.54, result[0].changeRate, 0.001)

            assertEquals("000660", result[1].ticker)
        }

    @Test
    fun `getMarketOhlcv on holiday should return empty list`() =
        runTest {
            val emptyResponse = """{"OutBlock_1": [], "totCnt": 0}"""
            mockServer.enqueue(MockResponse().setBody(emptyResponse).setResponseCode(200))

            val result = krxStock.getMarketOhlcv("20210101")

            assertTrue(result.isEmpty())
        }

    @Test
    fun `getMarketOhlcv with invalid date should throw InvalidDateError`() =
        runTest {
            assertFailsWith<KrxError.InvalidDateError> {
                krxStock.getMarketOhlcv("2021-01-22")
            }

            assertFailsWith<KrxError.InvalidDateError> {
                krxStock.getMarketOhlcv("invalid")
            }
        }

    @Test
    fun `getMarketOhlcv should send correct bld parameter`() =
        runTest {
            mockServer.enqueue(MockResponse().setBody("""{"OutBlock_1": [], "totCnt": 0}""").setResponseCode(200))

            krxStock.getMarketOhlcv("20210122")

            val request = mockServer.takeRequest()
            val body = java.net.URLDecoder.decode(request.body.readUtf8(), "UTF-8")
            assertTrue(body.contains("bld=${KrxEndpoints.Bld.STOCK_OHLCV_ALL}"))
            assertTrue(body.contains("trdDd=20210122"))
            assertTrue(body.contains("mktId=ALL"))
        }

    @Test
    fun `getMarketOhlcv should skip entries with empty ticker`() =
        runTest {
            val responseJson =
                """
                {
                    "OutBlock_1": [
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
                        },
                        {
                            "ISU_SRT_CD": "",
                            "ISU_ABBRV": "빈종목",
                            "TDD_CLSPRC": "0"
                        }
                    ],
                    "totCnt": 2
                }
                """.trimIndent()

            mockServer.enqueue(MockResponse().setBody(responseJson).setResponseCode(200))

            val result = krxStock.getMarketOhlcv("20210122")
            assertEquals(1, result.size)
            assertEquals("005930", result[0].ticker)
        }

    // ====================================================
    // getMarketCap
    // ====================================================

    @Test
    fun `getMarketCap should return parsed data`() =
        runTest {
            val responseJson =
                """
                {
                    "OutBlock_1": [
                        {
                            "ISU_SRT_CD": "005930",
                            "ISU_ABBRV": "삼성전자",
                            "TDD_CLSPRC": "84,400",
                            "FLUC_RT": "-2.54",
                            "MKTCAP": "503,825,840,000,000",
                            "LIST_SHRS": "5,969,782,550"
                        }
                    ],
                    "totCnt": 1
                }
                """.trimIndent()

            mockServer.enqueue(MockResponse().setBody(responseJson).setResponseCode(200))

            val result = krxStock.getMarketCap("20210122")

            assertEquals(1, result.size)
            assertEquals("005930", result[0].ticker)
            assertEquals(84400L, result[0].close)
            assertEquals(503825840000000L, result[0].marketCap)
            assertEquals(5969782550L, result[0].sharesOutstanding)
        }

    @Test
    fun `getMarketCap with invalid date should throw InvalidDateError`() =
        runTest {
            assertFailsWith<KrxError.InvalidDateError> {
                krxStock.getMarketCap("bad-date")
            }
        }

    // ====================================================
    // getMarketFundamental
    // ====================================================

    @Test
    fun `getMarketFundamental should return parsed data`() =
        runTest {
            val responseJson =
                """
                {
                    "OutBlock_1": [
                        {
                            "ISU_SRT_CD": "005930",
                            "ISU_ABBRV": "삼성전자",
                            "TDD_CLSPRC": "84,400",
                            "EPS": "3,166",
                            "PER": "26.66",
                            "BPS": "39,406",
                            "PBR": "2.14",
                            "DPS": "1,416",
                            "DVD_YLD": "1.68"
                        }
                    ],
                    "totCnt": 1
                }
                """.trimIndent()

            mockServer.enqueue(MockResponse().setBody(responseJson).setResponseCode(200))

            val result = krxStock.getMarketFundamental("20210122")

            assertEquals(1, result.size)
            assertEquals("005930", result[0].ticker)
        }

    // ====================================================
    // getTickerList
    // ====================================================

    @Test
    fun `getTickerList should return parsed data`() =
        runTest {
            val responseJson =
                """
                {
                    "OutBlock_1": [
                        {
                            "ISU_SRT_CD": "005930",
                            "ISU_ABBRV": "삼성전자",
                            "MKT_TP_NM": "KOSPI",
                            "ISU_CD": "KR7005930003"
                        },
                        {
                            "ISU_SRT_CD": "000660",
                            "ISU_ABBRV": "SK하이닉스",
                            "MKT_TP_NM": "KOSPI",
                            "ISU_CD": "KR7000660001"
                        }
                    ],
                    "totCnt": 2
                }
                """.trimIndent()

            mockServer.enqueue(MockResponse().setBody(responseJson).setResponseCode(200))

            val result = krxStock.getTickerList("20210122")

            assertEquals(2, result.size)
            assertEquals("005930", result[0].ticker)
            assertEquals("삼성전자", result[0].name)
            assertEquals("KOSPI", result[0].marketName)
            assertEquals("KR7005930003", result[0].isinCode)
        }
}
