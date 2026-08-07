package com.branchconsole.app.home

import com.branchconsole.lake.RunLogEntity
import com.branchconsole.lake.TickInputEntity
import org.junit.Assert.assertEquals
import org.junit.Test

private const val REGISTRY_VERSION = "0.0.0-test"
private const val DATE = "2026-08-06"

private fun tick(
    composite: Double? = 10.0,
    coverage: Double = 1.0,
    gapReason: String? = null,
) = TickInputEntity(
    tradingDate = DATE,
    composite = composite,
    distinctAxes = 0,
    anyCrit = false,
    anyExtreme = false,
    severitiesJson = "{}",
    coverage = coverage,
    registryVersion = REGISTRY_VERSION,
    gapReason = gapReason,
    frozenAt = 0L,
    firedAxes = null,
    visibleAtByIndicator = null,
    isCatchup = false,
    warmupStatusJson = null,
    pitQuality = "live",
)

private fun runLog(status: String) = RunLogEntity(tradingDate = DATE, ranAt = 0L, status = status, detail = null)

/** TASK MT1-08b 완료 기준 — 홈 상태 7분기 전건(`--tests "*HomeState*"`). */
class HomeStateMapperTest {
    @Test
    fun `EMPTY - no tick, no run log at all`() {
        val state = HomeStateMapper.compute(lastTick = null, lastRunLog = null, previewSuppressed = null)
        assertEquals(HomeState.EMPTY, state)
    }

    @Test
    fun `WARMUP - no tick yet but a WARMUP_INSUFFICIENT attempt was logged`() {
        val state =
            HomeStateMapper.compute(
                lastTick = null,
                lastRunLog = runLog("WARMUP_INSUFFICIENT"),
                previewSuppressed = null,
            )
        assertEquals(HomeState.WARMUP, state)
    }

    @Test
    fun `ERROR - most recent run failed, even if an older tick exists`() {
        val state = HomeStateMapper.compute(lastTick = tick(), lastRunLog = runLog("failed"), previewSuppressed = null)
        assertEquals(HomeState.ERROR, state)
    }

    @Test
    fun `ERROR - most recent run was a config_error`() {
        val state =
            HomeStateMapper.compute(lastTick = null, lastRunLog = runLog("config_error"), previewSuppressed = null)
        assertEquals(HomeState.ERROR, state)
    }

    @Test
    fun `aaa N-1 - ERROR when credentials are not configured, not silently EMPTY`() {
        val state =
            HomeStateMapper.compute(lastTick = null, lastRunLog = runLog("not_configured"), previewSuppressed = null)
        assertEquals(HomeState.ERROR, state)
    }

    @Test
    fun `ERROR - confirmed tick is eval-impossible (composite null, not a gap row)`() {
        val state =
            HomeStateMapper.compute(
                lastTick = tick(composite = null, gapReason = null),
                lastRunLog = runLog("success"),
                previewSuppressed = null,
            )
        assertEquals(HomeState.ERROR, state)
    }

    @Test
    fun `GAP - most recent tick is a catchup-truncation marker row`() {
        val state =
            HomeStateMapper.compute(
                lastTick = tick(composite = null, gapReason = "CATCHUP_GAP_TRUNCATED: cap 20 exceeded"),
                lastRunLog = runLog("success"),
                previewSuppressed = null,
            )
        assertEquals(HomeState.GAP, state)
    }

    @Test
    fun `SUPPRESSED - confirmed tick is fine, but the latest preview is coverage-suppressed`() {
        val state =
            HomeStateMapper.compute(
                lastTick = tick(coverage = 1.0),
                lastRunLog = runLog("success"),
                previewSuppressed = true,
            )
        assertEquals(HomeState.SUPPRESSED, state)
    }

    @Test
    fun `PARTIAL - confirmed tick landed but coverage is below 100 percent`() {
        val state =
            HomeStateMapper.compute(
                lastTick = tick(coverage = 0.677),
                lastRunLog = runLog("success"),
                previewSuppressed = false,
            )
        assertEquals(HomeState.PARTIAL, state)
    }

    @Test
    fun `NORMAL - everything nominal`() {
        val state =
            HomeStateMapper.compute(
                lastTick = tick(coverage = 1.0),
                lastRunLog = runLog("success"),
                previewSuppressed = false,
            )
        assertEquals(HomeState.NORMAL, state)
    }

    @Test
    fun `NORMAL - no preview has ever been run (null suppressed) still counts as nominal`() {
        val state =
            HomeStateMapper.compute(
                lastTick = tick(coverage = 1.0),
                lastRunLog = runLog("success"),
                previewSuppressed = null,
            )
        assertEquals(HomeState.NORMAL, state)
    }
}
