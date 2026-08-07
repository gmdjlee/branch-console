package com.branchconsole.engine.pit

/**
 * 계열의 가시성 규칙 종류(docs/plans/M1_PLAN_A.md §2.5.1 `L` 표, `run_replay.calendar_kind`
 * 대응). `KRW=X`(FX)와 KRX 계열은 L=0(당일 가시), yfinance 미국 지수는 L=1일(다음 그리드일에
 * 가시), FRED·ECOS는 `source.lag_days`.
 */
enum class CalendarKind { US_MARKET, FRED, KRX, FX }

/**
 * 프로덕션 매핑 — `indicators.yaml` `source.provider`(+`symbol`)에서 파생한다. 픽스처 전용
 * `backtest/fixture_schema.calendar_kind`(그 외 전부 us_market 폴백)를 그대로 쓰지 않는다
 * (M1_PLAN_A.md §2.5.1 "발견 사항" — ECOS를 그 휴리스틱으로 분류하면 우연히 lag_days=1과
 * 같아 "오늘은 값이 맞지만 근거가 틀린" 상태가 된다).
 */
object CalendarKindResolver {
    fun resolve(
        provider: String,
        symbol: String? = null,
    ): CalendarKind =
        when {
            symbol == "KRW=X" -> CalendarKind.FX
            provider == "yfinance" -> CalendarKind.US_MARKET
            provider == "pykrx" || provider == "krx_notice" || provider == "krx_margin" -> CalendarKind.KRX
            provider == "fred" -> CalendarKind.FRED
            provider == "ecos" -> CalendarKind.FRED // kr_lagged — lag_days 규약이 fred와 동일(§2.5.1)
            provider == "scrape_wgb" -> CalendarKind.KRX
            else -> error("unknown provider for calendar kind resolution: '$provider'")
        }
}
