package com.branchconsole.app.production

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.branchconsole.app.tick.ConfirmTickWorker

/**
 * MT1-08c/08d (aaa F-3 이관분) — `tick/ConfirmTickWorker.kt`의 `dailyCollect` KDoc이 예고한
 * 정확한 배선: "이 메서드를 override하는 서브클래스 + 커스텀 `WorkerFactory`로 ... 주입한다
 * (`ConfirmTickRunner` 쪽 코드는 변경 불필요)".
 *
 * `ConfirmTickWorker.schedulePeriodic`/`triggerCatchupNow`(tick/ConfirmTickWorker.kt, 병렬
 * 워커가 작업 중이라 수정하지 않는다)는 `PeriodicWorkRequestBuilder<ConfirmTickWorker>`로
 * WorkSpec에 클래스명 `"com.branchconsole.app.tick.ConfirmTickWorker"`를 그대로 못박는다 —
 * 이 팩토리가 그 정확한 이름을 가로채 [ProductionConfirmTickWorker](dailyCollect 실구현)를
 * 대신 내준다. `tick/`은 한 줄도 고치지 않는다.
 *
 * 다른 워커([com.branchconsole.app.notif.NotificationSyncWorker] 등)는 `null`을 반환해
 * WorkManager의 기본 리플렉션 팩토리로 위임한다(`WorkerFactory.createWorker` 계약).
 */
class BranchConsoleWorkerFactory : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? =
        if (workerClassName == ConfirmTickWorker::class.java.name) {
            ProductionConfirmTickWorker(appContext, workerParameters)
        } else {
            null
        }
}
