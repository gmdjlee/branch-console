package com.branchconsole.app.preview

import com.branchconsole.engine.scoring.Scoring
import java.time.Instant
import java.time.LocalDate

/**
 * 지표 1건의 프리뷰 표시 상태 — `observed=false`이면서 `severity != null`이면 이월(D-23
 * §23.3-1 "이월·as_of" 배지 대상), `observed=false`이면서 `severity == null`이면 이월도 실패한
 * 순수 결측(M1_PLAN_A.md §2.12 (b-0) W-P6/W-P7 — `tick_input` 0행이거나 이월 깊이 1에서도
 * 결측이던 지표).
 */
internal data class PreviewIndicatorState(
    val severity: Int?,
    val observed: Boolean,
    val carriedAsOfMillis: Long?,
)

/** 이 프리뷰 실행이 "언제"인지 — [tickDay]는 가시 판정용 날짜(§5.4.2), [evaluatedAt]은 스테일
 * 판정·표시용 실제 시각(now, M-39). */
internal data class PreviewMoment(val tickDay: LocalDate, val evaluatedAt: Instant)

/** [Scoring.computeComposite]가 요구하는 레지스트리 가중 한 쌍 — 항상 같이 다니므로 묶는다
 * (detekt `LongParameterList` 완화도 겸한다). */
internal data class PreviewScoringConfig(val weights: Map<String, Double>, val maxSeverities: Map<String, Int>?)

/**
 * §10.1.1 "raw가 정본이다" — [rawCoverage]는 이월 **전** coverage로, 억제 판정과 화면 표시
 * 양쪽의 유일 기준이다(D-23 §23.3-3). [filledCoverage]/[filledComposite]는 이월 **후** 값
 * (§4.5-0 "composite는 이월값을 포함해 계산") — 진단·표시용이며 억제 판정에는 쓰이지 않는다.
 */
internal data class PreviewResult(
    val tickDay: LocalDate,
    val evaluatedAt: Instant,
    val rawCoverage: Double,
    val filledCoverage: Double,
    val filledComposite: Double?,
    val suppressed: Boolean,
    val indicators: Map<String, PreviewIndicatorState>,
)

/**
 * MT1-07b coverage·carry-forward 병합(D-23 §23.2·§23.3, M1_PLAN_B.md §10.1) — 순수 함수(IO
 * 없음). [observedSeverities]는 이월 적용 **전** 신선 관측(읽기 지점 ②), [carried]는 이월
 * 원천(읽기 지점 ③, [CarryForwardResolver]). 억제 판정은 항상 [observedSeverities]만으로 낸
 * raw coverage로 한다 — 이월값을 유효가중에 계상하면 §23.3-3의 `<80%` 억제가 죽은 조문이
 * 된다(§10.1.1).
 */
internal object PreviewCoverage {
    fun compute(
        moment: PreviewMoment,
        observedSeverities: Map<String, Int?>,
        carried: Map<String, Carried>,
        scoring: PreviewScoringConfig,
        previewCoverageMin: Double,
    ): PreviewResult {
        val raw = Scoring.computeComposite(observedSeverities, scoring.weights, scoring.maxSeverities)
        val indicators = LinkedHashMap<String, PreviewIndicatorState>()
        val merged = LinkedHashMap<String, Int?>()
        for ((id, severity) in observedSeverities) {
            if (severity != null) {
                merged[id] = severity
                indicators[id] = PreviewIndicatorState(severity, observed = true, carriedAsOfMillis = null)
            } else {
                val c = carried[id]
                merged[id] = c?.severity
                indicators[id] = PreviewIndicatorState(c?.severity, observed = false, carriedAsOfMillis = c?.asOfMillis)
            }
        }
        val filled = Scoring.computeComposite(merged, scoring.weights, scoring.maxSeverities)
        return PreviewResult(
            tickDay = moment.tickDay,
            evaluatedAt = moment.evaluatedAt,
            rawCoverage = raw.coverage,
            filledCoverage = filled.coverage,
            filledComposite = filled.score,
            suppressed = raw.coverage < previewCoverageMin,
            indicators = indicators,
        )
    }
}
