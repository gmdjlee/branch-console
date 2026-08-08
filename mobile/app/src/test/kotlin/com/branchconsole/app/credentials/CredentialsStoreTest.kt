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
    fun `save then load round-trips padded input as trimmed (실기기 S-0)`() {
        val store = store()
        store.save(CredentialFields(krxId = " user1 ", krxPassword = "\npw1\n", fredApiKey = " fred-key "))

        val loaded = store.load()

        assertEquals("user1", loaded.krxId)
        assertEquals("pw1", loaded.krxPassword)
        assertEquals("fred-key", loaded.fredApiKey)
    }

    @Test
    fun `CredentialFields trimmed strips every field including KRX password`() {
        val padded =
            CredentialFields(
                krxId = " id ",
                krxPassword = "\npw\n",
                fredApiKey = " key ",
                ecosApiKey = "\tecos\t",
                kisAppKey = " kis-app ",
                kisAppSecret = " kis-secret ",
            )

        assertEquals(
            CredentialFields(
                krxId = "id",
                krxPassword = "pw",
                fredApiKey = "key",
                ecosApiKey = "ecos",
                kisAppKey = "kis-app",
                kisAppSecret = "kis-secret",
            ),
            padded.trimmed(),
        )
    }

    @Test
    fun `CredentialFields trimmed collapses whitespace-only fields to null`() {
        val trimmed = CredentialFields(krxId = "   ", krxPassword = "\n\t").trimmed()
        assertNull(trimmed.krxId)
        assertNull(trimmed.krxPassword)
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

    /**
     * aaa D-4 재판정 필수 증인 — legacy 저장값 자동 치유. `save()`를 우회해(트림 도입 이전에
     * 저장된 상태를 흉내내려는 것이므로 정상 저장 경로를 쓰면 안 된다) [CredentialsStore]의
     * private key 상수(krx_id/krx_password/fred_api_key/ecos_api_key)와 동일한 리터럴로
     * `SharedPreferences`에 패딩 값을 직접 심는다. provider 3종이 `load()`를 통해 트림된 값을
     * 반환해야 한다 — 실기기에 실제로 남아있는 오염된 키가 앱 업데이트만으로 치유됨을 증명한다.
     */
    @Test
    fun `providers auto-heal legacy padded values written directly to prefs, bypassing save()`() {
        val rawPrefs =
            ApplicationProvider.getApplicationContext<Context>()
                .getSharedPreferences("test_credentials_legacy_${System.nanoTime()}", Context.MODE_PRIVATE)
        rawPrefs.edit()
            .putString("krx_id", " user1 ")
            .putString("krx_password", "\npw1\n")
            .putString("fred_api_key", " fred-key ")
            .putString("ecos_api_key", " ecos-key ")
            .apply()
        val store = CredentialsStore(rawPrefs)

        val krxCreds = store.krxCredentialsProvider().get()
        assertEquals("user1", krxCreds.id)
        assertEquals("pw1", krxCreds.password)
        assertEquals("fred-key", store.fredCredentialsProvider().apiKey())
        assertEquals("ecos-key", store.ecosCredentialsProvider().apiKey())
    }
}
