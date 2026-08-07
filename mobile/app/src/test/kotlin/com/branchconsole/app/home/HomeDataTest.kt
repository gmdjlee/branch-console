package com.branchconsole.app.home

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.branchconsole.app.preview.PreviewIndicatorState
import com.branchconsole.app.preview.PreviewResult
import com.branchconsole.lake.LakeDatabase
import com.branchconsole.lake.RunLogEntity
import com.branchconsole.lake.TickInputEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

private const val REGISTRY_VERSION = "0.0.0-test"

private fun tickRow(
    date: String,
    severitiesJson: String,
    composite: Double? = 24.3,
    coverage: Double = 1.0,
) = TickInputEntity(
    tradingDate = date,
    composite = composite,
    distinctAxes = 0,
    anyCrit = false,
    anyExtreme = false,
    severitiesJson = severitiesJson,
    coverage = coverage,
    registryVersion = REGISTRY_VERSION,
    gapReason = null,
    frozenAt = 0L,
    firedAxes = null,
    visibleAtByIndicator = null,
    isCatchup = false,
    warmupStatusJson = null,
    pitQuality = "live",
)

/** MT1-08b — [HomeData.load]가 Room + 실 assets(registry_version·axis 매핑)에서 [HomeUiState]를
 * 조립하는지 검증한다(분기 로직 자체는 [HomeStateMapperTest]가 전건 담당). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class HomeDataTest {
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
    fun `empty lake yields EMPTY state with no tick fields`() =
        runTest {
            val ui = HomeData.load(ApplicationProvider.getApplicationContext(), db, previewResult = null)

            assertEquals(HomeState.EMPTY, ui.state)
            assertNull(ui.composite)
            assertNull(ui.lastTickDate)
            assertTrue(ui.topIndicators.isEmpty())
        }

    @Test
    fun `top indicators are ranked by severity descending and capped at 3, with real axis lookup`() =
        runTest {
            // "vix_level_z" is a real indicator id (axis vol_global) -- exercises the real
            // assets registry lookup, not a fixture.
            val severities = """{"vix_level_z": 3, "kospi_drawdown": 1, "usdkrw_z": 2, "dxy_z": null}"""
            db.tickInputDao().insert(tickRow("2026-08-06", severities))

            val ui = HomeData.load(ApplicationProvider.getApplicationContext(), db, previewResult = null)

            assertEquals(3, ui.topIndicators.size)
            assertEquals("vix_level_z", ui.topIndicators[0].id)
            assertEquals(3, ui.topIndicators[0].severity)
            assertEquals("vol_global", ui.topIndicators[0].axis)
            assertEquals("usdkrw_z", ui.topIndicators[1].id)
            assertEquals("kospi_drawdown", ui.topIndicators[2].id)
        }

    @Test
    fun `registry version is read from real assets`() =
        runTest {
            db.tickInputDao().insert(tickRow("2026-08-06", "{}"))

            val ui = HomeData.load(ApplicationProvider.getApplicationContext(), db, previewResult = null)

            assertTrue("registry_version must be non-blank from the real SSOT asset", !ui.registryVersion.isNullOrBlank())
        }

    @Test
    fun `last run log status and detail surface on the ui state`() =
        runTest {
            db.tickInputDao().insert(tickRow("2026-08-06", "{}"))
            db.runLogDao().insert(RunLogEntity(tradingDate = "2026-08-06", ranAt = 0L, status = "success", detail = "committed=1"))

            val ui = HomeData.load(ApplicationProvider.getApplicationContext(), db, previewResult = null)

            assertEquals("success", ui.lastRunStatus)
            assertEquals("committed=1", ui.lastRunDetail)
        }

    @Test
    fun `preview projection surfaces stale (carried) indicators as a distinct badge list`() =
        runTest {
            val previewResult =
                PreviewResult(
                    tickDay = LocalDate.of(2026, 8, 6),
                    evaluatedAt = Instant.parse("2026-08-06T01:00:00Z"),
                    rawCoverage = 21.0 / 31.0,
                    filledCoverage = 1.0,
                    filledComposite = 45.2,
                    suppressed = true,
                    indicators =
                        mapOf(
                            // Freshly observed this preview -- not stale.
                            "vix_level_z" to PreviewIndicatorState(severity = 2, observed = true, carriedAsOfMillis = null),
                            // Carried forward from the last confirmed tick -- stale badge target.
                            "kospi_drawdown" to PreviewIndicatorState(severity = 1, observed = false, carriedAsOfMillis = 1000L),
                            // Missing even after carry-forward -- not a stale badge (no severity to carry).
                            "usdkrw_z" to PreviewIndicatorState(severity = null, observed = false, carriedAsOfMillis = null),
                        ),
                )

            val ui = HomeData.load(ApplicationProvider.getApplicationContext(), db, previewResult)

            val preview = ui.preview
            assertTrue("preview projection must be present when a PreviewResult is supplied", preview != null)
            assertEquals(true, preview!!.suppressed)
            assertEquals(1, preview.staleIndicators.size)
            assertEquals("kospi_drawdown", preview.staleIndicators.single().id)
            assertEquals(1000L, preview.staleIndicators.single().carriedAsOfMillis)
        }
}
