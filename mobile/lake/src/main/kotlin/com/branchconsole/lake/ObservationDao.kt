package com.branchconsole.lake

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Stage 1 조회 결과 — as_of 오름차순 (as_of, value) 시계열 원소 하나. */
data class SeriesPoint(val asOf: Long, val value: Double)

/**
 * `observation` 원장 DAO — **insert 계열만 노출**(M-43b 전수표, docs/plans/M1_PLAN_A.md
 * §2.12 (b-0)). `@Update`/`@Delete` 메서드는 이 인터페이스는 물론 모듈 어디에도 없다
 * ([LakeArchitectureTest]가 컴파일 시점 부재를 증거화, 1차 방어). 원시 관측 조회는 아래
 * [confirmSeries]/[previewSeries] 2개로만 노출되므로 호출부가 `lane` 조건을 빠뜨릴 경로가
 * 없다(AT-10).
 */
@Dao
interface ObservationDao {
    @Insert
    suspend fun insert(observation: ObservationEntity): Long

    /**
     * 읽기 지점 ① — 확정 틱 Stage 1 (M1_PLAN_D.md §2.2.2 SQL 리터럴,
     * docs/journal/2026-08-07_MT1-00f_sqlite_plan.md §3 인덱스 플랜 실측).
     * `lane = 0` 하드 필터(프리뷰 행 배제, D-17 §3) + 셀당 최신 revision(상관 서브쿼리) +
     * as_of 오름차순. `to`(cutoff) 밖의 미래 행은 반환하지 않는다.
     */
    @Query(
        """
        SELECT o.as_of AS asOf, o.value AS value
          FROM observation o
         WHERE o.series_id = :seriesId AND o.field = :field
           AND o.as_of BETWEEN :fromAsOf AND :toAsOf
           AND o.lane = 0
           AND o.id = (
             SELECT o2.id FROM observation o2
              WHERE o2.series_id = o.series_id AND o2.field = o.field
                AND o2.as_of = o.as_of AND o2.lane = 0
              ORDER BY o2.revision DESC, o2.id DESC LIMIT 1
           )
         ORDER BY o.as_of ASC
        """,
    )
    suspend fun confirmSeries(
        seriesId: String,
        field: String,
        fromAsOf: Long,
        toAsOf: Long,
    ): List<SeriesPoint>

    /**
     * 읽기 지점 ② — 프리뷰 신선분 (M1_PLAN_A.md §2.12 (b) 쿼리 ②, 반려 A-15 정정 반영).
     * `lane IN (0, 1)`이되 동일 `as_of`에서는 `lane ASC`(확정 우선) → `revision DESC`.
     * 같은 as_of에서 종가(확정)는 장중 부분봉(프리뷰)보다 항상 우월하다는 tie-break를
     * `lane DESC`로 뒤집으면 [LakeDatabaseTest]의 퇴화 입력 증인이 실패한다.
     */
    @Query(
        """
        SELECT o.as_of AS asOf, o.value AS value
          FROM observation o
         WHERE o.series_id = :seriesId AND o.field = :field
           AND o.as_of BETWEEN :fromAsOf AND :toAsOf
           AND o.lane IN (0, 1)
           AND o.id = (
             SELECT o2.id FROM observation o2
              WHERE o2.series_id = o.series_id AND o2.field = o.field
                AND o2.as_of = o.as_of AND o2.lane IN (0, 1)
              ORDER BY o2.lane ASC, o2.revision DESC, o2.id DESC LIMIT 1
           )
         ORDER BY o.as_of ASC
        """,
    )
    suspend fun previewSeries(
        seriesId: String,
        field: String,
        fromAsOf: Long,
        toAsOf: Long,
    ): List<SeriesPoint>

    /**
     * MT1-08b 프리뷰 갱신 배선 — 같은 (series_id, field, as_of, lane) 셀을 하루 안에 여러 번
     * 다시 수집할 때(사용자가 "프리뷰 갱신"을 반복 탭) 다음 `revision`을 계산하기 위한 조회.
     * `ux_obs_cell_rev` UNIQUE(§ObservationEntity)가 같은 revision의 재삽입만 막으므로,
     * 값이 갱신된 재수집은 revision을 올려 새 행으로 append해야 한다(append-only 유지 —
     * 갱신이 아니라 새 revision 추가, `previewSeries`/`confirmSeries`가 `revision DESC`로
     * 최신을 고른다). null이면 그 셀이 아직 없다는 뜻(0부터 시작).
     */
    @Query(
        """
        SELECT MAX(revision) FROM observation
         WHERE series_id = :seriesId AND field = :field AND as_of = :asOf AND lane = :lane
        """,
    )
    suspend fun maxRevision(
        seriesId: String,
        field: String,
        asOf: Long,
        lane: Int,
    ): Int?
}
