package com.branchconsole.app.tick

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.branchconsole.engine.config.HyLevelBoost
import com.branchconsole.engine.config.StatemachineConfig
import com.branchconsole.engine.config.UsdkrwIntradayForce
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** [ConfirmTickConfigLoader]를 우회하고 즉시 유효한 설정을 반환한다 — 이 파일이 검증하는 것은
 * Worker↔WorkManager 배선(aaa F-1)이지 config 파싱이 아니다("확정 틱 지표 0종" 픽스처, 빈
 * lake와 결합하면 candidates=empty → NoOp → success로 최단 경로를 탄다).
 *
 * public이어야 한다(private 아님) — WorkManager 기본 팩토리(`TestListenableWorkerBuilder`가
 * 내부에서 쓰는 것과 동일 경로)는 리플렉션 생성자 호출 시 `setAccessible(true)`를 하지 않아
 * private 클래스는 `IllegalAccessException`으로 실패한다(실측).
 */
class FakeConfirmTickWorker(context: Context, params: WorkerParameters) : ConfirmTickWorker(context, params) {
    override fun loadConfig(): ConfirmTickConfig =
        ConfirmTickConfig(
            specs = emptyList(),
            weights = emptyMap(),
            axes = emptyMap(),
            maxSeverities = emptyMap(),
            fredLagDays = emptyMap(),
            staleWindows = emptyMap(),
            statemachineConfig =
                StatemachineConfig(
                    phases = listOf("GREEN", "AMBER", "ORANGE", "RED"),
                    initialPhase = "GREEN",
                    upgrade = emptyMap(),
                    downgrade = emptyMap(),
                    skipLevels = true,
                    profiles = emptyMap(),
                ),
            modifiers = HyLevelBoost(0.0, 0, 0) to UsdkrwIntradayForce(0.0, 0.0),
            confirmTimeKst = LocalTime.of(17, 0),
            catchupMaxTicks = 20,
            registryVersion = "0.0.0-test",
        )
}

/**
 * MT1-06 aaa F-1 — `androidx.work.testing`(work-testing) 실사용 증인. WorkManager의 기본
 * 리플렉션 팩토리가 아니라 [TestListenableWorkerBuilder]로 실제 [ConfirmTickWorker] 인스턴스를
 * 조립해 `doWork()`를 구동한다(Robolectric JVM — 계측 불필요).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConfirmTickWorkerTest {
    @Test
    fun `doWork succeeds end to end when config loads (empty lake, results in noop)`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val worker = TestListenableWorkerBuilder<FakeConfirmTickWorker>(context).build()

            val result = worker.doWork()

            assertTrue("expected Result.success(), got $result", result is ListenableWorker.Result.Success)
        }

    // 실제 SSOT(assets/configs/statemachine.yaml)는 아직 confirm_time_kst가 없다(AD-3b 실측
    // 대기, PROGRESS.md 2026-08-07) — 그 부재가 명시 실패로 전파돼 config_error로 착지하는지,
    // "조용한 17:00 기본값"으로 새지 않는지 실제 자산으로 확인한다.
    @Test
    fun `doWork returns failure via config_error when real assets lack confirm_time_kst`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val worker = TestListenableWorkerBuilder<ConfirmTickWorker>(context).build()

            val result = worker.doWork()

            assertTrue("expected Result.failure(), got $result", result is ListenableWorker.Result.Failure)
        }

    // aaa F-1 — enqueue 시각이 확정 시각 전이어도 첫 발화가 "오늘의" 확정 시각 이후로 앵커된다.
    @Test
    fun `initialDelayUntil anchors to today's confirm time when it has not passed yet`() {
        val clock = Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneId.of("UTC")) // 2026-08-04 09:00 KST
        val delay = ConfirmTickWorker.initialDelayUntil(LocalTime.of(17, 0), clock)
        // 09:00 KST -> 17:00 KST 같은 날 = 8h.
        assertEquals(Duration.ofHours(8), delay)
    }

    @Test
    fun `initialDelayUntil rolls over to tomorrow's confirm time once today's has already passed`() {
        val clock = Clock.fixed(Instant.parse("2026-08-04T09:00:00Z"), ZoneId.of("UTC")) // 2026-08-04 18:00 KST
        val delay = ConfirmTickWorker.initialDelayUntil(LocalTime.of(17, 0), clock)
        // 18:00 KST -> 내일 17:00 KST = 23h.
        assertEquals(Duration.ofHours(23), delay)
    }
}
