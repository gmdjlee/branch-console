package com.branchconsole.app.collectors

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * MT1-04g SSOT 배선 — `KrxRateLimitConfigTest`와 동일한 asset 로딩 경로(Robolectric
 * AssetManager, generated ssot assets)로 `configs/indicators.yaml` `engine.warmup_padding_days`를
 * 실제로 읽는다. 코드에 550을 재하드코딩하지 않는다.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class WarmupConfigTest {
    @Test
    fun `loads warmup_padding_days from indicators yaml`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val paddingDays = WarmupConfig.loadPaddingDays(context)

        // configs/indicators.yaml engine.warmup_padding_days: 550 — SSOT, asset에서 읽은 값을
        // 기대값과만 대조한다.
        assertEquals(550, paddingDays)
    }
}
