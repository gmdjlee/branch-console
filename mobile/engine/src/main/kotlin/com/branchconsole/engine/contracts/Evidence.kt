package com.branchconsole.engine.contracts

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Kotlin mirror of contracts/evidence.py (evidence-pack/1, pydantic SSOT).
 *
 * See Snapshot.kt's file header for the shared wire regulations (M1_PLAN_B.md §6.3).
 * Reuses [Phase] and [FiredIndicator] from Snapshot.kt — evidence.py itself imports both
 * from contracts.snapshot, so this mirrors the same cross-file relationship.
 */

private const val HEADLINES_MAX = 5
private const val EVIDENCE_PACK_SCHEMA_ID = "evidence-pack/1"

@Serializable
data class MarketSnapshot(
    @SerialName("as_of") val asOf: Instant,
    val kospi: Double,
    @SerialName("kospi_chg_1d_pct") val kospiChg1dPct: Double,
    @SerialName("kospi_drawdown_60d_pct") val kospiDrawdown60dPct: Double,
    val usdkrw: Double,
    @SerialName("usdkrw_chg_1d_pct") val usdkrwChg1dPct: Double,
    val vix: Double? = null,
    val vkospi: Double? = null,
    @SerialName("hy_oas") val hyOas: Double? = null,
    @SerialName("spx_chg_1d_pct") val spxChg1dPct: Double? = null,
)

@Serializable
data class NewsCluster(
    val topic: String,
    @SerialName("article_count") val articleCount: Int,
    @SerialName("novelty_z") val noveltyZ: Double? = null,
    @SerialName("representative_headlines") val representativeHeadlines: List<String>,
) {
    init {
        require(representativeHeadlines.size <= HEADLINES_MAX) {
            "representative_headlines must have <= $HEADLINES_MAX elements, " +
                "was ${representativeHeadlines.size}"
        }
    }
}

@Serializable
data class MacroEvent(
    val date: String,
    val name: String,
)

@Serializable
data class AnalogueRef(
    @SerialName("event_id") val eventId: String,
    val similarity: Double,
    @SerialName("outcome_summary") val outcomeSummary: String,
)

@Serializable
data class EvidencePack(
    @SerialName("schema") val schema: String = EVIDENCE_PACK_SCHEMA_ID,
    @SerialName("built_at") val builtAt: Instant,
    val phase: Phase,
    @SerialName("prev_phase") val prevPhase: Phase,
    @SerialName("composite_score") val compositeScore: Double,
    @SerialName("fired_indicators") val firedIndicators: List<FiredIndicator>,
    val market: MarketSnapshot,
    @SerialName("news_clusters") val newsClusters: List<NewsCluster> = emptyList(),
    @SerialName("macro_calendar_7d") val macroCalendar7d: List<MacroEvent> = emptyList(),
    val analogues: List<AnalogueRef> = emptyList(),
) {
    init {
        require(schema == EVIDENCE_PACK_SCHEMA_ID) {
            "schema must be '$EVIDENCE_PACK_SCHEMA_ID', was '$schema'"
        }
    }
}
