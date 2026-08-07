package com.branchconsole.app.home

import com.branchconsole.lake.RunLogEntity
import com.branchconsole.lake.TickInputEntity
import org.junit.Assert.assertEquals
import org.junit.Test

private const val REGISTRY_VERSION = "0.0.0-test"

private fun tick(
    composite: Double? = 10.0,
    coverage: Double = 1.0,
    gapReason: String? = null,
) = TickInputEntity(
    tradingDate = "2026-08-06",
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

private fun runLog(status: String) = RunLogEntity(tradingDate = "2026-08-06", ranAt = 0L, status = status, detail = null)

/** TASK MT1-08b 완료 기준 — 홈 상태 7분기 전건(`--tests "*HomeState*"`). */
class HomeStateMapperTest {
    @Test
    fun `EMPTY - no tick, no run log at all`() {
        assertEquals(HomeState.EMPTY, HomeStateMapper.compute(lastTick = null, lastRunLog = null, previewSuppressed = null))
    }

    @Test
    fun `WARMUP - no tick yet but a WARMUP_INSUFFICIENT attempt was logged`() {
        assertEquals(
            HomeState.WARMUP,
            HomeStateMapper.compute(lastTick = null, lastRunLog = runLog("WARMUP_INSUFFICIENT"), previewSuppressed = null),
        )
    }

    @Test
    fun `ERROR - most recent run failed, even if an older tick exists`() {
        assertEquals(
            HomeState.ERROR,
            HomeStateMapper.compute(lastTick = tick(), lastRunLog = runLog("failed"), previewSuppressed = null),
        )
    }

    @Test
    fun `ERROR - most recent run was a config_error`() {
        assertEquals(
            HomeState.ERROR,
            HomeStateMapper.compute(lastTick = null, lastRunLog = runLog("config_error"), previewSuppressed = null),
        )
    }

    @Test
    fun `ERROR - confirmed tick is eval-impossible (composite null, not a gap row)`() {
        assertEquals(
            HomeState.ERROR,
            HomeStateMapper.compute(
                lastTick = tick(composite = null, gapReason = null),
                lastRunLog = runLog("success"),
                previewSuppressed = null,
            ),
        )
    }

    @Test
    fun `GAP - most recent tick is a catchup-truncation marker row`() {
        assertEquals(
            HomeState.GAP,
            HomeStateMapper.compute(
                lastTick = tick(composite = null, gapReason = "CATCHUP_GAP_TRUNCATED: cap 20 exceeded"),
                lastRunLog = runLog("success"),
                previewSuppressed = null,
            ),
        )
    }

    @Test
    fun `SUPPRESSED - confirmed tick is fine, but the latest preview is coverage-suppressed`() {
        assertEquals(
            HomeState.SUPPRESSED,
            HomeStateMapper.compute(lastTick = tick(coverage = 1.0), lastRunLog = runLog("success"), previewSuppressed = true),
        )
    }

    @Test
    fun `PARTIAL - confirmed tick landed but coverage is below 100 percent`() {
        assertEquals(
            HomeState.PARTIAL,
            HomeStateMapper.compute(lastTick = tick(coverage = 0.677), lastRunLog = runLog("success"), previewSuppressed = false),
        )
    }

    @Test
    fun `NORMAL - everything nominal`() {
        assertEquals(
            HomeState.NORMAL,
            HomeStateMapper.compute(lastTick = tick(coverage = 1.0), lastRunLog = runLog("success"), previewSuppressed = false),
        )
    }

    @Test
    fun `NORMAL - no preview has ever been run (null suppressed) still counts as nominal`() {
        assertEquals(
            HomeState.NORMAL,
            HomeStateMapper.compute(lastTick = tick(coverage = 1.0), lastRunLog = runLog("success"), previewSuppressed = null),
        )
    }
}
