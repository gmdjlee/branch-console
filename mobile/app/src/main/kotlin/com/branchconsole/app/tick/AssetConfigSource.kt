package com.branchconsole.app.tick

import android.content.Context
import com.branchconsole.engine.config.ConfigSource
import java.io.InputStream

/**
 * `:engine`의 [ConfigSource] 계약을 Android `AssetManager`로 구현한다(docs/plans/M1_PLAN_A.md
 * AD-A1 — `:engine`은 Android API에 의존하지 않으므로 이 어댑터는 `:app`에만 존재한다). 대상은
 * `syncConfigs`(MT1-01b) 산출물인 `assets/configs/<name>` — [KrxRateLimitConfig][
 * com.branchconsole.app.collectors.krx.KrxRateLimitConfig]·[WarmupConfig][
 * com.branchconsole.app.collectors.WarmupConfig]와 동일한 경로 관례.
 */
class AssetConfigSource(private val context: Context) : ConfigSource {
    override fun open(name: String): InputStream = context.assets.open("configs/$name")
}
