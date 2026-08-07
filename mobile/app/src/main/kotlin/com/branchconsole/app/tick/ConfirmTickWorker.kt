package com.branchconsole.app.tick

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.branchconsole.lake.LakeDatabase
import com.branchconsole.lake.RunLogEntity
import java.time.Duration
import java.util.concurrent.TimeUnit

private const val UNIQUE_PERIODIC_WORK_NAME = "confirm-tick-daily"
private const val UNIQUE_CATCHUP_WORK_NAME = "confirm-tick-catchup-on-launch"
private val PERIODIC_INTERVAL: Duration = Duration.ofDays(1)

/**
 * MT1-06 WorkManager 진입점 (K-14 — 일일 작업은 정시 보장이 없다, 지연 허용 + 앱 실행 시
 * 캐치업(멱등)이 설계다. 정확 알람으로 우회하지 않는다). `ConfirmTickRunner` 자체는 WorkManager를
 * 모른다(순수 suspend 함수) — 이 클래스는 그것을 스케줄러에 연결하는 얇은 어댑터일 뿐이다.
 *
 * [dailyCollect] 훅은 확정 틱 평가 **이전**에 원계열을 수집해 lake에 append하는 지점이다
 * (파이프라인 개관: "수집 → append → 확정 틱"). 기본 구현은 no-op — K-17 자격증명 저장(Keystore/
 * EncryptedSharedPreferences) UI가 아직 없어(collectors.krx.KrxCredentialsProvider·
 * collectors.fred.FredCredentialsProvider KDoc, "향후 설정 화면" 확정) 이 서브태스크에서
 * collectors를 실제로 기동할 credentials 조립 지점이 없다. 온보딩이 배선되면 이 메서드를
 * override하는 서브클래스 + 커스텀 `WorkerFactory`로 `WarmupBackfillOrchestrator(...).run(...)`
 * 류를 주입한다(`ConfirmTickRunner` 쪽 코드는 변경 불필요) — 생성자에 람다를 받지 않는 이유는
 * WorkManager 기본 팩토리가 `(Context, WorkerParameters)` 정확히 2-인자 생성자만 리플렉션으로
 * 찾기 때문이다(K-14 스케줄러 제약, 추가 파라미터를 넣으면 기본 팩토리가 인스턴스화에 실패한다).
 */
open class ConfirmTickWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    protected open suspend fun dailyCollect() {}

    override suspend fun doWork(): Result {
        val db = LakeDatabase.build(applicationContext)
        try {
            dailyCollect()
            val config =
                runCatching { ConfirmTickConfigLoader.load(AssetConfigSource(applicationContext)) }
                    .getOrElse { e ->
                        db.runLogDao().insert(
                            RunLogEntity(
                                tradingDate = null,
                                ranAt = System.currentTimeMillis(),
                                status = "config_error",
                                detail = e.message,
                            ),
                        )
                        return Result.failure()
                    }
            val runner =
                ConfirmTickRunner(
                    observationDao = db.observationDao(),
                    tickInputDao = db.tickInputDao(),
                    runLogDao = db.runLogDao(),
                    gridProvider = TradingDayGridProvider(db.observationDao()),
                    config = config,
                )
            runner.run()
            return Result.success()
        } finally {
            db.close()
        }
    }

    companion object {
        /** 일일 고유 주기 작업(WorkManager 유니크 워크, K-14). [ExistingPeriodicWorkPolicy.KEEP]로
         * 중복 스케줄을 걸러 이중 실행 여지를 스케줄러 층에서도 막는다(물리적 멱등 근거는
         * `tick_input.trading_date` UNIQUE — 이건 벨트 앤 서스펜더). */
        fun schedulePeriodic(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<ConfirmTickWorker>(PERIODIC_INTERVAL.toMinutes(), TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** 앱 실행 시 캐치업 트리거(K-14 "앱 실행 시 캐치업") — 즉시 1회 실행, 유니크 워크로
         * 주기 작업과 별도 슬롯을 쓴다(둘이 겹쳐도 `tick_input` UNIQUE가 최종 방어선). */
        fun triggerCatchupNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<ConfirmTickWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_CATCHUP_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
