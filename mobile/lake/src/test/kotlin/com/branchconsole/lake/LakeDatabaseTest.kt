package com.branchconsole.lake

import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * MT1-03 완료 기준 증인 6종 + sqlite_version 1회 로깅
 * (docs/journal/2026-08-07_MT1-00f_sqlite_plan.md §6 제안).
 *
 * ① append-only(런타임 트리거) ② as-of 정렬(cutoff 밖 미반환) ③ 레인 격리
 * ④ tie-break(확정 우선, A-15 퇴화 입력 증인) ⑤ lastCommittedSeverities(gap 건너뜀·0행 null)
 * ⑥ 레인별 revision 채번. 컴파일 시점 append-only 증인(update/delete API 부재)은
 * [LakeArchitectureTest]가 담당한다.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class LakeDatabaseTest {
    private lateinit var db: LakeDatabase

    @Before
    fun setUp() {
        db = LakeDatabase.buildInMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `sqlite version is logged once`() =
        runTest {
            val version =
                db.openHelper.writableDatabase.query("SELECT sqlite_version()").use { cursor ->
                    check(cursor.moveToFirst()) { "sqlite_version() returned no rows" }
                    cursor.getString(0)
                }
            println("lake sqlite_version = $version")
            assertTrue(
                "unexpected sqlite_version format: $version",
                version.matches(Regex("""\d+\.\d+.*""")),
            )
        }

    // build()의 파일 기반(비인메모리) 경로 — buildInMemory()와 별개로 실제 배선 대상.
    @Test
    fun `build creates a usable file-backed database with the append-only guard installed`() =
        runTest {
            val fileDb = LakeDatabase.build(ApplicationProvider.getApplicationContext())
            try {
                fileDb.observationDao().insert(observation(asOf = 1L, value = 1.0))
                assertThrows(SQLiteConstraintException::class.java) {
                    fileDb.openHelper.writableDatabase.execSQL("DELETE FROM observation")
                }
            } finally {
                fileDb.close()
            }
        }

    // run_log — append-only 물리 강제 대상에서 제외되지만(M-22), 삽입 경로는 다른 DAO와 동일하다.
    @Test
    fun `run_log accepts inserts and is not guarded by the append-only trigger`() =
        runTest {
            db.runLogDao().insert(
                RunLogEntity(tradingDate = "2026-08-07", ranAt = 1L, status = "success", detail = null),
            )
            // M-22: run_log은 append-only 트리거 대상이 아니므로 원시 UPDATE가 허용된다(수명 분리 증거).
            db.openHelper.writableDatabase.execSQL("UPDATE run_log SET status = 'retried'")
        }

    // ① append-only — 런타임 트리거 증인(컴파일 시점 증인은 LakeArchitectureTest).
    @Test
    fun `raw UPDATE and DELETE on observation are blocked by trigger`() =
        runTest {
            db.observationDao().insert(observation(asOf = 1L, value = 10.0))

            assertThrows(SQLiteConstraintException::class.java) {
                db.openHelper.writableDatabase.execSQL("UPDATE observation SET value = 99.0")
            }
            assertThrows(SQLiteConstraintException::class.java) {
                db.openHelper.writableDatabase.execSQL("DELETE FROM observation")
            }
        }

    @Test
    fun `raw UPDATE and DELETE on tick_input are blocked by trigger`() =
        runTest {
            db.tickInputDao().insert(tickInput(date = "2026-08-07", composite = 10.0))

            assertThrows(SQLiteConstraintException::class.java) {
                db.openHelper.writableDatabase.execSQL("UPDATE tick_input SET composite = 0.0")
            }
            assertThrows(SQLiteConstraintException::class.java) {
                db.openHelper.writableDatabase.execSQL("DELETE FROM tick_input")
            }
        }

    // ② as-of 정렬 — cutoff 밖 미래 행 미반환, 오름차순.
    @Test
    fun `confirmSeries excludes rows beyond cutoff and returns ascending as_of`() =
        runTest {
            val dao = db.observationDao()
            listOf(1L, 2L, 3L, 10L).forEach { dao.insert(observation(asOf = it, value = it.toDouble())) }

            val result = dao.confirmSeries("^VIX", "close", fromAsOf = 0L, toAsOf = 3L)

            assertEquals(listOf(1L, 2L, 3L), result.map { it.asOf })
            assertEquals(listOf(1.0, 2.0, 3.0), result.map { it.value })
        }

    // ③ 레인 격리 — lane=1만 있는 셀은 confirmSeries에서 부재, previewSeries에서 존재.
    @Test
    fun `lane isolation - preview-only cell is invisible to confirmSeries but visible to previewSeries`() =
        runTest {
            val dao = db.observationDao()
            dao.insert(observation(asOf = 5L, value = 42.0, lane = Lane.PREVIEW))

            val confirmed = dao.confirmSeries("^VIX", "close", fromAsOf = 0L, toAsOf = 10L)
            val preview = dao.previewSeries("^VIX", "close", fromAsOf = 0L, toAsOf = 10L)

            assertTrue("confirmSeries must not see preview-only rows", confirmed.isEmpty())
            assertEquals(listOf(42.0), preview.map { it.value })
        }

    // ④ tie-break — 같은 as_of에 확정·프리뷰 공존 시 previewSeries는 확정을 선택한다.
    @Test
    fun `previewSeries prefers confirmed lane over preview lane at the same as_of`() =
        runTest {
            val dao = db.observationDao()
            // 13:00 부분봉(프리뷰) 먼저 적재, 17:00 종가(확정)가 이후 적재돼도 순서 무관하게 확정이 이겨야 한다.
            dao.insert(observation(asOf = 5L, value = 100.0, lane = Lane.PREVIEW, revision = 0))
            dao.insert(observation(asOf = 5L, value = 101.0, lane = Lane.CONFIRMED, revision = 0))

            val preview = dao.previewSeries("^VIX", "close", fromAsOf = 0L, toAsOf = 10L)

            assertEquals(1, preview.size)
            assertEquals(
                "lane ASC(확정 우선) tie-break가 lane DESC로 뒤집히면 부분봉(100.0)이 선택된다 — A-15 퇴화 입력 증인",
                101.0,
                preview.single().value,
                0.0,
            )
        }

    // ⑤ lastCommittedSeverities — 0행이면 null, gap 행(composite NULL)은 건너뛴다.
    @Test
    fun `lastCommittedSeverities returns null when tick_input is empty`() =
        runTest {
            assertNull(db.tickInputDao().lastCommittedSeverities())
        }

    @Test
    fun `lastCommittedSeverities skips a trailing gap row and returns the last evaluated tick`() =
        runTest {
            val dao = db.tickInputDao()
            dao.insert(tickInput(date = "2026-08-05", composite = 22.0, severitiesJson = """{"vix_level_z":2}"""))
            dao.insert(tickInput(date = "2026-08-06", composite = null, gapReason = "UNRECONSTRUCTABLE_GAP"))

            val last = dao.lastCommittedSeverities()

            assertEquals("2026-08-05", last?.tradingDate)
            assertEquals("""{"vix_level_z":2}""", last?.severitiesJson)
        }

    // ⑥ 레인별 revision 채번 — 같은 셀에서 레인마다 독립적으로 revision을 매길 수 있다(M-43).
    @Test
    fun `revision is numbered independently per lane for the same cell`() =
        runTest {
            val dao = db.observationDao()
            dao.insert(observation(asOf = 7L, value = 1.0, lane = Lane.CONFIRMED, revision = 0))
            dao.insert(observation(asOf = 7L, value = 2.0, lane = Lane.CONFIRMED, revision = 1)) // 확정 정정
            // 프리뷰가 확정과 무관하게 revision 0부터 채번해도 UNIQUE 충돌이 없어야 한다.
            dao.insert(observation(asOf = 7L, value = 9.0, lane = Lane.PREVIEW, revision = 0))

            val confirmed = dao.confirmSeries("^VIX", "close", fromAsOf = 0L, toAsOf = 10L)
            val previewOnly = dao.previewSeries("^VIX", "close", fromAsOf = 0L, toAsOf = 10L)

            assertEquals(
                "레인별 revision 채번 — confirmSeries는 lane=0의 최신 revision(1 -> 2.0)만 본다",
                listOf(2.0),
                confirmed.map { it.value },
            )
            assertEquals(
                "확정 revision 1(2.0)이 존재하므로 previewSeries도 tie-break로 확정을 고른다",
                listOf(2.0),
                previewOnly.map { it.value },
            )
        }

    // 테스트 픽스처 빌더 — ObservationEntity의 실제 열 수를 그대로 반영한다(임의 복잡도 아님).
    @Suppress("LongParameterList")
    private fun observation(
        seriesId: String = "^VIX",
        field: String = "close",
        asOf: Long,
        value: Double,
        lane: Int = Lane.CONFIRMED,
        revision: Int = 0,
        observedAt: Long = asOf,
        source: String = "yahoo",
    ) = ObservationEntity(
        seriesId = seriesId,
        field = field,
        asOf = asOf,
        value = value,
        observedAt = observedAt,
        revision = revision,
        lane = lane,
        source = source,
    )

    // 테스트 픽스처 빌더 — TickInputEntity(M-49 감사 합집합)의 실제 열 수를 그대로 반영한다.
    @Suppress("LongParameterList")
    private fun tickInput(
        date: String,
        composite: Double?,
        distinctAxes: Int = 0,
        anyCrit: Boolean = false,
        anyExtreme: Boolean = false,
        severitiesJson: String = "{}",
        coverage: Double = 0.0,
        registryVersion: String = "0.3.1-rc",
        gapReason: String? = null,
        frozenAt: Long = 0L,
        isCatchup: Boolean = false,
    ) = TickInputEntity(
        tradingDate = date,
        composite = composite,
        distinctAxes = distinctAxes,
        anyCrit = anyCrit,
        anyExtreme = anyExtreme,
        severitiesJson = severitiesJson,
        coverage = coverage,
        registryVersion = registryVersion,
        gapReason = gapReason,
        frozenAt = frozenAt,
        firedAxes = null,
        visibleAtByIndicator = null,
        isCatchup = isCatchup,
        warmupStatusJson = null,
        pitQuality = null,
    )
}
