package com.branchconsole.app.credentials

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.branchconsole.app.collectors.fred.FredCredentialsProvider
import com.branchconsole.app.collectors.krx.KrxCredentials
import com.branchconsole.app.collectors.krx.KrxCredentialsProvider

/**
 * MT1-08c/08d (aaa F-3 이관분) — K-17 자격증명 저장. `EncryptedSharedPreferences`
 * (MasterKey/AES256-GCM, Keystore 백킹)로 KRX ID/PW·FRED/ECOS API 키·KIS 앱키(옵션)를 저장한다.
 * `AndroidManifest.xml`의 `android:allowBackup="false"`가 백업 경로를 전면 차단하므로(Android
 * 12+ `dataExtractionRules`는 `allowBackup=true`일 때만 의미가 있어 별도로 추가하지 않는다)
 * 백업 유출 경로는 이미 없다 — 이 클래스는 로그·assets·코드 노출만 막으면 된다.
 *
 * `ANTHROPIC_API_KEY`는 없다(브리프 §2-6: M1은 LLM 미호출 — 입력란 자체를 두지 않는다).
 * ECOS는 00b 확정(BLOCKED)이라 값이 있어도 [WarmupBackfillOrchestrator]가 실제로 소비하지
 * 않는다 — 온보딩에는 "선택(미발급 시 관련 지표 미수집)"으로 노출한다(브리프 §4).
 *
 * [save]는 **전체 필드를 덮어쓴다** — 호출부(온보딩/설정 화면)는 항상 [load]로 현재값을 먼저
 * 읽어 폼 상태에 채운 뒤, 편집된 전체 [CredentialFields]를 한 번에 넘겨야 한다(부분 호출로
 * null을 넘기면 그 필드는 지워진다 — 부분 갱신 API가 아니다).
 *
 * 1차 생성자는 [SharedPreferences]를 직접 받는다(암호화 여부를 모른다) — 실측
 * (`java.security.KeyStoreException: AndroidKeyStore not found`)에 따르면 Robolectric(JVM)에는
 * `AndroidKeyStore` 보안 제공자가 없어 실제 Keystore 백킹 경로를 JVM 테스트로 구동할 수 없다.
 * 그래서 왕복·자격증명 어댑터 로직(이 클래스의 실질)은 평범한 [SharedPreferences]로 주입해
 * JVM에서 전수 검증하고([CredentialsStoreTest]), 실제 암호화 저장소 조립은 [create] 팩토리
 * 하나로 좁혀 그 팩토리 자체의 왕복만 계측 테스트로 확인한다(계측 최소 원칙,
 * `CredentialsStoreEncryptionInstrumentedTest`).
 */
class CredentialsStore(private val prefs: SharedPreferences) {
    fun load(): CredentialFields =
        CredentialFields(
            krxId = prefs.getString(KEY_KRX_ID, null),
            krxPassword = prefs.getString(KEY_KRX_PW, null),
            fredApiKey = prefs.getString(KEY_FRED_KEY, null),
            ecosApiKey = prefs.getString(KEY_ECOS_KEY, null),
            kisAppKey = prefs.getString(KEY_KIS_APP_KEY, null),
            kisAppSecret = prefs.getString(KEY_KIS_APP_SECRET, null),
        )

    fun save(fields: CredentialFields) {
        prefs.edit()
            .putOrRemove(KEY_KRX_ID, fields.krxId)
            .putOrRemove(KEY_KRX_PW, fields.krxPassword)
            .putOrRemove(KEY_FRED_KEY, fields.fredApiKey)
            .putOrRemove(KEY_ECOS_KEY, fields.ecosApiKey)
            .putOrRemove(KEY_KIS_APP_KEY, fields.kisAppKey)
            .putOrRemove(KEY_KIS_APP_SECRET, fields.kisAppSecret)
            .apply()
    }

    /** dailyCollect가 수집을 시도할 최소 요건 — KRX(id+pw)+FRED만 필수, ECOS/KIS는 옵션. */
    fun isCollectConfigured(): Boolean {
        val f = load()
        return !f.krxId.isNullOrBlank() && !f.krxPassword.isNullOrBlank() && !f.fredApiKey.isNullOrBlank()
    }

    fun krxCredentialsProvider(): KrxCredentialsProvider =
        KrxCredentialsProvider {
            val f = load()
            checkNotNull(f.krxId?.takeIf { it.isNotBlank() }) { "KRX_ID not configured" }
            val pw = f.krxPassword?.takeIf { it.isNotBlank() } ?: error("KRX_PW not configured")
            KrxCredentials(f.krxId, pw)
        }

    fun fredCredentialsProvider(): FredCredentialsProvider =
        FredCredentialsProvider {
            load().fredApiKey?.takeIf { it.isNotBlank() } ?: error("FRED_API_KEY not configured")
        }

    companion object {
        private const val PREFS_FILE_NAME = "branch_console_credentials"
        private const val KEY_KRX_ID = "krx_id"
        private const val KEY_KRX_PW = "krx_password"
        private const val KEY_FRED_KEY = "fred_api_key"
        private const val KEY_ECOS_KEY = "ecos_api_key"
        private const val KEY_KIS_APP_KEY = "kis_app_key"
        private const val KEY_KIS_APP_SECRET = "kis_app_secret"

        /** 프로덕션 조립 지점 — Keystore 백킹 AES256-GCM 암호화 저장소(K-17).
         * `EncryptedSharedPreferences`/`MasterKey`는 security-crypto 1.1.0에서 deprecated
         * 표기됐으나 대체할 안정 API가 아직 없다(androidx 공식 마이그레이션 가이드 부재) — 의도적 사용. */
        @Suppress("DEPRECATION")
        fun create(context: Context): CredentialsStore {
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val prefs =
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            return CredentialsStore(prefs)
        }

        private fun SharedPreferences.Editor.putOrRemove(
            key: String,
            value: String?,
        ): SharedPreferences.Editor = if (value.isNullOrBlank()) remove(key) else putString(key, value)
    }
}

/** 입력 항목(브리프 §4 — KRX ID/PW·FRED·ECOS(옵션)·KIS(옵션)). */
data class CredentialFields(
    val krxId: String? = null,
    val krxPassword: String? = null,
    val fredApiKey: String? = null,
    val ecosApiKey: String? = null,
    val kisAppKey: String? = null,
    val kisAppSecret: String? = null,
)
