package com.branchconsole.engine.config

import java.time.Duration

private val DURATION_RE = Regex("""^(\d+)([mhd])$""")

/** `engine_ref.registry`의 stale duration 파서 1:1 이식. */
object StaleWindows {
    fun parseDuration(s: String): Duration {
        val m = DURATION_RE.matchEntire(s.trim()) ?: error("unrecognized duration string: '$s'")
        val n = m.groupValues[1].toLong()
        return when (m.groupValues[2]) {
            "m" -> Duration.ofMinutes(n)
            "h" -> Duration.ofHours(n)
            "d" -> Duration.ofDays(n)
            else -> error("unrecognized duration unit in: '$s'")
        }
    }

    /**
     * `engine.stale_profiles[profile][cadence]` 조회. 프로파일 맵에 그 cadence 키가 없으면
     * (예: `mobile_daily`에 `intraday_30m` 없음) 그 프로파일의 `daily_kr` 창으로 폴백한다
     * (Advisor 지정 해석 — docs/plans/M1_PLAN_A.md §2.8 cadence 폴백 함정, 영향 지표:
     * usdkrw_z·vkospi_z·kospi_drawdown, 가중 8.0/31.0).
     */
    fun staleWindow(
        source: ConfigSource,
        profile: String,
        cadence: String,
    ): Duration {
        val root = YamlLoader.loadMap(source, "indicators.yaml")
        val windows = root.asMap("engine").asMap("stale_profiles").asMap(profile)
        val raw = (windows[cadence] as? String) ?: windows.asString("daily_kr")
        return parseDuration(raw)
    }
}
