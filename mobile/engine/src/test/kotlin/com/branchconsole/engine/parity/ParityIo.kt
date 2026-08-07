package com.branchconsole.engine.parity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime

/**
 * MT1-05j — BT-05 패리티 주입/산출물 I/O. 정본: docs/plans/M1_PLAN_C.md §9-C(4파일 규격) +
 * `backtest/export_parity.py`(Python 측 생성기, 이미 완성 — 이 파일은 그 출력의 소비자다).
 *
 * K-06/as_of 규약 확인(브리프 아이템 2): `raw.jsonl`의 `as_of`와 `grid.json`의 날짜 필드는
 * `backtest.fixture_schema.to_utc_midnight`으로 만든 UTC-자정 타임스탬프에서 `.date()`만
 * 취해 `isoformat()`한 **순수 달력일 문자열**이다(`export_parity.py.raw_records`/
 * `grid_record` 실측 확인) — 시각·타임존 성분이 전혀 없다. 따라서 이 문자열을
 * [LocalDate.parse]로 읽는 것은 Python 쪽 표현과 손실 없이 1:1 대응한다(양쪽 다 "그 날짜"
 * 그 자체를 나타낼 뿐, 자정이 어느 타임존인지는 이 시점에 정보로 존재하지 않는다 — KST
 * 오프셋은 이후 [com.branchconsole.engine.pit.Visibility.kstToUtc]가 확정 틱 시각을 붙일 때만
 * 부여된다). 이 문서화가 브리프 "as_of 규약 대조 선행" 항목의 결론이다: **불일치 없음,
 * 양측 모두 LocalDate 그 자체를 주고받는다.**
 */

@Serializable
private data class RawRowJson(
    @SerialName("series_id") val seriesId: String,
    val field: String,
    @SerialName("as_of") val asOf: String,
    val value: Double,
)

@Serializable
private data class GridJson(
    @SerialName("trading_days") val tradingDays: List<String>,
    @SerialName("eval_start") val evalStart: String,
    @SerialName("eval_end") val evalEnd: String,
    @SerialName("padding_days") val paddingDays: Int,
    @SerialName("confirm_time_kst") val confirmTimeKst: String,
    val profile: String,
    @SerialName("registry_version") val registryVersion: String,
)

data class RawRow(val seriesId: String, val field: String, val asOf: LocalDate, val value: Double)

data class Grid(
    val tradingDays: List<LocalDate>,
    val paddingDays: Int,
    val confirmTimeKst: LocalTime,
    val profile: String,
)

@Serializable
data class IndicatorLayer(
    val value: Double? = null,
    @SerialName("as_of") val asOf: String? = null,
    @SerialName("visible_at") val visibleAt: String? = null,
    val stale: Boolean = false,
    val severity: Int? = null,
)

@Serializable
data class TickRecord(
    @SerialName("evaluated_at") val evaluatedAt: String,
    @SerialName("kst_date") val kstDate: String,
    val indicators: Map<String, IndicatorLayer>,
    val composite: Double? = null,
    val coverage: Double,
    @SerialName("distinct_axes") val distinctAxes: Int,
    @SerialName("any_crit") val anyCrit: Boolean,
    @SerialName("any_extreme") val anyExtreme: Boolean,
    @SerialName("fired_axes") val firedAxes: List<String>,
    val phase: String,
)

/** `backtest/parity/<window_id>/` 4파일 I/O + K-16류 MANIFEST.sha256 무결성 검증. */
object ParityIo {
    // encodeDefaults=true: kotlinx.serialization defaults to *omitting* a property whose
    // value equals its declared default (e.g. IndicatorLayer.stale=false, .severity=null) —
    // without this, actual.jsonl silently drops keys the Python comparator requires on every
    // line, not just the ones that happen to differ from the default (found via a real run:
    // KeyError('stale') on ticks where stale happened to be false).
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * `MANIFEST.sha256`에 실린 해시 중 [consumedFiles]에 해당하는 것만 검증한다(§9-C
     * "양측이 MANIFEST.sha256을 검증한다... Kotlin이 로드 전" — Kotlin은 raw.jsonl/grid.json만
     * 소비하므로 그 둘만 확인하면 충분하다. expected.jsonl 해시 검증은 그것을 소비하는
     * Python 판정 스위트(backtest/test_bt05_parity.py)의 몫이다).
     */
    fun verifyManifest(
        windowDir: File,
        consumedFiles: Set<String> = setOf("raw.jsonl", "grid.json"),
    ) {
        val manifestFile = File(windowDir, "MANIFEST.sha256")
        check(manifestFile.isFile) { "MANIFEST.sha256 missing under $windowDir" }
        val entries =
            manifestFile.readLines().mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val parts = trimmed.split(Regex("\\s+"), limit = 2)
                check(parts.size == 2) { "malformed MANIFEST.sha256 line in $windowDir: '$line'" }
                parts[0] to parts[1]
            }
        for (name in consumedFiles) {
            val expected = entries.firstOrNull { it.second == name }?.first
            checkNotNull(expected) { "MANIFEST.sha256 has no entry for '$name' in $windowDir" }
            val file = File(windowDir, name)
            check(file.isFile) { "$name listed in MANIFEST but missing on disk: $windowDir" }
            val actual = sha256Hex(file.readBytes())
            check(actual == expected) {
                "MANIFEST.sha256 mismatch for $name in $windowDir: manifest=$expected actual=$actual " +
                    "(K-16 drift guard — re-run 'uv run python backtest/export_parity.py --window all')"
            }
        }
    }

    fun loadRaw(windowDir: File): List<RawRow> =
        File(windowDir, "raw.jsonl").readLines().filter { it.isNotBlank() }.map { line ->
            val r = json.decodeFromString(RawRowJson.serializer(), line)
            RawRow(r.seriesId, r.field, LocalDate.parse(r.asOf), r.value)
        }

    fun loadGrid(windowDir: File): Grid {
        val g = json.decodeFromString(GridJson.serializer(), File(windowDir, "grid.json").readText())
        return Grid(
            tradingDays = g.tradingDays.map { LocalDate.parse(it) },
            paddingDays = g.paddingDays,
            confirmTimeKst = LocalTime.parse(g.confirmTimeKst),
            profile = g.profile,
        )
    }

    fun writeActual(
        windowDir: File,
        ticks: List<TickRecord>,
    ) {
        val text = ticks.joinToString("\n") { json.encodeToString(TickRecord.serializer(), it) } + "\n"
        File(windowDir, "actual.jsonl").writeText(text)
    }
}
