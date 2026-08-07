@file:OptIn(ExperimentalSerializationApi::class)

package com.branchconsole.engine.contracts

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * MT1-02d (docs/plans/M1_PLAN_B.md §138 / M1_PLAN_FINAL.md §1.3 M-16 정규 참조): shape-digest
 * cross-check.
 *
 * [SnapshotContractsTest] already proves the two mirrors agree on 4 concrete JSON instances
 * + 8 invalid/1 asymmetric case, but a round-trip test is blind to a field that only ever
 * gets *added* on one side and is never exercised by an existing fixture (an optional field
 * nothing populates) - that silent drift is exactly what a frozen digest of the *shape
 * itself* (field name/type/required, not values) catches.
 * `scripts/gen_contract_snapshots.py` computes the Python-side digest from each pydantic
 * model's own `model_fields` and writes it to `contracts/snapshots/shape.sha256` (SSOT -
 * never edited here, read-only). This test independently re-derives the same digest from
 * the Kotlin mirror's *own* metadata - each `@Serializable` class's compiler-generated
 * [SerialDescriptor] - and asserts the two 32-byte digests match byte-for-byte.
 *
 * Field enumeration is fully descriptor-driven (`elementsCount`/`getElementName`/
 * `isElementOptional`/`getElementDescriptor`): adding, removing, or renaming a field on
 * either side changes the computed shape and therefore the digest, without this test being
 * told about the field by name - a hand-maintained field list would need its own
 * hand-updating and would carry exactly the "silent drift" risk this test exists to remove.
 *
 * Four fields are pydantic `Literal[...]` (single fixed string) / `tuple[float, float]`
 * types Kotlin's type system has no equivalent for (Snapshot.kt file header items 1/3:
 * `schema`/`target_market` are single-value Literals modeled here as `String` + `require()`;
 * `kospi_range_pct` is a fixed-size tuple modeled as `List<Double>` + `require()`) - the
 * descriptor can only report the Kotlin type that actually exists (`str`/`list[float]`), so
 * those four route through [TYPE_OVERRIDES] to the exact token the Python side reports.
 * Every other field (the overwhelming majority) is derived purely from the descriptor.
 */
class ShapeDigestParityTest {
    @Test
    fun `kotlin mirror shape digest matches the frozen contracts snapshot`() {
        val expectedDigest = shapeSha256File().readText(Charsets.UTF_8).trim()
        val actualDigest = shapeDigestHex()
        assertEquals(
            expectedDigest,
            actualDigest,
            "shape digest drift between contracts/*.py (Python SSOT) and Snapshot.kt/Evidence.kt (Kotlin mirror)",
        )
    }

    private fun shapeSha256File(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(MAX_PARENT_HOPS) {
            val candidate = dir?.let { File(it, "contracts/snapshots/shape.sha256") }
            if (candidate != null && candidate.isFile) return candidate
            dir = dir?.parentFile
        }
        error("contracts/snapshots/shape.sha256 not found by walking up from ${System.getProperty("user.dir")}")
    }

    private fun shapeDigestHex(): String {
        val canonical = canonicalJson(buildShapeDigest())
        val bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun buildShapeDigest(): Map<String, Any> =
        SHAPE_MODEL_DESCRIPTORS.associate { descriptor ->
            val modelName = descriptor.serialName.substringAfterLast('.')
            modelName to modelShape(modelName, descriptor)
        }

    private fun modelShape(
        modelName: String,
        descriptor: SerialDescriptor,
    ): Map<String, Any> {
        val fields =
            (0 until descriptor.elementsCount).associate { i ->
                val alias = descriptor.getElementName(i)
                val required = !descriptor.isElementOptional(i)
                val type = TYPE_OVERRIDES[modelName to alias] ?: typeRepr(descriptor.getElementDescriptor(i))
                alias to mapOf("required" to required, "type" to type)
            }
        return mapOf("fields" to fields)
    }

    private fun typeRepr(descriptor: SerialDescriptor): String {
        val token = baseTypeToken(descriptor)
        return if (descriptor.isNullable) listOf("None", token).sorted().joinToString("|") else token
    }

    /** kotlinx.serialization wraps a nullable element's descriptor (serialName gets a "?"
     * suffix, `.kind` still delegates to the wrapped non-null descriptor) - strip it before
     * comparing serialName tokens (e.g. `Instant`/`Instant?` must both map to "datetime"). */
    private fun baseTypeToken(descriptor: SerialDescriptor): String {
        val serialName = descriptor.serialName.removeSuffix("?")
        return when (descriptor.kind) {
            PrimitiveKind.STRING -> if (serialName == KOTLINX_DATETIME_INSTANT_SERIAL_NAME) "datetime" else "str"
            PrimitiveKind.INT -> "int"
            PrimitiveKind.DOUBLE -> "float"
            PrimitiveKind.BOOLEAN -> "bool"
            SerialKind.ENUM ->
                (0 until descriptor.elementsCount)
                    .map { descriptor.getElementName(it) }
                    .sorted()
                    .joinToString(",", "Literal[", "]")
            StructureKind.LIST -> "list[" + baseTypeToken(descriptor.getElementDescriptor(0)) + "]"
            StructureKind.CLASS -> serialName.substringAfterLast('.')
            else -> error("ShapeDigestParityTest: unhandled SerialKind ${descriptor.kind} for $serialName")
        }
    }

    /** Recursive canonical JSON writer matching Python's
     * `json.dumps(value, sort_keys=True, separators=(",", ":"))` for the value domain this
     * test produces (nested string-keyed maps of String/Boolean leaves only). */
    private fun canonicalJson(value: Any?): String =
        when (value) {
            is Map<*, *> ->
                value.entries
                    .sortedBy { it.key as String }
                    .joinToString(",", "{", "}") { (k, v) -> "\"$k\":${canonicalJson(v)}" }
            is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            is Boolean -> value.toString()
            else -> error("ShapeDigestParityTest: unsupported canonical json value $value")
        }

    private companion object {
        const val MAX_PARENT_HOPS = 8
        const val KOTLINX_DATETIME_INSTANT_SERIAL_NAME = "Instant"

        // Pydantic `Literal[...]` (single fixed string) and `tuple[float, float]` have no
        // Kotlin-type equivalent (Snapshot.kt file header items 1/3) - see class doc.
        val TYPE_OVERRIDES: Map<Pair<String, String>, String> =
            mapOf(
                ("KrImpact" to "kospi_range_pct") to "tuple[float,float]",
                ("ScenarioSnapshot" to "schema") to "Literal[scenario-snapshot/1]",
                ("ScenarioSnapshot" to "target_market") to "Literal[KR]",
                ("EvidencePack" to "schema") to "Literal[evidence-pack/1]",
            )

        val SHAPE_MODEL_DESCRIPTORS: List<SerialDescriptor> =
            listOf(
                FiredIndicator.serializer().descriptor,
                TriggerBlock.serializer().descriptor,
                EventClassification.serializer().descriptor,
                KrImpact.serializer().descriptor,
                Scenario.serializer().descriptor,
                ScenarioSnapshot.serializer().descriptor,
                MarketSnapshot.serializer().descriptor,
                NewsCluster.serializer().descriptor,
                MacroEvent.serializer().descriptor,
                AnalogueRef.serializer().descriptor,
                EvidencePack.serializer().descriptor,
            )
    }
}
