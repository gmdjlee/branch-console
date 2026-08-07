package com.branchconsole.app.collectors.krx

/**
 * KRX 로그인 자격증명 — 순수 주입 경계.
 *
 * `KrxClient.login(id, pw)`는 평문 문자열 파라미터만 받는다(00c/01g 실측 확인, 벤더 자체에는
 * 환경변수·프로퍼티 파일 직접 접근이 없음). 저장·온보딩(Keystore/EncryptedSharedPreferences,
 * K-17)은 이 서브태스크 범위가 아니다 — 호출부(향후 설정 화면)가 [KrxCredentialsProvider]를
 * 구현해 값을 어디서 읽어올지 책임진다.
 */
data class KrxCredentials(val id: String, val password: String)

fun interface KrxCredentialsProvider {
    /** @throws IllegalStateException 자격증명이 아직 설정되지 않은 경우 */
    fun get(): KrxCredentials
}
