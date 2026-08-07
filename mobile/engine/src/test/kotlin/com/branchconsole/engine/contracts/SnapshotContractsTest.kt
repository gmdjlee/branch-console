package com.branchconsole.engine.contracts

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * MT1-02b (docs/plans/M1_PLAN_B.md §6): JVM round-trip proof that the Kotlin mirror in
 * Snapshot.kt / Evidence.kt agrees with the frozen fixtures under the repo-root
 * `contracts/snapshots/` (MT1-02a, python-implementer). Fixtures are read from their
 * original location — never copied into this module (brief requirement) — by walking up
 * from the JVM test working directory until `contracts/snapshots` is found, so the test
 * doesn't depend on Gradle's exact working-directory convention.
 *
 * Covers (brief 완료 기준):
 *  ① positive fixtures x4: parse -> re-encode -> semantic equality (JSON tree, not bytes —
 *     "의미 동등" per the brief, since kotlinx.serialization's encodeDefaults would
 *     otherwise make byte-for-byte comparison brittle around default-valued fields).
 *  ② invalid/ x8: every one rejected by the model M1_PLAN_B.md §6.2/scripts/
 *     gen_contract_snapshots.py `_build_invalid_cases()` targets it at.
 *  ③ asymmetric/naive_datetime.json: Kotlin's `Instant.parse` rejects what Python
 *     currently accepts (§6.2.1) — the Kotlin half of that documented asymmetry.
 *  ④ "schema" key present, "schema_id" absent, on every positive fixture (M-16/D-B6).
 */
class SnapshotContractsTest {
    private val json = Json { encodeDefaults = true }

    private fun findSnapshotsDir(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(MAX_PARENT_HOPS) {
            val candidate = dir?.let { File(it, "contracts/snapshots") }
            if (candidate != null && candidate.isDirectory) return candidate
            dir = dir?.parentFile
        }
        error(
            "contracts/snapshots not found by walking up from " +
                System.getProperty("user.dir"),
        )
    }

    private val snapshotsDir = findSnapshotsDir()

    private fun read(relativePath: String): String = File(snapshotsDir, relativePath).readText(Charsets.UTF_8)

    private fun <T> assertSemanticRoundTrip(
        fileName: String,
        serializer: KSerializer<T>,
    ) {
        val text = read(fileName)
        val originalTree = json.parseToJsonElement(text)
        val decoded = json.decodeFromString(serializer, text)
        val reEncodedTree = json.encodeToJsonElement(serializer, decoded)
        assertEquals(originalTree, reEncodedTree, "$fileName: semantic round-trip mismatch")
    }

    // ---- ① positive fixtures: parse -> re-encode -> semantic equality ----

    @Test
    fun `scenario_snapshot min round-trips`() {
        assertSemanticRoundTrip("scenario_snapshot.min.json", ScenarioSnapshot.serializer())
    }

    @Test
    fun `scenario_snapshot full round-trips`() {
        assertSemanticRoundTrip("scenario_snapshot.full.json", ScenarioSnapshot.serializer())
    }

    @Test
    fun `evidence_pack min round-trips`() {
        assertSemanticRoundTrip("evidence_pack.min.json", EvidencePack.serializer())
    }

    @Test
    fun `evidence_pack full round-trips`() {
        assertSemanticRoundTrip("evidence_pack.full.json", EvidencePack.serializer())
    }

    // ---- ④ "schema" key present, "schema_id" absent (M-16/D-B6) ----

    @Test
    fun `positive fixtures use schema key not schema_id`() {
        for (name in POSITIVE_FIXTURES) {
            val root = json.parseToJsonElement(read(name)).jsonObject
            assertTrue("schema" in root, "$name: missing 'schema' key")
            assertTrue("schema_id" !in root, "$name: unexpectedly contains 'schema_id' key")
        }
    }

    // ---- ② invalid/ x8: each rejected by the model it violates ----

    @Test
    fun `composite_out_of_range rejected by TriggerBlock`() {
        assertFailsWith<Exception> {
            json.decodeFromString<TriggerBlock>(read("invalid/composite_out_of_range.json"))
        }
    }

    @Test
    fun `subjective_prob_over_one rejected by Scenario`() {
        assertFailsWith<Exception> {
            json.decodeFromString<Scenario>(read("invalid/subjective_prob_over_one.json"))
        }
    }

    @Test
    fun `horizon_days_zero rejected by Scenario`() {
        assertFailsWith<Exception> {
            json.decodeFromString<Scenario>(read("invalid/horizon_days_zero.json"))
        }
    }

    @Test
    fun `phase_unknown rejected by TriggerBlock`() {
        assertFailsWith<Exception> {
            json.decodeFromString<TriggerBlock>(read("invalid/phase_unknown.json"))
        }
    }

    @Test
    fun `scenarios_too_few rejected by ScenarioSnapshot`() {
        assertFailsWith<Exception> {
            json.decodeFromString<ScenarioSnapshot>(read("invalid/scenarios_too_few.json"))
        }
    }

    @Test
    fun `leading_indicators_one rejected by Scenario`() {
        assertFailsWith<Exception> {
            json.decodeFromString<Scenario>(read("invalid/leading_indicators_one.json"))
        }
    }

    @Test
    fun `severity_out_of_range rejected by FiredIndicator`() {
        assertFailsWith<Exception> {
            json.decodeFromString<FiredIndicator>(read("invalid/severity_out_of_range.json"))
        }
    }

    @Test
    fun `distinct_axes_negative rejected by TriggerBlock`() {
        assertFailsWith<Exception> {
            json.decodeFromString<TriggerBlock>(read("invalid/distinct_axes_negative.json"))
        }
    }

    @Test
    fun `all 8 documented invalid cases are present on disk`() {
        val names =
            File(snapshotsDir, "invalid").listFiles()
                ?.map { it.name }
                ?.toSet()
                .orEmpty()
        assertEquals(INVALID_CASE_FILENAMES, names)
    }

    // ---- ③ asymmetric/naive_datetime.json: Kotlin rejects, Python accepts (§6.2.1) ----

    @Test
    fun `asymmetric naive_datetime is rejected by Kotlin Instant parse`() {
        assertFailsWith<Exception> {
            json.decodeFromString<TriggerBlock>(read("asymmetric/naive_datetime.json"))
        }
    }

    private companion object {
        const val MAX_PARENT_HOPS = 8

        val POSITIVE_FIXTURES =
            listOf(
                "scenario_snapshot.min.json",
                "scenario_snapshot.full.json",
                "evidence_pack.min.json",
                "evidence_pack.full.json",
            )

        val INVALID_CASE_FILENAMES =
            setOf(
                "composite_out_of_range.json",
                "subjective_prob_over_one.json",
                "horizon_days_zero.json",
                "phase_unknown.json",
                "scenarios_too_few.json",
                "leading_indicators_one.json",
                "severity_out_of_range.json",
                "distinct_axes_negative.json",
            )
    }
}
