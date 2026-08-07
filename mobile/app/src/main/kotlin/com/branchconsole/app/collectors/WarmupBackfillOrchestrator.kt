package com.branchconsole.app.collectors

import com.branchconsole.lake.ObservationDao
import com.branchconsole.lake.ObservationEntity
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * MT1-04g — 최초 설치 웜업 백필 오케스트레이터.
 *
 * 오늘 - warmup_padding_days(달력일) ~ 오늘 범위를 [collectors] 각각에서 **단일 호출**로
 * 수집해 [ObservationDao]에 lane=0으로 append하고, 계열별 적재 행수·기간·결측 사유(구조적
 * 부재/오류/미수집)를 [WarmupReport]로 낸다. 웜업 범위는 호출부가 [WarmupConfig.loadPaddingDays]로
 * assets(SSOT)에서 읽어 [run]에 전달한다 — 이 클래스는 550을 모른다.
 *
 * 재개(중단 후 재실행)는 별도 체크포인트 없이 **전체 재실행 + 멱등 append**로 처리한다 — 이미
 * 적재된 셀은 `ux_obs_cell_rev`(series_id, field, as_of, lane, revision) UNIQUE 충돌로 조용히
 * 건너뛰고, 아직 못 받은 계열만 이번 실행에서 실제로 채워진다. `range` 상한이 병목이 아니라는
 * 실측(docs/journal/2026-08-07_MT1-00g_confirm_time_probe.md §7 — pykrx·yfinance 둘 다 요청
 * 구간을 그대로 반환, 관측된 상한 없음)이 이 단순화의 근거다 — 분할 호출·체크포인트 상태기계
 * 둘 다 과설계다.
 *
 * ponytail: 정교한 revision 채번(같은 as_of의 값이 바뀐 재수집이면 revision+1)은 후속 과업이다
 * — 이번 백필은 항상 revision=0으로 삽입하고, 같은 셀의 값이 달라진 재수집은 (아직) 구분하지
 * 않고 동일하게 건너뛴다.
 */
class WarmupBackfillOrchestrator(
    private val observationDao: ObservationDao,
    private val collectors: List<Collector>,
    private val notCollected: List<WarmupSeriesStatus> = DEFAULT_NOT_COLLECTED,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun run(paddingDays: Int): WarmupReport {
        val today = LocalDate.now(clock)
        val start = today.minusDays(paddingDays.toLong())
        val range = start..today
        val windowStartMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val windowEndMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        val statuses = mutableListOf<WarmupSeriesStatus>()
        for (collector in collectors) {
            val outcome = collector.collect(range)
            appendRows(outcome)
            statuses += statusesFor(collector.expectedSeriesIds, outcome, windowStartMillis)
        }
        statuses += notCollected

        return WarmupReport(windowStartMillis, windowEndMillis, statuses)
    }

    private suspend fun appendRows(outcome: CollectOutcome) {
        val rows =
            when (outcome) {
                is CollectOutcome.Ok -> outcome.rows
                is CollectOutcome.Partial -> outcome.rows
                is CollectOutcome.Failed -> return
            }
        for (row in rows) {
            // 멱등 재실행 — 이전 백필이 이미 채운 (series_id, field, as_of, lane, revision=0) 셀은
            // SQLiteConstraintException으로 거부된다. INSERT IGNORE 수준으로만 흡수한다(브리프
            // 지정, 정교한 revision 채번은 후속). runCatching이므로 SwallowedException 대상이
            // 아니다(catch 절이 아니라 값으로 다루고 명시적으로 버린다).
            runCatching {
                observationDao.insert(
                    ObservationEntity(
                        seriesId = row.seriesId,
                        field = row.field,
                        asOf = row.asOf.toEpochMilli(),
                        value = row.value,
                        observedAt = row.observedAt.toEpochMilli(),
                        revision = 0,
                        lane = row.lane,
                        source = row.source,
                    ),
                )
            }
        }
    }

    companion object {
        /** CDS는 00d에서 확정된 미수집 상태 — 수집을 시도하지 않고 리포트에만 표기한다. ECOS는
         * 00b §7.9로 K-04가 종결돼 MT1-04d부터 [com.branchconsole.app.collectors.ecos.EcosCollector]로
         * 실제 수집되므로(성공/실패 여부는 이제 일반 [collectors] 경로가 계열별로 채운다) 더 이상
         * 여기 없다. */
        val DEFAULT_NOT_COLLECTED =
            listOf(
                WarmupSeriesStatus(
                    seriesId = "KR_CDS_5Y",
                    status = WarmupStatus.NOT_COLLECTED,
                    rows = 0,
                    reason =
                        "모바일 v1 미수집 확정 (b) " +
                            "(docs/journal/2026-08-07_MT1-00d_cds_feasibility.md) — 수집 시도 안 함",
                ),
            )

        /**
         * [collector]의 결과를 [expectedSeriesIds] 각각에 대한 상태로 편다.
         *
         * `Failed`는 계열별 정보가 없으므로 전체를 ERROR로 채운다. `Ok`/`Partial`은 행을
         * seriesId로 묶어 계열별 행수·기간을 계산하고, 요청 구간 시작보다 늦게 시작하는 계열은
         * `structuralAbsenceBefore`로 표시한다(오류가 아니라 구조적 부재 — aaa 요건 3).
         */
        internal fun statusesFor(
            expectedSeriesIds: List<String>,
            outcome: CollectOutcome,
            windowStartMillis: Long,
        ): List<WarmupSeriesStatus> {
            if (outcome is CollectOutcome.Failed) {
                return expectedSeriesIds.map {
                    WarmupSeriesStatus(it, WarmupStatus.ERROR, rows = 0, reason = outcome.reason.message)
                }
            }
            val rows = if (outcome is CollectOutcome.Ok) outcome.rows else (outcome as CollectOutcome.Partial).rows
            val failures = if (outcome is CollectOutcome.Partial) outcome.failures else emptyList()
            val rowsBySeries = rows.groupBy { it.seriesId }
            val failuresBySeries = failures.groupBy { it.seriesId }
            return expectedSeriesIds.map { seriesId ->
                seriesStatus(
                    seriesId,
                    rowsBySeries[seriesId].orEmpty(),
                    failuresBySeries[seriesId].orEmpty(),
                    windowStartMillis,
                )
            }
        }

        private fun seriesStatus(
            seriesId: String,
            seriesRows: List<Observation>,
            seriesFailures: List<SeriesFailure>,
            windowStartMillis: Long,
        ): WarmupSeriesStatus {
            val earliest = seriesRows.minOfOrNull { it.asOf.toEpochMilli() }
            val latest = seriesRows.maxOfOrNull { it.asOf.toEpochMilli() }
            val status =
                when {
                    seriesRows.isEmpty() -> WarmupStatus.ERROR
                    seriesFailures.isNotEmpty() -> WarmupStatus.PARTIAL
                    else -> WarmupStatus.OK
                }
            return WarmupSeriesStatus(
                seriesId = seriesId,
                status = status,
                rows = seriesRows.size,
                earliestAsOf = earliest,
                latestAsOf = latest,
                structuralAbsenceBefore = earliest?.takeIf { it > windowStartMillis },
                reason = seriesFailures.firstOrNull()?.reason?.message,
            )
        }
    }
}
