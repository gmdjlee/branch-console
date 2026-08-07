package com.branchconsole.app.tick

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.branchconsole.app.collectors.WarmupStatus
import com.branchconsole.app.tick.WarmupGate.isReady
import com.branchconsole.engine.config.ConfigSource
import com.branchconsole.lake.LakeDatabase
import com.branchconsole.lake.ObservationEntity
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
            val runLog = db.runLogDao().allOrderedByRanAt()
            assertEquals(1, runLog.size)
            assertEquals("success", runLog.single().status)
            assertEquals(today.toString(), runLog.single().tradingDate)
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
            assertEquals("blocked_warmup", db.runLogDao().allOrderedByRanAt().single().status)
        }

    @Test
    fun `bootstrap gate opens once warmup rows are sufficient`() =
        runTest {
            val day1 = LocalDate.of(2026, 8, 3)
            val day2 = LocalDate.of(2026, 8, 4)
            val day3 = LocalDate.of(2026, 8, 5) // "today" — 3 rows meets requiredRows=3
            seedKospiClose(day1, 100.0)
            seedKospiClose(day2, 100.0)
            seedKospiClose(day3, 95.0)

            val outcome = runner(clockAt(day3, LocalTime.of(18, 0))).run()

            assertTrue(outcome is ConfirmTickOutcome.Committed)
            assertEquals(3, db.tickInputDao().allOrderedByDate().size)
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
