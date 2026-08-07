package com.branchconsole.lake

import androidx.room.Dao
import androidx.room.Insert

/**
 * `run_log` DAO — 실행 이력 기록만 담당(M-22, 조회는 후속 서브태스크에서 필요에 따라 추가).
 * append-only 물리 강제(트리거) 대상에서 제외되지만, 이 인터페이스 자체는 다른 DAO와 동일하게
 * `@Update`/`@Delete`를 두지 않는다 — purge는 후속 서브태스크가 별도 메커니즘으로 구현한다.
 */
@Dao
interface RunLogDao {
    @Insert
    suspend fun insert(entry: RunLogEntity): Long
}
