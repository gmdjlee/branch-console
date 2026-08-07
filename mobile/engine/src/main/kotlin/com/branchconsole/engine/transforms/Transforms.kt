package com.branchconsole.engine.transforms

import kotlin.math.abs

/**
 * `engine_ref/transforms.py` 1:1 이식 (docs/plans/M1_PLAN_A.md §2.4.2 체크리스트,
 * M1_PLAN_D.md §2.4 전치 정리). 롤링 집계가 필요한 나머지 함수는 [RollingTransforms]에 있다
 * (같은 규율을 공유하는 한 몸이며, 파일 분리는 순수히 detekt `TooManyFunctions` 완화 목적).
 *
 * 규율:
 *  - K-07: 전부 Double(float64) 고정, 반올림 없음(표시 계층 몫).
 *  - rolling 계열은 전부 `min_periods = window`다 — 창 안에 **정확히 window개의 non-NaN 값이
 *    연속으로** 있어야 값을 낸다(하나라도 NaN이면 그 행은 NaN). 앞쪽 `window-1`행은 항상 NaN
 *    (pandas `Series.rolling(window, min_periods=window)` 규약, 파리티 지뢰 2).
 *  - `Double.NaN`이 결측 표식이다. IEEE-754 비교 규약(NaN과의 모든 비교는 false)이 pandas/
 *    numpy NaN 산술과 동일하게 성립한다 — 별도 null 래핑을 쓰지 않는다. 이 성질을 지키려면
 *    호출부는 이 파일의 `Double`을 절대 박싱된 `Double?`로 바꾸면 안 된다(`Double?`의 `==`는
 *    NaN==NaN을 true로 취급해 이 규약을 깬다).
 *  - 표준편차는 ddof=1(표본) — pandas `rolling().std()` 기본값(파리티 지뢰 1).
 *  - 순수 함수, 부작용 없음. window·lookback 등 파라미터는 전부 호출부
 *    ([com.branchconsole.engine.config.TransformParser])에서 주입받는다 — 숫자 리터럴 금지
 *    (CLAUDE.md §1).
 */
object Transforms {
    private const val PERCENT = 100.0
    private const val PERIODS_1D = 1
    private const val PERIODS_5D = 5

    /** rolling z-score. absolute=true면 절대값 변형(dxy_z 등 direction=higher_is_risk용). */
    fun zscore(
        x: DoubleArray,
        window: Int,
        absolute: Boolean = false,
    ): DoubleArray {
        val mean = RollingWindow.mean(x, window)
        val std = RollingWindow.std(x, window)
        return DoubleArray(x.size) { i ->
            val z = (x[i] - mean[i]) / std[i]
            if (absolute) abs(z) else z
        }
    }

    /** 두 배열이 이미 같은(as_of) 인덱스로 정렬돼 있다고 가정한다(포지션 기반) — 서로 다른
     * 인덱스의 정렬은 [SeriesAlign.unionAlign]의 몫(pandas 자동 인덱스 정렬 대응). */
    fun ratio(
        a: DoubleArray,
        b: DoubleArray,
    ): DoubleArray {
        require(a.size == b.size) { "ratio: size mismatch (${a.size} vs ${b.size}) — align first" }
        return DoubleArray(a.size) { i -> a[i] / b[i] }
    }

    /** %-단위 시계열의 lookback일 변화를 bp로(1%p = 100bp). */
    fun deltaBp(
        x: DoubleArray,
        lookback: Int,
    ): DoubleArray = DoubleArray(x.size) { i -> if (i < lookback) Double.NaN else (x[i] - x[i - lookback]) * PERCENT }

    fun pctChange(
        x: DoubleArray,
        periods: Int,
    ): DoubleArray =
        DoubleArray(x.size) { i ->
            if (i < periods) Double.NaN else (x[i] - x[i - periods]) / x[i - periods] * PERCENT
        }

    fun pctChange1d(x: DoubleArray): DoubleArray = pctChange(x, PERIODS_1D)

    fun pctChange5d(x: DoubleArray): DoubleArray = pctChange(x, PERIODS_5D)

    fun absValue(x: DoubleArray): DoubleArray = DoubleArray(x.size) { i -> abs(x[i]) }

    /** 롤링 고점 대비 낙폭 %(양수 = 하락, 0 = 신고가). */
    fun drawdownFromHigh(
        x: DoubleArray,
        window: Int,
    ): DoubleArray {
        val rollingHigh = RollingWindow.max(x, window)
        return DoubleArray(x.size) { i -> (rollingHigh[i] - x[i]) / rollingHigh[i] * PERCENT }
    }

    /** 하락(음의 변화)이 위험(+)이 되도록 부호 반전한 z-score. */
    fun negZscore(
        x: DoubleArray,
        window: Int,
    ): DoubleArray {
        val z = zscore(x, window)
        return DoubleArray(z.size) { i -> -z[i] }
    }
}
