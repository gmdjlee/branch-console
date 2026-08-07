package com.branchconsole.app.notif

import android.content.Context
import com.branchconsole.app.tick.AssetConfigSource
import com.branchconsole.app.tick.toTick
import com.branchconsole.engine.config.ConfigSource
import com.branchconsole.engine.config.StatemachineConfigLoader
import com.branchconsole.engine.statemachine.StateMachine
import com.branchconsole.lake.RunLogDao
import com.branchconsole.lake.TickInputDao
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

private val KST = ZoneOffset.ofHours(9)
private const val PROFILE_NAME = "mobile_daily"
private const val PREFS_NAME = "notif_sync_state"
private const val KEY_LAST_PHASE_DATE = "last_phase_notified_trading_date"
private const val KEY_LAST_PHASE_VALUE = "last_phase_notified_value"
private const val KEY_LAST_FAILURE_ROW_ID = "last_failure_row_id_seen"
private const val KEY_LAST_FAILURE_NOTIFIED_KST_DATE = "last_failure_notified_kst_date"

// aaa N-1 (D §3.9.1 "status != success", C §4.1 KEY_MISSING) -- tick_failure must also fire for
// a confirm tick that never got attempted at all (config_error) or was skipped for missing
// credentials (not_configured, ProductionConfirmTickWorker.STATUS_NOT_CONFIGURED duplicated here
// as a literal -- same cross-file convention already used for STATUS_FAILED, not an SSOT value).
// The existing 1-per-KST-day cursor budget below applies unchanged to these two as well.
private const val STATUS_FAILED = "failed"
private const val STATUS_CONFIG_ERROR = "config_error"
private const val STATUS_NOT_CONFIGURED = "not_configured"
private val TICK_FAILURE_STATUSES = setOf(STATUS_FAILED, STATUS_CONFIG_ERROR, STATUS_NOT_CONFIGURED)
private const val NOTIF_ID_PHASE_TRANSITION = 1001
private const val NOTIF_ID_TICK_FAILURE = 1003

/**
 * MT1-08a 오케스트레이터 — `tick_input`/`run_log`(Room, `:lake`)를 읽어 `phase_transition`·
 * `tick_failure` 노티를 낸다. `provisional_alert`는 여기서 다루지 않는다 — 프리뷰는 사용자
 * 조작으로만 실행되고(D-17 §1) 배경 폴링 대상이 아니므로, 그 트리거는 프리뷰 갱신 직후
 * 홈 화면이 [ProvisionalAlertEvaluator]를 직접 호출한다.
 *
 * `tick/`(확정 틱 파이프라인)은 병렬 워커가 `ConfirmTickCandidates`를 재작업 중이라 그 파일들을
 * 수정하지 않는다(브리프 지시) — 이 클래스는 그 산출물(Room 테이블)만 읽고,
 * [AssetConfigSource]·[toTick]만 재사용한다(읽기 참조는 허용 — `com.branchconsole.app.preview`
 * 패키지가 이미 `tick` 패키지의 `ConfirmTickContext`·`ConfirmTickConfig` 등을 그대로 소비하는
 * 것과 동일한 선례. aaa M-3: 이 클래스가 자체 `toEngineTick()`을 재구현했던 판은 반려됐다 —
 * `PhaseDerivation.toTick()`과 완전히 동일한 4필드 매핑을 두 곳에 둘 이유가 없다).
 *
 * 커서는 평문 `SharedPreferences`(비밀 아님 — 그냥 "마지막으로 확인한 지점")로 유지해 중복·과거
 * 재알림을 막는다. 호출 시점은 [com.branchconsole.app.production.ProductionConfirmTickWorker]가
 * `doWork()`를 override해 `super.doWork()` 직후 [checkAndNotify]를 부르는 것뿐이다(aaa M-5 —
 * 확정 틱 완료 시점에 직접 물리고, 별도 폴링 워커를 두지 않는다). 이 클래스 자체는 WorkManager를
 * 전혀 모른다(순수 조립 + 부수효과 격리 — [notify] 콜백을 테스트가 가로챈다).
 */
internal class NotificationSync(
    private val context: Context,
    private val tickInputDao: TickInputDao,
    private val runLogDao: RunLogDao,
    private val configSource: ConfigSource = AssetConfigSource(context),
    private val clock: Clock = Clock.systemUTC(),
    private val notify: (channelId: String, notificationId: Int, title: String, text: String) -> Unit =
        { channelId, notificationId, title, text ->
            if (NotificationGate.isEnabled(context)) {
                NotificationSender.send(context, channelId, notificationId, title, text)
            }
        },
) {
    private val prefs get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun checkAndNotify() {
        checkPhaseTransition()
        checkTickFailure()
    }

    @Suppress("ReturnCount") // guard-clause style — same judgment as KrxCollector.ensureLoggedIn.
    private suspend fun checkPhaseTransition() {
        val rows = tickInputDao.allOrderedByDate()
        if (rows.isEmpty()) return
        val statemachineConfig =
            runCatching { StatemachineConfigLoader.load(configSource) }.getOrNull() ?: return
        val profile = statemachineConfig.profiles[PROFILE_NAME] ?: return
        val timeline = StateMachine.run(rows.map { it.toTick() }, profile, statemachineConfig)

        val lastNotifiedDate = prefs.getString(KEY_LAST_PHASE_DATE, null)
        val newIndices =
            rows.indices.filter { i -> lastNotifiedDate == null || rows[i].tradingDate > lastNotifiedDate }
        if (newIndices.isEmpty()) return

        val previousPhase =
            prefs.getString(KEY_LAST_PHASE_VALUE, null)
                ?: if (newIndices.first() == 0) statemachineConfig.initialPhase else timeline[newIndices.first() - 1]
        val decision = PhaseTransitionEvaluator.evaluate(previousPhase, newIndices.map { timeline[it] })

        if (decision.shouldNotify) {
            val text =
                if (decision.batchSize > 1) {
                    "${decision.batchSize}일 따라잡음 · ${decision.fromPhase} -> ${decision.toPhase}"
                } else {
                    "${decision.fromPhase} -> ${decision.toPhase} · ${rows[newIndices.last()].tradingDate}"
                }
            notify(NotificationChannels.PHASE_TRANSITION, NOTIF_ID_PHASE_TRANSITION, "국면 전이", text)
        }
        prefs.edit()
            .putString(KEY_LAST_PHASE_DATE, rows[newIndices.last()].tradingDate)
            .putString(KEY_LAST_PHASE_VALUE, decision.toPhase)
            .apply()
    }

    // guard-clause style (empty history / nothing new / already budgeted today) -- same judgment
    // as checkPhaseTransition above.
    @Suppress("ReturnCount")
    private suspend fun checkTickFailure() {
        val rows = runLogDao.allOrderedByRanAt()
        if (rows.isEmpty()) return
        val lastSeenId = prefs.getLong(KEY_LAST_FAILURE_ROW_ID, 0L)
        val newFailures = rows.filter { it.status in TICK_FAILURE_STATUSES && it.id > lastSeenId }
        prefs.edit().putLong(KEY_LAST_FAILURE_ROW_ID, rows.maxOf { it.id }).apply()
        if (newFailures.isEmpty()) return

        val today = LocalDate.now(clock.withZone(KST)).toString()
        val alreadyNotifiedToday = prefs.getString(KEY_LAST_FAILURE_NOTIFIED_KST_DATE, null) == today
        if (alreadyNotifiedToday) return

        notify(
            NotificationChannels.TICK_FAILURE,
            NOTIF_ID_TICK_FAILURE,
            "확정 틱 실패",
            newFailures.last().detail ?: "사유 미상 — 실행 이력을 확인하세요",
        )
        prefs.edit().putString(KEY_LAST_FAILURE_NOTIFIED_KST_DATE, today).apply()
    }
}
