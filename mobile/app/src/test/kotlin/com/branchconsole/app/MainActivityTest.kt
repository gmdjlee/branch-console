package com.branchconsole.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * MT1-01a — Compose·activity-compose·Material3 배선이 실제로 컴포지션까지 도는지 증명하는
 * Robolectric 스모크(coverage 회피용 더미가 아니라 "런치 시 크래시 없음"이라는 실질 회귀 신호).
 * Kover 70% 하한(docs/plans/M1_PLAN_A.md §2.9)을 자체 로직 제외 없이(R-B15) 만족시킨다.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class MainActivityTest {
    @Test
    fun `activity launches and composes without crashing`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
            }
        }
    }
}
