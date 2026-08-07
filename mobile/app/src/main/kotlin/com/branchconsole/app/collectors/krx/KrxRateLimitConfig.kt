package com.branchconsole.app.collectors.krx

import android.content.Context
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/**
 * K-03 간격 SSOT 로더. `configs/sources.yaml` `providers.pykrx.rate_limit.min_interval_s`를
 * generated assets(MT1-01b `syncConfigs` 산출물, `ConfigsManifestJvmTest`와 동일 로딩 경로)에서
 * 읽는다 — 코드에 간격 값을 하드코딩하지 않는다(CLAUDE.md §1 SSOT 규율).
 */
object KrxRateLimitConfig {
    private const val ASSET_PATH = "configs/sources.yaml"
    private const val MILLIS_PER_SECOND = 1000L

    /** @return 최소 호출 간격(ms). asset·키 부재 시 예외로 실패한다(조용한 기본값 금지). */
    fun loadMinIntervalMs(context: Context): Long {
        val root =
            context.assets.open(ASSET_PATH).use {
                Load(LoadSettings.builder().build()).loadFromInputStream(it)
            }
        val providers = (root as? Map<*, *>)?.get("providers") as? Map<*, *>
        val pykrx = providers?.get("pykrx") as? Map<*, *>
        val rateLimit = pykrx?.get("rate_limit") as? Map<*, *>
        val minIntervalS =
            (rateLimit?.get("min_interval_s") as? Number)?.toDouble()
                ?: error("providers.pykrx.rate_limit.min_interval_s missing from $ASSET_PATH")
        return (minIntervalS * MILLIS_PER_SECOND).toLong()
    }
}
