package com.branchconsole.app.preview

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.branchconsole.app.collectors.CollectOutcome
import com.branchconsole.app.collectors.Collector
import com.branchconsole.app.collectors.Observation
import com.branchconsole.app.tick.ConfirmSeriesIds
import com.branchconsole.engine.config.ConfigSource
import com.branchconsole.lake.LakeDatabase
import com.branchconsole.lake.Lane
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

private const val REGISTRY_VERSION = "0.0.0-test"

/** Reduced fixture registry (same shape/convention as PreviewTickRunnerTest) — this file only
 * exercises the collect -> append(lane=1) -> [PreviewTickRunner] wiring, not zscore numerics. */
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
          daily_kr: 240h
    indicators:
      - id: vix_level_z
        name_kr: test
        axis: vol_global
        weight: 1.0
        source: { provider: yfinance, symbol: "^VIX", field: close, cadence: daily_us }
        transform: zscore(close, window=2)
        direction: higher_is_risk
        thresholds: { watch: 1.5, warn: 2.0, crit: 3.0 }
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

private class RefreshFixtureConfigSource(private val docs: Map<String, String>) : ConfigSource {
    override fun open(name: String): InputStream = (docs[name] ?: error("no fixture for '$name'")).byteInputStream()
}

private val FIXTURE =
    RefreshFixtureConfigSource(mapOf("indicators.yaml" to INDICATORS_YAML, "statemachine.yaml" to STATEMACHINE_YAML))

/** Always returns the same 2 fixed VIX bars — deterministic, no network. */
private class FakeVixCollector(private val asOfDates: List<LocalDate>, private val value: Double) : Collector {
    override val id = "fake-yfinance"
    override val expectedSeriesIds = listOf(ConfirmSeriesIds.VIX)

    override suspend fun collect(range: ClosedRange<LocalDate>): CollectOutcome =
        CollectOutcome.Ok(
            asOfDates.map { date ->
                Observation(
                    seriesId = ConfirmSeriesIds.VIX,
                    field = ConfirmSeriesIds.FIELD_CLOSE,
                    asOf = date.atStartOfDay(ZoneOffset.UTC).toInstant(),
                    observedAt = Instant.now(),
                    source = "fake",
                    value = value,
                )
            },
        )
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class PreviewRefreshUseCaseTest {
    private lateinit var db: LakeDatabase

    @Before
    fun setUp() {
        db = LakeDatabase.buildInMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        db.close()
    }

    // 2026-08-06T01:00:00Z == 10:00 KST.
    private fun useCase(collectors: List<Collector>): PreviewRefreshUseCase =
        PreviewRefreshUseCase(
            context = ApplicationProvider.getApplicationContext(),
            db = db,
            collectors = collectors,
            configSource = FIXTURE,
            clock = Clock.fixed(Instant.parse("2026-08-06T01:00:00Z"), ZoneId.of("UTC")),
        )

    @Test
    fun `refresh appends collected rows to the preview lane, not the confirmed lane`() =
        runTest {
            val fixedDate = LocalDate.of(2026, 8, 5)
            useCase(listOf(FakeVixCollector(listOf(fixedDate), value = 15.0))).refresh()

            val rows = db.observationDao().previewSeries(ConfirmSeriesIds.VIX, ConfirmSeriesIds.FIELD_CLOSE, 0L, Long.MAX_VALUE)
            assertTrue("preview-lane read must see the appended row", rows.isNotEmpty())

            val confirmedOnly = db.observationDao().confirmSeries(ConfirmSeriesIds.VIX, ConfirmSeriesIds.FIELD_CLOSE, 0L, Long.MAX_VALUE)
            assertTrue("must NOT land in the confirmed lane", confirmedOnly.isEmpty())
        }

    @Test
    fun `a second refresh of the same cell bumps the revision instead of silently no-op-ing`() =
        runTest {
            val fixedDate = LocalDate.of(2026, 8, 5)
            val asOfMillis = fixedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

            useCase(listOf(FakeVixCollector(listOf(fixedDate), value = 15.0))).refresh()
            assertEquals(0, db.observationDao().maxRevision(ConfirmSeriesIds.VIX, ConfirmSeriesIds.FIELD_CLOSE, asOfMillis, Lane.PREVIEW))

            useCase(listOf(FakeVixCollector(listOf(fixedDate), value = 25.0))).refresh() // same day, intraday value moved

            assertEquals(
                "second refresh must not silently drop on the UNIQUE constraint",
                1,
                db.observationDao().maxRevision(ConfirmSeriesIds.VIX, ConfirmSeriesIds.FIELD_CLOSE, asOfMillis, Lane.PREVIEW),
            )
        }

    @Test
    fun `refresh runs PreviewTickRunner and never writes to tick_input (no state commit)`() =
        runTest {
            val fixedDate = LocalDate.of(2026, 8, 5)
            val result = useCase(listOf(FakeVixCollector(listOf(fixedDate), value = 15.0))).refresh()

            assertTrue(result.indicators.containsKey("vix_level_z"))
            assertTrue("preview must never commit tick_input", db.tickInputDao().allOrderedByDate().isEmpty())
        }
}
