package com.branchconsole.app.collectors.fred

import android.content.Context
import com.branchconsole.app.collectors.CollectFailureReason
import com.branchconsole.app.collectors.CollectOutcome
import com.branchconsole.app.collectors.Collector
import com.branchconsole.app.collectors.CollectorResult
import com.branchconsole.app.collectors.Observation
import com.branchconsole.app.collectors.SeriesFailure
import com.branchconsole.app.collectors.toCollectFailureReason
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * FRED 계열 2종(`BAMLH0A0HYM2`·`T10Y2Y`)을 [Collector] 공통 계약으로 감싼다(MT1-04g).
 *
 * `BAMLH0A0HYM2`는 호출 시점 기준 최근 3년 이전 구간을 요청하면 항상 빈 배열이 온다(00a 저널
 * §12.2) — 이 어댑터는 그 구간을 오류로 취급하지 않는다(HTTP 200 정상 응답). 반환된 관측치의
 * 최초 as_of가 요청 구간의 시작보다 늦어도 [CollectOutcome.Ok]로만 응답한다 — "구조적 부재"
 * 판정은 [WarmupBackfillOrchestrator]가 요청 구간 대 실제 커버리지를 비교해 리포트에서 매긴다.
 */
class FredCollector(
    private val fred: FredObservationsCollector,
    private val seriesIds: List<String> = DEFAULT_SERIES,
    private val nowProvider: () -> Instant = Instant::now,
) : Collector {
    override val id: String = "fred"

    override val expectedSeriesIds: List<String> = seriesIds

    override suspend fun collect(range: ClosedRange<LocalDate>): CollectOutcome {
        val rows = mutableListOf<Observation>()
        val failures = mutableListOf<SeriesFailure>()
        for (seriesId in seriesIds) {
            val failure = collectOne(seriesId, range, rows)
            if (failure != null) failures += failure
        }
        return if (failures.isEmpty()) CollectOutcome.Ok(rows) else CollectOutcome.Partial(rows, failures)
    }

    /**
     * 성공 시 [rows]에 직접 append하고 null을 반환한다. 실패 시 [SeriesFailure]를 반환한다.
     *
     * `fetchObservations()`가 예외를 던지는 유일한 경로는
     * [FredCredentialsProvider.apiKey]가 미설정 상태일 때다(`FredObservationsCollector`
     * 내부 — HTTP/네트워크/파싱 실패는 이미 [CollectorResult.Failed]로 흡수된다). 따라서 이
     * 경로에 도달하는 예외는 자격증명 부재로 매핑한다(`KrxCollector.ensureLoggedIn()`의
     * `credentialsProvider.get()` -> `NotConfigured` 매핑과 동일한 판단, 같은 `runCatching`
     * 형태라 SwallowedException 우려 없이 예외를 명시적으로 버린다).
     */
    private suspend fun collectOne(
        seriesId: String,
        range: ClosedRange<LocalDate>,
        rows: MutableList<Observation>,
    ): SeriesFailure? {
        val result =
            runCatching {
                fred.fetchObservations(
                    seriesId,
                    limit = FETCH_LIMIT,
                    sortOrder = "asc",
                    observationStart = range.start,
                    observationEnd = range.endInclusive,
                )
            }.getOrElse { return SeriesFailure(seriesId, CollectFailureReason.NotConfigured) }
        return when (result) {
            is CollectorResult.Success -> {
                rows += observationsOf(seriesId, result.value)
                null
            }
            is CollectorResult.Failed -> SeriesFailure(seriesId, result.toCollectFailureReason())
        }
    }

    private fun observationsOf(
        seriesId: String,
        series: FredSeriesObservations,
    ): List<Observation> =
        series.observations.mapNotNull { obs ->
            obs.value?.let {
                Observation(
                    seriesId = seriesId,
                    field = "value",
                    asOf = obs.asOf.atStartOfDay(ZoneOffset.UTC).toInstant(),
                    observedAt = nowProvider(),
                    source = "fred",
                    value = it,
                )
            }
        }

    companion object {
        /** `configs/indicators.yaml`의 fred 계열 2종(hy_oas_delta·ust_2s10s_move 본계열). */
        val DEFAULT_SERIES = listOf("BAMLH0A0HYM2", "T10Y2Y")

        // FRED series/observations limit — 계정 API 상한(100000)보다 훨씬 작게, 하지만
        // warmup_padding_days(달력일)를 영업일로 환산해도 넉넉히 덮는 크기. 임계값/가중치가
        // 아니라 HTTP 페이지 크기 상한이라 CLAUDE.md §1 SSOT 규율 대상이 아니다.
        private const val FETCH_LIMIT = 2000

        fun create(
            context: Context,
            credentials: FredCredentialsProvider,
        ): FredCollector = FredCollector(FredObservationsCollector.create(context, credentials))
    }
}
