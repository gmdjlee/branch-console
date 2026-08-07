package com.branchconsole.engine.config

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class StaleWindowsTest {
    @Test
    fun `parseDuration handles minutes hours and days`() {
        assertEquals(Duration.ofMinutes(90), StaleWindows.parseDuration("90m"))
        assertEquals(Duration.ofHours(30), StaleWindows.parseDuration("30h"))
        assertEquals(Duration.ofDays(2), StaleWindows.parseDuration("2d"))
    }

    @Test
    fun `staleWindow reads the direct cadence key when present`() {
        assertEquals(Duration.ofHours(48), StaleWindows.staleWindow(RepoConfigSource, "mobile_daily", "daily_us"))
        assertEquals(Duration.ofHours(96), StaleWindows.staleWindow(RepoConfigSource, "mobile_daily", "fred_daily"))
        assertEquals(Duration.ofHours(30), StaleWindows.staleWindow(RepoConfigSource, "mobile_daily", "daily_kr"))
    }

    @Test
    fun `staleWindow falls back to daily_kr when mobile_daily has no intraday_30m key`() {
        // M1_PLAN_A.md §2.8 cadence 폴백 함정 — usdkrw_z·vkospi_z·kospi_drawdown(가중
        // 8.0/31.0)이 이 경로로 30h(daily_kr) 창을 받는다. mobile_daily에 intraday_30m 키가
        // 없다는 사실 자체가 SSOT 함정이므로, 이 테스트가 그 부재를 전제로 폴백을 확인한다.
        assertEquals(Duration.ofHours(30), StaleWindows.staleWindow(RepoConfigSource, "mobile_daily", "intraday_30m"))
    }

    @Test
    fun `staleWindow does not fall back for server_intraday which does declare intraday_30m`() {
        assertEquals(
            Duration.ofMinutes(90),
            StaleWindows.staleWindow(RepoConfigSource, "server_intraday", "intraday_30m"),
        )
    }
}
