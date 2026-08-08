package com.branchconsole.app

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 실기기 S-0 발견 결함 회귀 가드 — AndroidManifest.xml에 `INTERNET` 권한 선언이 없어 모든 소켓
 * 호출(자격증명 검증·collectors·확정 틱)이 실기기에서 SecurityException으로 실패했다. JVM 테스트는
 * 네트워크 금지 규율이라 실호출 경로로는 못 잡는다 — 대신 Robolectric이 실제로 파싱한 패키지
 * 매니페스트(PackageManager)에 권한 선언 자체가 있는지를 단언한다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidManifestPermissionsTest {
    @Test
    fun `manifest declares INTERNET permission`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info =
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_PERMISSIONS,
            )
        assertTrue(
            "AndroidManifest.xml must declare android.permission.INTERNET " +
                "(missing it fails every socket call with SecurityException on-device)",
            info.requestedPermissions.orEmpty().contains("android.permission.INTERNET"),
        )
    }
}
