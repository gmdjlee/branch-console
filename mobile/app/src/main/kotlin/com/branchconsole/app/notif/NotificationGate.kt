package com.branchconsole.app.notif

import android.app.NotificationManager
import android.content.Context

/**
 * MT1-08a K-20 — 알림 표시 가능 여부 단일 판정점. `NotificationManager.areNotificationsEnabled()`
 * (API 24+, minSdk 29라 항상 사용 가능)는 targetSdk 33+의 런타임 `POST_NOTIFICATIONS` 거부
 * 상태까지 포함해 "지금 알림을 실제로 띄울 수 있는가"를 한 번에 답한다 — 별도로
 * `checkSelfPermission`을 부를 필요가 없다(권한 거부 시 이 값도 함께 false가 된다).
 *
 * 거부 시 노티는 발신 시도만 하고(플랫폼이 조용히 무시한다) **앱 기능을 막지 않는다** — 대체
 * 경로는 홈 배너다(브리프 "차단 금지", M1_PLAN_C.md §4.2 "권한 거부 대체 경로").
 */
object NotificationGate {
    fun isEnabled(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.areNotificationsEnabled()
    }
}
