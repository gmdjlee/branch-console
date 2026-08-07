package com.branchconsole.app.collectors.krx

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * MT1-04c K-03 SSOT 배선 — `ConfigsManifestJvmTest`(MT1-01b)와 동일한 asset 로딩 경로
 * (Robolectric AssetManager, generated ssot assets)로 `configs/sources.yaml`
 * `providers.pykrx.rate_limit.min_interval_s`를 실제로 읽는다.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class KrxRateLimitConfigTest {
    @Test
    fun `loads min_interval_s from sources yaml as milliseconds`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val minIntervalMs = KrxRateLimitConfig.loadMinIntervalMs(context)

        // configs/sources.yaml providers.pykrx.rate_limit.min_interval_s: 1.0 — SSOT, 여기서
        // 재하드코딩하지 않고 asset에서 읽은 값을 기대값과만 대조한다.
        assertEquals(1000L, minIntervalMs)
    }
}
