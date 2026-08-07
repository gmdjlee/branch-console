package com.branchconsole.app.preview

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.branchconsole.app.tick.ConfirmSeriesIds
import com.branchconsole.app.tick.ConfirmTickConfigLoader
import com.branchconsole.engine.config.ConfigSource
import com.branchconsole.engine.config.PreviewPolicy
import com.branchconsole.lake.LakeDatabase
import com.branchconsole.lake.Lane
import com.branchconsole.lake.ObservationEntity
import com.branchconsole.lake.TickInputEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * 픽스처 SSOT(실제 `configs/` 원본은 건드리지 않는다) — [com.branchconsole.app.tick.ConfirmTickRunnerTest]와
 * 동일 관례: 지표 2종만 두고 window를 축소해 최소 데이터로 파이프라인 메커니즘을 겨눈다.
 * `daily_kr: 20h`는 실 SSOT(30h)가 아니다 — 여기서 검증하는 것은 "실경과(now) 사용" 메커니즘
 * 자체이지 창 값의 정합이 아니다(그 값은 BT-05/production config가 담당). 20h는 아래
 * `kr indicator freshness` 테스트의 두 평가 시각(오전 elapsed 15h vs 밤 elapsed 27h) 사이에
 * 정확히 있어야 "실경과 채택(A)"과 "당일 확정 시각으로 스냅(B, 미채택)"이 갈린다 — B라면 두
 * 시각 모두 confirmInstant(today)-visibleAt=24h로 고정돼 항상 stale이 나와 구분되지 않는다.
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
          daily_us: 240h
          daily_kr: 20h
    indicators:
      - id: vix_level_z
        name_kr: test
        axis: vol_global
        weight: 1.0
        source: { provider: yfinance, symbol: "^VIX", field: close, cadence: daily_us }
        transform: zscore(close, window=2)
        direction: higher_is_risk
        thresholds: { watch: 1.5, warn: 2.0, crit: 3.0 }
      - id: kospi_drawdown
        name_kr: test
        axis: kr_flow_price
        weight: 1.0
        source: { provider: pykrx, dataset: index_ohlcv, symbol: "1001", cadence: daily_kr }
        transform: drawdown_from_high(window=2)
        direction: higher_is_risk
        thresholds: { watch: 3.0, warn: 8.0, crit: 12.0 }
    """.trimIndent()

private val STATEMACHINE_YAML =
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
        confirm_time_kst: "17:00"
        catchup_max_ticks: 20
    """.trimIndent()

private class FixtureConfigSource(private val docs: Map<String, String>) : ConfigSource {
    override fun open(name: String): InputStream = (docs[name] ?: error("no fixture for '$name'")).byteInputStream()
}

private val FIXTURE =
    FixtureConfigSource(mapOf("indicators.yaml" to INDICATORS_YAML, "statemachine.yaml" to STATEMACHINE_YAML))

private val KST = ZoneOffset.ofHours(9)

private fun severitiesJson(severities: Map<String, Int?>): String =
    JsonObject(severities.mapValues { (_, v) -> if (v == null) JsonNull else JsonPrimitive(v) }).toString()

/**
 * TASK MT1-07 완료 기준 ①③(부분) + aaa 확정 요건 3·4 — 프리뷰 파이프라인의 상태 불변·시각
 * 규약(§5.4.2)·이월 자기참조 부재(§10.1.2)를 Room(Robolectric) 기반으로 검증한다. D-23
 * §23.2 66.7/45.2/67.7 수치 재현은 [PreviewCoverageTest]가 담당한다(순수 함수 + 실 SSOT
 * 가중, 이 파일의 축소 픽스처와는 목적이 다르다).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class PreviewTickRunnerTest {
    private lateinit var db: LakeDatabase
    private val config = ConfirmTickConfigLoader.load(FIXTURE)
    private val previewCoverageMin = PreviewPolicy.previewCoverageMin(FIXTURE)

    @Before
    fun setUp() {
        db = LakeDatabase.buildInMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun runner(clock: Clock): PreviewTickRunner {
        return PreviewTickRunner(db.observationDao(), db.tickInputDao(), config, previewCoverageMin, clock)
    }

    private fun clockAt(
        date: LocalDate,
        time: LocalTime,
    ): Clock = Clock.fixed(date.atTime(time).atZone(KST).toInstant(), ZoneId.of("UTC"))

    private suspend fun seedClose(
        seriesId: String,
        field: String,
        date: LocalDate,
        value: Double,
        lane: Int = Lane.CONFIRMED,
    ) {
        val asOf = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        db.observationDao().insert(
            ObservationEntity(
                seriesId = seriesId,
                field = field,
                asOf = asOf,
                value = value,
                observedAt = asOf,
                revision = 0,
                lane = lane,
                source = "test",
            ),
        )
    }

    private suspend fun seedConfirmedTickInput(
        date: LocalDate,
        severities: Map<String, Int?>,
    ) {
        db.tickInputDao().insert(
            TickInputEntity(
                tradingDate = date.toString(),
                composite = 0.0,
                distinctAxes = 0,
                anyCrit = false,
                anyExtreme = false,
                severitiesJson = severitiesJson(severities),
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

    // ---------------------------------------------------------------- ① 상태기계 상태 불변

    @Test
    fun `preview never inserts into tick_input or run_log`() =
        runTest {
            val dMinus2 = LocalDate.of(2026, 8, 4)
            val dMinus1 = LocalDate.of(2026, 8, 5)
            val today = LocalDate.of(2026, 8, 6)
            seedClose(ConfirmSeriesIds.KOSPI, ConfirmSeriesIds.FIELD_CLOSE, dMinus2, 100.0)
            seedClose(ConfirmSeriesIds.KOSPI, ConfirmSeriesIds.FIELD_CLOSE, dMinus1, 90.0)
            seedConfirmedTickInput(dMinus1, mapOf("vix_level_z" to 1, "kospi_drawdown" to 2))

            val before = db.tickInputDao().allOrderedByDate()
            runner(clockAt(today, LocalTime.of(18, 0))).run()
            val after = db.tickInputDao().allOrderedByDate()

            assertEquals(before, after)
            val runLog = db.runLogDao().allOrderedByRanAt()
            assertTrue("preview must not write run_log either (no side effects at all)", runLog.isEmpty())
        }

    // ---------------------------------------------------------- §5.4.2 시각 규약(가시·스테일)

    @Test
    fun `morning preview sees yesterday US close even though today has not closed in KR yet`() =
        runTest {
            val twoDaysAgo = LocalDate.of(2026, 8, 4)
            val yesterday = LocalDate.of(2026, 8, 5)
            val today = LocalDate.of(2026, 8, 6) // no KOSPI observation seeded for today -> grid lacks "today"
            seedClose(ConfirmSeriesIds.VIX, ConfirmSeriesIds.FIELD_CLOSE, twoDaysAgo, 15.0)
            seedClose(ConfirmSeriesIds.VIX, ConfirmSeriesIds.FIELD_CLOSE, yesterday, 25.0)

            val result = runner(clockAt(today, LocalTime.of(10, 0))).run()

            assertNotNull(
                "US indicator must be visible in a 10am preview, not missing (§5.4.2 date-based visibility)",
                result.indicators.getValue("vix_level_z").severity,
            )
        }

    @Test
    fun `kr indicator freshness uses real elapsed time, not the day's fixed confirm instant`() =
        runTest {
            val twoDaysAgo = LocalDate.of(2026, 8, 4)
            val yesterday = LocalDate.of(2026, 8, 5)
            val today = LocalDate.of(2026, 8, 6)
            seedClose(ConfirmSeriesIds.KOSPI, ConfirmSeriesIds.FIELD_CLOSE, twoDaysAgo, 100.0)
            seedClose(ConfirmSeriesIds.KOSPI, ConfirmSeriesIds.FIELD_CLOSE, yesterday, 90.0)

            // Same data, same "today" — only the intraday clock differs. A buggy implementation
            // that reuses the fixed lookup instant (today 17:00) for staleness too would report
            // the SAME result (stale, elapsed pinned at 24h = confirmInstant(today) - D-1 17:00)
            // at both times; M-39's real-elapsed rule must disagree between them: 08:00 KST is
            // 15h after D-1's 17:00 KST visibility (< the 20h test window -> fresh), 20:00 KST
            // is 27h after it (> 20h -> stale).
            val morning = runner(clockAt(today, LocalTime.of(8, 0))).run()
            val night = runner(clockAt(today, LocalTime.of(20, 0))).run()

            assertNotNull(
                "15h real elapsed is within the 20h test window -> fresh",
                morning.indicators.getValue("kospi_drawdown").severity,
            )
            assertNull(
                "27h real elapsed exceeds the 20h test window -> stale/missing",
                night.indicators.getValue("kospi_drawdown").severity,
            )
        }

    // ------------------------------------------------------- 읽기 지점 ② — 프리뷰 레인 소비

    @Test
    fun `preview reads lane=1 observations that a confirm-only query would never see`() =
        runTest {
            val twoDaysAgo = LocalDate.of(2026, 8, 4)
            val yesterday = LocalDate.of(2026, 8, 5)
            val today = LocalDate.of(2026, 8, 6)
            // Only preview-lane rows exist — no confirmed (lane=0) VIX data at all.
            seedClose(ConfirmSeriesIds.VIX, ConfirmSeriesIds.FIELD_CLOSE, twoDaysAgo, 15.0, lane = Lane.PREVIEW)
            seedClose(ConfirmSeriesIds.VIX, ConfirmSeriesIds.FIELD_CLOSE, yesterday, 25.0, lane = Lane.PREVIEW)

            val result = runner(clockAt(today, LocalTime.of(10, 0))).run()

            assertNotNull(
                "preview must consume lane=1 rows via readSeriesForTick's read point (2)",
                result.indicators.getValue("vix_level_z").severity,
            )
        }

    // -------------------------------------------------- §10.1.2 이월 자기참조 부재 증인

    @Test
    fun `two consecutive previews carry-forward the same confirmed tick, never each other's output`() =
        runTest {
            val today = LocalDate.of(2026, 8, 6)
            seedConfirmedTickInput(today.minusDays(1), mapOf("vix_level_z" to 1, "kospi_drawdown" to null))

            val first = runner(clockAt(today, LocalTime.of(9, 0))).run()
            val second = runner(clockAt(today, LocalTime.of(9, 5))).run()

            val carriedFirst = first.indicators.filterValues { !it.observed }
            val carriedSecond = second.indicators.filterValues { !it.observed }
            val sameSourceMsg = "both previews must see the same single confirmed source, not each other"
            assertEquals(sameSourceMsg, carriedFirst, carriedSecond)
            assertEquals(1, carriedFirst.getValue("vix_level_z").severity)
            val depthOneMsg = "depth-1 carry: an indicator missing in the last confirmed tick stays missing"
            assertNull(depthOneMsg, carriedFirst.getValue("kospi_drawdown").severity)
        }

    @Test
    fun `no confirmed tick yet means no carry-forward, missing stays missing (M-50)`() =
        runTest {
            val today = LocalDate.of(2026, 8, 6)

            val result = runner(clockAt(today, LocalTime.of(9, 0))).run()

            assertTrue(result.indicators.values.all { !it.observed && it.severity == null })
        }
}
