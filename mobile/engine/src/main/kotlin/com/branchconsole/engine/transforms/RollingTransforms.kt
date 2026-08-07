package com.branchconsole.engine.transforms

import kotlin.math.sqrt

/**
 * `engine_ref/transforms.py`의 롤링 집계 함수군(rollingCorr·rollingMeanCorr·rollingSum·
 * gated·realizedVolKospi20d) — [Transforms]와 동일 규율(K-07 Double 고정, ddof=1, NaN 결측
 * 표식)을 공유한다. 분리는 detekt `TooManyFunctions` 완화 목적일 뿐, 의미상 [Transforms]와
 * 한 몸이다.
 */
object RollingTransforms {
    private const val PERCENT = 100.0
    private const val ANNUALIZATION_TRADING_DAYS = 252.0

    fun rollingCorr(
        a: DoubleArray,
        b: DoubleArray,
        window: Int,
    ): DoubleArray {
        require(a.size == b.size) { "rollingCorr: size mismatch (${a.size} vs ${b.size})" }
        val meanA = RollingWindow.mean(a, window)
        val meanB = RollingWindow.mean(b, window)
        return DoubleArray(a.size) { i -> correlationAt(a, b, meanA[i] to meanB[i], i, window) }
    }

    private fun correlationAt(
        a: DoubleArray,
        b: DoubleArray,
        means: Pair<Double, Double>,
        i: Int,
        window: Int,
    ): Double {
        val (meanA, meanB) = means
        if (meanA.isNaN() || meanB.isNaN()) return Double.NaN
        var cov = 0.0
        var varA = 0.0
        var varB = 0.0
        for (j in (i - window + 1)..i) {
            val da = a[j] - meanA
            val db = b[j] - meanB
            cov += da * db
            varA += da * da
            varB += db * db
        }
        return cov / sqrt(varA * varB)
    }

    /** 장기 평균 상관(예: mean_corr120) — corr 시계열 자체의 롤링 평균. */
    fun rollingMeanCorr(
        corr: DoubleArray,
        window: Int,
    ): DoubleArray = RollingWindow.mean(corr, window)

    fun rollingSum(
        x: DoubleArray,
        window: Int,
    ): DoubleArray = RollingWindow.sum(x, window)

    /** 게이트 조건 불리언 마스크. `op`는
     * [com.branchconsole.engine.config.TransformParser.parseGate]가 파싱한 비교 연산자
     * 문자열("<","<=",">",">=","=="). NaN 비교는 항상 false(IEEE-754). */
    fun gateMask(
        x: DoubleArray,
        op: String,
        threshold: Double,
    ): BooleanArray =
        BooleanArray(x.size) { i ->
            val v = x[i]
            when (op) {
                "<" -> v < threshold
                "<=" -> v <= threshold
                ">" -> v > threshold
                ">=" -> v >= threshold
                "==" -> v == threshold
                else -> error("unrecognized gate operator: '$op'")
            }
        }

    /** mask가 false인 지점을 0.0으로 마스킹(결측 아님 — severity 0으로 이어짐). mask가 true인데
     * x가 아직 NaN(웜업 중)이면 NaN을 그대로 통과시킨다(`.where(mask, 0.0)`과 동일 — 파리티
     * 지뢰: NaN→0 승격 금지). x·mask는 같은 인덱스 전제. */
    fun gated(
        x: DoubleArray,
        mask: BooleanArray,
    ): DoubleArray {
        require(x.size == mask.size) { "gated: size mismatch (${x.size} vs ${mask.size})" }
        return DoubleArray(x.size) { i -> if (mask[i]) x[i] else 0.0 }
    }

    /** K-02 VKOSPI 폴백: KOSPI 일수익률(%) N일 실현변동성 연율화(%). window은 호출부가
     * [com.branchconsole.engine.config.TransformParser.parseFallbackWindow]로 파싱해 주입한다.
     * 이 출력에 이후 [Transforms.zscore]를 적용하는 것은 registry/scoring 계층의 몫. */
    fun realizedVolKospi20d(
        dailyReturnPct: DoubleArray,
        window: Int,
    ): DoubleArray {
        val r = DoubleArray(dailyReturnPct.size) { i -> dailyReturnPct[i] / PERCENT }
        val std = RollingWindow.std(r, window)
        return DoubleArray(std.size) { i -> std[i] * sqrt(ANNUALIZATION_TRADING_DAYS) * PERCENT }
    }
}
