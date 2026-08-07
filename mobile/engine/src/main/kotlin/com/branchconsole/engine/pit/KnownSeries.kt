package com.branchconsole.engine.pit

import java.time.Instant
import java.time.LocalDate

/**
 * Stage 3(가시성 색인 + lookup) — `run_replay.build_known_series`/`lookup_known` 1:1 이식
 * (docs/plans/M1_PLAN_A.md §2.11, N-1 판정: 가시성은 **원관측이 아니라 transform 출력**에
 * 붙는다). transform 출력 시계열의 각 행에 가시 시각을 부착해 rowDate 오름차순으로 보관하고,
 * 평가 시각 이하로 가시화된 가장 최근 값을 이진탐색으로 고른다.
 */
class KnownSeries private constructor(
    val rowDates: List<LocalDate>,
    val visibilityTs: List<Instant>,
    private val values: DoubleArray,
) {
    companion object {
        /**
         * NaN 값 행·가시성 null 행은 제외한다(정본 L300-301, 311-312). `visibleAt`은 rowDate에
         * 대해 단조 비감소이므로(§2.2.3 보조정리 2) rowDate 정렬이 곧 visibilityTs 정렬이다 —
         * [KnownSeries.assertMonotonicVisibility]로 그 전제를 증거화한다(퇴화 입력 증인 W-V6).
         */
        fun build(
            rowDates: List<LocalDate>,
            values: DoubleArray,
            visibleAt: (LocalDate) -> Instant?,
        ): KnownSeries {
            require(rowDates.size == values.size) { "rowDates/values size mismatch" }
            val rows =
                rowDates.indices
                    .mapNotNull { i ->
                        val v = values[i]
                        if (v.isNaN()) return@mapNotNull null
                        val vis = visibleAt(rowDates[i]) ?: return@mapNotNull null
                        Triple(rowDates[i], vis, v)
                    }.sortedBy { it.first }
            return KnownSeries(rows.map { it.first }, rows.map { it.second }, rows.map { it.third }.toDoubleArray())
        }
    }

    /** `visibilityTs`가 [rowDates] 순서로 실제 비감소인지 확인한다(퇴화 입력 증인 W-V6). */
    fun assertMonotonicVisibility() {
        for (i in 1 until visibilityTs.size) {
            check(visibilityTs[i] >= visibilityTs[i - 1]) {
                "visibility_ts not monotonic at index $i: ${visibilityTs[i - 1]} -> ${visibilityTs[i]}"
            }
        }
    }

    data class LookupResult(val rowDate: LocalDate, val visibleAt: Instant, val value: Double)

    /**
     * 가장 최근에 가시화된 (row_date, 가시화 시각, value). 정본: `bisect_right(ts, evalAt) - 1`
     * — **가시 시각 == evaluatedAt인 행이 포함된다**(등호 포함, 파리티 지뢰 7 — mobile_daily의
     * KRX·FX 계열은 매 확정 틱마다 이 등호에 정확히 걸린다). 없으면 null.
     */
    fun lookup(evaluatedAt: Instant): LookupResult? {
        var lo = 0
        var hi = visibilityTs.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (visibilityTs[mid] <= evaluatedAt) lo = mid + 1 else hi = mid
        }
        val i = lo - 1
        if (i < 0) return null
        return LookupResult(rowDates[i], visibilityTs[i], values[i])
    }
}
