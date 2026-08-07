package com.branchconsole.app.collectors.ecos

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

/** lake seriesId — `configs/indicators.yaml`의 `item_codes` 키 이름을 그대로 쓴다(원시 ECOS
 * item_code 숫자열이 아니라 의미 있는 이름을 원계열 id로 채택, `kospi_investor_trading`류 선례). */
const val SERIES_CORP_AA3Y = "corp_aa3y"
const val SERIES_KTB_3Y = "ktb_3y"
private const val FIELD_VALUE = "value"
private const val SOURCE = "ecos"

/**
 * ECOS `817Y002`(시장금리 일별) 2계열(`corp_aa3y`·`ktb_3y`) 수집 어댑터 (MT1-04d, 00b 저널 §7.9
 * 실측 확정 계약).
 *
 * **`krx_credit_spread_delta` 지표 자체는 이 클래스가 배선하지 않는다** — 이 수집기는 원시
 * 관측치를 lake에 적재하는 데이터 계층까지만 책임진다. 그 지표의 severity 계산
 * (`delta_bp(corp_aa3y - ktb_3y, lookback=5)`)은 `backtest/run_replay.py`의 `_BUILDERS`에도
 * (`_ALWAYS_MISSING_INDICATORS`에 여전히 있음, BT-01 수집 범위 밖이라는 이유로) 존재하지
 * 않는 계산이다 — Python 정본에 없는 로직을 모바일에서 먼저 만들면 BT-05 패리티가 이를 검증할
 * golden이 없어 무회귀 게이트를 벗어난 채로 배포된다(`ConfirmIndicatorRuntime.kt`/`ParityEngine.kt`
 * 공통 KDoc "재구현 없음" 규율 위반). 그래서 `ConfirmSeriesIds.ALWAYS_MISSING_INDICATORS`·
 * `ConfirmIndicatorRuntime`은 이번 서브태스크에서 의도적으로 손대지 않는다 — Advisor 보고 참조.
 */
class EcosCollector(
    private val ecos: EcosObservationsCollector,
    private val statCode: String,
    private val itemCodes: EcosSeriesConfig.ItemCodes,
    private val nowProvider: () -> Instant = Instant::now,
) : Collector {
    override val id: String = "ecos"

    override val expectedSeriesIds: List<String> = listOf(SERIES_CORP_AA3Y, SERIES_KTB_3Y)

    override suspend fun collect(range: ClosedRange<LocalDate>): CollectOutcome {
        val rows = mutableListOf<Observation>()
        val failures = mutableListOf<SeriesFailure>()
        for ((seriesId, itemCode) in listOf(SERIES_CORP_AA3Y to itemCodes.corpAa3y, SERIES_KTB_3Y to itemCodes.ktb3y)) {
            val failure = collectOne(seriesId, itemCode, range, rows)
            if (failure != null) failures += failure
        }
        return if (failures.isEmpty()) CollectOutcome.Ok(rows) else CollectOutcome.Partial(rows, failures)
    }

    /**
     * 성공 시 [rows]에 직접 append하고 null을 반환한다. 실패 시 [SeriesFailure]를 반환한다.
     *
     * `fetchSeries()`가 예외를 던지는 유일한 경로는 [EcosCredentialsProvider.apiKey]가 미설정
     * 상태일 때다([FredCollector.collectOne]의 동일 `runCatching` 판단 — ECOS는 옵션 키라 "선택
     * 미발급" 상태를 흔히 겪는다, `CredentialsStore` KDoc).
     */
    private suspend fun collectOne(
        seriesId: String,
        itemCode: String,
        range: ClosedRange<LocalDate>,
        rows: MutableList<Observation>,
    ): SeriesFailure? {
        val result =
            runCatching { ecos.fetchSeries(itemCode, statCode, range.start, range.endInclusive) }
                .getOrElse { return SeriesFailure(seriesId, CollectFailureReason.NotConfigured) }
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
        series: EcosSeriesObservations,
    ): List<Observation> {
        val observedAt = nowProvider()
        return series.observations.map { obs ->
            Observation(
                seriesId = seriesId,
                field = FIELD_VALUE,
                asOf = obs.asOf.atStartOfDay(ZoneOffset.UTC).toInstant(),
                observedAt = observedAt,
                source = SOURCE,
                value = obs.value,
            )
        }
    }

    companion object {
        /** K-04 SSOT를 assets에서 로드해 조립하는 지점 — `KrxCollector.create`와 동일 패턴. */
        fun create(
            context: Context,
            credentials: EcosCredentialsProvider,
        ): EcosCollector {
            val (statCode, itemCodes) = EcosSeriesConfig.load(context)
            return EcosCollector(EcosObservationsCollector.create(context, credentials), statCode, itemCodes)
        }
    }
}
