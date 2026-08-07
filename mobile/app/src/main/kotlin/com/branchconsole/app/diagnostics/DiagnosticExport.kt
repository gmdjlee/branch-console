package com.branchconsole.app.diagnostics

import android.content.Context
import com.branchconsole.app.tick.AssetConfigSource
import com.branchconsole.app.tick.PhaseDerivation
import com.branchconsole.engine.config.IndicatorRegistry
import com.branchconsole.engine.config.StatemachineConfigLoader
import com.branchconsole.lake.LakeDatabase
import com.branchconsole.lake.RunLogEntity
import com.branchconsole.lake.TickInputEntity
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private const val PROFILE_NAME = "mobile_daily" // same per-file duplication as HomeData.kt/NotificationSync.kt.
private const val FILENAME_TIMESTAMP_PATTERN = "yyyyMMddHHmm"

/**
 * MT1-08d — 실기기 스모크 증빙용 진단 JSON(docs/plans/M1_PLAN_D.md §11.3, GATE_GM1 대상).
 *
 * **범위(브리프 명시 축소)**: §11.3 원안의 `phase_commit[]`은 부재 테이블이라 만들지 않는다
 * (`phase_commit` 자체가 프로덕션에 존재한 적 없음 — [PhaseDerivation] KDoc; 브리프 aaa 요건 1은
 * 그 대체로 `tick_input` 행수 + [PhaseDerivation.currentPhase]를 지정한다, 아래 `counts.tick_input`+
 * `current_phase`가 그 대체다). `indicators[]`/`preview[]`의 지표별 전체 덤프는 만들지 않는다 —
 * 대신 `last_run.detail`이 부트스트랩 게이트 실패 시의 [com.branchconsole.app.tick.WarmupGate]
 * 리포트를 이미 그대로 담고 있어([com.branchconsole.app.tick.ConfirmTickRunner]의
 * `WARMUP_INSUFFICIENT` 분기) 지표별 결측 사유를 다시 계산하지 않고 재사용한다.
 *
 * **K-17**: 이 파일 어디에도 [com.branchconsole.app.credentials.CredentialsStore]나
 * `SharedPreferences` 참조가 없다 — 화이트리스트 방식(아래 나열된 필드가 전부)이라 자격증명에
 * 닿을 경로가 구조적으로 없다. [DiagnosticExportTest]가 이를 실증(자격증명 값을 실제로 저장한
 * 상태에서 내보내 값이 섞여 나오지 않음을 단언)한다.
 */
object DiagnosticExport {
    /** `branchconsole-diag-<yyyyMMddHHmm>.json` (§11.3 파일명 규약, UTC 고정 — K-05 표시 규율). */
    fun fileName(clock: Clock = Clock.systemUTC()): String {
        val formatter = DateTimeFormatter.ofPattern(FILENAME_TIMESTAMP_PATTERN).withZone(ZoneOffset.UTC)
        return "branchconsole-diag-${formatter.format(Instant.now(clock))}.json"
    }

    suspend fun build(
        context: Context,
        db: LakeDatabase,
        clock: Clock = Clock.systemUTC(),
    ): String {
        val configSource = AssetConfigSource(context)
        val registryVersion = runCatching { IndicatorRegistry.registryVersion(configSource) }.getOrNull()
        val currentPhase = runCatching { currentPhase(db, configSource) }.getOrNull()

        val tickRows = db.tickInputDao().allOrderedByDate()
        val runRows = db.runLogDao().allOrderedByRanAt()

        val root =
            JsonObject(
                mapOf(
                    "app" to appBlock(context, registryVersion),
                    "exported_at_epoch_millis" to JsonPrimitive(Instant.now(clock).toEpochMilli()),
                    "counts" to
                        JsonObject(
                            mapOf(
                                "tick_input" to JsonPrimitive(tickRows.size),
                                "run_log" to JsonPrimitive(runRows.size),
                                "observation" to JsonPrimitive(observationCount(db)),
                            ),
                        ),
                    "current_phase" to jsonOf(currentPhase),
                    "last_tick" to (tickRows.lastOrNull()?.let(::lastTickBlock) ?: JsonNull),
                    "last_run" to (runRows.lastOrNull()?.let(::lastRunBlock) ?: JsonNull),
                ),
            )
        return root.toString()
    }

    private suspend fun currentPhase(
        db: LakeDatabase,
        configSource: AssetConfigSource,
    ): String? {
        val statemachineConfig = StatemachineConfigLoader.load(configSource)
        return PhaseDerivation.currentPhase(db.tickInputDao(), PROFILE_NAME, statemachineConfig)
    }

    private fun appBlock(
        context: Context,
        registryVersion: String?,
    ): JsonObject =
        JsonObject(
            mapOf(
                "version_name" to jsonOf(versionName(context)),
                "registry_version" to jsonOf(registryVersion),
                "assets_manifest_sha256" to jsonOf(runCatching { assetsManifestSha256(context) }.getOrNull()),
            ),
        )

    private fun versionName(context: Context): String? =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()

    /** `syncConfigs`(MT1-01b) 산출물 `assets/ssot.sha256`(K-16 매니페스트) 자체의 해시 — 개별
     * 파일 해시를 다시 계산하지 않고, 계측 테스트([com.branchconsole.app.ConfigsAssetsInstrumentedTest]
     * 등)가 대조하는 그 매니페스트 파일 하나의 지문만 남긴다. */
    private fun assetsManifestSha256(context: Context): String {
        val bytes = context.assets.open("ssot.sha256").use { it.readBytes() }
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun lastTickBlock(tick: TickInputEntity): JsonObject =
        JsonObject(
            mapOf(
                "trading_date" to JsonPrimitive(tick.tradingDate),
                "coverage" to JsonPrimitive(tick.coverage),
                "is_catchup" to JsonPrimitive(tick.isCatchup),
                "gap_reason" to jsonOf(tick.gapReason),
            ),
        )

    private fun lastRunBlock(run: RunLogEntity): JsonObject =
        JsonObject(
            mapOf(
                "trading_date" to jsonOf(run.tradingDate),
                "status" to JsonPrimitive(run.status),
                "detail" to jsonOf(run.detail),
            ),
        )

    private fun observationCount(db: LakeDatabase): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM observation").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun jsonOf(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)
}
