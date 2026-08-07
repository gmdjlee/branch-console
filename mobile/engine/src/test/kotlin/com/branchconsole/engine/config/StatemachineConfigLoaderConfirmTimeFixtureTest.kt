package com.branchconsole.engine.config

import java.io.InputStream
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `confirm_time_kst`/`catchup_max_ticks` 파싱 경로를, 실측 미완료로 값이 없는 실제 SSOT와
 * 독립적으로 고정한다(MT1-06 브리프 aaa 요건 1) — 키가 **있을 때** 올바르게 [LocalTime]으로
 * 파싱되는지는 [StatemachineConfigLoaderTest]가 아니라 이 픽스처가 증명한다(실제
 * `statemachine.yaml`은 아직 그 키가 없으므로).
 */
private object FixtureWithConfirmTime : ConfigSource {
    private val yaml =
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

    override fun open(name: String): InputStream = yaml.byteInputStream()
}

class StatemachineConfigLoaderConfirmTimeFixtureTest {
    @Test
    fun `confirm_time_kst parses as LocalTime when the SSOT key is present`() {
        val config = StatemachineConfigLoader.load(FixtureWithConfirmTime)
        val profile = config.profiles.getValue("mobile_daily")
        assertEquals(LocalTime.of(17, 0), profile.confirmTimeKst)
        assertEquals(20, profile.catchupMaxTicks)
    }
}
