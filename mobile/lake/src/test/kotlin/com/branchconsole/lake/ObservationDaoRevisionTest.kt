package com.branchconsole.lake

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * MT1-08b — [ObservationDao.maxRevision] 왕복(프리뷰 재수집 revision 채번의 기반 조회).
 * append-only 물리 강제 자체는 [LakeDatabaseTest]/[LakeArchitectureTest] 소관 — 이 파일은
 * 새 조회 메서드 하나만 겨눈다.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ObservationDaoRevisionTest {
    private lateinit var db: LakeDatabase

    @Before
    fun setUp() {
        db = LakeDatabase.buildInMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun row(
        revision: Int,
        lane: Int = Lane.PREVIEW,
    ) = ObservationEntity(
        seriesId = "^VIX",
        field = "close",
        asOf = 1000L,
        value = 15.0,
        observedAt = 1000L,
        revision = revision,
        lane = lane,
        source = "test",
    )

    @Test
    fun `unknown cell returns null`() =
        runTest {
            assertNull(db.observationDao().maxRevision("^VIX", "close", 1000L, Lane.PREVIEW))
        }

    @Test
    fun `returns the highest revision for that exact cell`() =
        runTest {
            db.observationDao().insert(row(revision = 0))
            db.observationDao().insert(row(revision = 1))
            db.observationDao().insert(row(revision = 2))

            assertEquals(2, db.observationDao().maxRevision("^VIX", "close", 1000L, Lane.PREVIEW))
        }

    @Test
    fun `lane is isolated - a confirmed-lane revision does not leak into the preview-lane query`() =
        runTest {
            db.observationDao().insert(row(revision = 5, lane = Lane.CONFIRMED))

            assertNull(db.observationDao().maxRevision("^VIX", "close", 1000L, Lane.PREVIEW))
        }
}
