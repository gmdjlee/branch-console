package com.branchconsole.app.home

import android.content.Context
import com.branchconsole.app.notif.NotificationGate
import com.branchconsole.app.preview.PreviewResult
import com.branchconsole.app.tick.AssetConfigSource
import com.branchconsole.app.tick.PhaseDerivation
import com.branchconsole.engine.config.IndicatorRegistry
import com.branchconsole.engine.config.StatemachineConfigLoader
import com.branchconsole.lake.LakeDatabase
import com.branchconsole.lake.TickInputEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val PROFILE_NAME = "mobile_daily"
private const val TOP_INDICATOR_COUNT = 3
private const val UNRANKED_SEVERITY = -1

/** 상위 발화 지표 표시 항목(브리프 §2 "상위 지표") — `tick_input.severities_json`이 갖는
 * 유일한 정보(severity)만 표시한다. 원값·as_of는 확정 원장 스키마에 없어(M-49 감사 합집합에
 * 미포함) 표시할 수 없다 — M2에서 필요해지면 스키마 확장이 선행돼야 한다. */
data class TopIndicator(val id: String, val axis: String?, val severity: Int?)

/** D-23 §23.3-1 "이월 · as_of" 배지 대상 — 이번 프리뷰에서 신선 관측되지 못하고 직전 확정값을
 * 이어받은 지표(순수 결측과 구분, [com.branchconsole.app.preview.PreviewIndicatorState] KDoc
 * 참조). [carriedAsOfMillis]는 그 이월 원천 확정 틱의 날짜(UTC epoch millis). */
data class PreviewStaleIndicator(val id: String, val carriedAsOfMillis: Long?)

/** MT1-07 이관분 — 마지막 프리뷰 갱신 결과의 홈 표시용 투영. */
data class PreviewUiState(
    val filledComposite: Double?,
    val rawCoverage: Double,
    val suppressed: Boolean,
    val asOfEpochMillis: Long,
    val staleIndicators: List<PreviewStaleIndicator>,
)

/** MT1-08b 홈 화면이 소비하는 전체 뷰 상태. */
data class HomeUiState(
    val state: HomeState,
    val phase: String?,
    val composite: Double?,
    val confirmedCoverage: Double?,
    val topIndicators: List<TopIndicator>,
    val lastTickDate: String?,
    val lastTickIsCatchup: Boolean,
    val gapReason: String?,
    val lastRunStatus: String?,
    val lastRunDetail: String?,
    val registryVersion: String?,
    val notificationsEnabled: Boolean,
    val preview: PreviewUiState?,
)

/**
 * MT1-08b — Room(`:lake`)·assets(SSOT)에서 [HomeUiState]를 조립한다. [HomeStateMapper]가
 * 상태 분기를, 이 오브젝트는 그 상태에 딸린 표시 필드 조립을 맡는다(관심사 분리 — 분기 로직은
 * 이미 [HomeStateMapperTest]가 전건 검증).
 *
 * 국면 조회([PhaseDerivation.currentPhase])는 `confirm_time_kst` 없이도 동작한다
 * (`StateMachine.run`은 그 값을 쓰지 않는다 — 오직 [com.branchconsole.app.tick.
 * ConfirmTickConfigLoader]만 그 부재를 확정 틱 조립 시점에 실패시킨다) — 그래서 MT1-00g
 * 실측 대기 중에도 홈 화면은 이미 커밋된 국면을 정상 표시할 수 있다.
 */
internal object HomeData {
    suspend fun load(
        context: Context,
        db: LakeDatabase,
        previewResult: PreviewResult?,
    ): HomeUiState {
        val tickRows = db.tickInputDao().allOrderedByDate()
        val lastTick = tickRows.lastOrNull()
        val lastRunLog = db.runLogDao().allOrderedByRanAt().lastOrNull()
        val state = HomeStateMapper.compute(lastTick, lastRunLog, previewResult?.suppressed)

        val configSource = AssetConfigSource(context)
        val specs = runCatching { IndicatorRegistry.loadIndicatorSpecs(configSource) }.getOrDefault(emptyList())
        val axisMap = IndicatorRegistry.axisMap(specs)
        val registryVersion = runCatching { IndicatorRegistry.registryVersion(configSource) }.getOrNull()
        val phase =
            runCatching {
                val statemachineConfig = StatemachineConfigLoader.load(configSource)
                PhaseDerivation.currentPhase(db.tickInputDao(), PROFILE_NAME, statemachineConfig)
            }.getOrNull()

        return HomeUiState(
            state = state,
            phase = phase,
            composite = lastTick?.composite,
            confirmedCoverage = lastTick?.coverage,
            topIndicators = lastTick?.let { topIndicatorsOf(it, axisMap) }.orEmpty(),
            lastTickDate = lastTick?.tradingDate,
            lastTickIsCatchup = lastTick?.isCatchup ?: false,
            gapReason = lastTick?.gapReason,
            lastRunStatus = lastRunLog?.status,
            lastRunDetail = lastRunLog?.detail,
            registryVersion = registryVersion,
            notificationsEnabled = NotificationGate.isEnabled(context),
            preview = previewResult?.let(::toPreviewUiState),
        )
    }

    private fun toPreviewUiState(result: PreviewResult): PreviewUiState {
        val stale =
            result.indicators
                .filterValues { !it.observed && it.severity != null }
                .map { (id, indicator) -> PreviewStaleIndicator(id, indicator.carriedAsOfMillis) }
        return PreviewUiState(
            filledComposite = result.filledComposite,
            rawCoverage = result.rawCoverage,
            suppressed = result.suppressed,
            asOfEpochMillis = result.evaluatedAt.toEpochMilli(),
            staleIndicators = stale,
        )
    }

    private fun topIndicatorsOf(
        tick: TickInputEntity,
        axisMap: Map<String, String>,
    ): List<TopIndicator> {
        val severities =
            runCatching {
                Json.parseToJsonElement(tick.severitiesJson).jsonObject.mapValues { (_, v) ->
                    if (v == JsonNull) null else v.jsonPrimitive.int
                }
            }.getOrDefault(emptyMap())
        return severities.entries
            .sortedByDescending { it.value ?: UNRANKED_SEVERITY }
            .take(TOP_INDICATOR_COUNT)
            .map { (id, severity) -> TopIndicator(id, axisMap[id], severity) }
    }
}
