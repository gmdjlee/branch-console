package com.branchconsole.app.tick

import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.branchconsole.engine.config.ConfigSource
import com.branchconsole.lake.LakeDatabase
import com.branchconsole.lake.Lane
import com.branchconsole.lake.ObservationEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs

private val KST = ZoneOffset.ofHours(9)

/**
 * MT1-05k (docs/plans/M1_PLAN_D.md §2.10 "①②는 MT1-05k(원장→사슬 e2e) 1건과 MT1-06g·MT1-07의
 * 증인들이 메운다" / M1_PLAN_FINAL.md §1.3 M-11b): ledger-to-chain e2e.
 *
 * [ConfirmTickRunnerTest] already proves the pipeline *mechanism* (fold, catchup, idempotency,
 * warmup gate, F-2/F-4/F-5 audits) against a real Room DB - but with a single-indicator toy
 * `indicators.yaml` fixture (`kospi_drawdown`, window=2), decoupled from the real registry.
 * BT-05 ([com.branchconsole.engine.parity.ParityRunnerTest]) proves the *engine* reproduces
 * `engine_ref` for the real 13-indicator registry - but it injects `raw.jsonl` straight into
 * an in-memory map ([com.branchconsole.engine.parity.ParityEngine]), never touching the real
 * Room schema, DAO SQL, or lane/tie-break rules stage ① (ledger) and ② (query) are entirely
 * outside BT-05's reach (docs/plans/M1_PLAN_D.md §2.10 table: "① 원장: ✗", "② 조회: △").
 *
 * This test closes exactly that gap for one window: every observation from a frozen BT-05
 * fixture (`backtest/parity/w2026_structural/raw.jsonl`) is appended into a *real* in-memory
 * [LakeDatabase] at `lane = 0` (stage ①), then the real production pipeline -
 * [ConfirmTickContext] (stage ②) -> [ConfirmTickRunner] (stages ③-⑦) -> `tick_input` -
 * walks the window day by day exactly as the live app would, using the real 13-indicator
 * `configs/indicators.yaml`/`configs/statemachine.yaml` (via [AssetConfigSource], same asset
 * path the production [ConfirmTickWorker] uses). The resulting (composite, phase) sequence is
 * asserted against `expected.jsonl` (BT-05's already-validated `engine_ref` ground truth for
 * this exact window) tick by tick.
 *
 * Window: `w2026_structural` (53 ticks, phases GREEN/AMBER/ORANGE - a real multi-transition
 * sequence, not a monotone one) rather than the more commonly cited `w2020_covid`: the latter
 * has **zero** `BAMLH0A0HYM2` rows for its entire span (FRED HY OAS coverage only starts
 * 2023-08, docs/journal), which would permanently block the bootstrap warmup gate for the real
 * `hy_oas_delta` indicator (unlike `krx_credit_spread_delta`/`kr_cds_5y_delta`,
 * `hy_oas_delta` is not in [ConfirmSeriesIds.ALWAYS_MISSING_INDICATORS] - production assumes it
 * eventually collects). `w2026_structural`'s HY OAS coverage (451 rows) has no such gap.
 *
 * `confirm_time_kst` provisional value: `configs/statemachine.yaml`'s `mobile_daily` profile
 * has this key deliberately commented out today (AD-3b/MT1-00g real-world measurement,
 * 3-trading-day x 6-slot polling, is still in progress per PROGRESS.md) -
 * [ConfirmTickConfigLoader] treats its absence as an explicit load failure by design (no silent
 * 17:00 default). [ProvisionalConfirmTimeConfigSource] reads the *real* asset bytes unmodified
 * and patches in the exact same provisional "17:00" value every `backtest/parity` window's
 * grid.json already assumes for its BT-05 fixture (so both sides make the identical assumption)
 * - once MT1-00g
 * lands for real, the patch is a no-op (see its KDoc) and this test starts exercising the true
 * SSOT value with zero code changes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class LedgerToChainE2eTest {
    private lateinit var db: LakeDatabase

    @Before
    fun setUp() {
        db = LakeDatabase.buildInMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `real ledger, real config, real chain reproduce engine_ref for the whole window`() =
        runTest {
            val rawRows = readJsonLines(rawFixtureFile(), RawFixtureRow.serializer())
            val expectedTicks = readJsonLines(expectedFixtureFile(), ExpectedTick.serializer()).sortedBy { it.kstDate }
            check(expectedTicks.isNotEmpty()) { "$WINDOW_ID/expected.jsonl is empty - fixture regenerated?" }

            seedLedger(rawRows)

            val config =
                ConfirmTickConfigLoader.load(
                    ProvisionalConfirmTimeConfigSource(AssetConfigSource(ApplicationProvider.getApplicationContext())),
                )
            val gridProvider = TradingDayGridProvider(db.observationDao())

            for (expectedTick in expectedTicks) {
                val today = LocalDate.parse(expectedTick.kstDate)
                val clock = Clock.fixed(today.atTime(EVAL_TIME_KST).atZone(KST).toInstant(), ZoneId.of("UTC"))
                val runner =
                    ConfirmTickRunner(
                        db.observationDao(),
                        db.tickInputDao(),
                        db.runLogDao(),
                        gridProvider,
                        config,
                        clock,
                    )

                val outcome = runner.run()

                assertTrue(
                    "${expectedTick.kstDate}: expected a committed tick, got $outcome",
                    outcome is ConfirmTickOutcome.Committed,
                )
                assertEquals(
                    "${expectedTick.kstDate}: expected exactly one committed date (day-by-day live walk, no batching)",
                    listOf(today),
                    (outcome as ConfirmTickOutcome.Committed).committedDates,
                )

                val committedRow = db.tickInputDao().allOrderedByDate().last()
                assertEquals("committed row must be today's own row", today.toString(), committedRow.tradingDate)
                assertNull("day-by-day live walk must never freeze a gap row", committedRow.gapReason)
                assertComposite(expectedTick, committedRow.composite)

                val phase =
                    PhaseDerivation.currentPhase(db.tickInputDao(), config.profileName, config.statemachineConfig)
                assertEquals("${expectedTick.kstDate}: phase mismatch", expectedTick.phase, phase)
            }

            val rows = db.tickInputDao().allOrderedByDate()
            assertEquals("every expected tick committed, none skipped/gapped", expectedTicks.size, rows.size)
            assertEquals(0, rows.count { it.gapReason != null })
        }

    private fun assertComposite(
        expectedTick: ExpectedTick,
        actualComposite: Double?,
    ) {
        if (expectedTick.composite == null) {
            assertNull("${expectedTick.kstDate}: expected composite=null (0% coverage)", actualComposite)
            return
        }
        assertNotNull("${expectedTick.kstDate}: expected a non-null composite, got null", actualComposite)
        assertTrue(
            "${expectedTick.kstDate}: composite $actualComposite not within " +
                "$COMPOSITE_ABS_TOL of expected ${expectedTick.composite} (D-18/BT-05 §9-C L4 tolerance)",
            abs(actualComposite!! - expectedTick.composite) <= COMPOSITE_ABS_TOL,
        )
    }

    // ---------------------------------------------------------------------
    // stage ① — append every fixture observation through the real DAO at lane=0
    // ---------------------------------------------------------------------

    private suspend fun seedLedger(rawRows: List<RawFixtureRow>) {
        db.withTransaction {
            for (row in rawRows) {
                val mobileSeriesId = toMobileSeriesId(row.seriesId) ?: continue
                val asOfMillis = LocalDate.parse(row.asOf).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                db.observationDao().insert(
                    ObservationEntity(
                        seriesId = mobileSeriesId,
                        field = toMobileField(row.seriesId, row.field),
                        asOf = asOfMillis,
                        value = row.value,
                        observedAt = asOfMillis,
                        revision = 0,
                        lane = Lane.CONFIRMED,
                        source = "mt1-05k-fixture",
                    ),
                )
            }
        }
    }

    /** KRX-sourced pairs only (docs/plans/M1_PLAN_D.md §2.1 rationale, [ConfirmSeriesIds] file
     * header) - yfinance/FRED fixture ids already match the mobile collector's own seriesId.
     * `KRX:VKOSPI` returns null (never wired into v1 scoring, M-19(c)) - not seeded; its
     * absence is harmless because `vkospi_z` falls back to `KOSPI/close`
     * ([ConfirmSeriesIds.VKOSPI_FALLBACK_SERIES_FIELD]), which *is* seeded. */
    private fun toMobileSeriesId(fixtureSeriesId: String): String? =
        when (fixtureSeriesId) {
            FIXTURE_KOSPI -> ConfirmSeriesIds.KOSPI
            FIXTURE_KOSPI_INVESTOR -> ConfirmSeriesIds.KOSPI_INVESTOR
            FIXTURE_VKOSPI -> null
            else -> fixtureSeriesId
        }

    private fun toMobileField(
        fixtureSeriesId: String,
        fixtureField: String,
    ): String =
        if (fixtureSeriesId == FIXTURE_KOSPI_INVESTOR) {
            ConfirmSeriesIds.FIELD_FOREIGN_NET_BUY_VALUE
        } else {
            fixtureField
        }

    // ---------------------------------------------------------------------
    // fixture loading (backtest/parity/$WINDOW_ID, read from its original repo-root location -
    // never copied into this module, SnapshotContractsTest/FixtureCrossCheckTest precedent)
    // ---------------------------------------------------------------------

    @Serializable
    private data class RawFixtureRow(
        @SerialName("series_id") val seriesId: String,
        val field: String,
        @SerialName("as_of") val asOf: String,
        val value: Double,
    )

    @Serializable
    private data class ExpectedTick(
        @SerialName("kst_date") val kstDate: String,
        val composite: Double? = null,
        val phase: String,
    )

    private fun <T> readJsonLines(
        file: File,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): List<T> {
        val json = Json { ignoreUnknownKeys = true }
        return file.readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() }
            .map { json.decodeFromString(serializer, it) }
    }

    private fun rawFixtureFile(): File = findUnderRepoRoot("backtest/parity/$WINDOW_ID/raw.jsonl")

    private fun expectedFixtureFile(): File = findUnderRepoRoot("backtest/parity/$WINDOW_ID/expected.jsonl")

    private fun findUnderRepoRoot(relativePath: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(MAX_PARENT_HOPS) {
            val candidate = dir?.let { File(it, relativePath) }
            if (candidate != null && candidate.isFile) return candidate
            dir = dir?.parentFile
        }
        error("$relativePath not found by walking up from ${System.getProperty("user.dir")}")
    }

    /**
     * Wraps the real [AssetConfigSource] and patches only the single known placeholder line in
     * `statemachine.yaml`'s `mobile_daily` profile (`# confirm_time_kst: 미기입 — ...`) with the
     * provisional "17:00" value every `backtest/parity` window's grid.json fixture already
     * assumes (MT1-05e `export_parity.py`). Every other line - all 13 real indicator specs, thresholds,
     * modifiers, transition rules - passes through byte-for-byte unmodified. Once MT1-00g's
     * real-world measurement lands and the SSOT gets a real `confirm_time_kst:` line, the regex
     * simply finds no match and this becomes a pure pass-through - no future edit needed here.
     */
    private class ProvisionalConfirmTimeConfigSource(private val delegate: ConfigSource) : ConfigSource {
        override fun open(name: String): InputStream {
            val text = delegate.open(name).use { it.readBytes().toString(Charsets.UTF_8) }
            if (name != "statemachine.yaml") return text.byteInputStream(Charsets.UTF_8)
            val patched = PLACEHOLDER_LINE.replace(text, "    confirm_time_kst: \"$PROVISIONAL_CONFIRM_TIME_KST\"")
            return patched.byteInputStream(Charsets.UTF_8)
        }

        private companion object {
            val PLACEHOLDER_LINE = Regex("""(?m)^\s*#\s*confirm_time_kst:.*$""")
        }
    }

    private companion object {
        const val MAX_PARENT_HOPS = 8
        const val WINDOW_ID = "w2026_structural"
        const val PROVISIONAL_CONFIRM_TIME_KST = "17:00"
        val EVAL_TIME_KST: LocalTime = LocalTime.of(18, 0) // after 17:00 confirm time (existing test convention)

        // §9-C L4 (backtest/test_bt05_parity.py COMPOSITE_ABS_TOL comment: "composite |Δ| <= 0.05",
        // D-18/BT-05 규정) - the same cross-language floating-point tolerance BT-05 itself uses.
        const val COMPOSITE_ABS_TOL = 0.05

        const val FIXTURE_KOSPI = "KRX:1001"
        const val FIXTURE_KOSPI_INVESTOR = "KRX:investor_foreign_kospi"
        const val FIXTURE_VKOSPI = "KRX:VKOSPI"
    }
}
