package com.branchconsole.engine.indicators

import com.branchconsole.engine.transforms.RollingTransforms
import com.branchconsole.engine.transforms.Transforms

/**
 * `vkospi_z` — K-02 실현변동성 폴백 체인의 데이터 기반 분기(`run_replay._build_vkospi_z`,
 * docs/plans/M1_PLAN_D.md §2.3.1). 실측 VKOSPI 계열이 있으면 그것을 그대로 zscore하고, 없으면
 * KOSPI 종가에서 유도한 실현변동성으로 대체한다 — **분기는 데이터로만 판정**한다(하드코딩
 * 금지, M-19(c)). 브리프 지시 "실측 VKOSPI 계열을 스코어링에 배선 금지"를 지키는 유일한
 * 지점 — 이 함수 밖에서 vkospiClose를 직접 스코어링에 넘기지 않는다.
 */
object Vkospi {
    fun vkospiZ(
        vkospiClose: DoubleArray,
        kospiClose: DoubleArray,
        zscoreWindow: Int,
        fallbackWindow: Int,
    ): DoubleArray =
        if (vkospiClose.isNotEmpty()) {
            Transforms.zscore(vkospiClose, zscoreWindow)
        } else {
            val dailyReturn = Transforms.pctChange1d(kospiClose)
            val realizedVol = RollingTransforms.realizedVolKospi20d(dailyReturn, fallbackWindow)
            Transforms.zscore(realizedVol, zscoreWindow)
        }
}
