package com.branchconsole.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * MT1-01b — K-16 해시 검증의 계층 ③(계측 정본, docs/plans/M1_PLAN_A.md §2.4 L-B).
 *
 * `ConfigsManifestJvmTest`(계층 ②)와 동일한 로직이지만 실제 패키징된 APK에서 `AssetManager`로
 * 읽은 바이트를 대조한다 — aapt·번들·머지 룰을 실제로 통과해야만 증명되는 유일한 층이다.
 * 실기기·에뮬레이터가 있어야 실행되므로 이 세션(Windows 개발 머신, 브리프 §3.2)에서는 실행하지
 * 않는다 — 배선(컴파일 가능성)만 증명하고, 실행은 GM1 실기기 스모크로 넘긴다(TASK MT1-01).
 * `./gradlew connectedDebugAndroidTest --tests "*ConfigsAssets*"`로 실기기에서 실행.
 */
@RunWith(AndroidJUnit4::class)
class ConfigsAssetsInstrumentedTest {
    private fun assetBytes(path: String): ByteArray =
        InstrumentationRegistry.getInstrumentation()
            .targetContext
            .assets
            .open(path)
            .use { it.readBytes() }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun packagedAssetsMatchSsotManifest() {
        val manifestLines =
            String(assetBytes("ssot.sha256"), Charsets.UTF_8)
                .lineSequence()
                .filter { it.isNotBlank() }
                .toList()
        assertTrue("ssot.sha256 매니페스트가 비어 있다", manifestLines.isNotEmpty())

        manifestLines.forEach { line ->
            val (expectedHash, relPath) = line.split("  ", limit = 2)
            val actualHash = sha256Hex(assetBytes(relPath))
            assertEquals("패키징된 asset 해시 불일치: $relPath", expectedHash, actualHash)
        }
    }
}
