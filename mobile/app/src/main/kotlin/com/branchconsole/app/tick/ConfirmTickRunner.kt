package com.branchconsole.app.tick

import android.database.sqlite.SQLiteConstraintException
import com.branchconsole.app.collectors.WarmupReport
import com.branchconsole.app.tick.WarmupGate.isReady
import com.branchconsole.engine.pit.Visibility
import com.branchconsole.lake.ObservationDao
import com.branchconsole.lake.RunLogDao
import com.branchconsole.lake.RunLogEntity
import com.branchconsole.lake.TickInputDao
import com.branchconsole.lake.TickInputEntity
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val KST = ZoneOffset.ofHours(9)

// run_log status/reason 어휘. WARMUP_INSUFFICIENT·CATCHUP_GAP_TRUNCATED·CALENDAR_FALLBACK은
// docs/plans/M1_PLAN_C.md §4.1 카탈로그의 코드를 그대로 쓴다 — 이 러너가 자신 있게 원인을 특정할
// 수 있는 것은 이 셋뿐이다. 그 외 예외는 "failed" + 원본 예외 메시지로 남긴다(24종 카탈로그
// 전건 분류는 collectors·UI 계층을 가로지르는 별개 과업, MT1-08 소관).
private const val STATUS_STARTED = "started"
private const val STATUS_NOOP = "noop"
private const val STATUS_SUCCESS = "success"
private const val STATUS_FAILED = "failed"
private const val REASON_WARMUP_INSUFFICIENT = "WARMUP_INSUFFICIENT"
private const val REASON_CATCHUP_GAP_TRUNCATED = "CATCHUP_GAP_TRUNCATED"

internal sealed interface ConfirmTickOutcome {
    data object NoOp : ConfirmTickOutcome

    data class WarmupBlocked(val report: WarmupReport) : ConfirmTickOutcome

    data class Committed(val committedDates: List<LocalDate>, val gapSkipped: List<LocalDate>) : ConfirmTickOutcome
}

/**
 * MT1-06c~h — 일일 확정 틱 파이프라인 진입점(WorkManager가 매일 호출하는 단일 지점,
 * [com.branchconsole.app.tick.ConfirmTickWorker]). 라이브 실행과 캐치업은 **같은 코드 경로**다
 * (docs/plans/M1_PLAN_B.md §5.6 — 놓친 거래일이 1개면 평범한 일일 실행, N개면 캐치업. 둘을
 * 가르는 것은 `toProcess.size`뿐, 별도 분기가 없다).
 *
 * 절차 — **B §5.6은 틱 1건의 절차이고 이 함수는 배치(캐치업 포함) 단위 절차라 1:1은 아니다**
 * (aaa F-2·F-6, 이전 판의 "1:1" 주석은 과장이었다). 그 취지(started 선기록 → 본문 → 실패도
 * 기록 → 종료)만 그대로 옮긴다:
 *  1) `run_log(started)` 선기록(F-2) — 이후 어디서 예외가 나도 실행 흔적이 남는다
 *  2) 그리드([TradingDayGridProvider]) 확보, 그리드 공백 의심 지점 기록
 *     ([ConfirmTickCandidates.logSuspectedGaps], F-5)
 *  3) 후보 산출([ConfirmTickCandidates]) — 부트스트랩(최초 실행)은 **최신 거래일 1건**만(D-D4,
 *     F-4), 이후는 "미커밋 집합 차집합"이라 늦게 도착한 관측도 재편입된다(F-5)
 *  4) 후보가 없으면 no-op(`run_log` "noop")
 *  5) `tick_input`이 비어 있으면(=최초 부트스트랩) 게이트([WarmupGate]) 통과 확인 —
 *     미통과 시 행 0개로 종료(D-D4, M-45/46) + `run_log` "WARMUP_INSUFFICIENT"
 *  6) 상한(`catchup_max_ticks`, M-17b) 초과분은 오래된 쪽을 잘라 `composite=NULL` +
 *     `CATCHUP_GAP_TRUNCATED` 동결 행 1건으로 남긴다(M-34) — 절단 이전 `tick_input` 행은 그대로
 *     두므로(fold가 §5.6.2에서 증명한 대로 카운터를 조작하지 않는다) 별도 리셋 로직이 없다
 *  7) 남은 후보를 오름차순 1일 1커밋 — `tick_input.trading_date` UNIQUE가 물리적 멱등 근거
 *     (K-14, 이중 실행·레이스 모두 이 제약 하나로 흡수)
 *  8) `run_log`에 성공/실패 이력 기록(K-15 누락 노출)
 *
 * `date != today`로 개별 틱의 `is_catchup`을 정한다 — 같은 배치에서 여러 날짜를 한꺼번에
 * 커밋해도 "오늘" 날짜의 틱만 라이브(정시 산출)이고 나머지는 근사-PIT 재구성이다.
 */
internal class ConfirmTickRunner(
    private val observationDao: ObservationDao,
    private val tickInputDao: TickInputDao,
    private val runLogDao: RunLogDao,
    private val gridProvider: TradingDayGridProvider,
    private val config: ConfirmTickConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** F-2: `started`를 맨 먼저 남기고 본문 전체를 감싼다 — 예외가 나도 run_log에 사유가
     * 남은 뒤 재전파된다(호출자 [ConfirmTickWorker.doWork]가 `Result.failure()`로 변환).
     * 실패를 삼키지 않고 기록 후 다시 던지므로 `TooGenericExceptionCaught` 대상이 아니다. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun run(): ConfirmTickOutcome {
        val ranAt = clock.instant()
        logRun(ranAt, tradingDate = null, status = STATUS_STARTED, detail = null)
        try {
            return runInternal(ranAt)
        } catch (e: Exception) {
            val reason = "${e::class.simpleName}: ${e.message}"
            logRun(clock.instant(), tradingDate = null, status = STATUS_FAILED, detail = reason)
            throw e
        }
    }

    @Suppress("ReturnCount") // 가드 절 스타일(no-op·부트스트랩 차단·정상 종료) — KrxCollector.ensureLoggedIn과 동일 판단.
    private suspend fun runInternal(ranAt: Instant): ConfirmTickOutcome {
        val today = LocalDate.now(clock.withZone(KST))
        val grid = gridProvider.tradingDaysUpTo(today)
        val committed = tickInputDao.allOrderedByDate()
        val isBootstrap = committed.isEmpty()

        if (!isBootstrap) ConfirmTickCandidates.logSuspectedGaps(grid, committed, today, ranAt, runLogDao)

        val candidates =
            if (isBootstrap) {
                ConfirmTickCandidates.forBootstrap(grid, today, ranAt, config.confirmTimeKst)
            } else {
                ConfirmTickCandidates.forOngoing(grid, committed, today, ranAt, config.confirmTimeKst)
            }

        if (candidates.isEmpty()) {
            logRun(ranAt, tradingDate = null, status = STATUS_NOOP, detail = "no candidate trading days")
            return ConfirmTickOutcome.NoOp
        }

        val ctx = ConfirmTickContext.load(observationDao, grid, config, candidates.last())
        val cutoffMillis = candidates.last().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val gate = WarmupGate.check(ctx, config.specs, cutoffMillis)

        if (isBootstrap && !gate.isReady()) {
            logRun(ranAt, tradingDate = null, status = REASON_WARMUP_INSUFFICIENT, detail = gate.toJson())
            return ConfirmTickOutcome.WarmupBlocked(gate)
        }

        val toProcess = candidates.takeLast(config.catchupMaxTicks)
        val gapSkipped = candidates.dropLast(toProcess.size)
        val runtimes = config.specs.associate { it.id to buildIndicatorRuntime(it, ctx) }

        if (gapSkipped.isNotEmpty()) insertGapRow(gapSkipped, ranAt)

        val committedDates = mutableListOf<LocalDate>()
        for (date in toProcess) {
            val evaluatedAt = confirmInstant(date)
            val evaluation = ConfirmTickEvaluator.evaluate(date, evaluatedAt, config, runtimes)
            val entity =
                evaluation.toEntity(
                    isCatchup = date != today,
                    registryVersion = config.registryVersion,
                    frozenAt = ranAt.toEpochMilli(),
                    warmupStatusJson = gate.toJson(),
                )
            if (insertIfAbsent(entity)) committedDates += date
        }

        logRun(
            ranAt,
            tradingDate = toProcess.last().toString(),
            status = STATUS_SUCCESS,
            detail = "committed=${committedDates.size} gap_skipped=${gapSkipped.size}",
        )
        return ConfirmTickOutcome.Committed(committedDates, gapSkipped)
    }

    private fun confirmInstant(date: LocalDate): Instant = Visibility.kstToUtc(date, config.confirmTimeKst)

    // trading_date PK 충돌(SQLiteConstraintException, 메시지에 그 컬럼명이 실린다)만 멱등
    // no-op으로 흡수한다(aaa F-2 — 이전 판은 모든 제약 위반을 흡수했다). 다른 제약 위반은 실제
    // 버그일 수 있으므로 그대로 전파한다 — catch 자체가 좁혀졌으므로 SwallowedException 대상이
    // 아니다.
    private suspend fun insertIfAbsent(entity: TickInputEntity): Boolean {
        try {
            tickInputDao.insert(entity)
            return true
        } catch (e: SQLiteConstraintException) {
            if (e.message?.contains("trading_date") == true) return false
            throw e
        }
    }

    private suspend fun insertGapRow(
        skipped: List<LocalDate>,
        ranAt: Instant,
    ) {
        val gapDate = skipped.last()
        val reason =
            "$REASON_CATCHUP_GAP_TRUNCATED: cap ${config.catchupMaxTicks} exceeded, " +
                "${skipped.size} trading day(s) skipped (${skipped.first()}..${skipped.last()})"
        val entity =
            TickInputEntity(
                tradingDate = gapDate.toString(),
                composite = null,
                distinctAxes = 0,
                anyCrit = false,
                anyExtreme = false,
                severitiesJson = "{}",
                coverage = 0.0,
                registryVersion = config.registryVersion,
                gapReason = reason,
                frozenAt = ranAt.toEpochMilli(),
                firedAxes = null,
                visibleAtByIndicator = null,
                isCatchup = true,
                warmupStatusJson = null,
                pitQuality = "gap",
            )
        insertIfAbsent(entity)
    }

    private suspend fun logRun(
        ranAt: Instant,
        tradingDate: String?,
        status: String,
        detail: String?,
    ) {
        runLogDao.insert(
            RunLogEntity(tradingDate = tradingDate, ranAt = ranAt.toEpochMilli(), status = status, detail = detail),
        )
    }
}
