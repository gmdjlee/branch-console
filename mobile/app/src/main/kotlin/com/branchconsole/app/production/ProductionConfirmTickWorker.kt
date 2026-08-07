package com.branchconsole.app.production

import android.content.Context
import android.util.Log
import androidx.work.WorkerParameters
import com.branchconsole.app.collectors.CollectOutcome
import com.branchconsole.app.collectors.Collector
import com.branchconsole.app.collectors.CollectorFactory
import com.branchconsole.app.collectors.Observation
import com.branchconsole.app.credentials.CredentialsStore
import com.branchconsole.app.notif.NotificationSync
import com.branchconsole.app.tick.ConfirmTickWorker
import com.branchconsole.lake.LakeDatabase
import com.branchconsole.lake.ObservationEntity
import com.branchconsole.lake.RunLogEntity
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

private const val TAG = "ProductionConfirmTick"
private val KST = ZoneOffset.ofHours(9)

// Operational safety margin for the raw-observation self-heal fetch, not an SSOT threshold: it
// doesn't affect scoring, phase transitions, or when the cron fires (CLAUDE.md §1 scope). Same
// classification as FredCollector.FETCH_LIMIT / YahooCollector's RANGE_BUCKETS in this codebase.
// Covers weekends/holidays plus K-14 WorkManager delay without re-walking the full 550-day warmup
// window (and its KRX rate-limit cost) every day.
private const val DAILY_COLLECT_LOOKBACK_DAYS = 7L
private const val STATUS_NOT_CONFIGURED = "not_configured"

/**
 * MT1-08c/08d dailyCollect 실구현 (aaa F-3 이관분). `tick/ConfirmTickWorker.kt`가 예고한
 * override 지점 두 개 중 [dailyCollect]를 쓰고, `doWork()`도 재정의한다(그 메서드는 `override
 * suspend fun`이라 `final`이 아닌 한 기본적으로 open이다 — aaa M-5 정정: 최초 판은 이걸
 * "닫혀 있다"고 오판해 별도 15분 폴링 워커를 뒀었다). `loadConfig()`는 기본 구현(실제 assets)을
 * 그대로 쓴다. 조립(WorkManager가 이 서브클래스를 실제로 만들게 하는 배선)은
 * [BranchConsoleWorkerFactory] 소관.
 *
 * [doWork]는 `super.doWork()`(수집·평가·커밋)가 끝난 **직후** [NotificationSync.checkAndNotify]를
 * 부른다 — 확정 틱 완료 시점에 정확히 물려 지연 0·불필요한 기상 0회로 노티를 배선한다(aaa M-5).
 * 확정 틱이 실패해도(예: `confirm_time_kst` 미기입으로 인한 `config_error`) 알림 확인은 그대로
 * 수행된다 — `run_log`에 남은 실패 자체가 `tick_failure` 트리거 대상이기 때문이다. 이 확인이
 * 실패해도(`NotificationSync` 내부 오류) 확정 틱 결과([Result])는 절대 오염시키지 않는다
 * (`runCatching` + 로그만).
 *
 * `ConfirmTickWorker.doWork()`는 이미 자신만의 `LakeDatabase` 핸들을 열어 두지만(dailyCollect
 * 호출 **이전**) 그 인스턴스를 서브클래스에 넘기는 필드가 없다(그 파일을 고칠 수 없어 추가
 * 불가) — 그래서 [dailyCollect]·[notifyAfterTick] 각각 **별도** `LakeDatabase` 연결을 열었다
 * 닫는다. 같은 SQLite 파일에 순차(비중첩) 연결 여러 개는 안전하지만 낭비다. `ponytail`:
 * dailyCollect/notify가 상위 파이프라인과 핸들을 공유할 지점이 생기면(예: `ConfirmTickWorker`에
 * protected db 필드가 추가되면) 이 중복 연결을 제거한다 — 지금은 tick/ 수정 금지 제약 때문에
 * 불가능하다.
 *
 * 자격증명 미설정이면 `run_log`에 `not_configured` 1건만 남기고 무해 종료한다(브리프 지시).
 * "오늘"은 KST 기준이다(aaa M-4 — 인접한 [com.branchconsole.app.preview.PreviewTickRunner]와
 * 동일 관례. `Clock.systemUTC()`를 시각 원천으로 그대로 두되 날짜만 KST로 해석한다).
 *
 * `credentialsStoreFactory`/`dbFactory`/`collectorsFactory`는 프로덕션 기본값을 그대로 두는
 * 테스트 주입 지점이다 — 이 클래스는 [BranchConsoleWorkerFactory]가 직접 생성자를 호출해
 * 조립하므로(WorkManager의 리플렉션 2-인자 제약이 적용되지 않는다, `ConfirmTickWorker` KDoc
 * 참조) 추가 파라미터를 자유롭게 둘 수 있다.
 */
class ProductionConfirmTickWorker(
    context: Context,
    params: WorkerParameters,
    private val credentialsStoreFactory: (Context) -> CredentialsStore = { CredentialsStore.create(it) },
    private val dbFactory: (Context) -> LakeDatabase = { LakeDatabase.build(it) },
    private val collectorsFactory: (Context, CredentialsStore) -> List<Collector> = CollectorFactory::createAll,
    private val clock: Clock = Clock.systemUTC(),
) : ConfirmTickWorker(context, params) {
    // A broken notification check must never fail the confirm tick result itself.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): Result {
        val result = super.doWork()
        try {
            notifyAfterTick()
        } catch (e: Exception) {
            Log.e(TAG, "post-tick notification check failed", e)
        }
        return result
    }

    private suspend fun notifyAfterTick() {
        val db = dbFactory(applicationContext)
        try {
            NotificationSync(applicationContext, db.tickInputDao(), db.runLogDao(), clock = clock).checkAndNotify()
        } finally {
            db.close()
        }
    }

    override suspend fun dailyCollect() {
        val credentialsStore = credentialsStoreFactory(applicationContext)
        if (!credentialsStore.isCollectConfigured()) {
            recordNotConfigured()
            return
        }
        val db = dbFactory(applicationContext)
        try {
            val collectors = collectorsFactory(applicationContext, credentialsStore)
            val today = LocalDate.now(clock.withZone(KST))
            val range = today.minusDays(DAILY_COLLECT_LOOKBACK_DAYS)..today
            for (collector in collectors) {
                appendConfirmedRows(db, collector.collect(range))
            }
        } finally {
            db.close()
        }
    }

    private suspend fun recordNotConfigured() {
        val db = dbFactory(applicationContext)
        try {
            db.runLogDao().insert(
                RunLogEntity(
                    tradingDate = null,
                    ranAt = clock.millis(),
                    status = STATUS_NOT_CONFIGURED,
                    detail = "KRX/FRED credentials not configured -- dailyCollect skipped, complete onboarding",
                ),
            )
        } finally {
            db.close()
        }
    }

    private suspend fun appendConfirmedRows(
        db: LakeDatabase,
        outcome: CollectOutcome,
    ) {
        val rows =
            when (outcome) {
                is CollectOutcome.Ok -> outcome.rows
                is CollectOutcome.Partial -> outcome.rows
                is CollectOutcome.Failed -> return
            }
        for (row in rows) {
            // Same-cell re-collection within the lookback window collides on the UNIQUE index
            // (ux_obs_cell_rev) -- absorbed exactly like WarmupBackfillOrchestrator.appendRows
            // (revision=0 always; confirmed daily closes are stable once collected).
            runCatching { db.observationDao().insert(row.toConfirmedEntity()) }
        }
    }

    private fun Observation.toConfirmedEntity() =
        ObservationEntity(
            seriesId = seriesId,
            field = field,
            asOf = asOf.toEpochMilli(),
            value = value,
            observedAt = observedAt.toEpochMilli(),
            revision = 0,
            lane = lane,
            source = source,
        )
}
