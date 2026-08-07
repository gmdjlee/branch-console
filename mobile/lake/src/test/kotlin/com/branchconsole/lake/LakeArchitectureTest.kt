package com.branchconsole.lake

import androidx.room.Delete
import androidx.room.Update
import com.lemonappdev.konsist.api.Konsist
import org.junit.Test

/**
 * Append-only 1차 방어(컴파일 시점)의 증거화 — docs/plans/M1_PLAN_A.md §2.6 AT-6과 동형:
 * `:lake` 모듈 어디에도 `@Update`/`@Delete` 애노테이션이 존재하지 않는다. 물리 강제(2차 방어,
 * `BEFORE UPDATE`/`BEFORE DELETE` 트리거)는 [LakeDatabaseTest]가 런타임으로 증거화한다.
 */
class LakeArchitectureTest {
    @Test
    fun `no Update or Delete Room annotations exist in the lake module`() {
        val offenders =
            Konsist.scopeFromModule("lake")
                .files
                .flatMap { it.functions(true, true) }
                .filter { it.hasAnnotationOf(Update::class, Delete::class) }
        check(offenders.isEmpty()) {
            "@Update/@Delete found in :lake (append-only violation): " +
                offenders.joinToString { fn -> fn.name }
        }
    }
}
