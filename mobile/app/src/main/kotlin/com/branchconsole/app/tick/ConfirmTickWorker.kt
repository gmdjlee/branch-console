package com.branchconsole.app.tick

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.branchconsole.engine.pit.Visibility
import com.branchconsole.lake.LakeDatabase
import com.branchconsole.lake.RunLogEntity
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

private const val UNIQUE_PERIODIC_WORK_NAME = "confirm-tick-daily"
private const val UNIQUE_CATCHUP_WORK_NAME = "confirm-tick-catchup-on-launch"
private const val PERIODIC_INTERVAL_DAYS = 1L
private val FLEX: Duration = Duration.ofHours(1)
private val KST = ZoneOffset.ofHours(9)

/**
 * MT1-06 WorkManager 진입점 (K-14 — 일일 작업은 정시 보장이 없다, 지연 허용 + 앱 실행 시
 * 캐치업(멱등)이 설계다. 정확 알람으로 우회하지 않는다). `ConfirmTickRunner` 자체는 WorkManager를
 * 모른다(순수 suspend 함수) — 이 클래스는 그것을 스케줄러에 연결하는 얇은 어댑터일 뿐이다.
 *
 * 프로덕션 도달 경로: [com.branchconsole.app.BranchConsoleApplication.onCreate]가
 * [schedulePeriodic]·[triggerCatchupNow]를 호출한다(aaa F-1 — 이전 판은 호출자가 0개였다).
 *
 * [dailyCollect] 훅은 확정 틱 평가 **이전**에 원계열을 수집해 lake에 append하는 지점이다
 * (파이프라인 개관: "수집 → append → 확정 틱"). 기본 구현은 no-op — K-17 자격증명 저장(Keystore/
 * EncryptedSharedPreferences) UI가 아직 없어(collectors.krx.KrxCredentialsProvider·
 * collectors.fred.FredCredentialsProvider KDoc, "향후 설정 화면" 확정) 이 서브태스크에서
 * collectors를 실제로 기동할 credentials 조립 지점이 없다. **이관 확정: MT1-08c/08d가 실구현한다**
 * (aaa F-3) — 온보딩이 배선되면 이 메서드를 override하는 서브클래스 + 커스텀 `WorkerFactory`로
 * `WarmupBackfillOrchestrator(...).run(...)` 류를 주입한다(`ConfirmTickRunner` 쪽 코드는 변경
 * 불필요). [loadConfig] 역시 같은 이유(테스트 주입 지점)로 열어 둔다.
 *
 * 생성자에 람다를 받지 않는 이유는 WorkManager 기본 팩토리가 `(Context, WorkerParameters)`
 * 정확히 2-인자 생성자만 리플렉션으로 찾기 때문이다(K-14 스케줄러 제약, 추가 파라미터를 넣으면
 * 기본 팩토리가 인스턴스화에 실패한다) — override 지점은 전부 서브클래스 몫이다.
 */
open class ConfirmTickWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    protected open suspend fun dailyCollect() {}

    // internal(protected 아님): ConfirmTickConfig 자체가 internal이라 protected로 노출하면
    // "protected 멤버가 internal 타입을 노출" 오류가 난다. 테스트 서브클래스는 같은 모듈이라
    // internal override로 충분하다.
    internal open fun loadConfig(): ConfirmTickConfig {
        return ConfirmTickConfigLoader.load(AssetConfigSource(applicationContext))
    }

    // TooGenericExceptionCaught: ConfirmTickRunner.run()이 이미 run_log에 사유를 남긴다 — 여기서는
    // Result 변환만. SwallowedException: 같은 이유로 e를 여기서 다시 기록하지 않는다(중복 로그 방지).
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override suspend fun doWork(): Result {
        val db = LakeDatabase.build(applicationContext)
        try {
            dailyCollect()
            val config =
                runCatching { loadConfig() }
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
            return try {
                runner.run()
                Result.success()
            } catch (e: Exception) {
                Result.failure() // 사유는 runner.run() 자신이 이미 run_log(failed)로 기록했다.
            }
        } finally {
            db.close()
        }
    }

    companion object {
        /**
         * 일일 고유 주기 작업(WorkManager 유니크 워크, K-14). [ExistingPeriodicWorkPolicy.KEEP]로
         * 중복 스케줄을 걸러 이중 실행 여지를 스케줄러 층에서도 막는다(물리적 멱등 근거는
         * `tick_input.trading_date` UNIQUE — 이건 벨트 앤 서스펜더).
         *
         * `initialDelay`를 확정 시각([initialDelayUntil])에 앵커한다 — 앵커가 없으면 enqueue
         * 시각이 그날 확정 시각보다 이르고 24h 주기가 매번 같은(이른) 시각에 떨어지는 경우,
         * 후보가 "아직 확정 시각 전"이라 매일 no-op만 반복하며 캐치업이 영구히 쌓인다(aaa F-1).
         * `confirm_time_kst` 미기입 시 이 함수는 [ConfirmTickConfigLoader]와 동일하게 명시
         * 실패한다(조용한 기본값 금지 — 호출부인 `BranchConsoleApplication`이 흡수한다).
         */
        fun schedulePeriodic(context: Context) {
            val confirmTimeKst = ConfirmTickConfigLoader.load(AssetConfigSource(context)).confirmTimeKst
            val initialDelay = initialDelayUntil(confirmTimeKst, Clock.systemUTC())
            val request =
                PeriodicWorkRequestBuilder<ConfirmTickWorker>(Duration.ofDays(PERIODIC_INTERVAL_DAYS), FLEX)
                    .setInitialDelay(initialDelay)
                    .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** `now`부터 다음 `confirmTimeKst`(KST) 발생 시점까지의 지연 — 오늘 그 시각이 아직
         * 안 지났으면 오늘, 이미 지났으면 내일. */
        internal fun initialDelayUntil(
            confirmTimeKst: LocalTime,
            clock: Clock,
        ): Duration {
            val now = clock.instant()
            val todayKst = LocalDate.now(clock.withZone(KST))
            val todayTarget = Visibility.kstToUtc(todayKst, confirmTimeKst)
            val tomorrowTarget = Visibility.kstToUtc(todayKst.plusDays(1), confirmTimeKst)
            val target = if (now.isBefore(todayTarget)) todayTarget else tomorrowTarget
            return Duration.between(now, target)
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
