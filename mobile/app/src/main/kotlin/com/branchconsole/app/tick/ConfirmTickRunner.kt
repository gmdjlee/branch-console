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
 * 절차(§5.6 커밋 절차 원자성 1:1):
 *  1) 그리드([TradingDayGridProvider]) 확보
 *  2) 마지막 커밋 거래일 이후 ~ 오늘까지, **확정 시각이 이미 지난** 거래일만 후보로 추림
 *  3) 후보가 없으면 no-op(`run_log` "noop")
 *  4) `tick_input`이 비어 있으면(=최초 부트스트랩) 게이트([WarmupGate]) 통과 확인 —
 *     미통과 시 행 0개로 종료(D-D4, M-45/46) + `run_log` "blocked_warmup"
 *  5) 상한(`catchup_max_ticks`, M-17b) 초과분은 오래된 쪽을 잘라 `composite=NULL` + `gap_reason`
 *     동결 행 1건으로 남긴다(M-34) — 절단 이전 `tick_input` 행은 그대로 두므로(fold가 §5.6.2에서
 *     증명한 대로 카운터를 조작하지 않는다) 별도 리셋 로직이 없다
 *  6) 남은 후보를 오름차순 1일 1커밋 — `tick_input.trading_date` UNIQUE가 물리적 멱등 근거
 *     (K-14, 이중 실행·레이스 모두 이 제약 하나로 흡수)
 *  7) `run_log`에 실행 이력 기록(K-15 누락 노출)
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
    // 가드 절 스타일(no-op·부트스트랩 차단·정상 종료 3갈래) — KrxCollector.ensureLoggedIn과 동일 판단
    // (각 갈래를 별도 private 함수로 쪼개면 ranAt·candidates·gate 등 지역 상태를 도로 파라미터로
    // 되돌려줘야 해 오히려 가독성이 떨어진다).
    @Suppress("ReturnCount")
    suspend fun run(): ConfirmTickOutcome {
        val ranAt = clock.instant()
        val today = LocalDate.now(clock.withZone(KST))
        val grid = gridProvider.tradingDaysUpTo(today)
        val committed = tickInputDao.allOrderedByDate()
        val lastCommittedDate = committed.lastOrNull()?.let { LocalDate.parse(it.tradingDate) }

        val candidates =
            grid
                .filter { lastCommittedDate == null || it > lastCommittedDate }
                .filter { !it.isAfter(today) }
                .filter { !ranAt.isBefore(confirmInstant(it)) }

        if (candidates.isEmpty()) {
            logRun(ranAt, tradingDate = null, status = "noop", detail = "no candidate trading days")
            return ConfirmTickOutcome.NoOp
        }

        val ctx = ConfirmTickContext.load(observationDao, grid, config, candidates.last())
        val cutoffMillis = candidates.last().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val gate = WarmupGate.check(ctx, config.specs, cutoffMillis)

        if (committed.isEmpty() && !gate.isReady()) {
            logRun(ranAt, tradingDate = null, status = "blocked_warmup", detail = gate.toJson())
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
            status = "success",
            detail = "committed=${committedDates.size} gap_skipped=${gapSkipped.size}",
        )
        return ConfirmTickOutcome.Committed(committedDates, gapSkipped)
    }

    private fun confirmInstant(date: LocalDate): Instant = Visibility.kstToUtc(date, config.confirmTimeKst)

    // trading_date UNIQUE 충돌(SQLiteConstraintException) = 이미 처리됨(동시 실행 레이스 또는
    // 재실행) — 멱등 no-op이라 예외 내용 자체는 버려도 정보 손실이 없다(예외 타입이 이미 원인을
    // 특정한다). 다른 예외 타입은 여전히 전파된다(catch 절이 좁게 잡혀 있다).
    @Suppress("SwallowedException")
    private suspend fun insertIfAbsent(entity: TickInputEntity): Boolean =
        try {
            tickInputDao.insert(entity)
            true
        } catch (e: SQLiteConstraintException) {
            false
        }

    private suspend fun insertGapRow(
        skipped: List<LocalDate>,
        ranAt: Instant,
    ) {
        val gapDate = skipped.last()
        val reason =
            "catchup cap ${config.catchupMaxTicks} exceeded: ${skipped.size} trading day(s) skipped " +
                "(${skipped.first()}..${skipped.last()})"
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
