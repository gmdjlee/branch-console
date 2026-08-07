package com.branchconsole.app.notif

import android.app.Notification
import android.app.NotificationManager
import android.content.Context

/**
 * MT1-08a — 실제 발신. `setSmallIcon`은 프레임워크 내장 드로어블을 쓴다(M1은 기능판이라 앱
 * 아이콘·알림 아이콘 디자인은 M2 이관 대상, M1_PLAN_C.md §4.3 "M1이 하지 않는 것"). `notify()`를
 * 채널별 고정 id로 호출하므로 같은 채널의 반복 알림은 스택이 아니라 갱신된다(M1에 그룹핑
 * 기능이 없다는 것과 동일한 근거 — M1_PLAN_C.md §4.2 "M1에서 만들지 않는 것").
 */
internal object NotificationSender {
    fun send(
        context: Context,
        channelId: String,
        notificationId: Int,
        title: String,
        text: String,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val notification =
            Notification.Builder(context, channelId)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setAutoCancel(true)
                .build()
        manager.notify(notificationId, notification)
    }
}
