package com.branchconsole.app.preview

import com.lemonappdev.konsist.api.Konsist
import org.junit.Test

/**
 * TASK MT1-07 완료 기준 ② — "확정 틱 경로에서 carry-forward 호출 불가"의 아키텍처 증거화
 * (docs/plans/M1_PLAN_A.md §2.6 AT-9/AT-10, M1_PLAN_C.md §4.5 "carry-forward 격리"와 동형).
 * `com.branchconsole.app.tick`(확정 틱·캐치업, [com.branchconsole.app.tick.ConfirmTickRunner])
 * 어디에도 `com.branchconsole.app.preview` 패키지에 대한 참조가 0건이어야 한다. 이 테스트는
 * `app.tick` 패키지 파일을 전혀 편집하지 않고(병렬 워커 작업 중) 그 파일들을 **읽기만** 해서
 * 검증한다 — 회귀가 생기면 tick 쪽 변경이 원인이지 이 테스트의 소관이 아니다.
 */
class PreviewArchitectureTest {
    private fun tickModuleFiles() =
        Konsist.scopeFromModule("app")
            .files
            .filter { it.path.replace('\\', '/').contains("/main/kotlin/com/branchconsole/app/tick/") }

    @Test
    fun `confirm tick package never imports the preview package`() {
        val offenders =
            tickModuleFiles()
                .filter { file -> file.imports.any { it.name.startsWith("com.branchconsole.app.preview") } }
        check(offenders.isEmpty()) {
            "com.branchconsole.app.preview import found in app/tick (confirm path must never " +
                "reach carry-forward): ${offenders.map { it.path }}"
        }
    }

    /**
     * import 없이 완전 정규화 이름(`com.branchconsole.app.preview.Xxx`)으로 참조하는 우회까지
     * 잡는다 — 위 import 검사만으로는 놓칠 수 있는 경로다.
     */
    @Test
    fun `confirm tick package never references preview symbols by fully-qualified name`() {
        val offenders =
            tickModuleFiles()
                .filter { file -> file.text.contains("com.branchconsole.app.preview.") }
        check(offenders.isEmpty()) {
            "fully-qualified com.branchconsole.app.preview reference found in app/tick: " +
                offenders.map { it.path }
        }
    }

    /**
     * [CarryForwardResolver]의 타입 수준 격리(AD-A5) — 생성자가 `TickInputDao` 이외의 원장
     * 쓰기·조회 인터페이스를 받지 않는지 **import**로 확인한다(이월 값을 원장에 쓸 수 있는
     * 참조 자체가 없다는 것을 코드로 증거화). KDoc 설명문이 "ObservationDao"라는 단어 자체를
     * 언급하므로(왜 안 쓰는지 설명하기 위해) 원시 텍스트 검색은 자기 자신의 주석에 걸려
     * 오탐한다 — import 목록만 본다.
     */
    @Test
    fun `CarryForwardResolver has no import of ObservationDao`() {
        val file =
            Konsist.scopeFromModule("app")
                .files
                .first { it.path.replace('\\', '/').endsWith("app/preview/CarryForwardResolver.kt") }
        check(file.imports.none { it.name == "com.branchconsole.lake.ObservationDao" }) {
            "CarryForwardResolver must not import ObservationDao (type-level isolation, AD-A5)"
        }
    }
}
