package com.branchconsole.app.notif

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * MT1-08a — 노티 3채널(D §3.9.1 "M1에서 확정" 표). 채널 ID·중요도는 여기서 **한 번만** 정해야
 * 한다 — 앱이 생성한 뒤에는 중요도를 코드로 올릴 수 없고, 같은 ID로 삭제·재생성해도 사용자가
 * 이미 조정한 설정(꺼짐 등)이 복원되는 플랫폼 제약 때문이다(D §3.9.1). 문구·아이콘·액션 버튼
 * 등 "표현"은 M2 소관 — 여기서 정하는 것은 ID·중요도·존재뿐이다.
 *
 * 채널 이름(`provisional_alert`)은 브리프·docs/plans/M1_PLAN_C.md §4.2를 따른다 — 자매 문서
 * M1_PLAN_D.md §3.9.1 표는 이름을 `preview_alert`로 적어 두 정본 사이에 표기 불일치가 있다
 * (두 문서 모두 계획 위원회 PASS). 채널 ID는 설치 후 고정되는 값이라 이름 하나를 확정해야
 * 하므로, 더 상세한 설계(관점 C 심화, §4.2)와 브리프가 일치하는 `provisional_alert`를 채택한다
 * — GATE_GM1에 이 표기 불일치를 기록할 것.
 *
 * [ensureCreated]는 멱등이다(Android 공식 문서: 동일 ID로 재호출은 no-op) — 앱 시작마다
 * 안전하게 호출할 수 있다.
 */
object NotificationChannels {
    const val PHASE_TRANSITION = "phase_transition"
    const val PROVISIONAL_ALERT = "provisional_alert"
    const val TICK_FAILURE = "tick_failure"

    fun ensureCreated(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(PHASE_TRANSITION, "국면 전이", NotificationManager.IMPORTANCE_HIGH),
        )
        manager.createNotificationChannel(
            NotificationChannel(PROVISIONAL_ALERT, "잠정 경보", NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(TICK_FAILURE, "틱 실패 · 데이터 문제", NotificationManager.IMPORTANCE_LOW),
        )
    }
}
