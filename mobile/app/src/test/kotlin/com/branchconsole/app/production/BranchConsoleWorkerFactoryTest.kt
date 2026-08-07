package com.branchconsole.app.production

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.TestListenableWorkerBuilder
import com.branchconsole.app.notif.NotificationSyncWorker
import com.branchconsole.app.tick.ConfirmTickWorker
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
        // TestListenableWorkerBuilder<NotificationSyncWorker>.build() only succeeds if
        // BranchConsoleWorkerFactory.createWorker() returns null for this class name (telling
        // WorkManager to fall back to its default reflection-based factory) -- a thrown
        // exception or a wrongly-substituted worker would fail this call.
        val worker = TestListenableWorkerBuilder<NotificationSyncWorker>(context()).setWorkerFactory(BranchConsoleWorkerFactory()).build()

        assertNotNull(worker)
    }
}
