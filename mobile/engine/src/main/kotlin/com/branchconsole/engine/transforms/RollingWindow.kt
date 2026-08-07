package com.branchconsole.engine.transforms

import kotlin.math.sqrt

/**
 * `pandas Series.rolling(window, min_periods=window)` 규약의 공용 커널 — [Transforms]와
 * [RollingTransforms] 양쪽이 쓴다(파일을 나눈 이유는 순수히 detekt `TooManyFunctions` 완화,
 * 의미상 한 몸이다). 창 안에 NaN이 하나라도 있으면 그 위치는 NaN이다(파리티 지뢰 2).
 */
internal object RollingWindow {
    /** [from, endInclusive] 창에 NaN이 하나라도 있으면 true(min_periods=window 미충족). */
    private fun hasGap(
        x: DoubleArray,
        endInclusive: Int,
        window: Int,
    ): Boolean {
        for (i in (endInclusive - window + 1)..endInclusive) {
            if (x[i].isNaN()) return true
        }
        return false
    }

    inline fun reduce(
        x: DoubleArray,
        window: Int,
        reducer: (DoubleArray, Int, Int) -> Double,
    ): DoubleArray =
        DoubleArray(x.size) { i ->
            if (i < window - 1 || hasGap(x, i, window)) Double.NaN else reducer(x, i - window + 1, i)
        }

    fun mean(
        x: DoubleArray,
        window: Int,
    ): DoubleArray =
        reduce(x, window) { arr, from, to ->
            var sum = 0.0
            for (j in from..to) sum += arr[j]
            sum / window
        }

    fun sum(
        x: DoubleArray,
        window: Int,
    ): DoubleArray =
        reduce(x, window) { arr, from, to ->
            var total = 0.0
            for (j in from..to) total += arr[j]
            total
        }

    fun max(
        x: DoubleArray,
        window: Int,
    ): DoubleArray =
        reduce(x, window) { arr, from, to ->
            var m = Double.NEGATIVE_INFINITY
            for (j in from..to) if (arr[j] > m) m = arr[j]
            m
        }

    /** 표본표준편차(ddof=1) — pandas `Series.rolling(w).std()` 기본값과 동일(파리티 지뢰 1). */
    fun std(
        x: DoubleArray,
        window: Int,
    ): DoubleArray {
        val means = mean(x, window)
        return DoubleArray(x.size) { i ->
            val m = means[i]
            if (m.isNaN()) {
                Double.NaN
            } else {
                var sumSq = 0.0
                for (j in (i - window + 1)..i) {
                    val d = x[j] - m
                    sumSq += d * d
                }
                sqrt(sumSq / (window - 1))
            }
        }
    }
}
