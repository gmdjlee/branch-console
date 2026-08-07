package com.branchconsole.app.tick

import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.branchconsole.app.collectors.WarmupStatus
import com.branchconsole.app.tick.WarmupGate.isReady
import com.branchconsole.engine.config.ConfigSource
import com.branchconsole.lake.LakeDatabase
import com.branchconsole.lake.ObservationDao
import com.branchconsole.lake.ObservationEntity
import com.branchconsole.lake.SeriesPoint
import com.branchconsole.lake.TickInputDao
import com.branchconsole.lake.TickInputEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.InputStream
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

private const val REGISTRY_VERSION = "0.0.0-test"

/**
 * 픽스처 SSOT (실제 `configs/` 원본은 건드리지 않는다 — SSOT 규율). 지표 1종
 * `kospi_drawdown`(실제 BUILDERS 맵의 알려진 id — 임의 id는 [buildIndicatorRuntime]이
 * 거부한다)만 두되, `window=2`로 축소해 최소 3행이면 웜업이 충족되게 한다(실제 SSOT의
 * `window=60`을 쓰면 매 테스트가 60행 이상을 세팅해야 해 검증 대상(파이프라인 배선)과 무관한
 * 잡음이 커진다 — 픽스처는 파이프라인 메커니즘만 겨눈다, BT-05가 이미 실지표 수치 정합을 덮는다).
 */
private val INDICATORS_YAML =
    """
    registry_version: "$REGISTRY_VERSION"
    engine:
      warmup_padding_days: 550
      preview_coverage_min: 0.80
      modifiers:
        - { id: hy_level_boost, rule: "hy_oas_level > 4.5 -> hy_oas_delta.severity += 1 (max 3)" }
        - { id: usdkrw_intraday_force, rule: "usdkrw intraday_range >= 1.2% -> severity max(warn); >= 2.0% -> crit" }
      stale_profiles:
        mobile_daily:
          daily_kr: 720h
    indicators:
      - id: kospi_drawdown
        name_kr: test
        axis: kr_flow_price
        weight: 1.0
        source: { provider: pykrx, dataset: index_ohlcv, symbol: "1001", cadence: daily_kr }
        transform: drawdown_from_high(window=2)
        direction: higher_is_risk
        thresholds: { watch: 3.0, warn: 8.0, crit: 12.0 }
    """.trimIndent()

private fun statemachineYaml(confirmTimeLine: String) =
    """
    schema: statemachine/1
    phases: [GREEN, AMBER, ORANGE, RED]
    initial_phase: GREEN
    upgrade:
      rules:
        AMBER: { composite_gte: 20 }
        ORANGE: { composite_gte: 40, distinct_axes_gte: 2 }
        RED: { composite_gte: 60, distinct_axes_gte: 3 }
    downgrade:
      rules:
        exit_RED: { composite_lt: 50 }
        exit_ORANGE: { composite_lt: 32 }
        exit_AMBER: { composite_lt: 14 }
    skip_levels: true
    profiles:
      mobile_daily:
        tick: 1d
        promote_sustain_ticks: 1
        demote_below_ticks: 3
        min_dwell_ticks: 5
        reentry_cooldown_ticks: 2
        $confirmTimeLine
        catchup_max_ticks: 20
    """.trimIndent()

private class FixtureConfigSource(private val docs: Map<String, String>) : ConfigSource {
    override fun open(name: String): InputStream = (docs[name] ?: error("no fixture for '$name'")).byteInputStream()
}

private val STATEMACHINE_YAML_WITH_CONFIRM_TIME = statemachineYaml("""confirm_time_kst: "17:00"""")
private val STATEMACHINE_YAML_WITHOUT_CONFIRM_TIME = statemachineYaml("# confirm_time_kst intentionally absent")

private val CONFIRM_17 =
    FixtureConfigSource(
        mapOf("indicators.yaml" to INDICATORS_YAML, "statemachine.yaml" to STATEMACHINE_YAML_WITH_CONFIRM_TIME),
    )
private val CONFIRM_MISSING =
    FixtureConfigSource(
        mapOf("indicators.yaml" to INDICATORS_YAML, "statemachine.yaml" to STATEMACHINE_YAML_WITHOUT_CONFIRM_TIME),
    )

private val KST = ZoneOffset.ofHours(9)

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ConfirmTickRunnerTest {
    private lateinit var db: LakeDatabase

    private val config = ConfirmTickConfigLoader.load(CONFIRM_17)

    @Before
    fun setUp() {
        db = LakeDatabase.buildInMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun runner(clock: Clock) = runnerFor(db, clock)

    private fun clockAt(
        date: LocalDate,
        time: LocalTime,
    ): Clock = Clock.fixed(date.atTime(time).atZone(KST).toInstant(), ZoneId.of("UTC"))

    private suspend fun seedKospiClose(
        date: LocalDate,
        value: Double,
    ) = seedKospiCloseInto(db, date, value)

    /** 부트스트랩 게이트를 우회하기 위한 마커 — 웜업 판정과 무관한 테스트(①②③④⑥)가 게이트
     * 자체를 재검증하지 않도록 "이미 한 번 확정 틱이 커밋된 상태"를 흉내낸다(⑤가 게이트 자체를
     * 전담해서 검증한다). */
    private suspend fun seedBootstrapMarker(beforeDate: LocalDate) = seedBootstrapMarkerInto(db, beforeDate)

    // ---------------------------------------------------------------- ① 정상 확정 틱

    @Test
    fun `normal confirm tick commits one row and logs success`() =
        runTest {
            val marker = LocalDate.of(2026, 8, 3)
            val today = LocalDate.of(2026, 8, 4)
            seedBootstrapMarker(marker)
            seedKospiClose(marker, 100.0)
            seedKospiClose(today, 95.0)

            val outcome = runner(clockAt(today, LocalTime.of(18, 0))).run()

            assertTrue(outcome is ConfirmTickOutcome.Committed)
            assertEquals(listOf(today), (outcome as ConfirmTickOutcome.Committed).committedDates)
            val rows = db.tickInputDao().allOrderedByDate()
            assertEquals(2, rows.size) // marker + today
            assertEquals(today.toString(), rows.last().tradingDate)
            assertFalse("today's own tick is live, not catchup", rows.last().isCatchup)
            // F-2: run_log(started)가 항상 먼저 기록되고, 그 뒤에 성공 행이 붙는다.
            val runLog = db.runLogDao().allOrderedByRanAt()
            assertEquals(listOf("started", "success"), runLog.map { it.status })
            assertEquals(today.toString(), runLog.last().tradingDate)
        }

    // ---------------------------------------------------------------- ② 이중 실행 멱등

    @Test
    fun `running twice on the same day is idempotent`() =
        runTest {
            val marker = LocalDate.of(2026, 8, 3)
            val today = LocalDate.of(2026, 8, 4)
            seedBootstrapMarker(marker)
            seedKospiClose(marker, 100.0)
            seedKospiClose(today, 95.0)
            val r = runner(clockAt(today, LocalTime.of(18, 0)))

            val first = r.run()
            val afterFirst = db.tickInputDao().allOrderedByDate()
            val second = r.run()
            val afterSecond = db.tickInputDao().allOrderedByDate()

            assertTrue(first is ConfirmTickOutcome.Committed)
            assertEquals(ConfirmTickOutcome.NoOp, second)
            assertEquals(afterFirst, afterSecond)
        }

    // ---------------------------------------------------------------- ③ 캐치업 + evaluatedAt=각 D 확정시각

    @Test
    fun `catchup commits missing trading days in ascending order using each day's own confirm time`() =
        runTest {
            val marker = LocalDate.of(2026, 8, 3) // Monday
            val tue = LocalDate.of(2026, 8, 4)
            val wed = LocalDate.of(2026, 8, 5)
            val thu = LocalDate.of(2026, 8, 6) // "today" — app was down Tue/Wed
            seedBootstrapMarker(marker)
            seedKospiClose(marker, 100.0)
            seedKospiClose(tue, 100.0)
            seedKospiClose(wed, 90.0) // window=2 high(tue,wed)=100 -> dd=10% -> severity warn(2)
            seedKospiClose(thu, 100.0) // window=2 high(wed,thu)=100 -> dd=0% -> severity none(0)

            val outcome = runner(clockAt(thu, LocalTime.of(20, 0))).run()

            assertTrue(outcome is ConfirmTickOutcome.Committed)
            assertEquals(listOf(tue, wed, thu), (outcome as ConfirmTickOutcome.Committed).committedDates)
            val rows = db.tickInputDao().allOrderedByDate().drop(1) // drop marker
            assertEquals(listOf(tue.toString(), wed.toString(), thu.toString()), rows.map { it.tradingDate })
            assertTrue("tue is reconstructed after the fact", rows[0].isCatchup)
            assertTrue("wed is reconstructed after the fact", rows[1].isCatchup)
            assertFalse("thu is today's own live tick", rows[2].isCatchup)
            // evaluatedAt precision witness: wed's own tick must see [tue,wed] (severity warn=2 ->
            // composite=100*2/3≈66.7), NOT the batch's final cutoff data [wed,thu] (severity 0).
            // A bug that reused one shared "now" for every date in the catchup batch instead of
            // each date's own confirm time would collapse wed's composite to null/0 here.
            assertEquals(100.0 * 2 / 3, rows[1].composite!!, 1e-9)
            assertEquals(0.0, rows[2].composite!!, 1e-9)
        }

    // ---------------------------------------------------------------- ④ 상한 초과 절단

    @Test
    fun `catchup beyond the cap keeps only the most recent N ticks and freezes a gap row`() =
        runTest {
            val marker = LocalDate.of(2026, 1, 1)
            seedBootstrapMarker(marker)
            val days = (1..25).map { marker.plusDays(it.toLong()) }
            days.forEach { seedKospiClose(it, 100.0) }
            val today = days.last()

            val outcome = runner(clockAt(today, LocalTime.of(20, 0))).run()

            assertTrue(outcome is ConfirmTickOutcome.Committed)
            val committed = (outcome as ConfirmTickOutcome.Committed)
            assertEquals(20, committed.committedDates.size)
            assertEquals(days.takeLast(20), committed.committedDates)
            assertEquals(5, committed.gapSkipped.size)

            val rows = db.tickInputDao().allOrderedByDate().drop(1) // drop marker
            assertEquals(21, rows.size) // 1 gap row + 20 real ticks
            val gapRow = rows.first()
            assertEquals(days[4].toString(), gapRow.tradingDate) // last of the 5 skipped days
            assertNull(gapRow.composite)
            assertTrue(gapRow.gapReason!!.contains("5 trading day"))
            assertTrue(gapRow.isCatchup)
            val realRows = rows.drop(1)
            assertEquals(days.takeLast(20).map { it.toString() }, realRows.map { it.tradingDate })
        }

    // ---------------------------------------------------------------- ⑤ 부트스트랩 게이트

    @Test
    fun `bootstrap gate blocks tick generation until warmup rows are sufficient`() =
        runTest {
            val day1 = LocalDate.of(2026, 8, 3)
            val day2 = LocalDate.of(2026, 8, 4) // "today" — only 2 rows, requiredRows=3
            seedKospiClose(day1, 100.0)
            seedKospiClose(day2, 95.0)

            val outcome = runner(clockAt(day2, LocalTime.of(18, 0))).run()

            assertTrue(outcome is ConfirmTickOutcome.WarmupBlocked)
            val report = (outcome as ConfirmTickOutcome.WarmupBlocked).report
            assertFalse(report.isReady())
            assertTrue(report.series.any { it.status == WarmupStatus.INSUFFICIENT })
            assertTrue(db.tickInputDao().allOrderedByDate().isEmpty())
            assertEquals(listOf("started", "WARMUP_INSUFFICIENT"), db.runLogDao().allOrderedByRanAt().map { it.status })
        }

    // F-4: 부트스트랩(최초 실행)은 그리드 전체가 아니라 최신 거래일 1건만 후보로 좁힌다 — 게이트가
    // 열려도 3행이 아니라 1행만 커밋된다(설치 첫날이 3일치 역사를 "소급 캐치업"한 것으로 둔갑하면
    // 안 된다).
    @Test
    fun `bootstrap gate opens once warmup rows are sufficient but commits only the latest day`() =
        runTest {
            val day1 = LocalDate.of(2026, 8, 3)
            val day2 = LocalDate.of(2026, 8, 4)
            val day3 = LocalDate.of(2026, 8, 5) // "today" — 3 rows meets requiredRows=3
            seedKospiClose(day1, 100.0)
            seedKospiClose(day2, 100.0)
            seedKospiClose(day3, 95.0)

            val outcome = runner(clockAt(day3, LocalTime.of(18, 0))).run()

            assertTrue(outcome is ConfirmTickOutcome.Committed)
            assertEquals(listOf(day3), (outcome as ConfirmTickOutcome.Committed).committedDates)
            val rows = db.tickInputDao().allOrderedByDate()
            assertEquals(listOf(day3.toString()), rows.map { it.tradingDate })
            assertTrue(rows.single().gapReason == null)
        }

    // aaa F-4 — 부트스트랩 × 상한 상호작용을, 게이트를 우회하는 marker 선삽입 없이 진짜 최초
    // 실행 경로로 증명한다: 25거래일치 데이터가 이미 lake에 있어도(예: 웜업 백필 직후 최초
    // 기동) 설치 첫 확정 틱은 그 25일을 "20틱 소급 + gap 1건"으로 둔갑시키지 않고 **오늘 1건만**
    // 커밋한다.
    @Test
    fun `bootstrap with a large pre-existing backlog commits only today, zero backdating, zero gap rows`() =
        runTest {
            val start = LocalDate.of(2026, 1, 1)
            val days = (0..24).map { start.plusDays(it.toLong()) } // 25 trading days, all pre-seeded
            days.forEach { seedKospiClose(it, 100.0) }
            val today = days.last()

            val outcome = runner(clockAt(today, LocalTime.of(20, 0))).run()

            assertTrue(outcome is ConfirmTickOutcome.Committed)
            val committed = outcome as ConfirmTickOutcome.Committed
            assertEquals(listOf(today), committed.committedDates)
            assertTrue("bootstrap must not manufacture a gap row", committed.gapSkipped.isEmpty())
            val rows = db.tickInputDao().allOrderedByDate()
            assertEquals(listOf(today.toString()), rows.map { it.tradingDate })
            assertTrue(rows.single().gapReason == null)
        }

    // aaa F-4R (F-4의 2차 재발, 비평가 재현 시나리오 그대로) — 부트스트랩은 gap 행을 만들지
    // 않으므로(F-4) `forOngoing`의 하한이 gap 경계에만 의존하면 두 번째 실행에서 하한이
    // 사라진다: 부트스트랩 이전 24일 백로그가 재실행마다 되살아나 20틱 소급 커밋 +
    // 허위 CATCHUP_GAP_TRUNCATED 행을 만든다. 같은 날 재실행 → 그리고 익일 실행 → 둘 다
    // tick_input 행 수·gapReason 유무를 단언해 이 회귀가 다시는 조용히 통과하지 못하게 한다.
    @Test
    fun `bootstrap then rerunning on the same day never resurrects the pre-bootstrap backlog`() =
        runTest {
            val start = LocalDate.of(2026, 1, 1)
            val days = (0..24).map { start.plusDays(it.toLong()) } // 25 trading days, all pre-seeded
            days.forEach { seedKospiClose(it, 100.0) }
            val bootstrapDay = days.last() // 2026-01-25

            val outcome1 = runner(clockAt(bootstrapDay, LocalTime.of(20, 0))).run()
            val outcome2 = runner(clockAt(bootstrapDay, LocalTime.of(21, 0))).run() // same day, rerun

            assertTrue(outcome1 is ConfirmTickOutcome.Committed)
            assertEquals(listOf(bootstrapDay), (outcome1 as ConfirmTickOutcome.Committed).committedDates)
            assertEquals(
                "a same-day rerun must be a plain no-op, not a rediscovered backlog",
                ConfirmTickOutcome.NoOp,
                outcome2,
            )
            val rows = db.tickInputDao().allOrderedByDate()
            assertEquals("only the single bootstrap tick may exist", 1, rows.size)
            assertEquals("no gap row — nothing was ever actually missed", 0, rows.count { it.gapReason != null })
            assertEquals(listOf(bootstrapDay.toString()), rows.map { it.tradingDate })
        }

    @Test
    fun `bootstrap then the next day's periodic run never resurrects the pre-bootstrap backlog`() =
        runTest {
            val start = LocalDate.of(2026, 1, 1)
            val days = (0..24).map { start.plusDays(it.toLong()) } // 25 trading days, all pre-seeded
            days.forEach { seedKospiClose(it, 100.0) }
            val bootstrapDay = days.last() // 2026-01-25
            val nextDay = bootstrapDay.plusDays(1) // 2026-01-26

            val outcome1 = runner(clockAt(bootstrapDay, LocalTime.of(20, 0))).run()
            seedKospiClose(nextDay, 100.0)
            val outcome2 = runner(clockAt(nextDay, LocalTime.of(18, 0))).run() // next day's own periodic run

            assertTrue(outcome1 is ConfirmTickOutcome.Committed)
            assertTrue(outcome2 is ConfirmTickOutcome.Committed)
            val committed2 = outcome2 as ConfirmTickOutcome.Committed
            assertEquals(listOf(nextDay), committed2.committedDates)
            assertTrue(
                "must not resurrect the pre-bootstrap backlog as a cap-exceeded gap",
                committed2.gapSkipped.isEmpty(),
            )
            val rows = db.tickInputDao().allOrderedByDate()
            assertEquals("only bootstrapDay + nextDay — not 21 rows", 2, rows.size)
            assertEquals("no gap row — nothing was ever actually missed", 0, rows.count { it.gapReason != null })
            assertEquals(setOf(bootstrapDay.toString(), nextDay.toString()), rows.map { it.tradingDate }.toSet())
        }

    // ---------------------------------------------------------------- ⑥ 휴장일(공백일) 무커밋

    @Test
    fun `a day with no anchor observation is never a candidate and gets no tick_input row`() =
        runTest {
            val marker = LocalDate.of(2026, 8, 2) // Sunday, synthetic boundary
            val mon = LocalDate.of(2026, 8, 3)
            val tue = LocalDate.of(2026, 8, 4)
            val wed = LocalDate.of(2026, 8, 5) // holiday proxy — no KOSPI observation seeded
            val thu = LocalDate.of(2026, 8, 6)
            val fri = LocalDate.of(2026, 8, 7)
            seedBootstrapMarker(marker)
            seedKospiClose(marker, 100.0)
            listOf(mon, tue, thu, fri).forEach { seedKospiClose(it, 100.0) }

            val outcome = runner(clockAt(fri, LocalTime.of(20, 0))).run()

            assertTrue(outcome is ConfirmTickOutcome.Committed)
            assertEquals(listOf(mon, tue, thu, fri), (outcome as ConfirmTickOutcome.Committed).committedDates)
            val dates = db.tickInputDao().allOrderedByDate().map { it.tradingDate }
            assertFalse("wed has no anchor observation and must never be committed", dates.contains(wed.toString()))
        }

    // ---------------------------------------------------------------- aaa F-5 그리드 공백: 기록 + 재편입

    // 2026-08-03(월)~08-07(금)은 실제 평일, 08-08(토)/08-09(일)은 실제 주말(확인: date +%A) —
    // 08-05(수)만 관측이 없다(수집 실패 시뮬레이션). 주말은 관측이 없어도 "휴장으로 추정"조차
    // 하지 않고(K-03 실시간 영업일 API 미배선 — 확신할 근거가 없다), 오직 **평일**의 그리드 공백만
    // CALENDAR_FALLBACK으로 기록한다(§4.1 카탈로그) — 이것이 "수집 실패 vs 휴장 구분" witness다.
    @Test
    fun `a weekday grid gap is logged as CALENDAR_FALLBACK but weekend absence is never flagged`() =
        runTest {
            val marker = LocalDate.of(2026, 7, 31) // pre-bootstrap boundary (bypasses the warmup
            // gate — that interaction is F-4's own test, not this one's concern).
            val mon = LocalDate.of(2026, 8, 3)
            val tue = LocalDate.of(2026, 8, 4)
            val wed = LocalDate.of(2026, 8, 5) // weekday, no observation -> suspected collection gap
            val thu = LocalDate.of(2026, 8, 6)
            val fri = LocalDate.of(2026, 8, 7)
            val sat = LocalDate.of(2026, 8, 8) // real weekend, no observation -> never flagged
            val sun = LocalDate.of(2026, 8, 9) // real weekend, no observation -> never flagged
            val nextMon = LocalDate.of(2026, 8, 10)
            seedBootstrapMarker(marker)
            seedKospiClose(marker, 100.0)
            seedKospiClose(mon, 100.0)
            runner(clockAt(mon, LocalTime.of(18, 0))).run() // commits mon only
            seedKospiClose(nextMon, 100.0) // tue/wed/thu/fri never arrive (simulated failure week)

            runner(clockAt(nextMon, LocalTime.of(18, 0))).run()

            val gapLogs = db.runLogDao().allOrderedByRanAt().filter { it.status == "CALENDAR_FALLBACK" }
            assertTrue("expected at least one CALENDAR_FALLBACK entry", gapLogs.isNotEmpty())
            val detail = gapLogs.last().detail!!
            for (weekday in listOf(tue, wed, thu, fri)) {
                assertTrue("$weekday must be flagged as a suspected gap", detail.contains(weekday.toString()))
            }
            for (weekend in listOf(sat, sun)) {
                assertFalse("$weekend is a real weekend and must never be flagged", detail.contains(weekend.toString()))
            }
        }

    // aaa F-5 — 늦게 도착한 앵커 관측의 재편입: tue의 데이터가 처음엔 없어(수집 실패) 후보가
    // 되지 못했고, 그 사이 wed는 정상 커밋됐다. 나중에 tue의 관측이 도착하면(재수집 성공), wed가
    // 이미 커밋돼 있어도 tue는 여전히 후보로 재편입돼야 한다(이전 판은 `it > lastCommittedDate`
    // 서수 비교로 이런 날짜를 영구 배제했다).
    @Test
    fun `a late-arriving anchor observation is re-admitted as a candidate even after later dates were committed`() =
        runTest {
            val marker = LocalDate.of(2026, 7, 31) // pre-bootstrap boundary (see previous test).
            val mon = LocalDate.of(2026, 8, 3)
            val tue = LocalDate.of(2026, 8, 4) // missing at first — arrives late below
            val wed = LocalDate.of(2026, 8, 5)
            seedBootstrapMarker(marker)
            seedKospiClose(marker, 100.0)
            seedKospiClose(mon, 100.0)
            runner(clockAt(mon, LocalTime.of(18, 0))).run() // commits mon

            seedKospiClose(wed, 100.0) // tue still missing
            val outcome1 = runner(clockAt(wed, LocalTime.of(20, 0))).run()
            assertEquals(listOf(wed), (outcome1 as ConfirmTickOutcome.Committed).committedDates)
            assertTrue(db.tickInputDao().allOrderedByDate().none { it.tradingDate == tue.toString() })

            seedKospiClose(tue, 100.0) // late arrival (e.g. a retried collector backfilled it)
            val outcome2 = runner(clockAt(wed, LocalTime.of(21, 0))).run() // still "today" = wed

            assertTrue(outcome2 is ConfirmTickOutcome.Committed)
            assertEquals(listOf(tue), (outcome2 as ConfirmTickOutcome.Committed).committedDates)
            val rows = db.tickInputDao().allOrderedByDate()
            val expectedDates = setOf(marker.toString(), mon.toString(), tue.toString(), wed.toString())
            assertEquals(expectedDates, rows.map { it.tradingDate }.toSet())
            assertTrue(
                "tue must be marked catchup (reconstructed after the fact)",
                rows.single { it.tradingDate == tue.toString() }.isCatchup,
            )
        }

    // ---------------------------------------------------------------- aaa F-2 실패 감사

    private class ThrowingObservationDao(private val delegate: ObservationDao) : ObservationDao by delegate {
        override suspend fun confirmSeries(
            seriesId: String,
            field: String,
            fromAsOf: Long,
            toAsOf: Long,
        ): List<SeriesPoint> = error("simulated store failure")
    }

    // 이전 판은 run() 본문에 try/catch가 없어 예외 발생 시 run_log 행이 0개였다(F-2). started를
    // 선기록하고 실패도 사유와 함께 기록한 뒤 재전파하는지 확인한다 — 그리드는 정상 조회되게
    // 두고(candidates가 실제로 생기게) `ConfirmTickContext.load` 단계에서만 예외를 유발한다.
    @Test
    fun `an unexpected failure mid-run is recorded as started then failed, and still propagates`() =
        runTest {
            val marker = LocalDate.of(2026, 8, 3)
            val today = LocalDate.of(2026, 8, 4)
            seedBootstrapMarker(marker)
            seedKospiClose(marker, 100.0)
            seedKospiClose(today, 95.0)
            val failingRunner =
                ConfirmTickRunner(
                    observationDao = ThrowingObservationDao(db.observationDao()),
                    tickInputDao = db.tickInputDao(),
                    runLogDao = db.runLogDao(),
                    gridProvider = TradingDayGridProvider(db.observationDao()),
                    config = config,
                    clock = clockAt(today, LocalTime.of(18, 0)),
                )

            val error = runCatching { failingRunner.run() }.exceptionOrNull()

            assertTrue("expected the exception to propagate, got: $error", error != null)
            val runLog = db.runLogDao().allOrderedByRanAt()
            assertEquals(listOf("started", "failed"), runLog.map { it.status })
            assertTrue(runLog.last().detail!!.contains("simulated store failure"))
            assertTrue(db.tickInputDao().allOrderedByDate().size == 1) // only the pre-seeded marker
        }

    private class ConstraintViolatingTickInputDao(private val delegate: TickInputDao) : TickInputDao by delegate {
        override suspend fun insert(tick: TickInputEntity): Long =
            throw SQLiteConstraintException("NOT NULL constraint failed: tick_input.severities_json")
    }

    // qa 반려(마이너) — insertIfAbsent는 trading_date PK 충돌"만" 흡수해야 한다는 좁힘(F-2)의
    // 반증 방향 witness: 메시지가 "trading_date"를 언급하지 않는 SQLiteConstraintException은
    // 멱등 no-op으로 삼켜지지 않고 그대로 전파돼야 한다(진짜 스키마 버그를 조용히 숨기지 않는다).
    @Test
    fun `a non-PK constraint violation propagates instead of being absorbed as idempotent no-op`() =
        runTest {
            val marker = LocalDate.of(2026, 8, 3)
            val today = LocalDate.of(2026, 8, 4)
            seedBootstrapMarker(marker)
            seedKospiClose(marker, 100.0)
            seedKospiClose(today, 95.0)
            val violatingRunner =
                ConfirmTickRunner(
                    observationDao = db.observationDao(),
                    tickInputDao = ConstraintViolatingTickInputDao(db.tickInputDao()),
                    runLogDao = db.runLogDao(),
                    gridProvider = TradingDayGridProvider(db.observationDao()),
                    config = config,
                    clock = clockAt(today, LocalTime.of(18, 0)),
                )

            val error = runCatching { violatingRunner.run() }.exceptionOrNull()

            assertTrue(
                "expected the non-PK constraint violation to propagate, got: $error",
                error is SQLiteConstraintException,
            )
            val runLog = db.runLogDao().allOrderedByRanAt()
            assertEquals(listOf("started", "failed"), runLog.map { it.status })
            assertTrue(runLog.last().detail!!.contains("severities_json"))
            // today's tick must NOT have been silently dropped as if it were a duplicate.
            assertTrue(db.tickInputDao().allOrderedByDate().none { it.tradingDate == today.toString() })
        }

    // ---------------------------------------------------------------- ⑦ confirm_time 미기입 시 명시 실패

    @Test
    fun `loading config without confirm_time_kst fails explicitly instead of defaulting to 17-00`() {
        val error =
            runCatching { ConfirmTickConfigLoader.load(CONFIRM_MISSING) }
                .exceptionOrNull()
        assertTrue("expected an explicit failure, got: $error", error is IllegalStateException)
        assertTrue(error!!.message!!.contains("confirm_time_kst"))
    }

    // ---------------------------------------------------------------- 결정론 (bonus, D-25 §1~4 승계)

    private suspend fun seedKospiCloseInto(
        db: LakeDatabase,
        date: LocalDate,
        value: Double,
    ) {
        val asOf = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        db.observationDao().insert(
            ObservationEntity(
                seriesId = "1001",
                field = "close",
                asOf = asOf,
                value = value,
                observedAt = asOf,
                revision = 0,
                lane = 0,
                source = "krx_mobile",
            ),
        )
    }

    private suspend fun seedBootstrapMarkerInto(
        db: LakeDatabase,
        beforeDate: LocalDate,
    ) {
        db.tickInputDao().insert(
            TickInputEntity(
                tradingDate = beforeDate.toString(),
                composite = 5.0,
                distinctAxes = 0,
                anyCrit = false,
                anyExtreme = false,
                severitiesJson = "{}",
                coverage = 1.0,
                registryVersion = REGISTRY_VERSION,
                gapReason = null,
                frozenAt = 0L,
                firedAxes = null,
                visibleAtByIndicator = null,
                isCatchup = false,
                warmupStatusJson = null,
                pitQuality = "live",
            ),
        )
    }

    private fun runnerFor(
        db: LakeDatabase,
        clock: Clock,
    ) = ConfirmTickRunner(
        db.observationDao(),
        db.tickInputDao(),
        db.runLogDao(),
        TradingDayGridProvider(db.observationDao()),
        config,
        clock,
    )

    private suspend fun foldRelevantRows(db: LakeDatabase) =
        db.tickInputDao().allOrderedByDate().drop(1).map { Triple(it.composite, it.anyCrit, it.anyExtreme) }

    @Test
    fun `live day-by-day execution and one-shot catchup produce the same fold-relevant tick sequence`() =
        runTest {
            val marker = LocalDate.of(2026, 8, 3)
            val tue = LocalDate.of(2026, 8, 4)
            val wed = LocalDate.of(2026, 8, 5)
            val thu = LocalDate.of(2026, 8, 6)

            // Path A: one-shot catchup on thu.
            val dbA = LakeDatabase.buildInMemory(ApplicationProvider.getApplicationContext())
            seedBootstrapMarkerInto(dbA, marker)
            seedKospiCloseInto(dbA, marker, 100.0)
            seedKospiCloseInto(dbA, tue, 100.0)
            seedKospiCloseInto(dbA, wed, 90.0)
            seedKospiCloseInto(dbA, thu, 100.0)
            runnerFor(dbA, clockAt(thu, LocalTime.of(20, 0))).run()
            val pathA = foldRelevantRows(dbA)
            dbA.close()

            // Path B: three separate live runs, one per day.
            val dbB = LakeDatabase.buildInMemory(ApplicationProvider.getApplicationContext())
            seedBootstrapMarkerInto(dbB, marker)
            seedKospiCloseInto(dbB, marker, 100.0)
            seedKospiCloseInto(dbB, tue, 100.0)
            runnerFor(dbB, clockAt(tue, LocalTime.of(18, 0))).run()
            seedKospiCloseInto(dbB, wed, 90.0)
            runnerFor(dbB, clockAt(wed, LocalTime.of(18, 0))).run()
            seedKospiCloseInto(dbB, thu, 100.0)
            runnerFor(dbB, clockAt(thu, LocalTime.of(18, 0))).run()
            val pathB = foldRelevantRows(dbB)
            dbB.close()

            assertEquals(pathA, pathB)
        }

    // ---------------------------------------------------------------- fold 배선 스모크

    @Test
    fun `current phase derives from folding tick_input, not a stored column`() =
        runTest {
            val marker = LocalDate.of(2026, 8, 3)
            val today = LocalDate.of(2026, 8, 4)
            seedBootstrapMarker(marker)
            seedKospiClose(marker, 100.0)
            seedKospiClose(today, 100.0)
            runner(clockAt(today, LocalTime.of(18, 0))).run()

            val phase = PhaseDerivation.currentPhase(db.tickInputDao(), config.profileName, config.statemachineConfig)

            assertEquals("GREEN", phase) // composite stays low across both ticks
        }
}
