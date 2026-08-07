package com.branchconsole.app.credentials

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MT1-08c K-17 — 계측 최소 원칙에 따른 유일한 예외: [CredentialsStore.create]가 실제로
 * `AndroidKeyStore` 백킹 `EncryptedSharedPreferences`를 조립하고 값을 왕복시키는지는 실기기/
 * 에뮬레이터에서만 확인할 수 있다(Robolectric에는 `AndroidKeyStore` 보안 제공자가 없다 — 실측,
 * [CredentialsStoreTest] KDoc 참조). 나머지 저장소 로직 전부는 그 JVM 테스트가 이미 담당한다.
 */
@RunWith(AndroidJUnit4::class)
class CredentialsStoreEncryptionInstrumentedTest {
    @Test
    fun createBuildsARealEncryptedStoreThatRoundTrips() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = CredentialsStore.create(context)

        store.save(CredentialFields(krxId = "instrumented-user", fredApiKey = "instrumented-key"))
        val loaded = store.load()

        assertEquals("instrumented-user", loaded.krxId)
        assertEquals("instrumented-key", loaded.fredApiKey)
    }
}
