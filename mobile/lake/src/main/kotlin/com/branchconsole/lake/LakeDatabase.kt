package com.branchconsole.lake

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Append-only 원장 (docs/plans/M1_PLAN_D.md §2.1·§2.2.2, M1_PLAN_A.md §2.12 (b-0),
 * M1_PLAN_FINAL.md §1.1~1.2 M-43·M-49·M-43b·M-34·M-22).
 *
 * 이중 방어: 1차(컴파일) — `observation`·`tick_input` DAO에 `@Update`/`@Delete` 메서드가
 * 물리적으로 없다. 2차(런타임) — [AppendOnlyGuard]가 `BEFORE UPDATE`/`BEFORE DELETE` 트리거를
 * 심어 원시 SQL조차 `RAISE(ABORT, …)`로 차단한다(Android에서 `SQLiteConstraintException`으로
 * 전파). `run_log`는 M-22에 따라 이 가드에서 제외한다(수명 분리 — 180일 purge 허용).
 */
@Database(
    entities = [ObservationEntity::class, TickInputEntity::class, RunLogEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class LakeDatabase : RoomDatabase() {
    abstract fun observationDao(): ObservationDao

    abstract fun tickInputDao(): TickInputDao

    abstract fun runLogDao(): RunLogDao

    companion object {
        private const val DB_NAME = "branch-console-lake.db"

        fun build(context: Context): LakeDatabase =
            Room.databaseBuilder(context, LakeDatabase::class.java, DB_NAME)
                .addCallback(AppendOnlyGuard)
                .build()

        /** 테스트 전용 — 인메모리, 단일 스레드(Robolectric) 동기 호출 허용. */
        fun buildInMemory(context: Context): LakeDatabase =
            Room.inMemoryDatabaseBuilder(context, LakeDatabase::class.java)
                .addCallback(AppendOnlyGuard)
                .allowMainThreadQueries()
                .build()
    }
}

/** 물리 강제 대상 — `run_log`는 제외(M-22, 수명 분리). */
private val APPEND_ONLY_TABLES = listOf("observation", "tick_input")

private object AppendOnlyGuard : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        APPEND_ONLY_TABLES.forEach { table ->
            db.execSQL(
                "CREATE TRIGGER trg_${table}_no_update BEFORE UPDATE ON $table " +
                    "BEGIN SELECT RAISE(ABORT, '$table is append-only: UPDATE forbidden'); END;",
            )
            db.execSQL(
                "CREATE TRIGGER trg_${table}_no_delete BEFORE DELETE ON $table " +
                    "BEGIN SELECT RAISE(ABORT, '$table is append-only: DELETE forbidden'); END;",
            )
        }
    }
}
