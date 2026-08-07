package com.branchconsole.lake

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Lake 원장(ledger)의 원계열 관측 테이블. Append-only — 이 클래스가 속한 모듈에는
 * `@Update`/`@Delete` DAO 메서드가 존재하지 않는다(1차 방어, [LakeArchitectureTest]가 증거화).
 * 물리 강제(2차 방어)는 [LakeDatabase]의 `BEFORE UPDATE/DELETE` 트리거.
 *
 * 참조: docs/plans/M1_PLAN_D.md §2.1 (스키마 SQL 리터럴), M1_PLAN_A.md §2.12 (b-0)
 * (lane 판별자·읽기 지점), M1_PLAN_FINAL.md M-43 (lane 채택).
 *
 * `lane`(0=confirmed, 1=preview)은 `UNIQUE`에 편입돼 있어 같은 셀에서 두 레인이 공존할 수
 * 있고, `revision`은 레인별로 독립 채번된다(같은 as_of에 lane 0 rev 0·1, lane 1 rev 0이
 * 동시에 존재 가능 — [LakeDatabaseTest]의 lane-scoped revision 증인).
 *
 * `ix_obs_scan(series_id, field, lane, as_of)`은 의도적으로 두지 않는다:
 * docs/journal/2026-08-07_MT1-00f_sqlite_plan.md §5 실측에 따르면 확정 스캔은
 * `ux_obs_cell_rev`만으로 이미 풀스캔이 없고(§3 EXPLAIN QUERY PLAN), 별도 인덱스는 이 쿼리
 * 형태(레인이 리터럴로 고정된 두 메서드)에서 ~33% 단축(틱당 ≈11ms)을 주지만 절대 비용이
 * 무의미한 수준이라 저널이 "택1, 강제 아님"으로 명시한 선택지 (a)를 채택한다.
 */
@Entity(
    tableName = "observation",
    indices = [
        Index(
            value = ["series_id", "field", "as_of", "lane", "revision"],
            unique = true,
            name = "ux_obs_cell_rev",
        ),
    ],
)
data class ObservationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "series_id") val seriesId: String,
    @ColumnInfo(name = "field") val field: String,
    @ColumnInfo(name = "as_of") val asOf: Long,
    @ColumnInfo(name = "value") val value: Double,
    @ColumnInfo(name = "observed_at") val observedAt: Long,
    @ColumnInfo(name = "revision") val revision: Int,
    @ColumnInfo(name = "lane") val lane: Int,
    @ColumnInfo(name = "source") val source: String,
)

/** lane 판별자 값 (M-43). 3종 이상으로 늘면 enum으로 승격한다(D §2.12 (a) ponytail 주석). */
object Lane {
    const val CONFIRMED = 0
    const val PREVIEW = 1
}
