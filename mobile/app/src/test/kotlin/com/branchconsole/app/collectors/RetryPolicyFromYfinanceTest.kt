package com.branchconsole.app.collectors

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * aaa D-1 조건부 해소 — `RetryPolicy.DEFAULT` 하드코딩 리터럴을 제거하고 `configs/sources.yaml`
 * `providers.yfinance.retry`를 assets에서 읽도록 바꾼 로더의 실값 검증
 * (`KrxRateLimitConfigTest`와 동일한 asset 로딩 경로 — Robolectric AssetManager, generated
 * ssot assets).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RetryPolicyFromYfinanceTest {
    @Test
    fun `loads attempts and backoff_s (as milliseconds) from sources yaml`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val policy = RetryPolicy.fromYfinance(context)

        // configs/sources.yaml providers.yfinance.retry: { attempts: 3, backoff_s: [5, 30, 120] }
        // — SSOT, 여기서 재하드코딩하지 않고 asset에서 읽은 값을 기대값과만 대조한다.
        assertEquals(3, policy.attempts)
        assertEquals(listOf(5_000L, 30_000L, 120_000L), policy.backoffMs)
    }
}
