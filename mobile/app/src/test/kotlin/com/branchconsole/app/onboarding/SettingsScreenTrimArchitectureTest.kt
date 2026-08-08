package com.branchconsole.app.onboarding

import com.lemonappdev.konsist.api.Konsist
import org.junit.Test

/**
 * 실기기 S-0 발견 결함(자격증명 공백 미트림) 회귀 가드 — 검증 경로(KRX/FRED 검증 버튼)가
 * [com.branchconsole.app.credentials.CredentialFields.trimmed] 초크포인트를 계속 거치는지
 * 확인한다. 저장 경로는 [com.branchconsole.app.credentials.CredentialsStore.save] 내부에서
 * 트림하므로 Robolectric으로 직접 실행 검증되지만(CredentialsStoreTest), 검증 버튼은 Compose
 * `onClick` 람다 안에 있어 Compose UI 테스트 의존성 없이는 런타임으로 도달할 수 없다
 * (WindowInsetsArchitectureTest.kt와 동일 사유·동일 해법 — Konsist로 귀속 위치까지 고정한 텍스트
 * 앵커를 검사한다. 단순 `.trimmed()` 존재 여부만 보면 주석 자기 언급에 오탐하므로(qa-verifier
 * 뮤테이션 실증 선례) 실제 호출부 인자 텍스트까지 정확히 앵커한다).
 */
class SettingsScreenTrimArchitectureTest {
    private fun String.withoutComments(): String =
        replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//.*"), "")

    private fun settingsScreenText(): String =
        Konsist.scopeFromModule("app")
            .files
            .first { it.path.replace('\\', '/').endsWith("app/onboarding/SettingsScreen.kt") }
            .text
            .withoutComments()

    @Test
    fun `KRX verify button passes trimmed id and password`() {
        val anchor = "CredentialVerification.verifyKrx(trimmed.krxId.orEmpty(), trimmed.krxPassword.orEmpty())"
        check(settingsScreenText().contains(anchor)) {
            "KRX verify button must call CredentialVerification.verifyKrx with fields.trimmed() output " +
                "(trailing whitespace from paste/autocomplete must not reach the network call)"
        }
    }

    @Test
    fun `FRED verify button passes trimmed key`() {
        val anchor = "CredentialVerification.verifyFred(fields.trimmed().fredApiKey.orEmpty())"
        check(settingsScreenText().contains(anchor)) {
            "FRED verify button must call CredentialVerification.verifyFred with fields.trimmed() output " +
                "(trailing whitespace from paste/autocomplete must not reach the network call)"
        }
    }
}
