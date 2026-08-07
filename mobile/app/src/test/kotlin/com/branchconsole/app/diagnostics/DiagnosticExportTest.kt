package com.branchconsole.app.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.branchconsole.app.credentials.CredentialFields
import com.branchconsole.app.credentials.CredentialsStore
import com.branchconsole.lake.LakeDatabase
import com.branchconsole.lake.ObservationEntity
import com.branchconsole.lake.RunLogEntity
import com.branchconsole.lake.TickInputEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private fun tickRow(
    date: String,
    composite: Double? = 24.3,
    coverage: Double = 1.0,
    isCatchup: Boolean = false,
    gapReason: String? = null,
) = TickInputEntity(
    tradingDate = date,
    composite = composite,
    distinctAxes = 0,
    anyCrit = false,
    anyExtreme = false,
    severitiesJson = "{}",
    coverage = coverage,
    registryVersion = "0.0.0-test",
    gapReason = gapReason,
    frozenAt = 0L,
    firedAxes = null,
    visibleAtByIndicator = null,
    isCatchup = isCatchup,
    warmupStatusJson = null,
    pitQuality = "live",
)

/**
 * MT1-08d — [DiagnosticExport] 왕복·스키마·K-17 검증(브리프 완료 기준 "diag JSON에 비밀이
 * 없음을 단언하는 테스트"). Harness는 [com.branchconsole.app.home.HomeDataTest]와 동형
 * (Robolectric 실 assets + `LakeDatabase.buildInMemory`).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class DiagnosticExportTest {
    private lateinit var db: LakeDatabase

    @Before
    fun setUp() {
        db = LakeDatabase.buildInMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun exportRoot(): JsonObject {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Json.parseToJsonElement(DiagnosticExport.build(context, db)).jsonObject
    }

    @Test
    fun `empty lake yields zero counts and null phase and tick and run blocks`() =
        runTest {
            val root = exportRoot()

            assertEquals(0, root["counts"]!!.jsonObject["tick_input"]!!.jsonPrimitive.int)
            assertEquals(0, root["counts"]!!.jsonObject["run_log"]!!.jsonPrimitive.int)
            assertEquals(0, root["counts"]!!.jsonObject["observation"]!!.jsonPrimitive.int)
            assertEquals(JsonNull, root["current_phase"])
            assertEquals(JsonNull, root["last_tick"])
            assertEquals(JsonNull, root["last_run"])
            assertEquals(JsonNull, root["last_success_run"])
        }

    @Test
    fun `counts reflect inserted rows across all three tables`() =
        runTest {
            db.tickInputDao().insert(tickRow("2026-08-06"))
            db.runLogDao().insert(
                RunLogEntity(tradingDate = "2026-08-06", ranAt = 0L, status = "success", detail = null),
            )
            db.runLogDao().insert(
                RunLogEntity(tradingDate = "2026-08-06", ranAt = 1L, status = "success", detail = "ok"),
            )
            db.observationDao().insert(
                ObservationEntity(
                    seriesId = "^GSPC",
                    field = "close",
                    asOf = 0L,
                    value = 1.0,
                    observedAt = 0L,
                    revision = 0,
                    lane = 0,
                    source = "yahoo",
                ),
            )

            val root = exportRoot()
            val counts = root["counts"]!!.jsonObject

            assertEquals(1, counts["tick_input"]!!.jsonPrimitive.int)
            assertEquals(2, counts["run_log"]!!.jsonPrimitive.int)
            assertEquals(1, counts["observation"]!!.jsonPrimitive.int)
        }

    @Test
    fun `last tick and last run blocks surface the most recent row's audit fields`() =
        runTest {
            db.tickInputDao().insert(tickRow("2026-08-05"))
            db.tickInputDao().insert(tickRow("2026-08-06", isCatchup = true, gapReason = "CATCHUP_GAP_TRUNCATED"))
            db.runLogDao().insert(
                RunLogEntity(tradingDate = "2026-08-06", ranAt = 0L, status = "success", detail = "committed=1"),
            )

            val root = exportRoot()
            val lastTick = root["last_tick"]!!.jsonObject
            val lastRun = root["last_run"]!!.jsonObject

            assertEquals("2026-08-06", lastTick["trading_date"]!!.jsonPrimitive.content)
            assertTrue(lastTick["is_catchup"]!!.jsonPrimitive.boolean)
            assertEquals("CATCHUP_GAP_TRUNCATED", lastTick["gap_reason"]!!.jsonPrimitive.content)
            assertEquals("success", lastRun["status"]!!.jsonPrimitive.content)
            assertEquals("committed=1", lastRun["detail"]!!.jsonPrimitive.content)
        }

    @Test
    fun `aaa C-1 -- last_success_run still points at the committed date after a cold-start catchup noop`() =
        runTest {
            // Reproduces the flake the critic found: BranchConsoleApplication.onCreate calls
            // triggerCatchupNow on every cold start, which re-runs ConfirmTickRunner. If today's
            // tick is already committed there's nothing left to do, so it logs a later "noop" row
            // -- making `last_run` (ordered by ran_at) misreport a tick that actually succeeded.
            db.tickInputDao().insert(tickRow("2026-08-06"))
            db.runLogDao().insert(
                RunLogEntity(tradingDate = "2026-08-06", ranAt = 0L, status = "success", detail = "committed=1"),
            )
            db.runLogDao().insert(RunLogEntity(tradingDate = null, ranAt = 1L, status = "started", detail = null))
            db.runLogDao().insert(
                RunLogEntity(tradingDate = null, ranAt = 2L, status = "noop", detail = "no candidate trading days"),
            )

            val root = exportRoot()

            assertEquals("noop", root["last_run"]!!.jsonObject["status"]!!.jsonPrimitive.content)
            val lastSuccess = root["last_success_run"]!!.jsonObject
            assertEquals("2026-08-06", lastSuccess["trading_date"]!!.jsonPrimitive.content)
            assertEquals("committed=1", lastSuccess["detail"]!!.jsonPrimitive.content)
        }

    @Test
    fun `registry version is read from real assets`() =
        runTest {
            val root = exportRoot()
            val registryVersion = root["app"]!!.jsonObject["registry_version"]!!.jsonPrimitive.content

            assertTrue("registry_version must be non-blank from the real SSOT asset", registryVersion.isNotBlank())
        }

    @Test
    fun `assets manifest sha256 is a 64-char hex digest of the packaged ssot manifest`() =
        runTest {
            val root = exportRoot()
            val digest = root["app"]!!.jsonObject["assets_manifest_sha256"]!!.jsonPrimitive.content

            assertEquals(64, digest.length)
            assertTrue(digest.all { it.isDigit() || it in 'a'..'f' })
        }

    @Test
    fun `filename follows the branchconsole-diag timestamp pattern`() {
        val name = DiagnosticExport.fileName()
        assertTrue(name.matches(Regex("""branchconsole-diag-\d{12}\.json""")))
    }

    @Test
    fun `K-17 -- credential values saved on this device never appear in the exported diagnostic json`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val prefs = context.getSharedPreferences("test_creds_${System.nanoTime()}", Context.MODE_PRIVATE)
            val secretStore = CredentialsStore(prefs)
            val secrets =
                CredentialFields(
                    krxId = "smoke-krx-id",
                    krxPassword = "smoke-krx-password-9f31",
                    fredApiKey = "smoke-fred-key-2b7e",
                    ecosApiKey = "smoke-ecos-key-5c10",
                    kisAppKey = "smoke-kis-app-key",
                    kisAppSecret = "smoke-kis-app-secret",
                )
            secretStore.save(secrets)
            db.tickInputDao().insert(tickRow("2026-08-06"))

            val json = DiagnosticExport.build(context, db)

            listOf(
                secrets.krxId,
                secrets.krxPassword,
                secrets.fredApiKey,
                secrets.ecosApiKey,
                secrets.kisAppKey,
                secrets.kisAppSecret,
            ).forEach { secret ->
                assertFalse("diag json must never contain credential value '$secret'", json.contains(secret!!))
            }
        }
}
