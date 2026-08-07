package com.branchconsole.app.collectors.fred

/**
 * FRED API 키 주입 경계 (K-17). 이 인터페이스는 키를 어디에 어떻게 저장하는지 전혀 모른다 —
 * 실제 저장(Keystore/EncryptedSharedPreferences)과 이 인터페이스의 구현은 후속 서브태스크
 * 소관이다. [FredObservationsCollector]는 키 값을 로그·예외 메시지에 절대 포함하지 않는다.
 */
fun interface FredCredentialsProvider {
    fun apiKey(): String
}
