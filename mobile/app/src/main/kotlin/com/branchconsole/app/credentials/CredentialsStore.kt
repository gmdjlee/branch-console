package com.branchconsole.app.credentials

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.branchconsole.app.collectors.ecos.EcosCredentialsProvider
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
    /**
     * aaa D-4 — [CredentialFields.trimmed]을 여기서 거친다. 이유: [krxCredentialsProvider]/
     * [fredCredentialsProvider]/[ecosCredentialsProvider]와 [isCollectConfigured]가 전부 이
     * 함수 하나로 수렴하므로, 여기서 트림하면 그 4곳이 자동으로 커버된다. 이게 결정적인 이유는
     * S-0에서 트림 도입 **이전에** 이미 후행 공백 포함 키가 실기기에 저장돼 있었다는 사실 —
     * `save()`만 고쳐서는 legacy 저장값이 영구히 오염된 채 남아 확정 틱 수집이 조용히
     * 400/불일치로 실패한다(§2.2 조용한 실패 금지). `load()`가 트림하면 그 legacy 값도 읽을
     * 때마다 자동 치유된다.
     */
    fun load(): CredentialFields =
        CredentialFields(
            krxId = prefs.getString(KEY_KRX_ID, null),
            krxPassword = prefs.getString(KEY_KRX_PW, null),
            fredApiKey = prefs.getString(KEY_FRED_KEY, null),
            ecosApiKey = prefs.getString(KEY_ECOS_KEY, null),
            kisAppKey = prefs.getString(KEY_KIS_APP_KEY, null),
            kisAppSecret = prefs.getString(KEY_KIS_APP_SECRET, null),
        ).trimmed()

    fun save(fields: CredentialFields) {
        val normalized = fields.trimmed()
        prefs.edit()
            .putOrRemove(KEY_KRX_ID, normalized.krxId)
            .putOrRemove(KEY_KRX_PW, normalized.krxPassword)
            .putOrRemove(KEY_FRED_KEY, normalized.fredApiKey)
            .putOrRemove(KEY_ECOS_KEY, normalized.ecosApiKey)
            .putOrRemove(KEY_KIS_APP_KEY, normalized.kisAppKey)
            .putOrRemove(KEY_KIS_APP_SECRET, normalized.kisAppSecret)
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

    /** ECOS는 옵션 키(브리프 §4) — 미설정이면 [EcosCredentialsProvider.apiKey] 호출부가 예외를
     * 던지고, [com.branchconsole.app.collectors.ecos.EcosCollector]가 그것을
     * `CollectFailureReason.NotConfigured`로 흡수한다([fredCredentialsProvider]와 동일 계약). */
    fun ecosCredentialsProvider(): EcosCredentialsProvider =
        EcosCredentialsProvider {
            load().ecosApiKey?.takeIf { it.isNotBlank() } ?: error("ECOS_API_KEY not configured")
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
) {
    /**
     * 실기기 S-0 발견 결함 수정 — 신뢰 경계(폼 입력·영속값) 위생의 공용 정규화 함수(aaa D-4
     * 재판정으로 "유일한 초크포인트" 문구 정정: 실제로는 3곳에서 이 함수를 호출한다).
     * 모바일 키보드 자동완성/붙여넣기가 남기는 후행 공백을 okhttp가 그대로 `%20`으로 인코딩해
     * FRED가 키 불일치 400을 반환한다(실기기 재현: 정상 32자 키 200 vs 같은 키+공백1 400).
     * - [CredentialsStore.load] — **결정적 호출부**. provider 3종([CredentialsStore.krxCredentialsProvider]
     *   /[CredentialsStore.fredCredentialsProvider]/[CredentialsStore.ecosCredentialsProvider])과
     *   [CredentialsStore.isCollectConfigured]가 전부 load()로 수렴해, 트림 도입 이전에 저장된
     *   legacy 패딩 값까지 읽을 때마다 자동 치유한다.
     * - [CredentialsStore.save] — 저장 시점에도 트림(중복이지만 무해 — load가 어차피 다시
     *   트림하므로 상태 오염 방지의 이중 방어일 뿐).
     * - SettingsScreen 검증 버튼 — 아직 저장하지 않은 폼 입력값을 검증 직전에 커버(load를
     *   거치지 않는 유일한 미저장 경로).
     * KRX 비밀번호도 예외 없이 트림한다 — 양끝 공백이 유효한 비밀번호일 가능성보다 붙여넣기
     * 오염일 가능성이 압도적이다. 트림 후 공백만 남으면 null로 접어 기존 `isNullOrBlank()`
     * 판정과 일관되게 한다.
     */
    fun trimmed(): CredentialFields =
        CredentialFields(
            krxId = krxId?.trim()?.ifBlank { null },
            krxPassword = krxPassword?.trim()?.ifBlank { null },
            fredApiKey = fredApiKey?.trim()?.ifBlank { null },
            ecosApiKey = ecosApiKey?.trim()?.ifBlank { null },
            kisAppKey = kisAppKey?.trim()?.ifBlank { null },
            kisAppSecret = kisAppSecret?.trim()?.ifBlank { null },
        )
}
