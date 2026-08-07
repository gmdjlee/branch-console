package com.branchconsole.app.preview

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.branchconsole.app.tick.AssetConfigSource
import com.branchconsole.engine.config.IndicatorRegistry
import com.branchconsole.engine.config.PreviewPolicy
import com.branchconsole.engine.scoring.Scoring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToLong

/**
 * TASK MT1-07 완료 기준 ③④ — D-23 §23.2 수치 예 재현. 가중은 리터럴이 아니라 실 SSOT
 * `configs/indicators.yaml`(syncConfigs 산출 asset, Robolectric AssetManager로 로드 —
 * [com.branchconsole.app.ConfigsManifestJvmTest]와 동일 경로)에서 로드한다(CLAUDE.md §1,
 * 브리프 aaa 요건 1-c "리터럴 좌변 금지"). severity는 실제 수집·transform 파이프라인을 거치지
 * 않고 시나리오가 직접 구성하므로 ECOS(MT1-00b BLOCKED)와 무관하게 판정 가능하다(브리프 aaa
 * 요건 1-c) — 실제 13종 빌더를 통한 값 산출 정합은 BT-05가 이미 담당한다.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class PreviewCoverageTest {
    private val source = AssetConfigSource(ApplicationProvider.getApplicationContext())
    private val specs = IndicatorRegistry.loadIndicatorSpecs(source, enabledOnly = true)
    private val weights = IndicatorRegistry.weightMap(specs)
    private val maxSeverities = IndicatorRegistry.maxSeverityMap(specs)
    private val axes = IndicatorRegistry.axisMap(specs)
    private val previewCoverageMin = PreviewPolicy.previewCoverageMin(source)

    // D-23 §23.2 시나리오: KRX계 4지표(kr_flow_price 축) + kr_cds_5y_delta(G-4 (b) 미수집 확정)
    // 결측. 나머지 10종은 전부 severity=2(warn) — 어느 지표가 그 21.0을 구성하는지는 산식에
    // 영향이 없다(가중 합이 서로 같은 severity를 곱하므로), 실 SSOT의 axis 분류만 의미가 있다.
    private val missingIds = axes.filterValues { it == "kr_flow_price" }.keys + "kr_cds_5y_delta"
    private val effectiveWeight = weights.filterKeys { it !in missingIds }.values.sum()
    private val totalWeight = weights.values.sum()

    private fun observedAllWarn(): Map<String, Int?> {
        return weights.keys.associateWith { id -> if (id in missingIds) null else 2 }
    }

    private fun round1(x: Double): Double = (x * 10).roundToLong() / 10.0

    @Test
    fun `kr axis plus cds missing scenario reproduces raw coverage 21-31 and suppresses`() {
        val raw = Scoring.computeComposite(observedAllWarn(), weights, maxSeverities)

        assertEquals(effectiveWeight / totalWeight, raw.coverage, 1e-9)
        assertEquals(67.7, round1(raw.coverage * 100), 0.0) // 문서 표기값 부가 단언(K-07 반올림은 표시 계층)
        assertTrue("raw coverage below preview_coverage_min must suppress", raw.coverage < previewCoverageMin)
    }

    @Test
    fun `D-23 23-2 no-carry vs carried-zero composite reproduces 66-7 vs 45-2, raw coverage unaffected`() {
        val observed = observedAllWarn()

        // (i) 이월 없음 — D-23이 문제로 지목한 값.
        val noCarry = Scoring.computeComposite(observed, weights, maxSeverities)
        assertEquals(100.0 * (effectiveWeight * 2) / (effectiveWeight * 3), noCarry.score!!, 1e-9)
        assertEquals(66.7, round1(noCarry.score!!), 0.0)

        // (ii)/(iii) — 본 구현이 실제로 산출하는 이월 후 값. missing 5종의 직전 확정
        // severity=0(서버 동시각과 동일 산식)을 이월했다고 가정한다.
        val carriedAsOf = LocalDate.of(2026, 8, 5).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val carried = missingIds.associateWith { Carried(severity = 0, asOfMillis = carriedAsOf) }
        val result =
            PreviewCoverage.compute(
                moment = PreviewMoment(tickDay = LocalDate.of(2026, 8, 6), evaluatedAt = Instant.EPOCH),
                observedSeverities = observed,
                carried = carried,
                scoring = PreviewScoringConfig(weights = weights, maxSeverities = maxSeverities),
                previewCoverageMin = previewCoverageMin,
            )

        val missingWeight = totalWeight - effectiveWeight
        val expectedFilled = 100.0 * (effectiveWeight * 2 + missingWeight * 0) / (totalWeight * 3)
        assertEquals(expectedFilled, result.filledComposite!!, 1e-9)
        assertEquals(45.2, round1(result.filledComposite), 0.0)

        // §10.1.1 "raw가 정본이다" — 이월이 성공해도 raw coverage·억제 판정은 불변.
        assertEquals(raw67(), result.rawCoverage, 1e-9)
        assertTrue("still suppressed — raw coverage is unaffected by carry-forward", result.suppressed)
    }

    private fun raw67(): Double = effectiveWeight / totalWeight

    /**
     * 퇴화 증인 — 이월값을 coverage 계상에 섞으면(§10.1.1이 금지하는 오구현) §23.3-3의 `<80%`
     * 억제가 죽은 조문이 된다는 것을 직접 보인다. [PreviewCoverage.compute]는 이 병합을 절대
     * 하지 않지만, 이 테스트는 "왜 그래야 하는가"를 수치로 고정한다(신설 규율 ① 정신).
     */
    @Test
    fun `mixing carried severities into coverage would make the 80pct suppression a dead letter`() {
        val observed = observedAllWarn()
        val carried = missingIds.associateWith { 0 }
        val merged = observed.mapValues { (id, sev) -> sev ?: carried.getValue(id) }

        val wronglyMixedCoverage = Scoring.computeComposite(merged, weights, maxSeverities).coverage

        assertEquals(1.0, wronglyMixedCoverage, 1e-9) // 항상 100%로 회복 — 억제가 발동할 수 없다.
        assertTrue(wronglyMixedCoverage >= previewCoverageMin)
    }
}
