package com.branchconsole.app.preview

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.branchconsole.app.tick.AssetConfigSource
import com.branchconsole.app.tick.ConfirmSeriesIds
import com.branchconsole.engine.config.IndicatorRegistry
import com.branchconsole.engine.scoring.Scoring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * MT1-04d A-1 검증(PROGRESS.md GM1 기록 "ECOS 차단 시 0.792 < 0.80... 키 발급 시 0.847로 해소")
 * — ECOS collector 완성이 실제로 raw coverage 상한에 미치는 영향을 SSOT 가중치로 검증한다
 * (`PreviewCoverageTest`와 동일하게 리터럴 좌변 금지 — 가중은 실 asset에서 로드).
 *
 * **결론(브리프와 어긋나는 판단, Advisor 보고 대상)**: 이 과업(수집기 완성)만으로는 raw coverage
 * 상한이 회복되지 않는다. `krx_credit_spread_delta`의 severity 계산은 `backtest/run_replay.py`의
 * `_BUILDERS`에도 존재하지 않는 로직이다(`_ALWAYS_MISSING_INDICATORS`에 여전히 있음, "BT-01
 * 수집 범위 밖"이 사유) — Python 정본에 없는 계산을 모바일이 먼저 발명하면 BT-05 패리티가 그
 * 결과를 검증할 golden이 없어 무회귀 게이트를 벗어난다(`ConfirmIndicatorRuntime.kt`/
 * `ParityEngine.kt`의 "재구현 없음" 공통 규율). 그래서 `ConfirmSeriesIds.ALWAYS_MISSING_INDICATORS`는
 * 이번 서브태스크에서 그대로 두었다(`EcosCollector.kt` KDoc에 동일 근거 기록) — PROGRESS.md의
 * "키 발급 시 0.847로 해소" 기대는 Python 빌더 신설 + BT-05 golden 갱신이라는 별도 과업이 선행돼야
 * 성립한다. 이 테스트는 그 갭을 명시적으로 고정한다: 상한이 바뀌지 않았음을 단언하는 동시에,
 * 그 지표가 훗날 배선되면 상한이 얼마나 올라갈지(assets 가중 산출)도 참고용으로 남긴다.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class EcosCoverageTest {
    private val source = AssetConfigSource(ApplicationProvider.getApplicationContext())
    private val specs = IndicatorRegistry.loadIndicatorSpecs(source, enabledOnly = true)
    private val weights = IndicatorRegistry.weightMap(specs)
    private val maxSeverities = IndicatorRegistry.maxSeverityMap(specs)
    private val totalWeight = weights.values.sum()

    @Test
    fun `ECOS collector alone does not lift raw coverage ceiling -- no Python builder yet`() {
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

        // 참고용(assets 가중 산출, 하드코딩 아님) -- krx_credit_spread_delta에 Python builder가
        // 생겨 ALWAYS_MISSING_INDICATORS에서 빠지면 도달 가능한 상한. 이번 과업으로는 도달하지
        // 않음을 위 currentCeiling 단언이 이미 고정했다 -- 이 값은 후속 결정을 위한 참고치일 뿐이다.
        val ceilingIfWired = (totalWeight - weights.getValue("kr_cds_5y_delta")) / totalWeight
        assertTrue(
            "wiring krx_credit_spread_delta would raise the ceiling above today's value",
            ceilingIfWired > currentCeiling,
        )
    }
}
