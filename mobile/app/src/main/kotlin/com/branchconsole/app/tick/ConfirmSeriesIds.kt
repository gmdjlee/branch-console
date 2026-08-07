package com.branchconsole.app.tick

import com.branchconsole.engine.pit.CalendarKind

/**
 * 지표 → (계열id, 필드) 프로덕션 배선 상수 (MT1-06c). 정본은
 * `mobile/engine/src/test/kotlin/.../parity/ParityEngine.kt`의 13종 빌더(BT-05 패리티 참조
 * 구현)이지만, **거기서 쓰는 계열id는 백테스트 픽스처 관례(`KRX:1001`류)이고 실제 모바일
 * collectors가 lake에 적재하는 계열id는 다르다** — 두 값이 갈리는 지점을 여기 한 곳에
 * 모아 명시한다(발견: MT1-04c `KrxCollector`는 `KrxIndex.TICKER_KOSPI`="1001"을 그대로
 * seriesId로 쓰고 `KRX:` 접두사를 붙이지 않으며, VKOSPI는 "vkospi", 외국인 순매수는
 * "kospi_investor_trading"/"foreign_net_buy_value"로 적재한다).
 *
 * 이 파일이 바뀌는 유일한 이유는 collectors가 실제로 쓰는 seriesId/field가 바뀔 때뿐이다 —
 * indicators.yaml의 `source` 블록과 값이 문자 그대로 같지 않은 것은 SSOT 위반이 아니라(이
 * 매핑 자체가 `engine_ref._BUILDERS`처럼 코드 인프라다, CLAUDE.md §1은 임계값·가중치·스케줄에
 * 적용된다), MT1-04c가 그 문자열을 확정했기 때문이다.
 */
internal object ConfirmSeriesIds {
    // yfinance (YahooCollector — seriesId=symbol, field 그대로).
    const val VIX = "^VIX"
    const val VIX3M = "^VIX3M"
    const val MOVE = "^MOVE"
    const val GSPC = "^GSPC"
    const val DXY = "DX-Y.NYB"
    const val USDKRW = "KRW=X"

    // fred (FredCollector — seriesId=series_id, field="value").
    const val HY_OAS = "BAMLH0A0HYM2"
    const val UST_2S10S = "T10Y2Y"

    // pykrx (KrxCollector 실제 적재 seriesId — "KRX:" 접두사 없음, MT1-04c 확정).
    const val KOSPI = "1001"
    const val VKOSPI = "vkospi"
    const val KOSPI_INVESTOR = "kospi_investor_trading"

    const val FIELD_CLOSE = "close"
    const val FIELD_VALUE = "value"
    const val FIELD_TRADING_VALUE = "trading_value"
    const val FIELD_HIGH = "high"
    const val FIELD_LOW = "low"
    const val FIELD_FOREIGN_NET_BUY_VALUE = "foreign_net_buy_value"

    /** BT-01 수집 범위 밖 — M0 픽스처부터 상시 결측(`ParityEngine.ALWAYS_MISSING_INDICATORS`와
     * 동일 집합). 부트스트랩 게이트가 이 둘을 "웜업 대상"에서 제외하는 근거이기도 하다(영원히
     * 0행이라 포함하면 게이트가 열리지 않는다). */
    val ALWAYS_MISSING_INDICATORS = setOf("krx_credit_spread_delta", "kr_cds_5y_delta")

    private val KRX_SERIES_IDS = setOf(KOSPI, VKOSPI, KOSPI_INVESTOR)

    /** `run_replay.calendar_kind` 프로덕션 대응(§2.5.1 L 표) — provider 기반 [
     * com.branchconsole.engine.pit.CalendarKindResolver]를 쓰지 않는 이유: `global_corr_break`의
     * `source.provider: derived`는 그 리졸버가 모르는 provider이고, 여기서 필요한 것은 스펙의
     * provider가 아니라 **개별 원계열**(예: `^GSPC`)의 캘린더 종류이므로 seriesId 기반이 더
     * 직접적이다(ParityEngine.kt의 동일한 로컬 `calendarKindOf` 패턴 재사용). */
    fun calendarKindOf(seriesId: String): CalendarKind =
        when {
            seriesId in KRX_SERIES_IDS -> CalendarKind.KRX
            seriesId == USDKRW -> CalendarKind.FX
            seriesId == HY_OAS || seriesId == UST_2S10S -> CalendarKind.FRED
            else -> CalendarKind.US_MARKET
        }

    /**
     * 부트스트랩 게이트(MT1-06h, WarmupGate)가 "요구 행 수 충족 여부"를 판정할 때 볼 원계열 —
     * 지표별 빌더가 **가장 먼저** 읽는 (seriesId, field)와 1:1(§2.11 "requiredRows는 이
     * 원계열의 raw row count로 판정"). `vkospi_z`는 데이터 기반 폴백이라 두 후보 모두 등재하고
     * [WarmupGate]가 실제 존재하는 쪽을 고른다.
     */
    val PRIMARY_SERIES_FIELD: Map<String, Pair<String, String>> =
        mapOf(
            "vix_level_z" to (VIX to FIELD_CLOSE),
            "vix_term_structure" to (VIX to FIELD_CLOSE),
            "move_index_z" to (MOVE to FIELD_CLOSE),
            "hy_oas_delta" to (HY_OAS to FIELD_VALUE),
            "dxy_z" to (DXY to FIELD_CLOSE),
            "ust_2s10s_move" to (UST_2S10S to FIELD_VALUE),
            "spx_drawdown_momentum" to (GSPC to FIELD_CLOSE),
            "global_corr_break" to (KOSPI to FIELD_CLOSE),
            "kospi_drawdown" to (KOSPI to FIELD_CLOSE),
            "foreign_net_sell_kospi" to (KOSPI_INVESTOR to FIELD_FOREIGN_NET_BUY_VALUE),
            "kospi_volume_distribution" to (KOSPI to FIELD_TRADING_VALUE),
            "usdkrw_z" to (USDKRW to FIELD_CLOSE),
        )

    /** `vkospi_z` 전용 — 폴백 분기 판정에 필요한 두 번째 후보(§2.11 [WarmupGate]). */
    val VKOSPI_FALLBACK_SERIES_FIELD: Pair<String, String> = KOSPI to FIELD_CLOSE

    /** 확정 틱 한 번에 필요한 (seriesId, field) 전체 — 프리페치 대상(중복 제거). 13개 빌더 +
     * modifier 보조 입력(HY 레벨은 HY_OAS 자체 재사용, USDKRW high/low)의 합집합. */
    val REQUIRED_SERIES_FIELDS: List<Pair<String, String>> =
        listOf(
            VIX to FIELD_CLOSE,
            VIX3M to FIELD_CLOSE,
            MOVE to FIELD_CLOSE,
            HY_OAS to FIELD_VALUE,
            DXY to FIELD_CLOSE,
            UST_2S10S to FIELD_VALUE,
            GSPC to FIELD_CLOSE,
            KOSPI to FIELD_CLOSE,
            KOSPI to FIELD_TRADING_VALUE,
            VKOSPI to FIELD_CLOSE,
            KOSPI_INVESTOR to FIELD_FOREIGN_NET_BUY_VALUE,
            USDKRW to FIELD_CLOSE,
            USDKRW to FIELD_HIGH,
            USDKRW to FIELD_LOW,
        ).distinct()
}
