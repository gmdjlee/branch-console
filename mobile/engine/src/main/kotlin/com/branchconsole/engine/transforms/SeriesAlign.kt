package com.branchconsole.engine.transforms

import java.time.LocalDate

/**
 * 날짜 인덱스가 있는 두 시계열의 정렬 유틸 — [Transforms]의 함수는 전부 포지션 기반(인덱스를
 * 모른다)이므로, pandas의 자동 인덱스 정렬(`ratio`·`rolling_corr`가 전제) 및
 * `global_corr_break` 전용 causal ffill 정렬(`run_replay._align_to_ffill`, 파리티 지뢰 6)은
 * 호출부가 이 파일로 먼저 처리한 뒤 [Transforms]에 넘긴다(docs/plans/M1_PLAN_A.md §2.11).
 */
object SeriesAlign {
    data class Aligned(val dates: List<LocalDate>, val a: DoubleArray, val b: DoubleArray)

    /** union(datesA, datesB) 오름차순 위에 a·b를 재배치, 한쪽에 없는 날짜는 NaN(pandas
     * 시리즈 산술의 자동 인덱스 정렬과 동일 — `engine_ref/transforms.py:33-34 ratio`가 전제). */
    fun unionAlign(
        datesA: List<LocalDate>,
        valuesA: DoubleArray,
        datesB: List<LocalDate>,
        valuesB: DoubleArray,
    ): Aligned {
        require(datesA.size == valuesA.size && datesB.size == valuesB.size) {
            "unionAlign: dates/values size mismatch"
        }
        val mapA = datesA.indices.associate { datesA[it] to valuesA[it] }
        val mapB = datesB.indices.associate { datesB[it] to valuesB[it] }
        val union = (datesA.toSet() + datesB.toSet()).sorted()
        val a = DoubleArray(union.size) { i -> mapA[union[i]] ?: Double.NaN }
        val b = DoubleArray(union.size) { i -> mapB[union[i]] ?: Double.NaN }
        return Aligned(union, a, b)
    }

    /**
     * `source`(다른 거래 달력)를 `targetDates`(예: KOSPI 거래일)에 causal 하게 ffill 정렬한다
     * — `global_corr_break` 전용 재량 규칙(`run_replay._align_to_ffill`). `targetDates[i]`에서는
     * `source`의 **그 날짜 이하 최신 관측값**만 쓴다(미래값 참조 없음). 다른 지표에는 적용하면
     * 안 된다(정본 주석 그대로 — 이식 시 이름·의미 보존).
     */
    fun alignToFfillCausal(
        sourceDates: List<LocalDate>,
        sourceValues: DoubleArray,
        targetDates: List<LocalDate>,
    ): DoubleArray {
        require(sourceDates.size == sourceValues.size) { "alignToFfillCausal: source size mismatch" }
        return DoubleArray(targetDates.size) { i -> lastOnOrBefore(sourceDates, sourceValues, targetDates[i]) }
    }

    private fun lastOnOrBefore(
        dates: List<LocalDate>,
        values: DoubleArray,
        day: LocalDate,
    ): Double {
        var lo = 0
        var hi = dates.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (dates[mid] <= day) lo = mid + 1 else hi = mid
        }
        val idx = lo - 1
        return if (idx < 0) Double.NaN else values[idx]
    }

    /** `close.shift(1)`(직전 관측 **행**, 포지션 기준) 이식 — 달력 전일이 아니다
     * (`run_replay.py:578`, `usdkrw_z`의 prevClose 조회 규칙). */
    fun shift(
        values: DoubleArray,
        periods: Int,
    ): DoubleArray =
        DoubleArray(values.size) { i ->
            val j = i - periods
            if (j < 0 || j >= values.size) Double.NaN else values[j]
        }
}
