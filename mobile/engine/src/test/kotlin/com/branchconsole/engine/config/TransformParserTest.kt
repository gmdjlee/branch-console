package com.branchconsole.engine.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TransformParserTest {
    @Test
    fun `parseCallKwargs extracts only the named call's top-level kwargs, not nested ones`() {
        // gated( zscore(trading_value, window=60), gate="daily_return < 0" ) —
        // kospi_volume_distribution's actual transform string.
        val transform = """gated( zscore(trading_value, window=60), gate="daily_return < 0" )"""
        assertEquals(mapOf("window" to 60), TransformParser.parseCallKwargs("zscore", transform))
        assertEquals(mapOf("gate" to "daily_return < 0"), TransformParser.parseCallKwargs("gated", transform))
    }

    @Test
    fun `parseCallKwargs does not confuse zscore with neg_zscore (word boundary)`() {
        val transform = "neg_zscore(pct_change_5d, window=252)"
        assertFailsWith<IllegalStateException> { TransformParser.parseCallKwargs("zscore", transform) }
        assertEquals(mapOf("window" to 252), TransformParser.parseCallKwargs("neg_zscore", transform))
    }

    @Test
    fun `parseCallKwargs handles boolean and coerces int vs double correctly`() {
        val transform = "zscore(pct_change_5d, window=252, absolute=true)"
        val kwargs = TransformParser.parseCallKwargs("zscore", transform)
        assertEquals(252, kwargs["window"])
        assertEquals(true, kwargs["absolute"])
    }

    @Test
    fun `parseFallbackWindow extracts the trailing _Nd window`() {
        assertEquals(20, TransformParser.parseFallbackWindow("realized_vol_kospi_20d"))
    }

    @Test
    fun `parseGate splits variable, operator and threshold`() {
        val (variable, op, threshold) = TransformParser.parseGate("daily_return < 0")
        assertEquals("daily_return", variable)
        assertEquals("<", op)
        assertEquals(0.0, threshold)
    }

    @Test
    fun `extractCallBody balances nested parentheses`() {
        val body =
            TransformParser.extractCallBody(
                "abs",
                "abs( rolling_corr(a, b, window=20) - rolling_mean_corr(window=120) )",
            )
        assertEquals(" rolling_corr(a, b, window=20) - rolling_mean_corr(window=120) ", body)
    }

    // ---- requiredRows: docs/plans/M1_PLAN_D.md §2.3.2 도출표 전건 대조 ----
    // 값은 SSOT 리터럴이 아니라 실제 configs/indicators.yaml의 transform 문자열에서 파생된다
    // (RepoConfigSource로 로드) — D 문서 표와 일치가 회귀 기준.

    private val specs by lazy { IndicatorRegistry.loadIndicatorSpecs(RepoConfigSource, enabledOnly = false) }

    private fun spec(id: String) = specs.first { it.id == id }

    @Test
    fun `requiredRows matches the M1_PLAN_D 2-3-2 derivation table`() {
        assertEquals(253, TransformParser.requiredRows(spec("vix_level_z")))
        assertEquals(1, TransformParser.requiredRows(spec("vix_term_structure")))
        assertEquals(253, TransformParser.requiredRows(spec("move_index_z")))
        assertEquals(6, TransformParser.requiredRows(spec("hy_oas_delta")))
        assertEquals(6, TransformParser.requiredRows(spec("krx_credit_spread_delta")))
        assertEquals(6, TransformParser.requiredRows(spec("kr_cds_5y_delta")))
        assertEquals(254, TransformParser.requiredRows(spec("usdkrw_z")))
        assertEquals(258, TransformParser.requiredRows(spec("dxy_z")))
        assertEquals(6, TransformParser.requiredRows(spec("ust_2s10s_move")))
        assertEquals(318, TransformParser.requiredRows(spec("spx_drawdown_momentum")))
        assertEquals(141, TransformParser.requiredRows(spec("global_corr_break")))
        assertEquals(
            273,
            TransformParser.requiredRows(spec("vkospi_z")),
            "conservative upper bound includes the fallback field's _20d suffix",
        )
        assertEquals(61, TransformParser.requiredRows(spec("kospi_drawdown")))
        assertEquals(258, TransformParser.requiredRows(spec("foreign_net_sell_kospi")))
        assertEquals(61, TransformParser.requiredRows(spec("kospi_volume_distribution")))
    }
}
