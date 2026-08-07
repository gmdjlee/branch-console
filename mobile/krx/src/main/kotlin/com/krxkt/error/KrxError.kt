package com.krxkt.error

/**
 * KRX API 에러 타입 정의
 *
 * 네트워크 에러는 재시도 가능, 파싱/날짜 에러는 재시도 불필요
 */
sealed class KrxError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /**
     * 네트워크 관련 에러 (타임아웃, 연결 실패 등)
     * 재시도 대상
     */
    class NetworkError(message: String, cause: Throwable? = null) : KrxError(message, cause)

    /**
     * JSON 파싱 에러
     * 재시도 불필요 (응답 데이터 문제)
     */
    class ParseError(message: String, cause: Throwable? = null) : KrxError(message, cause)

    /**
     * 잘못된 날짜 형식
     * 재시도 불필요 (입력값 문제)
     */
    class InvalidDateError(val date: String) : KrxError("Invalid date format: $date (expected: yyyyMMdd)")

    /**
     * 인증 에러 (로그인 필요)
     * login() 호출 후 재시도 필요
     */
    class AuthenticationError(
        message: String = "Login required. Call KrxClient.login(id, pw) before making API requests.",
    ) : KrxError(message)

    /**
     * 재시도 가능 여부 판단
     * NetworkError만 재시도 대상
     */
    fun isRetriable(): Boolean = this is NetworkError
}
