package com.branchconsole.app.preview

import android.content.Context
import com.branchconsole.app.collectors.CollectOutcome
import com.branchconsole.app.collectors.Collector
import com.branchconsole.app.collectors.Observation
import com.branchconsole.app.format.formatComposite
import com.branchconsole.app.format.formatCoveragePercent
import com.branchconsole.app.notif.NotificationChannels
import com.branchconsole.app.notif.NotificationGate
import com.branchconsole.app.notif.NotificationSender
import com.branchconsole.app.notif.ProvisionalAlertEvaluator
import com.branchconsole.app.tick.AssetConfigSource
import com.branchconsole.app.tick.ConfirmTickConfigLoader
import com.branchconsole.engine.config.ConfigSource
import com.branchconsole.engine.config.PreviewPolicy
import com.branchconsole.lake.LakeDatabase
import com.branchconsole.lake.Lane
import com.branchconsole.lake.ObservationEntity
import java.time.Clock
import java.time.LocalDate

// Same classification as ProductionConfirmTickWorker.DAILY_COLLECT_LOOKBACK_DAYS: an operational
// fetch-window margin (covers weekends/holidays for the underlying series read), not a threshold/
// weight/schedule (CLAUDE.md §1 scope) -- it doesn't affect scoring, phase transitions, or when
// this runs (only the user's own button tap does).
private const val PREVIEW_COLLECT_LOOKBACK_DAYS = 5L
private const val NOTIF_ID_PROVISIONAL_ALERT = 1002

/**
 * MT1-08b — "프리뷰 갱신" 버튼의 실제 진입점. [PreviewTickRunner] 자체는 의도적으로 수집
 * 오케스트레이션을 갖지 않는다(그 클래스 KDoc: "collectors 트리거는 이 클래스 밖 — MT1-08 UI가
 * 실제 트리거를 필요로 할 때 추가"). 이 클래스가 그 지점이다.
 *
 * 절차: (1) [collectors]를 최근 [PREVIEW_COLLECT_LOOKBACK_DAYS]일 구간으로 돌려 lane=1
 * (PREVIEW)로 append (2) [PreviewTickRunner] 실행 (3) crit 이면서 억제가 아니면
 * `provisional_alert` 발신(D §3.9.1).
 *
 * lane=1 재작성은 매번 다음 revision을 계산해 삽입한다(revision=0 고정이면 장중 반복 탭에서
 * 두 번째 탭부터 `ux_obs_cell_rev` UNIQUE 충돌로 조용히 무시돼 버튼이 죽은 척한다 —
 * [com.branchconsole.app.collectors.WarmupBackfillOrchestrator]의 "매일 1회, 확정 계열이라
 * 안정적" 전제가 프리뷰에는 성립하지 않는다).
 */
internal class PreviewRefreshUseCase(
    private val context: Context,
    private val db: LakeDatabase,
    private val collectors: List<Collector>,
    private val configSource: ConfigSource = AssetConfigSource(context),
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun refresh(): PreviewResult {
        collectAndAppendPreviewRows()

        val config = ConfirmTickConfigLoader.load(configSource)
        val previewCoverageMin = PreviewPolicy.previewCoverageMin(configSource)
        val result = PreviewTickRunner(db.observationDao(), db.tickInputDao(), config, previewCoverageMin, clock).run()

        maybeSendProvisionalAlert(result)
        return result
    }

    private suspend fun collectAndAppendPreviewRows() {
        val today = LocalDate.now(clock)
        val range = today.minusDays(PREVIEW_COLLECT_LOOKBACK_DAYS)..today
        for (collector in collectors) {
            appendPreviewRows(collector.collect(range))
        }
    }

    private suspend fun appendPreviewRows(outcome: CollectOutcome) {
        val rows =
            when (outcome) {
                is CollectOutcome.Ok -> outcome.rows
                is CollectOutcome.Partial -> outcome.rows
                is CollectOutcome.Failed -> return
            }
        for (row in rows) appendPreviewRow(row)
    }

    private suspend fun appendPreviewRow(row: Observation) {
        val asOfMillis = row.asOf.toEpochMilli()
        val nextRevision = (db.observationDao().maxRevision(row.seriesId, row.field, asOfMillis, Lane.PREVIEW) ?: -1) + 1
        // Same-cell races (two refreshes overlapping) would collide on the UNIQUE index; absorbed
        // the same way WarmupBackfillOrchestrator.appendRows does (K-14-style "best effort, never
        // crash the whole refresh over one row").
        runCatching {
            db.observationDao().insert(
                ObservationEntity(
                    seriesId = row.seriesId,
                    field = row.field,
                    asOf = asOfMillis,
                    value = row.value,
                    observedAt = row.observedAt.toEpochMilli(),
                    revision = nextRevision,
                    lane = Lane.PREVIEW,
                    source = row.source,
                ),
            )
        }
    }

    private fun maybeSendProvisionalAlert(result: PreviewResult) {
        val severities = result.indicators.values.map { it.severity }
        if (!ProvisionalAlertEvaluator.shouldNotify(result.suppressed, severities)) return
        if (!NotificationGate.isEnabled(context)) return
        NotificationSender.send(
            context = context,
            channelId = NotificationChannels.PROVISIONAL_ALERT,
            notificationId = NOTIF_ID_PROVISIONAL_ALERT,
            title = "잠정 경보",
            text = "PREVIEW · composite ${formatComposite(result.filledComposite)} · coverage ${formatCoveragePercent(result.rawCoverage)}",
        )
    }
}
