package com.branchconsole.app.collectors

import android.content.Context
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/**
 * MT1-04g 웜업/백필 범위 SSOT 로더. `configs/indicators.yaml` `engine.warmup_padding_days`를
 * assets(syncConfigs 산출물, [com.branchconsole.app.collectors.krx.KrxRateLimitConfig]와 동일
 * 로딩 경로)에서 읽는다 — 코드에 550을 하드코딩하지 않는다(CLAUDE.md §1 SSOT 규율,
 * docs/plans/M1_PLAN_D.md D-D1).
 */
object WarmupConfig {
    private const val ASSET_PATH = "configs/indicators.yaml"

    /** @return 웜업/백필 범위(달력일). asset·키 부재 시 예외로 실패한다(조용한 기본값 금지). */
    fun loadPaddingDays(context: Context): Int {
        val root =
            context.assets.open(ASSET_PATH).use {
                Load(LoadSettings.builder().build()).loadFromInputStream(it)
            }
        val engine = (root as? Map<*, *>)?.get("engine") as? Map<*, *>
        return (engine?.get("warmup_padding_days") as? Number)?.toInt()
            ?: error("engine.warmup_padding_days missing from $ASSET_PATH")
    }
}
