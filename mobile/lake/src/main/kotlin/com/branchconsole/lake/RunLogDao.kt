package com.branchconsole.lake

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * `run_log` DAO — 실행 이력 기록·조회(M-22). append-only 물리 강제(트리거) 대상에서 제외되지만,
 * 이 인터페이스 자체는 다른 DAO와 동일하게 `@Update`/`@Delete`를 두지 않는다 — purge는 후속
 * 서브태스크가 별도 메커니즘으로 구현한다.
 */
@Dao
interface RunLogDao {
    @Insert
    suspend fun insert(entry: RunLogEntity): Long

    /** MT1-06f 실행 이력 노출(K-15) — `ran_at` 오름차순. 이전 판은 "조회는 후속 서브태스크에서
     * 필요에 따라 추가"로 미뤄뒀고, 이 서브태스크(확정 틱 파이프라인)가 그 필요를 만든다. */
    @Query("SELECT * FROM run_log ORDER BY ran_at ASC")
    suspend fun allOrderedByRanAt(): List<RunLogEntity>
}
