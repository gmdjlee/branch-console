package com.branchconsole.app.preview

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.branchconsole.app.tick.AssetConfigSource
import com.branchconsole.app.tick.ConfirmSeriesIds
import com.branchconsole.engine.config.IndicatorRegistry
import com.branchconsole.engine.config.PreviewPolicy
import com.branchconsole.engine.scoring.Scoring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * MT1-04d A-1 검증 — aaa M-1 정정판(라운드 2). 최초 판은 PROGRESS.md GM1 기록의 "ECOS 차단 시
 * 0.792 < 0.80... 키 발급 시 0.847로 해소"를 그대로 인용했으나, **그 두 수치 자체가 SSOT에서
 * 도출 불가능한 오류였다**(W0 발원 드리프트 — 실제 등록 가중치(31.0)로는 재현되지 않음). 이
 * 클래스가 관계식만 단언하고 실수치를 동결하지 않아 그 불일치를 잡지 못했다(aaa M-1 지적) —
 * 이번 판은 실수치를 동결 단언으로 못박는다.
 *
 * 실측 정본(assets 가중 산출, `configs/indicators.yaml` 15개 활성 지표 weight 합계=31.0):
 * - 현행 상한(구조적 결측 2종만 제외: `krx_credit_spread_delta` 2.0 + `kr_cds_5y_delta` 1.5)
 *   = 27.5/31.0 = **0.8871** — `preview_coverage_min`(0.80)보다 **높다**. 즉 구조적 결측만으로는
 *   프리뷰가 억제되지 않는다(최초 판·`PreviewTickRunner` KDoc의 "상시 억제 = 정상 동작" 서술은
 *   틀렸다 — 정정은 해당 KDoc에서 별도로 함).
 * - ECOS 배선 후 도달 가능한 상한(`kr_cds_5y_delta`만 제외) = 29.5/31.0 = **0.9516**.
 * - 실제 억제는 구조적 결측이 아니라 **런타임 결측**(예: 00a 저널의 `^MOVE`·`^VIX3M` 갱신 정지 —
 *   `move_index_z`(1.5)+`vix_term_structure`(2.5) 추가 결측 시 23.5/31.0=0.7581<0.80)에서 걸린다.
 *
 * 이 지표의 severity 계산(`delta_bp(corp_aa3y - ktb_3y, lookback=5)`)은 `backtest/run_replay.py`의
 * `_BUILDERS`에도 없는 로직이다(`_ALWAYS_MISSING_INDICATORS`, "BT-01 수집 범위 밖") — Python
 * 정본에 없는 계산을 모바일이 먼저 발명하면 BT-05가 검증할 golden이 없다("재구현 없음" 규율,
 * `EcosCollector.kt` KDoc과 동일 근거). 그래서 `ConfirmSeriesIds.ALWAYS_MISSING_INDICATORS`는
 * 이번 서브태스크에서 그대로 두었다 — 0.9516은 그 지표가 훗날 배선되면 도달 가능한 참고 상한이다.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class EcosCoverageTest {
    private val source = AssetConfigSource(ApplicationProvider.getApplicationContext())
    private val specs = IndicatorRegistry.loadIndicatorSpecs(source, enabledOnly = true)
    private val weights = IndicatorRegistry.weightMap(specs)
    private val maxSeverities = IndicatorRegistry.maxSeverityMap(specs)
    private val totalWeight = weights.values.sum()
    private val previewCoverageMin = PreviewPolicy.previewCoverageMin(source)

    @Test
    fun `structural-missing-only ceiling is 0-8871, not the erroneous 0-792-0-847 pair, and does not suppress`() {
        assertTrue(
            "krx_credit_spread_delta must stay ALWAYS_MISSING until a Python-side builder + BT-05 golden exist",
            "krx_credit_spread_delta" in ConfirmSeriesIds.ALWAYS_MISSING_INDICATORS,
        )

        val stillMissing = ConfirmSeriesIds.ALWAYS_MISSING_INDICATORS
        val currentCeiling = (totalWeight - weights.filterKeys { it in stillMissing }.values.sum()) / totalWeight

        // 실제 파이프라인(observed=0 for all wired indicators, null for the two always-missing
        // ones)으로 재계산해 위 산술과 일치함을 이중 확인한다.
        val observed = weights.keys.associateWith { id -> if (id in stillMissing) null else 0 }
        val actualCoverage = Scoring.computeComposite(observed, weights, maxSeverities).coverage
        assertEquals(currentCeiling, actualCoverage, 1e-9)

        // 동결 단언(aaa M-1) — 좌변=assets 산출값, 우변=정본 동결 기대치(27.5/31.0).
        assertEquals(0.8871, currentCeiling, 1e-4)
        assertTrue(
            "structural missing alone (0.8871) must NOT trip preview_coverage_min ($previewCoverageMin)",
            currentCeiling >= previewCoverageMin,
        )

        // 참고용(assets 가중 산출) -- krx_credit_spread_delta에 Python builder가 생겨
        // ALWAYS_MISSING_INDICATORS에서 빠지면 도달 가능한 상한(29.5/31.0). 이번 과업으로는
        // 도달하지 않음을 위 currentCeiling 단언이 이미 고정했다.
        val ceilingIfWired = (totalWeight - weights.getValue("kr_cds_5y_delta")) / totalWeight
        assertEquals(0.9516, ceilingIfWired, 1e-4)
        assertTrue(
            "wiring krx_credit_spread_delta would raise the ceiling above today's value",
            ceilingIfWired > currentCeiling,
        )
    }
}
