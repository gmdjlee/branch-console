package com.branchconsole.app.collectors.ecos

/**
 * ECOS API 키 주입 경계 (K-17), [com.branchconsole.app.collectors.fred.FredCredentialsProvider]와
 * 동일한 판단 — 이 인터페이스는 키를 어디에 어떻게 저장하는지 전혀 모른다. [EcosObservationsCollector]는
 * 키 값을 로그·예외 메시지에 절대 포함하지 않는다(00b 저널 §7.5 — authkey가 URL **경로**
 * 세그먼트에 들어가므로 요청 URL 전체를 로그·예외에 남기지 않도록 특히 주의).
 */
fun interface EcosCredentialsProvider {
    fun apiKey(): String
}
