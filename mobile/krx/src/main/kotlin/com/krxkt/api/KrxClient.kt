package com.krxkt.api

import com.google.gson.JsonParser
import com.krxkt.error.KrxError
import kotlinx.coroutines.delay
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * KRX API HTTP 클라이언트
 *
 * 특징:
 * - OkHttp 기반 POST 요청
 * - application/x-www-form-urlencoded 인코딩
 * - 필수 Referer 헤더 포함
 * - Exponential backoff 재시도 (3회: 1s/2s/4s)
 * - 쿠키 자동 관리 (세션 유지)
 * - 세션 초기화로 JSESSIONID 획득
 *
 * Referer 전략:
 * - 일반 통계 (MDCSTAT00301 등): outerLoader Referer → 세션 불필요
 * - 파생상품 (MDCSTAT01201, MDCSTAT13102): mdiLoader Referer → 세션 필요
 *
 * @param okHttpClient 커스텀 OkHttpClient (테스트용)
 * @param baseUrl 기본 API URL (테스트/프록시용)
 * @param sessionInitUrl 세션 초기화 URL (테스트용)
 * @param maxRetries 요청 실패 시 재시도 횟수 (MT1-01g 파라미터화 — 실제 값 배선은 어댑터
 *   계층 SSOT 로드가 담당, MT1-04c 소관. 기본값은 upstream 원값 그대로 유지)
 * @param retryDelaysMs 재시도별 backoff 지연(ms), 크기는 [maxRetries]와 일치해야 함
 */
class KrxClient(
    private val okHttpClient: OkHttpClient = createDefaultClient(),
    private val baseUrl: String = KrxEndpoints.BASE_URL,
    private val sessionInitUrl: String = KrxEndpoints.SESSION_INIT_URL,
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
) {
    @Volatile
    private var sessionInitialized = false

    @Volatile
    private var loggedIn = false

    companion object {
        /** 기본 재시도 횟수 (upstream 원값). 호출 간격·재시도 정책의 실제 SSOT 배선은 MT1-04c. */
        const val DEFAULT_MAX_RETRIES = 3
        val DEFAULT_RETRY_DELAYS_MS = listOf(1000L, 2000L, 4000L)

        /** Maximum response body size: 50MB (KRX full-market responses can be large) */
        private const val MAX_RESPONSE_SIZE_BYTES = 50L * 1024 * 1024

        /**
         * 기본 OkHttpClient 생성
         * - 연결 타임아웃: 30초
         * - 읽기 타임아웃: 30초
         * - 쓰기 타임아웃: 30초
         * - InMemoryCookieJar로 세션 쿠키 관리
         */
        fun createDefaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .cookieJar(InMemoryCookieJar())
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }

    /**
     * KRX 세션 초기화
     *
     * KRX 메인 페이지를 GET 요청하여 JSESSIONID 쿠키를 획득.
     * 파생상품 통계 등 세션 검증이 엄격한 엔드포인트 호출 전에 사용.
     */
    fun initSession() {
        if (sessionInitialized) return

        val request =
            Request.Builder()
                .url(sessionInitUrl)
                .get()
                .addHeader("User-Agent", KrxEndpoints.USER_AGENT)
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

        try {
            okHttpClient.newCall(request).execute().close()
            sessionInitialized = true
        } catch (_: IOException) {
            // 세션 초기화 실패는 무시 — API 호출 시 재시도됨
        }
    }

    /**
     * KRX data.krx.co.kr 로그인
     *
     * 로그인 후 세션 쿠키(JSESSIONID)가 갱신되어 이후 모든 API 호출에 자동 적용됨.
     * 동일한 KrxClient 인스턴스(OkHttpClient/CookieJar)를 공유하는 KrxStock, KrxEtf,
     * KrxIndex 등 모든 API 객체에서 로그인 세션이 유지됨.
     *
     * 로그인 흐름:
     *   1. GET MDCCOMS001.cmd  → 초기 JSESSIONID 발급
     *   2. GET login.jsp       → iframe 세션 초기화
     *   3. POST MDCCOMS001D1.cmd → 실제 로그인
     *   4. CD011(중복 로그인) → skipDup=Y 추가 후 재전송
     *
     * 사용 예:
     * ```
     * val client = KrxClient()
     * val success = client.login("your_id", "your_pw")
     * if (success) {
     *     val stock = KrxStock(client)
     *     val ohlcv = stock.getMarketOhlcv("20260227")
     * }
     * ```
     *
     * @param loginId KRX 로그인 ID
     * @param loginPw KRX 로그인 비밀번호
     * @return 로그인 성공 여부 (CD001 = 성공)
     * @throws KrxError.NetworkError 네트워크 에러
     */
    fun login(
        loginId: String,
        loginPw: String,
    ): Boolean {
        // 1. GET 로그인 페이지 → 초기 JSESSIONID 발급
        val pageRequest =
            Request.Builder()
                .url(KrxEndpoints.LOGIN_PAGE)
                .get()
                .addHeader("User-Agent", KrxEndpoints.USER_AGENT)
                .build()

        try {
            okHttpClient.newCall(pageRequest).execute().close()
        } catch (e: IOException) {
            throw KrxError.NetworkError("Failed to load login page", e)
        }

        // 2. GET login.jsp → iframe 세션 초기화
        val jspRequest =
            Request.Builder()
                .url(KrxEndpoints.LOGIN_JSP)
                .get()
                .addHeader("User-Agent", KrxEndpoints.USER_AGENT)
                .addHeader("Referer", KrxEndpoints.LOGIN_PAGE)
                .build()

        try {
            okHttpClient.newCall(jspRequest).execute().close()
        } catch (e: IOException) {
            throw KrxError.NetworkError("Failed to load login JSP", e)
        }

        // 3. POST 로그인
        val headers =
            mapOf(
                "User-Agent" to KrxEndpoints.USER_AGENT,
                "Referer" to KrxEndpoints.LOGIN_PAGE,
            )

        var errorCode = postLogin(loginId, loginPw, headers, skipDup = false)

        // 4. CD011 중복 로그인 처리
        if (errorCode == "CD011") {
            errorCode = postLogin(loginId, loginPw, headers, skipDup = true)
        }

        if (errorCode == "CD001") {
            sessionInitialized = true
            loggedIn = true
            return true
        }

        return false
    }

    /**
     * 로그인 상태 확인
     */
    fun isLoggedIn(): Boolean = loggedIn

    /**
     * 테스트용: 로그인 상태 강제 설정
     *
     * MockWebServer 테스트에서 로그인 과정을 건너뛰기 위해 사용.
     * 프로덕션 코드에서는 login()을 사용할 것.
     */
    internal fun setLoggedInForTest(value: Boolean) {
        loggedIn = value
        sessionInitialized = value
    }

    /**
     * 로그인 POST 실행 (내부용)
     *
     * @return _error_code 값
     */
    private fun postLogin(
        loginId: String,
        loginPw: String,
        headers: Map<String, String>,
        skipDup: Boolean,
    ): String {
        val formBuilder =
            FormBody.Builder()
                .add("mbrNm", "")
                .add("telNo", "")
                .add("di", "")
                .add("certType", "")
                .add("mbrId", loginId)
                .add("pw", loginPw)

        if (skipDup) {
            formBuilder.add("skipDup", "Y")
        }

        val requestBuilder =
            Request.Builder()
                .url(KrxEndpoints.LOGIN_URL)
                .post(formBuilder.build())

        headers.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }

        val response =
            try {
                okHttpClient.newCall(requestBuilder.build()).execute()
            } catch (e: IOException) {
                throw KrxError.NetworkError("Login request failed", e)
            }

        return response.use { resp ->
            val body = resp.body?.string() ?: throw KrxError.NetworkError("Empty login response")
            try {
                val json = JsonParser.parseString(body).asJsonObject
                json.get("_error_code")?.asString ?: ""
            } catch (e: Exception) {
                throw KrxError.ParseError("Failed to parse login response: ${body.take(200)}", e)
            }
        }
    }

    /**
     * KRX API POST 요청 실행 (기본 Referer)
     *
     * @param params 요청 파라미터 (bld, mktId, trdDd 등)
     * @return JSON 응답 문자열
     * @throws KrxError.NetworkError 네트워크 에러 (재시도 후에도 실패)
     */
    suspend fun post(params: Map<String, String>): String {
        return post(params, KrxEndpoints.REFERER)
    }

    /**
     * KRX API POST 요청 실행 (커스텀 Referer)
     *
     * 파생상품 통계처럼 mdiLoader Referer가 필요한 엔드포인트에서 사용.
     *
     * @param params 요청 파라미터
     * @param referer 커스텀 Referer URL
     * @return JSON 응답 문자열
     * @throws KrxError.NetworkError 네트워크 에러
     */
    suspend fun post(
        params: Map<String, String>,
        referer: String,
    ): String {
        if (!loggedIn) {
            throw KrxError.AuthenticationError()
        }

        var lastException: Exception? = null

        repeat(maxRetries) { attempt ->
            try {
                return executeRequest(params, referer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                lastException = e
                if (e.message?.contains("LOGOUT") == true) {
                    loggedIn = false
                    sessionInitialized = false
                    throw KrxError.AuthenticationError("Session expired (LOGOUT). Please call login() again.")
                }
                if (attempt < maxRetries - 1) {
                    delay(retryDelaysMs[attempt])
                }
            }
        }

        throw KrxError.NetworkError(
            "Failed after $maxRetries attempts: ${lastException?.message}",
            lastException,
        )
    }

    /**
     * 실제 HTTP 요청 실행
     */
    private fun executeRequest(
        params: Map<String, String>,
        referer: String,
    ): String {
        val formBody =
            FormBody.Builder().apply {
                params.forEach { (key, value) ->
                    add(key, value)
                }
            }.build()

        val bld = params["bld"] ?: "unknown"

        val request =
            Request.Builder()
                .url(baseUrl)
                .post(formBody)
                .addHeader("Referer", referer)
                .addHeader("User-Agent", KrxEndpoints.USER_AGENT)
                .addHeader("Accept", "application/json, text/javascript, */*; q=0.01")
                .addHeader("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                .addHeader("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .addHeader("Origin", "https://data.krx.co.kr")
                .build()

        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string()

            // LOGOUT 감지: 200 또는 400 모두에서 발생 가능
            if (body?.trim() == "LOGOUT") {
                throw IOException("KRX session expired or access denied (LOGOUT). Try from Korean network or use VPN.")
            }

            if (!response.isSuccessful) {
                val errorSnippet = body?.take(500)
                throw IOException("Unexpected response code: ${response.code} for bld=$bld, body=$errorSnippet")
            }

            if (body == null) {
                throw IOException("Empty response body")
            }

            if (body.length > MAX_RESPONSE_SIZE_BYTES) {
                throw IOException("Response too large: ${body.length} bytes exceeds limit of $MAX_RESPONSE_SIZE_BYTES bytes")
            }

            return body
        }
    }

    /**
     * 리소스 정리 (OkHttpClient 연결 풀)
     */
    fun close() {
        okHttpClient.dispatcher.executorService.shutdown()
        okHttpClient.connectionPool.evictAll()
    }
}

/**
 * 메모리 기반 쿠키 저장소
 *
 * KRX 세션 쿠키(JSESSIONID)를 자동 관리.
 * requests.Session()의 쿠키 관리와 동일한 역할.
 *
 * 스레드 안전: synchronized(lock) 으로 동시 접근 보호.
 * OkHttp는 여러 스레드에서 CookieJar를 호출할 수 있으므로 필수.
 */
class InMemoryCookieJar : CookieJar {
    private val lock = Any()
    private val store = mutableListOf<Cookie>()

    override fun saveFromResponse(
        url: HttpUrl,
        cookies: List<Cookie>,
    ) {
        synchronized(lock) {
            cookies.forEach { newCookie ->
                store.removeAll { it.name == newCookie.name && it.domain == newCookie.domain }
            }
            store.addAll(cookies)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            store.removeAll { it.expiresAt < now }
            return store.filter { it.matches(url) }.toList() // 방어적 복사
        }
    }
}
