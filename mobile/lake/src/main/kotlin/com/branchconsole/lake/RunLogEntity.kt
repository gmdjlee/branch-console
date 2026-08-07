package com.branchconsole.lake

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 실행 이력 — `observation`·`tick_input`과 수명이 다르다(M-22, M1_PLAN_FINAL.md §1.1):
 * 180일 purge 허용 대상이며, append-only 물리 강제(트리거)에서 **의도적으로 제외**한다.
 * purge 구현 자체는 후속 서브태스크 몫이며, 이 테이블은 그 결정을 표현하는 스키마만 지금 둔다.
 */
@Entity(tableName = "run_log")
data class RunLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "trading_date") val tradingDate: String?,
    @ColumnInfo(name = "ran_at") val ranAt: Long,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "detail") val detail: String?,
)
