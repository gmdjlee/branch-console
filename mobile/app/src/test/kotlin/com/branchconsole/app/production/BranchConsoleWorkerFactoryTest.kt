package com.branchconsole.app.production

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.branchconsole.app.tick.ConfirmTickWorker
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A worker unrelated to [ConfirmTickWorker], used only to prove the factory defers to the
 * default reflection-based factory for class names it doesn't special-case. Must be non-private:
 * WorkManager's default reflection factory doesn't call `setAccessible(true)`, so a private class
 * fails with `IllegalAccessException` (same constraint documented on
 * [com.branchconsole.app.tick.ConfirmTickWorkerTest]'s `FakeConfirmTickWorker`). */
class UnrelatedWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()
}

/**
 * MT1-08c/08d — [BranchConsoleWorkerFactory]가 실제로 `tick/ConfirmTickWorker`(정확한 클래스명)
 * 요청을 [ProductionConfirmTickWorker]로 바꿔치기하는지, 그리고 다른 워커는 기본 팩토리로
 * 위임하는지 확인한다. `TestListenableWorkerBuilder`(androidx.work.testing,
 * [com.branchconsole.app.tick.ConfirmTickWorkerTest]와 동일 하니스)에 팩토리를 직접 주입해
 * 실제 WorkManager `Configuration.Provider` 초기화 경로 없이도 팩토리 동작 자체를 검증한다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BranchConsoleWorkerFactoryTest {
    private fun context() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `factory substitutes ProductionConfirmTickWorker for the ConfirmTickWorker class name`() {
        val worker =
            TestListenableWorkerBuilder<ConfirmTickWorker>(context())
                .setWorkerFactory(BranchConsoleWorkerFactory())
                .build()

        assertTrue(
            "WorkManager asked for ConfirmTickWorker must receive the dailyCollect-enabled subclass",
            worker is ProductionConfirmTickWorker,
        )
    }

    @Test
    fun `factory defers to the default reflection factory for unrelated workers`() {
        // TestListenableWorkerBuilder<UnrelatedWorker>.build() only succeeds if
        // BranchConsoleWorkerFactory.createWorker() returns null for this class name (telling
        // WorkManager to fall back to its default reflection-based factory) -- a thrown
        // exception or a wrongly-substituted worker would fail this call.
        val worker =
            TestListenableWorkerBuilder<UnrelatedWorker>(context())
                .setWorkerFactory(BranchConsoleWorkerFactory())
                .build()

        assertNotNull(worker)
    }
}
