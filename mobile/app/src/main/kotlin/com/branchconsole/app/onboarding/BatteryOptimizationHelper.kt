package com.branchconsole.app.onboarding

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings

private const val SAMSUNG = "samsung"

/**
 * MT1-08c K-15 — OEM 절전 관리자가 WorkManager 작업을 죽일 수 있다는 사실을 온보딩에서
 * 안내한다. 제조사별 상세 가이드는 M2(M1_PLAN_C.md §4.4) — M1은 문구 2종(삼성/기타)만 분기한다.
 * 딥링크는 `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`(전체 목록 화면 — 앱별 직접 요청
 * 다이얼로그의 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 권한 선언이 불필요하다).
 */
internal object BatteryOptimizationHelper {
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun guidanceText(manufacturer: String): String =
        if (manufacturer.equals(SAMSUNG, ignoreCase = true)) {
            "설정 > 배터리 및 디바이스 케어 > 백그라운드 사용량 제한에서 branch-console을 절전 예외로 등록하세요."
        } else {
            "설정 > 배터리에서 이 앱의 배터리 최적화를 '제한 없음'으로 변경하세요."
        }

    fun openSettingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}
