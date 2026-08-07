package com.branchconsole.app.tick

import com.branchconsole.engine.config.IndicatorSpec
import com.branchconsole.engine.scoring.Scoring
import com.branchconsole.engine.statemachine.Tick
import com.branchconsole.lake.TickInputEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.encodeToJsonElement
import java.time.Instant
import java.time.LocalDate

private fun cadenceOf(spec: IndicatorSpec): String = spec.source["cadence"] as? String ?: ""

/**
 * 확정 틱 1건 평가 결과 — `ParityEngine.evaluateTick`의 프로덕션 대응(단일 날짜, JSON export
 * 없음). `severities`는 indicators.yaml 선언 순서(LinkedHashMap)를 유지한다(부동소수 누적 순서
 * 파리티, §2.7 파리티 지뢰 3).
 */
internal data class TickEvaluation(
    val tradingDate: LocalDate,
    val evaluatedAt: Instant,
    val composite: Double?,
    val coverage: Double,
    val distinctAxes: Int,
    val anyCrit: Boolean,
    val anyExtreme: Boolean,
    val firedAxes: List<String>,
    val severities: Map<String, Int?>,
    val visibleAtByIndicator: Map<String, String?>,
) {
    fun toTick(): Tick {
        return Tick(composite = composite, distinctAxes = distinctAxes, anyCrit = anyCrit, anyExtreme = anyExtreme)
    }

    /** `tick_input`(M-49 감사 합집합) 프로덕션 행 — [warmupStatusJson]은 배치 실행 시점에
     * [WarmupGate]가 산출한 상태(대상 지표 전부가 게이트를 통과한 이후에도 개별 지표의 웜업
     * 진행률을 계속 감사할 수 있게 매 틱 기록한다, D-D4 "온보딩 진행률로 노출"). */
    fun toEntity(
        isCatchup: Boolean,
        registryVersion: String,
        frozenAt: Long,
        warmupStatusJson: String?,
    ): TickInputEntity =
        TickInputEntity(
            tradingDate = tradingDate.toString(),
            composite = composite,
            distinctAxes = distinctAxes,
            anyCrit = anyCrit,
            anyExtreme = anyExtreme,
            severitiesJson = encodeSeverities(severities),
            coverage = coverage,
            registryVersion = registryVersion,
            gapReason = null,
            frozenAt = frozenAt,
            firedAxes = encodeStringList(firedAxes),
            visibleAtByIndicator = encodeNullableStringMap(visibleAtByIndicator),
            isCatchup = isCatchup,
            warmupStatusJson = warmupStatusJson,
            pitQuality = if (isCatchup) "approximate_pit" else "live",
        )
}

private fun encodeSeverities(severities: Map<String, Int?>): String =
    JsonObject(severities.mapValues { (_, v) -> if (v == null) JsonNull else JsonPrimitive(v) }).toString()

private fun encodeNullableStringMap(map: Map<String, String?>): String =
    JsonObject(map.mapValues { (_, v) -> if (v == null) JsonNull else JsonPrimitive(v) }).toString()

private fun encodeStringList(values: List<String>): String =
    buildJsonArray { values.forEach { add(Json.encodeToJsonElement(it)) } }.toString()

internal object ConfirmTickEvaluator {
    fun evaluate(
        date: LocalDate,
        evaluatedAt: Instant,
        config: ConfirmTickConfig,
        runtimes: Map<String, Runtime>,
    ): TickEvaluation {
        val resolved = LinkedHashMap<String, Resolved>()
        for (spec in config.specs) {
            val staleWindow = config.staleWindows.getValue(cadenceOf(spec))
            resolved[spec.id] =
                resolveSeverity(spec, runtimes.getValue(spec.id), evaluatedAt, staleWindow, config.modifiers)
        }
        val severities = LinkedHashMap<String, Int?>()
        resolved.forEach { (id, r) -> severities[id] = r.severity }

        val anyCrit = severities.values.any { it != null && it >= 3 }
        val anyExtreme = resolved.values.any { it.isExtreme }
        val distinctAxes = Scoring.distinctAxes(severities, config.axes)
        val composite = Scoring.computeComposite(severities, config.weights, config.maxSeverities)
        val firedAxes =
            severities.entries
                .filter { (_, s) -> s != null && s >= 2 }
                .mapNotNull { config.axes[it.key] }
                .toSortedSet()
                .toList()

        val visibleAtByIndicator = LinkedHashMap<String, String?>()
        for (spec in config.specs) {
            val looked = runtimes.getValue(spec.id).primaryKnown()?.lookup(evaluatedAt)
            visibleAtByIndicator[spec.id] = looked?.visibleAt?.toString()
        }

        return TickEvaluation(
            tradingDate = date,
            evaluatedAt = evaluatedAt,
            composite = composite.score,
            coverage = composite.coverage,
            distinctAxes = distinctAxes,
            anyCrit = anyCrit,
            anyExtreme = anyExtreme,
            firedAxes = firedAxes,
            severities = severities,
            visibleAtByIndicator = visibleAtByIndicator,
        )
    }
}
