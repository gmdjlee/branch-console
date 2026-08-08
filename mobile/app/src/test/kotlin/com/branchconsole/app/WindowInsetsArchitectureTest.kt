package com.branchconsole.app

import com.lemonappdev.konsist.api.Konsist
import org.junit.Test

/**
 * MT1-08b 결함 수정 회귀 가드 — compileSdk/targetSdk 36의 edge-to-edge 강제로 루트 Column이
 * 상태바 뒤에 그려져 탭 행(홈/이력/설정)이 눌리지 않던 결함(실기기 SM-F966N 실측: 상태바
 * [0,0][1968,89] 프레임에 탭 터치영역이 가려짐, y<=100 터치가 앱에 도달하지 않음)의 수정을
 * 지킨다. Robolectric은 실제 윈도우 인셋을 재현하지 못해(인셋 값이 항상 0) 인셋 적용 여부를
 * 런타임 동작으로 구분하는 테스트는 무의미하다.
 *
 * PreviewArchitectureTest.kt는 원시 텍스트 검색의 오탐(주석의 자기 언급)을 피하려고 정확히
 * **import 목록만** 검사하는 선례다 — 이 테스트는 그 반대 사유로 import를 쓰지 않는다: import는
 * 호출이 파일 어딘가에 존재한다는 것만 보증하고 **어디에 적용됐는지**는 말해주지 않는다
 * (safeDrawingPadding을 루트 Column이 아니라 리프 Text로 옮겨도 import는 그대로 남는다 — aaa
 * 뮤턴트 C 실증). 그래서 주석 제거 텍스트에서 귀속 위치까지 고정한 전체 체인
 * `Modifier.fillMaxSize().safeDrawingPadding()`이 존재하는지를 단언한다.
 */
class WindowInsetsArchitectureTest {
    /**
     * qa-verifier 뮤테이션 실증(2026-08-08) — `file.text`에 KDoc 주석까지 포함되어 있어, 주석에
     * 결함 설명으로 적어둔 식별자 문자열 자체가 코드 본문의 모디파이어 제거를 가려버렸다. 라인/
     * 블록 주석을 제거한 코드 본문만 검사해 재발을 막는다.
     */
    private fun String.withoutComments(): String =
        replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//.*"), "")

    @Test
    fun `MainActivity root column applies safeDrawingPadding`() {
        val file =
            Konsist.scopeFromModule("app")
                .files
                .first { it.path.replace('\\', '/').endsWith("app/MainActivity.kt") }
        check(file.text.withoutComments().contains("Modifier.fillMaxSize().safeDrawingPadding()")) {
            "MainActivity root Column must apply Modifier.fillMaxSize().safeDrawingPadding() " +
                "(edge-to-edge tap-through fix; the chain must stay anchored on the root Column, " +
                "not a descendant leaf)"
        }
    }
}
