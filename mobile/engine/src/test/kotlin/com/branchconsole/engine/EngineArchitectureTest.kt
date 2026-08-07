package com.branchconsole.engine

import com.lemonappdev.konsist.api.Konsist
import kotlin.test.Test

/**
 * AT-3 (docs/plans/M1_PLAN_A.md §2.6): `:engine` 소스에 `android.` import가 존재하면 안 된다.
 * 순수 JVM 경계(AD-A1)를 코드로 증거화한다. Konsist 0.17.3 내부 파서(Kotlin 2.0.20 고정)가
 * 이 모듈의 Kotlin 2.1.0 문법을 실제로 파싱할 수 있는지 확인하는 실행 검증도 겸한다
 * (docs/journal/2026-08-07_MT1-00e_toolchain_matrix.md §9.2 리스크).
 */
class EngineArchitectureTest {
    @Test
    fun `engine module does not import android APIs`() {
        Konsist.scopeFromModule("engine")
            .files
            .forEach { file ->
                val androidImports = file.imports.filter { it.name.startsWith("android") }
                check(androidImports.isEmpty()) {
                    "android.* import found in :engine (${file.path}): $androidImports"
                }
            }
    }

    /**
     * K-05 (CLAUDE.md §3): "kotlinx-datetime는 계약 미러 전용" — MT1-05의 pit/statemachine/
     * transforms/config 경로는 `java.time`만 쓴다. `contracts` 패키지(Snapshot/Evidence wire
     * mirror)만 예외다(브리프 지시 그대로).
     */
    @Test
    fun `only the contracts package may import kotlinx-datetime`() {
        val offenders =
            Konsist.scopeFromModule("engine")
                .files
                .filter { !it.path.replace('\\', '/').contains("/contracts/") }
                .filter { file -> file.imports.any { it.name.startsWith("kotlinx.datetime") } }
        check(offenders.isEmpty()) {
            "kotlinx.datetime import found outside :engine.contracts: ${offenders.map { it.path }}"
        }
    }
}
