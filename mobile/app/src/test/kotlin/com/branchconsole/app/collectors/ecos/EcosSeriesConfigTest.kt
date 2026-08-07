package com.branchconsole.app.collectors.ecos

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * MT1-04d K-04 SSOT 배선 — `KrxRateLimitConfigTest`/`WarmupConfigTest`와 동일한 asset 로딩 경로
 * (Robolectric AssetManager, generated ssot assets)로 `configs/indicators.yaml`
 * `krx_credit_spread_delta.source`(`stat_code`·`item_codes`)를 실제로 읽는다. 00b 저널 §7.9
 * 실측 확정값을 코드에 재하드코딩하지 않는다.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class EcosSeriesConfigTest {
    @Test
    fun `loads stat_code and item_codes from indicators yaml`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val (statCode, itemCodes) = EcosSeriesConfig.load(context)

        // configs/indicators.yaml krx_credit_spread_delta.source — SSOT, asset에서 읽은 값을
        // 기대값과만 대조한다(00b 저널 §7.9 실측 확정).
        assertEquals("817Y002", statCode)
        assertEquals("010300000", itemCodes.corpAa3y)
        assertEquals("010200000", itemCodes.ktb3y)
    }
}
