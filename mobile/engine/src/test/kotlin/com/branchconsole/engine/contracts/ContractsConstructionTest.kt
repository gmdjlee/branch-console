package com.branchconsole.engine.contracts

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * MT1-02b: direct (non-JSON) construction of every mirror data class.
 *
 * SnapshotContractsTest exercises these classes exclusively through
 * `Json.decodeFromString`, which kotlinx.serialization's compiler plugin routes through a
 * synthetic marker constructor distinct from the plain Kotlin constructor declared in
 * source — so the visible `<init>`/`require()` lines never register as executed that way.
 * This file calls the real constructors directly so the coverage measured by
 * `koverVerify` (`:engine` >= 90% line, docs/plans/M1_PLAN_B.md §3.2.1) reflects the
 * mirror's actual validation logic, not just the deserialization entrypoint.
 *
 * Also covers the constraints that have no invalid/ JSON fixture on the Python side
 * (KrImpact.kospiRangePct size 2, ScenarioSnapshot.targetMarket == "KR",
 * NewsCluster.representativeHeadlines <= 5, EvidencePack.schema literal) — these are real
 * `require()` guards in Snapshot.kt/Evidence.kt (§6.3) with no MT1-02a fixture to drive
 * them, so they're only provable here.
 */
class ContractsConstructionTest {
    private val instant = Instant.parse("2026-08-06T17:00:00Z")

    private fun firedIndicator() = FiredIndicator(id = "vix_level_z", axis = "vol", severity = 2, value = 28.4)

    private fun krImpact() =
        KrImpact(
            kospiRangePct = listOf(-8.5, -2.0),
            sectorsHit = listOf("semis"),
            sectorsDefensive = listOf("utilities"),
            usdkrwBias = UsdKrwBias.UP,
        )

    private fun scenario() =
        Scenario(
            name = "scenario_a",
            subjectiveProb = 0.5,
            narrative = "narrative",
            krImpact = krImpact(),
            leadingIndicators = listOf("a", "b"),
            invalidation = "invalidation",
            horizonDays = 30,
        )

    private fun triggerBlock() =
        TriggerBlock(
            phase = Phase.ORANGE,
            prevPhase = Phase.AMBER,
            compositeScore = 62.5,
            distinctAxes = 3,
            firedIndicators = listOf(firedIndicator()),
            evaluatedAt = instant,
        )

    private fun eventClassification() =
        EventClassification(
            type = EventType.MIXED,
            severity = ClassificationLevel.HIGH,
            confidence = ClassificationLevel.MEDIUM,
            rationale = "rationale",
        )

    private fun marketSnapshot() =
        MarketSnapshot(
            asOf = instant,
            kospi = 2410.5,
            kospiChg1dPct = -2.3,
            kospiDrawdown60dPct = -9.8,
            usdkrw = 1365.2,
            usdkrwChg1dPct = 1.1,
        )

    @Test
    fun `every mirror data class constructs directly with valid fields`() {
        val fired = firedIndicator()
        val trigger = triggerBlock()
        val classification = eventClassification()
        val impact = krImpact()
        val scenario = scenario()
        val snapshot =
            ScenarioSnapshot(
                generatedAt = instant,
                trigger = trigger,
                eventClassification = classification,
                scenarios = listOf(scenario, scenario),
                watchlistNext7d = listOf("FOMC"),
            )
        val market = marketSnapshot()
        val newsCluster =
            NewsCluster(
                topic = "carry_unwind",
                articleCount = 42,
                representativeHeadlines = listOf("headline one", "headline two"),
            )
        val macroEvent = MacroEvent(date = "2026-08-10", name = "FOMC")
        val analogueRef =
            AnalogueRef(
                eventId = "2024-08-05_carry_unwind",
                similarity = 0.82,
                outcomeSummary = "KOSPI 5d -6.2%",
            )
        val evidencePack =
            EvidencePack(
                builtAt = instant,
                phase = Phase.ORANGE,
                prevPhase = Phase.AMBER,
                compositeScore = 62.5,
                firedIndicators = listOf(fired),
                market = market,
                newsClusters = listOf(newsCluster),
                macroCalendar7d = listOf(macroEvent),
                analogues = listOf(analogueRef),
            )

        // touch generated members too (equals/hashCode/toString/copy all participate in
        // the same coverage measurement as the constructors above).
        assertConstructedAndSelfEqual(fired, fired.copy())
        assertConstructedAndSelfEqual(trigger, trigger.copy())
        assertConstructedAndSelfEqual(classification, classification.copy())
        assertConstructedAndSelfEqual(impact, impact.copy())
        assertConstructedAndSelfEqual(scenario, scenario.copy())
        assertConstructedAndSelfEqual(snapshot, snapshot.copy())
        assertConstructedAndSelfEqual(market, market.copy())
        assertConstructedAndSelfEqual(newsCluster, newsCluster.copy())
        assertConstructedAndSelfEqual(macroEvent, macroEvent.copy())
        assertConstructedAndSelfEqual(analogueRef, analogueRef.copy())
        assertConstructedAndSelfEqual(evidencePack, evidencePack.copy())
    }

    private fun <T> assertConstructedAndSelfEqual(
        value: T,
        copy: T,
    ) {
        check(value == copy) { "copy() must equal the original: $value vs $copy" }
        check(value.hashCode() == copy.hashCode()) { "copy() must hash equal: $value" }
        check(value.toString().isNotEmpty())
    }

    // ---- constraints with no invalid/ JSON fixture (only provable via direct construction) ----

    @Test
    fun `KrImpact rejects a kospiRangePct that is not exactly 2 elements`() {
        assertFailsWith<IllegalArgumentException> {
            KrImpact(
                kospiRangePct = listOf(-1.0),
                sectorsHit = listOf("tech"),
                sectorsDefensive = listOf("utilities"),
                usdkrwBias = UsdKrwBias.FLAT,
            )
        }
    }

    @Test
    fun `ScenarioSnapshot rejects a target_market other than KR`() {
        assertFailsWith<IllegalArgumentException> {
            ScenarioSnapshot(
                generatedAt = instant,
                targetMarket = "US",
                trigger = triggerBlock(),
                eventClassification = eventClassification(),
                scenarios = listOf(scenario(), scenario()),
                watchlistNext7d = emptyList(),
            )
        }
    }

    @Test
    fun `ScenarioSnapshot rejects a schema other than scenario-snapshot slash 1`() {
        assertFailsWith<IllegalArgumentException> {
            ScenarioSnapshot(
                schema = "scenario-snapshot/2",
                generatedAt = instant,
                trigger = triggerBlock(),
                eventClassification = eventClassification(),
                scenarios = listOf(scenario(), scenario()),
                watchlistNext7d = emptyList(),
            )
        }
    }

    @Test
    fun `NewsCluster rejects more than 5 representative headlines`() {
        assertFailsWith<IllegalArgumentException> {
            NewsCluster(
                topic = "carry_unwind",
                articleCount = 1,
                representativeHeadlines = List(6) { "headline $it" },
            )
        }
    }

    @Test
    fun `EvidencePack rejects a schema other than evidence-pack slash 1`() {
        assertFailsWith<IllegalArgumentException> {
            EvidencePack(
                schema = "evidence-pack/2",
                builtAt = instant,
                phase = Phase.GREEN,
                prevPhase = Phase.GREEN,
                compositeScore = 0.0,
                firedIndicators = emptyList(),
                market = marketSnapshot(),
            )
        }
    }
}
