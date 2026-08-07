package com.krxkt.api

import com.krxkt.error.KrxError
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.net.URLDecoder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KrxClientTest {
    private lateinit var mockServer: MockWebServer
    private lateinit var client: KrxClient

    @BeforeTest
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()
        client = KrxClient(baseUrl = mockServer.url("/").toString())
        client.setLoggedInForTest(true)
    }

    @AfterTest
    fun teardown() {
        mockServer.shutdown()
        client.close()
    }

    // ====================================================
    // Successful POST requests
    // ====================================================

    @Test
    fun `post should return response body on success`() =
        runTest {
            val expectedJson = """{"OutBlock_1": [{"ISU_SRT_CD": "005930"}], "totCnt": 1}"""
            mockServer.enqueue(MockResponse().setBody(expectedJson).setResponseCode(200))

            val params =
                mapOf(
                    "bld" to KrxEndpoints.Bld.STOCK_OHLCV_ALL,
                    "mktId" to "ALL",
                    "trdDd" to "20210122",
                )

            val result = client.post(params)
            assertEquals(expectedJson, result)
        }

    @Test
    fun `post should send correct headers`() =
        runTest {
            mockServer.enqueue(MockResponse().setBody("{}").setResponseCode(200))

            val params = mapOf("bld" to "test", "trdDd" to "20210122")
            client.post(params)

            val request = mockServer.takeRequest()
            assertEquals("POST", request.method)
            assertEquals(KrxEndpoints.REFERER, request.getHeader("Referer"))
            assertEquals(KrxEndpoints.USER_AGENT, request.getHeader("User-Agent"))
            assertContains(request.getHeader("Content-Type") ?: "", "application/x-www-form-urlencoded")
            assertEquals("XMLHttpRequest", request.getHeader("X-Requested-With"))
            assertEquals("https://data.krx.co.kr", request.getHeader("Origin"))
        }

    @Test
    fun `post should send correct form body params`() =
        runTest {
            mockServer.enqueue(MockResponse().setBody("{}").setResponseCode(200))

            val params =
                mapOf(
                    "bld" to KrxEndpoints.Bld.STOCK_OHLCV_ALL,
                    "mktId" to "ALL",
                    "trdDd" to "20210122",
                )
            client.post(params)

            val request = mockServer.takeRequest()
            val body = request.body.readUtf8()
            val decoded = URLDecoder.decode(body, "UTF-8")

            assertContains(decoded, "bld=${KrxEndpoints.Bld.STOCK_OHLCV_ALL}")
            assertContains(decoded, "mktId=ALL")
            assertContains(decoded, "trdDd=20210122")
        }

    @Test
    fun `post should return empty OutBlock_1 JSON`() =
        runTest {
            val expectedJson = """{"OutBlock_1": [], "totCnt": 0}"""
            mockServer.enqueue(MockResponse().setBody(expectedJson).setResponseCode(200))

            val result = client.post(mapOf("bld" to "test", "trdDd" to "20210101"))
            assertEquals(expectedJson, result)
        }

    // ====================================================
    // Retry logic
    // ====================================================

    @Test
    fun `post should retry on IOException and succeed on second attempt`() =
        runTest {
            // First request fails with disconnect
            mockServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            // Second request succeeds
            val expectedJson = """{"OutBlock_1": [], "totCnt": 0}"""
            mockServer.enqueue(MockResponse().setBody(expectedJson).setResponseCode(200))

            val result = client.post(mapOf("bld" to "test"))
            assertEquals(expectedJson, result)
            assertEquals(2, mockServer.requestCount)
        }

    @Test
    fun `post should retry 3 times and throw NetworkError on all failures`() =
        runTest {
            // All 3 attempts fail
            repeat(3) {
                mockServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            }

            val error =
                assertFailsWith<KrxError.NetworkError> {
                    client.post(mapOf("bld" to "test"))
                }
            assertContains(error.message ?: "", "Failed after 3 attempts")
            assertEquals(3, mockServer.requestCount)
        }

    @Test
    fun `post should succeed on third attempt after two failures`() =
        runTest {
            // First two fail, third succeeds
            mockServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            mockServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val expectedJson = """{"result": "ok"}"""
            mockServer.enqueue(MockResponse().setBody(expectedJson).setResponseCode(200))

            val result = client.post(mapOf("bld" to "test"))
            assertEquals(expectedJson, result)
            assertEquals(3, mockServer.requestCount)
        }

    // ====================================================
    // LOGOUT response
    // ====================================================

    @Test
    fun `post should throw AuthenticationError on LOGOUT response`() =
        runTest {
            mockServer.enqueue(MockResponse().setBody("LOGOUT").setResponseCode(200))

            val error =
                assertFailsWith<KrxError.AuthenticationError> {
                    client.post(mapOf("bld" to "test"))
                }
            assertContains(error.message ?: "", "Session expired")
            assertTrue(!client.isLoggedIn())
        }

    @Test
    fun `post should throw AuthenticationError on LOGOUT with whitespace`() =
        runTest {
            mockServer.enqueue(MockResponse().setBody("  LOGOUT  ").setResponseCode(200))

            val error =
                assertFailsWith<KrxError.AuthenticationError> {
                    client.post(mapOf("bld" to "test"))
                }
            assertContains(error.message ?: "", "Session expired")
        }

    @Test
    fun `post should throw AuthenticationError when not logged in`() =
        runTest {
            client.setLoggedInForTest(false)

            val error =
                assertFailsWith<KrxError.AuthenticationError> {
                    client.post(mapOf("bld" to "test"))
                }
            assertContains(error.message ?: "", "Login required")
        }

    // ====================================================
    // Empty response body
    // ====================================================

    @Test
    fun `post should handle empty string response body`() =
        runTest {
            // MockWebServer with no setBody returns empty string, not null
            // An empty JSON-like body should be returned as-is (parser handles it)
            mockServer.enqueue(MockResponse().setBody("").setResponseCode(200))

            val result = client.post(mapOf("bld" to "test"))
            assertEquals("", result)
        }

    // ====================================================
    // HTTP error codes
    // ====================================================

    @Test
    fun `post should throw IOException on HTTP 500 and retry`() =
        runTest {
            repeat(3) {
                mockServer.enqueue(MockResponse().setResponseCode(500))
            }

            val error =
                assertFailsWith<KrxError.NetworkError> {
                    client.post(mapOf("bld" to "test"))
                }
            assertContains(error.message ?: "", "Failed after 3 attempts")
            assertEquals(3, mockServer.requestCount)
        }

    @Test
    fun `post should throw IOException on HTTP 404 and retry`() =
        runTest {
            repeat(3) {
                mockServer.enqueue(MockResponse().setResponseCode(404))
            }

            val error =
                assertFailsWith<KrxError.NetworkError> {
                    client.post(mapOf("bld" to "test"))
                }
            assertContains(error.message ?: "", "Failed after 3 attempts")
        }

    @Test
    fun `post should recover from HTTP 500 on retry`() =
        runTest {
            mockServer.enqueue(MockResponse().setResponseCode(500))
            val expectedJson = """{"result": "ok"}"""
            mockServer.enqueue(MockResponse().setBody(expectedJson).setResponseCode(200))

            val result = client.post(mapOf("bld" to "test"))
            assertEquals(expectedJson, result)
            assertEquals(2, mockServer.requestCount)
        }

    // ====================================================
    // KrxError types
    // ====================================================

    @Test
    fun `KrxError NetworkError should be retriable`() {
        val error = KrxError.NetworkError("Connection failed")
        assertTrue(error.isRetriable())
    }

    @Test
    fun `KrxError ParseError should not be retriable`() {
        val error = KrxError.ParseError("Invalid JSON")
        assertTrue(!error.isRetriable())
    }

    @Test
    fun `KrxError InvalidDateError should not be retriable`() {
        val error = KrxError.InvalidDateError("invalid-date")
        assertTrue(!error.isRetriable())
        assertEquals("invalid-date", error.date)
    }

    @Test
    fun `KrxError NetworkError should preserve cause`() {
        val cause = java.io.IOException("timeout")
        val error = KrxError.NetworkError("Connection failed", cause)
        assertEquals(cause, error.cause)
    }

    // ====================================================
    // KrxEndpoints
    // ====================================================

    @Test
    fun `KrxEndpoints should use HTTPS by default`() {
        assertEquals(
            "https://data.krx.co.kr/comm/bldAttendant/getJsonData.cmd",
            KrxEndpoints.BASE_URL,
        )
        assertTrue(KrxEndpoints.BASE_URL.startsWith("https://"))
    }

    @Test
    fun `KrxEndpoints Bld should have correct values`() {
        assertEquals(
            "dbms/MDC/STAT/standard/MDCSTAT01501",
            KrxEndpoints.Bld.STOCK_OHLCV_ALL,
        )
    }

    // ====================================================
    // initSession (MT1-01f coverage — sessionInitUrl is already test-overridable)
    // ====================================================

    private fun sessionTestClient(): KrxClient =
        KrxClient(
            baseUrl = mockServer.url("/").toString(),
            sessionInitUrl = mockServer.url("/init").toString(),
        )

    @Test
    fun `initSession should mark session initialized on success`() {
        mockServer.enqueue(MockResponse().setBody("<html></html>").setResponseCode(200))
        val freshClient = sessionTestClient()

        freshClient.initSession()

        val request = mockServer.takeRequest()
        assertEquals("GET", request.method)
        freshClient.close()
    }

    @Test
    fun `initSession should be a no-op on second call`() {
        mockServer.enqueue(MockResponse().setBody("<html></html>").setResponseCode(200))
        val freshClient = sessionTestClient()

        freshClient.initSession()
        freshClient.initSession() // second call must not issue a second request

        assertEquals(1, mockServer.requestCount)
        freshClient.close()
    }

    @Test
    fun `initSession should swallow IOException`() {
        mockServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val freshClient = sessionTestClient()

        freshClient.initSession() // must not throw

        freshClient.close()
    }

    // ====================================================
    // login / postLogin (MT1-01f: loginPageUrl/loginJspUrl/loginUrl parameterized for testability,
    // same pattern as baseUrl/sessionInitUrl — PROVENANCE.md §3.1)
    // ====================================================

    private fun loginTestClient(): KrxClient =
        KrxClient(
            baseUrl = mockServer.url("/base").toString(),
            loginPageUrl = mockServer.url("/page").toString(),
            loginJspUrl = mockServer.url("/jsp").toString(),
            loginUrl = mockServer.url("/login").toString(),
        )

    @Test
    fun `login should succeed on CD001`() {
        mockServer.enqueue(MockResponse().setBody("<html></html>").setResponseCode(200)) // page
        mockServer.enqueue(MockResponse().setBody("<html></html>").setResponseCode(200)) // jsp
        mockServer.enqueue(MockResponse().setBody("""{"_error_code": "CD001"}""").setResponseCode(200)) // login
        val freshClient = loginTestClient()

        val result = freshClient.login("id", "pw")

        assertTrue(result)
        assertTrue(freshClient.isLoggedIn())
        assertEquals(3, mockServer.requestCount)
        freshClient.close()
    }

    @Test
    fun `login should retry with skipDup on CD011 then succeed`() {
        mockServer.enqueue(MockResponse().setBody("<html></html>").setResponseCode(200)) // page
        mockServer.enqueue(MockResponse().setBody("<html></html>").setResponseCode(200)) // jsp
        mockServer.enqueue(MockResponse().setBody("""{"_error_code": "CD011"}""").setResponseCode(200)) // dup
        mockServer.enqueue(MockResponse().setBody("""{"_error_code": "CD001"}""").setResponseCode(200)) // retry
        val freshClient = loginTestClient()

        val result = freshClient.login("id", "pw")

        assertTrue(result)
        assertEquals(4, mockServer.requestCount)
        mockServer.takeRequest()
        mockServer.takeRequest()
        mockServer.takeRequest() // first POST, no skipDup
        val retryRequest = mockServer.takeRequest()
        val decoded = URLDecoder.decode(retryRequest.body.readUtf8(), "UTF-8")
        assertContains(decoded, "skipDup=Y")
        freshClient.close()
    }

    @Test
    fun `login should return false on unrecognized error code`() {
        mockServer.enqueue(MockResponse().setBody("<html></html>").setResponseCode(200)) // page
        mockServer.enqueue(MockResponse().setBody("<html></html>").setResponseCode(200)) // jsp
        mockServer.enqueue(MockResponse().setBody("""{"_error_code": "CD999"}""").setResponseCode(200)) // bad creds
        val freshClient = loginTestClient()

        val result = freshClient.login("id", "wrong-pw")

        assertTrue(!result)
        assertTrue(!freshClient.isLoggedIn())
        freshClient.close()
    }

    @Test
    fun `login should throw NetworkError when login page GET fails`() {
        mockServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val freshClient = loginTestClient()

        val error = assertFailsWith<KrxError.NetworkError> { freshClient.login("id", "pw") }
        assertContains(error.message ?: "", "Failed to load login page")
        freshClient.close()
    }

    @Test
    fun `login should throw NetworkError when login jsp GET fails`() {
        mockServer.enqueue(MockResponse().setBody("<html></html>").setResponseCode(200)) // page
        mockServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) // jsp
        val freshClient = loginTestClient()

        val error = assertFailsWith<KrxError.NetworkError> { freshClient.login("id", "pw") }
        assertContains(error.message ?: "", "Failed to load login JSP")
        freshClient.close()
    }

    @Test
    fun `login should throw NetworkError when login POST fails`() {
        mockServer.enqueue(MockResponse().setBody("<html></html>").setResponseCode(200)) // page
        mockServer.enqueue(MockResponse().setBody("<html></html>").setResponseCode(200)) // jsp
        mockServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) // login
        val freshClient = loginTestClient()

        val error = assertFailsWith<KrxError.NetworkError> { freshClient.login("id", "pw") }
        assertContains(error.message ?: "", "Login request failed")
        freshClient.close()
    }

    @Test
    fun `login should throw ParseError on empty login response body`() {
        // MockWebServer/OkHttp never yields a null ResponseBody for a normal HTTP response
        // (mirrors "post should handle empty string response body" above) — an empty body
        // reaches JsonParser as "" and fails JSON parsing, not the body==null NetworkError
        // branch. That branch is defensive/unreachable via real HTTP, same category as
        // executeRequest's body==null check.
        mockServer.enqueue(MockResponse().setBody("<html></html>").setResponseCode(200)) // page
        mockServer.enqueue(MockResponse().setBody("<html></html>").setResponseCode(200)) // jsp
        mockServer.enqueue(MockResponse().setBody("").setResponseCode(200)) // login, empty
        val freshClient = loginTestClient()

        val error = assertFailsWith<KrxError.ParseError> { freshClient.login("id", "pw") }
        assertContains(error.message ?: "", "Failed to parse login response")
        freshClient.close()
    }

    @Test
    fun `login should throw ParseError on malformed login response`() {
        mockServer.enqueue(MockResponse().setBody("<html></html>").setResponseCode(200)) // page
        mockServer.enqueue(MockResponse().setBody("<html></html>").setResponseCode(200)) // jsp
        mockServer.enqueue(MockResponse().setBody("not-json").setResponseCode(200)) // login, malformed
        val freshClient = loginTestClient()

        val error = assertFailsWith<KrxError.ParseError> { freshClient.login("id", "pw") }
        assertContains(error.message ?: "", "Failed to parse login response")
        freshClient.close()
    }

    // ====================================================
    // InMemoryCookieJar (MT1-01f coverage — no HTTP needed, pure in-memory logic)
    // ====================================================

    @Test
    fun `InMemoryCookieJar should return cookies matching the request url`() {
        val jar = InMemoryCookieJar()
        val url = mockServer.url("/any")
        val cookie =
            Cookie.Builder()
                .name("JSESSIONID")
                .value("abc123")
                .domain(url.host)
                .build()

        jar.saveFromResponse(url, listOf(cookie))
        val loaded = jar.loadForRequest(url)

        assertEquals(1, loaded.size)
        assertEquals("abc123", loaded.first().value)
    }

    @Test
    fun `InMemoryCookieJar should replace a cookie with the same name and domain`() {
        val jar = InMemoryCookieJar()
        val url = mockServer.url("/any")
        val original = Cookie.Builder().name("JSESSIONID").value("old").domain(url.host).build()
        val refreshed = Cookie.Builder().name("JSESSIONID").value("new").domain(url.host).build()

        jar.saveFromResponse(url, listOf(original))
        jar.saveFromResponse(url, listOf(refreshed))
        val loaded = jar.loadForRequest(url)

        assertEquals(1, loaded.size)
        assertEquals("new", loaded.first().value)
    }

    @Test
    fun `InMemoryCookieJar should evict expired cookies on load`() {
        val jar = InMemoryCookieJar()
        val url = mockServer.url("/any")
        val expired =
            Cookie.Builder()
                .name("JSESSIONID")
                .value("stale")
                .domain(url.host)
                .expiresAt(System.currentTimeMillis() - 1000L)
                .build()

        jar.saveFromResponse(url, listOf(expired))
        val loaded = jar.loadForRequest(url)

        assertTrue(loaded.isEmpty())
    }
}
