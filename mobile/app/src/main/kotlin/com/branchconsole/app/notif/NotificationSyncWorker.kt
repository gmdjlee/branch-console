package com.branchconsole.app.notif

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.branchconsole.lake.LakeDatabase
import java.time.Duration

private const val TAG = "NotificationSyncWorker"
private const val UNIQUE_WORK_NAME = "notification-sync"

/**
 * MT1-08a 스케줄러 배선 — 확정 틱(`tick/ConfirmTickWorker`, 별도 유니크 워크)이 백그라운드에서
 * 언제 끝나는지 관찰하지 않는다(그러려면 그 워크의 private 상수를 알아야 해 `tick/` 결합이
 * 생긴다). 대신 독립된 15분 주기(`PeriodicWorkRequest`의 플랫폼 최소 간격, K-14와 동일하게
 * "정시 보장 없음, 지연 허용"을 그대로 받아들인다) 폴링으로 `tick_input`/`run_log`의 델타를
 * 스스로 감지한다 — `tick/`을 전혀 모르는 완전히 분리된 워커(브리프 tick/ 수정 금지 요건을
 * import 없이도 만족).
 */
class NotificationSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    @Suppress("TooGenericExceptionCaught") // one bad tick shouldn't crash the periodic sync forever; retry next window.
    override suspend fun doWork(): Result {
        val db = LakeDatabase.build(applicationContext)
        return try {
            NotificationSync(applicationContext, db.tickInputDao(), db.runLogDao()).checkAndNotify()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "notification sync failed, will retry next window", e)
            Result.retry()
        } finally {
            db.close()
        }
    }

    companion object {
        private val INTERVAL: Duration = Duration.ofMinutes(15)

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<NotificationSyncWorker>(INTERVAL).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
