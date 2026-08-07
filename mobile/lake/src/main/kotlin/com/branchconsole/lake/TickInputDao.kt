package com.branchconsole.lake

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * `tick_input` DAO — **insert 계열만 노출**(append-only, AT-9: 쓰는 코드 경로는 확정 틱 하나뿐).
 */
@Dao
interface TickInputDao {
    @Insert
    suspend fun insert(tick: TickInputEntity): Long

    /** fold(§2.7)가 매 확정 틱마다 통째로 재생하는 동결 시퀀스 — trading_date 오름차순. */
    @Query("SELECT * FROM tick_input ORDER BY trading_date ASC")
    suspend fun allOrderedByDate(): List<TickInputEntity>

    /**
     * 읽기 지점 ③ — carry-forward 이월 원천(★라운드6 전환, docs/plans/M1_PLAN_A.md §2.12
     * (b-0) 표 ③, M-43b-i). `WHERE composite IS NOT NULL`로 평가 불능·공백 틱(D-25 §3,
     * M-34 동결 행)을 건너뛴다 — "직전 확정값"은 마지막으로 *평가된* 확정 틱 1건이다.
     * 0행이면 null(설치 직후 — carry-forward 미수행, M-50).
     */
    @Query(
        "SELECT * FROM tick_input WHERE composite IS NOT NULL ORDER BY trading_date DESC LIMIT 1",
    )
    suspend fun lastCommittedSeverities(): TickInputEntity?
}
