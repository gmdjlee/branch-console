package com.branchconsole.app.notif

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MT1-08a — 채널 ID·중요도가 D §3.9.1 표대로 확정됐는지 확인한다(설치 후 변경 불가 제약,
 * [NotificationChannels] KDoc 참조). 중요도 값은 여기서만 리터럴로 등장한다 — 이건 SSOT
 * 임계값이 아니라 플랫폼 API 상수(Android `NotificationManager.IMPORTANCE_*`)다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationChannelsTest {
    private fun manager(): NotificationManager =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(NotificationManager::class.java)

    @Test
    fun `ensureCreated creates all 3 channels with the fixed IDs and importance`() {
        NotificationChannels.ensureCreated(ApplicationProvider.getApplicationContext())

        val phase = manager().getNotificationChannel(NotificationChannels.PHASE_TRANSITION)
        val provisional = manager().getNotificationChannel(NotificationChannels.PROVISIONAL_ALERT)
        val failure = manager().getNotificationChannel(NotificationChannels.TICK_FAILURE)

        assertNotNull(phase)
        assertNotNull(provisional)
        assertNotNull(failure)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, phase.importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, provisional.importance)
        assertEquals(NotificationManager.IMPORTANCE_LOW, failure.importance)
    }

    @Test
    fun `ensureCreated is idempotent (safe to call on every app start)`() {
        NotificationChannels.ensureCreated(ApplicationProvider.getApplicationContext())
        NotificationChannels.ensureCreated(ApplicationProvider.getApplicationContext())

        assertEquals(3, manager().notificationChannels.size)
    }
}
