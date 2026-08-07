package com.branchconsole.engine.contracts

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Kotlin mirror of contracts/snapshot.py (scenario-snapshot/1, pydantic SSOT).
 *
 * docs/plans/M1_PLAN_B.md §6 / M1_PLAN_FINAL.md §1.3 M-16: contracts/snapshot.py and
 * contracts/evidence.py stay the only source of truth (CLAUDE.md §1) — this file
 * reproduces their current wire shape and constraints, it does not define new ones.
 * Round-trip proof against the JSON fixtures under contracts/snapshots lives in
 * SnapshotContractsTest.kt.
 *
 * Wire regulations enforced here (M1_PLAN_B.md §6.3):
 *  1. "schema" is the wire field name (Python's `by_alias=True`, not "schema_id" — D-B6).
 *  2. datetime -> kotlinx.datetime.Instant. K-05: aware-only. `Instant.parse` rejects a
 *     naive string (no offset/"Z") where pydantic currently accepts one — that Python/
 *     Kotlin asymmetry is deliberate (§6.2.1) and pinned by
 *     SnapshotContractsTest on asymmetric/naive_datetime.json.
 *  3. `tuple[float, float]` -> `List<Double>` of size 2 (KrImpact.kospiRangePct).
 *  4. confloat/conint/min_length/max_length constraints aren't enforced by
 *     kotlinx.serialization itself, so each one is reproduced with `require()` in an
 *     `init` block — the plugin-generated deserializer calls the real constructor, so
 *     `init` still runs on decode. K-07: every numeric field is Double, never Float.
 *  6. Literal/enum fields reject unknown values for free — kotlinx.serialization's
 *     default enum serializer already throws on an unrecognized wire value.
 */

enum class Phase { GREEN, AMBER, ORANGE, RED }

enum class ClassificationLevel {
    @SerialName("low")
    LOW,

    @SerialName("medium")
    MEDIUM,

    @SerialName("high")
    HIGH,
}

enum class EventType {
    @SerialName("monetary_policy")
    MONETARY_POLICY,

    @SerialName("credit_stress")
    CREDIT_STRESS,

    @SerialName("geopolitical")
    GEOPOLITICAL,

    @SerialName("growth_shock")
    GROWTH_SHOCK,

    @SerialName("tech_supply_demand")
    TECH_SUPPLY_DEMAND,

    @SerialName("domestic_kr")
    DOMESTIC_KR,

    @SerialName("mixed")
    MIXED,

    @SerialName("unknown")
    UNKNOWN,
}

enum class UsdKrwBias {
    @SerialName("up")
    UP,

    @SerialName("down")
    DOWN,

    @SerialName("flat")
    FLAT,
}

private const val SEVERITY_MIN = 0
private const val SEVERITY_MAX = 3
private const val COMPOSITE_MIN = 0.0
private const val COMPOSITE_MAX = 100.0
private const val PROB_MIN = 0.0
private const val PROB_MAX = 1.0
private const val HORIZON_MIN = 1
private const val HORIZON_MAX = 120
private const val LEADING_INDICATORS_MIN = 2
private const val SCENARIOS_MIN = 2
private const val SCENARIOS_MAX = 4
private const val KOSPI_RANGE_SIZE = 2
private const val SCENARIO_SNAPSHOT_SCHEMA_ID = "scenario-snapshot/1"
private const val TARGET_MARKET_KR = "KR"

@Serializable
data class FiredIndicator(
    val id: String,
    val axis: String,
    val severity: Int,
    val value: Double,
    val z: Double? = null,
    val note: String? = null,
) {
    init {
        require(severity in SEVERITY_MIN..SEVERITY_MAX) {
            "severity must be in $SEVERITY_MIN..$SEVERITY_MAX, was $severity"
        }
    }
}

@Serializable
data class TriggerBlock(
    val phase: Phase,
    @SerialName("prev_phase") val prevPhase: Phase,
    @SerialName("composite_score") val compositeScore: Double,
    @SerialName("distinct_axes") val distinctAxes: Int,
    @SerialName("fired_indicators") val firedIndicators: List<FiredIndicator>,
    @SerialName("evaluated_at") val evaluatedAt: Instant,
) {
    init {
        require(compositeScore in COMPOSITE_MIN..COMPOSITE_MAX) {
            "composite_score must be in $COMPOSITE_MIN..$COMPOSITE_MAX, was $compositeScore"
        }
        require(distinctAxes >= 0) { "distinct_axes must be >= 0, was $distinctAxes" }
    }
}

@Serializable
data class EventClassification(
    val type: EventType,
    val severity: ClassificationLevel,
    val confidence: ClassificationLevel,
    val analogues: List<String> = emptyList(),
    val rationale: String,
)

@Serializable
data class KrImpact(
    @SerialName("kospi_range_pct") val kospiRangePct: List<Double>,
    @SerialName("sectors_hit") val sectorsHit: List<String>,
    @SerialName("sectors_defensive") val sectorsDefensive: List<String>,
    @SerialName("usdkrw_bias") val usdkrwBias: UsdKrwBias,
) {
    init {
        require(kospiRangePct.size == KOSPI_RANGE_SIZE) {
            "kospi_range_pct must have exactly $KOSPI_RANGE_SIZE elements, was ${kospiRangePct.size}"
        }
    }
}

@Serializable
data class Scenario(
    val name: String,
    @SerialName("subjective_prob") val subjectiveProb: Double,
    val narrative: String,
    @SerialName("kr_impact") val krImpact: KrImpact,
    @SerialName("leading_indicators") val leadingIndicators: List<String>,
    val invalidation: String,
    @SerialName("horizon_days") val horizonDays: Int,
) {
    init {
        require(subjectiveProb in PROB_MIN..PROB_MAX) {
            "subjective_prob must be in $PROB_MIN..$PROB_MAX, was $subjectiveProb"
        }
        require(leadingIndicators.size >= LEADING_INDICATORS_MIN) {
            "leading_indicators must have >= $LEADING_INDICATORS_MIN elements, was ${leadingIndicators.size}"
        }
        require(horizonDays in HORIZON_MIN..HORIZON_MAX) {
            "horizon_days must be in $HORIZON_MIN..$HORIZON_MAX, was $horizonDays"
        }
    }
}

@Serializable
data class ScenarioSnapshot(
    @SerialName("schema") val schema: String = SCENARIO_SNAPSHOT_SCHEMA_ID,
    @SerialName("generated_at") val generatedAt: Instant,
    @SerialName("target_market") val targetMarket: String = TARGET_MARKET_KR,
    val trigger: TriggerBlock,
    @SerialName("event_classification") val eventClassification: EventClassification,
    val scenarios: List<Scenario>,
    @SerialName("watchlist_next_7d") val watchlistNext7d: List<String>,
    val disclaimer: String = DEFAULT_DISCLAIMER,
) {
    init {
        require(schema == SCENARIO_SNAPSHOT_SCHEMA_ID) {
            "schema must be '$SCENARIO_SNAPSHOT_SCHEMA_ID', was '$schema'"
        }
        require(targetMarket == TARGET_MARKET_KR) {
            "target_market must be '$TARGET_MARKET_KR', was '$targetMarket'"
        }
        require(scenarios.size in SCENARIOS_MIN..SCENARIOS_MAX) {
            "scenarios must have $SCENARIOS_MIN..$SCENARIOS_MAX elements, was ${scenarios.size}"
        }
    }

    companion object {
        const val DEFAULT_DISCLAIMER: String =
            "subjective_prob는 비보정 주관 확률. 투자 자문이 아닌 의사결정 보조 정보."
    }
}
