package com.branchconsole.app.preview

import com.branchconsole.app.tick.ConfirmTickConfig
import com.branchconsole.app.tick.ConfirmTickContext
import com.branchconsole.app.tick.TradingDayGridProvider
import com.branchconsole.app.tick.buildIndicatorRuntime
import com.branchconsole.engine.config.IndicatorSpec
import com.branchconsole.engine.pit.Visibility
import com.branchconsole.lake.ObservationDao
import com.branchconsole.lake.TickInputDao
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

private val KST = ZoneOffset.ofHours(9)

private fun cadenceOf(spec: IndicatorSpec): String = spec.source["cadence"] as? String ?: ""

/**
 * MT1-07a/b 진입점 — 수동 갱신 파이프라인(D-17). **국면을 커밋하지 않는다**: `tick_input`
 * 삽입도, statemachine fold 호출도 이 클래스 어디에도 없다 — TASK 완료 기준 ①("프리뷰가
 * 상태기계 상태를 변경하지 않음")이 코드에 그 경로가 아예 없다는 사실로 성립한다(실행 후 검사가
 * 아니라 구조적 보장). LLM 호출도 없다(브리프 §2-6).
 *
 * ECOS는 MT1-04d로 실제 수집되지만(K-04 종결, `EcosCollector`) `krx_credit_spread_delta`
 * **지표**는 여전히 [com.branchconsole.app.tick.ConfirmSeriesIds.ALWAYS_MISSING_INDICATORS]에
 * 속해 상시 결측이다(Python `backtest/run_replay.py`에 이 지표의 builder 자체가 없다 —
 * `EcosCollector.kt`/`EcosCoverageTest` KDoc 참조, BT-05 golden 부재로 모바일이 먼저 구현할 수
 * 없다). **다만 이 구조적 결측 2종(`krx_credit_spread_delta`+`kr_cds_5y_delta`)만으로는 raw
 * coverage가 억제 임계를 넘지 않는다** — 실측 상한은 27.5/31.0=0.8871 > `preview_coverage_min`
 * 0.80(`EcosCoverageTest`가 SSOT 가중치로 동결, aaa M-1 정정 — 최초 판이 인용한 "0.792/0.847"은
 * SSOT에서 도출 불가능한 오류 수치였다). 프리뷰 억제는 이 구조적 결측이 아니라 **실제 런타임
 * 결측**(예: 00a 저널의 `^MOVE`·`^VIX3M` 갱신 정지로 `move_index_z`+`vix_term_structure`까지
 * 추가로 결측되면 23.5/31.0=0.7581<0.80)에서만 발생한다.
 *
 * `ponytail`: 병렬 수집(collectors 트리거) 오케스트레이션은 이 클래스 밖이다 — collectors는
 * 이미 각자의 `collect()` 진입점을 갖고 있고(MT1-04 완비), 그것을 호출한 뒤 이 러너를 도는
 * 얇은 wiring은 MT1-08 UI(프리뷰 갱신 버튼)가 실제 트리거를 필요로 할 때 추가한다 — 지금
 * 추가하면 검증 대상(coverage·carry-forward·격리)과 무관한 네트워크 오케스트레이션 코드가
 * 늘어나기만 한다.
 */
internal class PreviewTickRunner(
    private val observationDao: ObservationDao,
    tickInputDao: TickInputDao,
    private val config: ConfirmTickConfig,
    private val previewCoverageMin: Double,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val carryForward = CarryForwardResolver(tickInputDao)

    suspend fun run(): PreviewResult {
        val now = clock.instant()
        val today = LocalDate.now(clock.withZone(KST))
        val grid = previewGrid(today)
        val ctx = ConfirmTickContext.load(PreviewLaneObservationDao(observationDao), grid, config, today)
        // §5.4.2 "가시 판정에만 사용" — confirmTimeKst가 고정이므로 이 등호 포함 bisect 하나가
        // 곧 "오늘 날짜라면 이미 보이는가"라는 날짜 비교와 동치다([PreviewInstants] KDoc 참조).
        val instants = PreviewInstants(lookupAt = Visibility.kstToUtc(today, config.confirmTimeKst), staleAt = now)
        val runtimes = config.specs.associate { it.id to buildIndicatorRuntime(it, ctx) }

        val observed = LinkedHashMap<String, Int?>()
        for (spec in config.specs) {
            val staleWindow = config.staleWindows.getValue(cadenceOf(spec))
            val runtime = runtimes.getValue(spec.id)
            val resolved = resolvePreviewSeverity(spec, runtime, instants, staleWindow, config.modifiers)
            observed[spec.id] = resolved.severity
        }

        val carried = carryForward.lastConfirmed()
        return PreviewCoverage.compute(
            moment = PreviewMoment(tickDay = today, evaluatedAt = now),
            observedSeverities = observed,
            carried = carried,
            scoring = PreviewScoringConfig(weights = config.weights, maxSeverities = config.maxSeverities),
            previewCoverageMin = previewCoverageMin,
        )
    }

    /**
     * [TradingDayGridProvider]는 "관측된 KRX 종가 as_of 날짜"만 그리드로 본다(app/tick 설계,
     * K-19). 확정 틱은 항상 그날의 KOSPI 종가가 이미 수집된 뒤(17:00)에 돌아 오늘이 그리드에
     * 이미 들어 있지만, 프리뷰는 장중(예: 오전, KOSPI 미종가)에도 실행되므로 오늘이 아직
     * 그리드에 없을 수 있다. 그 경우 `visDay(US_MARKET, D-1) = firstAfter(grid, D-1)`이 "오늘"을
     * 찾지 못해 null이 되어, 어제 미국 종가가 오전 프리뷰에서 결측으로 오판된다(§5.4.2 완료
     * 기준 ①이 요구하는 정반대 결과). tickDay=today는 가시 판정 전용 개념(§5.4.2)이므로, 오늘의
     * 실제 KOSPI 종가 유무와 무관하게 그리드 끝에 today를 붙인다 — 오늘 날짜의 관측 자체가
     * 없으면(장중이라 당연히 없다) 어차피 그 날짜의 행은 생성되지 않으므로 값을 지어내는 것은
     * 아니다. `ponytail`: 오늘이 실제로는 KR 휴장일인 경우 이 삽입이 그 뒤(D-1 등)의 visDay
     * 계산을 하루 앞당길 수 있다 — 프리뷰는 비커밋·정보용이라 영향이 그 틱의 표시값에 그치고
     * 확정 경로(app/tick, 별도 그리드 계산)에는 닿지 않는다. 문제가 실측되면 그때 KRX 휴장
     * 캘린더 조회를 추가한다.
     */
    private suspend fun previewGrid(today: LocalDate): List<LocalDate> {
        val observedGrid = TradingDayGridProvider(observationDao).tradingDaysUpTo(today)
        return if (observedGrid.lastOrNull() == today) observedGrid else observedGrid + today
    }
}
