package com.branchconsole.app.credentials

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MT1-08c K-17 — 자격증명 저장 왕복 검증(저장소 로직 자체 — 실제 `AndroidKeyStore` 백킹
 * 암호화 조립은 [CredentialsStore.create] 팩토리 하나로 좁혀뒀고, Robolectric(JVM)에는
 * `AndroidKeyStore` 보안 제공자가 없어(실측: `KeyStoreException: AndroidKeyStore not found`)
 * 그 팩토리만은 계측 테스트(`CredentialsStoreEncryptionInstrumentedTest`)로 확인한다 — 이
 * 파일은 평범한 [SharedPreferences] 주입으로 왕복·[KrxCredentialsProvider]/
 * [FredCredentialsProvider] 어댑터 계약을 JVM에서 전수 검증한다).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CredentialsStoreTest {
    private fun store() =
        CredentialsStore(
            ApplicationProvider.getApplicationContext<Context>()
                .getSharedPreferences("test_credentials_${System.nanoTime()}", Context.MODE_PRIVATE),
        )

    @Test
    fun `save then load round-trips all fields`() {
        val store = store()
        val fields =
            CredentialFields(
                krxId = "user1",
                krxPassword = "pw1",
                fredApiKey = "fred-key",
                ecosApiKey = "ecos-key",
                kisAppKey = "kis-app",
                kisAppSecret = "kis-secret",
            )

        store.save(fields)
        val loaded = store.load()

        assertEquals(fields, loaded)
    }

    @Test
    fun `unset store loads all-null fields`() {
        val loaded = store().load()
        assertEquals(CredentialFields(), loaded)
    }

    @Test
    fun `isCollectConfigured is false until krx and fred are all present`() {
        val store = store()
        assertFalse(store.isCollectConfigured())

        store.save(CredentialFields(krxId = "id", krxPassword = "pw"))
        assertFalse("FRED key still missing", store.isCollectConfigured())

        store.save(CredentialFields(krxId = "id", krxPassword = "pw", fredApiKey = "key"))
        assertTrue(store.isCollectConfigured())
    }

    @Test
    fun `blank values are treated as unset (removed, not stored blank)`() {
        val store = store()
        store.save(CredentialFields(krxId = "id", krxPassword = "pw", fredApiKey = "key"))
        store.save(CredentialFields(krxId = "id", krxPassword = "pw", fredApiKey = "  "))

        assertNull(store.load().fredApiKey)
        assertFalse(store.isCollectConfigured())
    }

    @Test
    fun `krxCredentialsProvider throws when not configured, returns value once saved`() {
        val store = store()
        val provider = store.krxCredentialsProvider()
        try {
            provider.get()
            org.junit.Assert.fail("expected IllegalStateException for missing KRX credentials")
        } catch (expected: IllegalStateException) {
            // expected
        }

        store.save(CredentialFields(krxId = "user1", krxPassword = "pw1"))
        val creds = provider.get()
        assertEquals("user1", creds.id)
        assertEquals("pw1", creds.password)
    }

    @Test
    fun `fredCredentialsProvider throws when not configured, returns key once saved`() {
        val store = store()
        val provider = store.fredCredentialsProvider()
        try {
            provider.apiKey()
            org.junit.Assert.fail("expected exception for missing FRED key")
        } catch (expected: IllegalStateException) {
            // expected
        }

        store.save(CredentialFields(fredApiKey = "fred-key"))
        assertEquals("fred-key", provider.apiKey())
    }
}
