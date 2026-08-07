package com.branchconsole.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.security.MessageDigest

/**
 * MT1-01b — K-16 해시 검증의 계층 ②(JVM 보강, docs/plans/M1_PLAN_A.md §2.4).
 *
 * `:app:verifyConfigHashes`(계층 ①)는 빌드 타임에 "저장소 원본 == generated assets" 바이트
 * 동일성을 증명한다. 이 테스트는 그와 별개로 실제 Android API 표면(`AssetManager`)을 Robolectric으로
 * 실행해 — `testOptions.unitTests.isIncludeAndroidResources = true`가 merge된 debug assets를
 * 유닛테스트 클래스패스에 올려준다 — 계측 정본(`ConfigsAssetsInstrumentedTest`, androidTest)이
 * 실기기에서 증명할 것과 동일한 로딩 경로를 JVM에서 미리 검증한다. 계측 테스트를 대체하지 않는다.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ConfigsManifestJvmTest {
    private fun assetBytes(path: String): ByteArray =
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .assets
            .open(path)
            .use { it.readBytes() }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `assets loaded via AssetManager match the packaged ssot manifest`() {
        val manifestLines =
            String(assetBytes("ssot.sha256"), Charsets.UTF_8)
                .lineSequence()
                .filter { it.isNotBlank() }
                .toList()
        assertTrue("ssot.sha256 매니페스트가 비어 있다 — syncConfigs가 실행되지 않았는가?", manifestLines.isNotEmpty())

        manifestLines.forEach { line ->
            val (expectedHash, relPath) = line.split("  ", limit = 2)
            val actualHash = sha256Hex(assetBytes(relPath))
            assertEquals("asset 해시 불일치: $relPath", expectedHash, actualHash)
        }
    }

    @Test
    fun `indicators registry_version loaded from assets is 0-3-1-rc`() {
        val loaded =
            ApplicationProvider.getApplicationContext<android.content.Context>()
                .assets
                .open("configs/indicators.yaml")
                .use { Load(LoadSettings.builder().build()).loadFromInputStream(it) }

        check(loaded is Map<*, *>) { "indicators.yaml 최상위가 Map이 아니다: ${loaded?.javaClass}" }
        assertEquals("0.3.1-rc", loaded["registry_version"])
    }
}
