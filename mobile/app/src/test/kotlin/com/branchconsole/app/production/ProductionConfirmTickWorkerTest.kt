package com.branchconsole.app.production

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.branchconsole.app.collectors.CollectOutcome
import com.branchconsole.app.collectors.Collector
import com.branchconsole.app.collectors.Observation
import com.branchconsole.app.credentials.CredentialFields
import com.branchconsole.app.credentials.CredentialsStore
import com.branchconsole.lake.LakeDatabase
import com.branchconsole.lake.Lane
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

private class FakeCollector(private val date: LocalDate, private val value: Double) : Collector {
    override val id = "fake"
    override val expectedSeriesIds = listOf("^VIX")

    override suspend fun collect(range: ClosedRange<LocalDate>): CollectOutcome =
        CollectOutcome.Ok(
            listOf(
                Observation(
                    seriesId = "^VIX",
                    field = "close",
                    asOf = date.atStartOfDay(ZoneOffset.UTC).toInstant(),
                    observedAt = Instant.now(),
                    source = "fake",
                    value = value,
                ),
            ),
        )
}

/**
 * MT1-08c/08d — `dailyCollect` 실구현. `dailyCollect()`는 `protected`(기반 클래스 계약을
 * 그대로 따름)이므로 [ConfirmTickWorker.doWork]를 통해서만 검증한다
 * ([com.branchconsole.app.tick.ConfirmTickWorkerTest]와 동일 관례) — `doWork()`가 먼저
 * `dailyCollect()`를 부르고 그 다음에 `loadConfig()`(실 assets, `confirm_time_kst` 미기입으로
 * 항상 실패)를 부르므로, `dailyCollect()`의 부수효과는 전체 `doWork()` 결과가 실패로 끝나도
 * 이미 반영돼 있다.
 *
 * `dailyCollect()`는 자신이 연 `LakeDatabase`를 스스로 닫는다(운영 코드 그대로) — 그래서
 * 이 테스트의 `dbFactory`는 매번 실제 파일 백킹 [LakeDatabase.build]를 새로 여는 프로덕션
 * 기본값을 쓰고, 검증은 `doWork()` 종료 후 **같은 파일에 새 핸들을 다시 열어** 확인한다
 * (Robolectric은 테스트마다 격리된 파일시스템을 준다 — 다른 테스트와 간섭 없음).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProductionConfirmTickWorkerTest {
    private fun context() = ApplicationProvider.getApplicationContext<Context>()

    private fun credentialsFactory(fields: CredentialFields): (Context) -> CredentialsStore =
        { ctx ->
            val prefs = ctx.getSharedPreferences("fake_creds_${System.nanoTime()}", Context.MODE_PRIVATE)
            CredentialsStore(prefs).apply { save(fields) }
        }

    private fun buildWorker(
        credentialsStoreFactory: (Context) -> CredentialsStore,
        collectorsFactory: (Context, CredentialsStore) -> List<Collector>,
        clock: Clock,
    ): ProductionConfirmTickWorker {
        val factory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker =
                    ProductionConfirmTickWorker(
                        appContext,
                        workerParameters,
                        credentialsStoreFactory,
                        { LakeDatabase.build(it) },
                        collectorsFactory,
                        clock,
                    )
            }
        return TestListenableWorkerBuilder<ProductionConfirmTickWorker>(context()).setWorkerFactory(factory).build()
    }

    @Test
    fun `not configured records a single run_log entry and never calls the collectors`() =
        runTest {
            var collectorsFactoryCalled = false
            // CredentialFields() -> nothing saved.
            val worker =
                buildWorker(
                    credentialsStoreFactory = credentialsFactory(CredentialFields()),
                    collectorsFactory = { _, _ ->
                        collectorsFactoryCalled = true
                        emptyList()
                    },
                    clock = Clock.fixed(Instant.parse("2026-08-06T08:00:00Z"), ZoneId.of("UTC")),
                )

            worker.doWork()

            assertTrue("collectors must never run when credentials are missing", !collectorsFactoryCalled)
            val db = LakeDatabase.build(context())
            try {
                assertEquals(1, db.runLogDao().allOrderedByRanAt().count { it.status == "not_configured" })
            } finally {
                db.close()
            }
        }

    @Test
    fun `configured credentials trigger collection and append confirmed-lane rows`() =
        runTest {
            val fixedDate = LocalDate.of(2026, 8, 5)
            val fields = CredentialFields(krxId = "id", krxPassword = "pw", fredApiKey = "key")
            val worker =
                buildWorker(
                    credentialsStoreFactory = credentialsFactory(fields),
                    collectorsFactory = { _, _ -> listOf(FakeCollector(fixedDate, 15.0)) },
                    clock = Clock.fixed(Instant.parse("2026-08-06T08:00:00Z"), ZoneId.of("UTC")),
                )

            worker.doWork()

            val db = LakeDatabase.build(context())
            try {
                val rows = db.observationDao().confirmSeries("^VIX", "close", 0L, Long.MAX_VALUE)
                assertEquals(1, rows.size)
                assertEquals(15.0, rows.single().value, 0.0)
                val maxRevision = db.observationDao().maxRevision("^VIX", "close", rows.single().asOf, Lane.CONFIRMED)
                assertEquals(0, maxRevision)
            } finally {
                db.close()
            }
        }
}
