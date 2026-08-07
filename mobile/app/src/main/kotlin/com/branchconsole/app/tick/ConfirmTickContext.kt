package com.branchconsole.app.tick

import com.branchconsole.engine.pit.CalendarKind
import com.branchconsole.engine.pit.KnownSeries
import com.branchconsole.engine.pit.Visibility
import com.branchconsole.lake.ObservationDao
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * `readSeriesForTick`의 프로덕션 조립 지점(§5.4.1) — [ObservationDao.confirmSeries]로 원계열
 * 시계열을 **한 번에 전부** 읽어 메모리에 올린 뒤, 이후 계산(13종 빌더·가시성·severity)은
 * [ConfirmIndicatorRuntime]의 순수 함수가 담당한다(BT-05 `ParityContext`와 동형 — §5.4.1(3)
 * "①의 컷오프 절단은 안전하다"에 따라, 배치(캐치업) 안의 **가장 늦은 날짜** 하나의 컷오프로
 * 전체를 한 번만 읽고 그 안의 모든 날짜를 재평가해도 각 날짜의 `evaluatedAt`이 각자의 가시성만
 * 골라내므로 비트 동일하다).
 *
 * `fromAsOf`는 하한을 두지 않는다([Long.MIN_VALUE]) — MT1-00f 실측(인덱스 탐색만, 풀스캔 없음)에
 * 따라 모바일 관측 테이블 규모(계열당 최대 수년 치 일봉)에서 이 하한 생략의 성능 비용은
 * 무의미하다. `ponytail`: observation이 무기한 누적돼 지연이 실측되면 그때
 * `warmup_padding_days`(engine.warmup_padding_days) 만큼만 뒤로 자르는 하한을 추가한다 — 지금은
 * 불필요한 조기 최적화다.
 */
internal class ConfirmTickContext private constructor(
    private val bySeries: Map<Pair<String, String>, List<Pair<LocalDate, Double>>>,
    private val grid: List<LocalDate>,
    private val confirmTimeKst: java.time.LocalTime,
    private val fredLagDays: Map<String, Long>,
) {
    fun series(
        seriesId: String,
        field: String,
    ): Pair<List<LocalDate>, DoubleArray> {
        val rows = bySeries[seriesId to field].orEmpty()
        return rows.map { it.first } to rows.map { it.second }.toDoubleArray()
    }

    fun byDate(
        seriesId: String,
        field: String,
    ): Map<LocalDate, Double> = bySeries[seriesId to field].orEmpty().toMap()

    /** 웜업 게이트(§2.11) — 이 계열이 이 배치 컷오프까지 실제로 가진 원계열 행 수. */
    fun rowCount(
        seriesId: String,
        field: String,
    ): Int = bySeries[seriesId to field].orEmpty().size

    fun known(
        dates: List<LocalDate>,
        values: DoubleArray,
        inputIds: List<String>,
    ): KnownSeries {
        val inputs =
            inputIds.map { Visibility.VisibilityInput(ConfirmSeriesIds.calendarKindOf(it), fredLagDays[it] ?: 0L) }
        return KnownSeries.build(dates, values) { d ->
            Visibility.combinedVisibleAt(inputs, d, grid, confirmTimeKst)
        }
    }

    companion object {
        private fun asOfCutoffMillis(
            kind: CalendarKind,
            seriesId: String,
            asOfCutoffDate: LocalDate,
            fredLagDays: Map<String, Long>,
        ): Long {
            val cutoffDate =
                when (kind) {
                    CalendarKind.US_MARKET -> asOfCutoffDate.minusDays(1)
                    CalendarKind.FRED -> asOfCutoffDate.minusDays(fredLagDays[seriesId] ?: 0L)
                    CalendarKind.KRX, CalendarKind.FX -> asOfCutoffDate
                }
            return cutoffDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }

        /**
         * @param grid 거래일 그리드([TradingDayGridProvider]) — 가시성 계산용.
         * @param asOfCutoffDate 이 배치에서 처리할 **가장 늦은** 거래일(보통 오늘) — §5.4 컷오프의
         *   기준. 이보다 이른 날짜들의 평가는 같은 컨텍스트를 재사용해도 안전하다(클래스 문서 참조).
         */
        suspend fun load(
            observationDao: ObservationDao,
            grid: List<LocalDate>,
            config: ConfirmTickConfig,
            asOfCutoffDate: LocalDate,
            seriesFields: List<Pair<String, String>> = ConfirmSeriesIds.REQUIRED_SERIES_FIELDS,
        ): ConfirmTickContext {
            val loaded = mutableMapOf<Pair<String, String>, List<Pair<LocalDate, Double>>>()
            for ((seriesId, field) in seriesFields) {
                val kind = ConfirmSeriesIds.calendarKindOf(seriesId)
                val cutoffMillis = asOfCutoffMillis(kind, seriesId, asOfCutoffDate, config.fredLagDays)
                val rows = observationDao.confirmSeries(seriesId, field, Long.MIN_VALUE, cutoffMillis)
                loaded[seriesId to field] =
                    rows.map { Instant.ofEpochMilli(it.asOf).atZone(ZoneOffset.UTC).toLocalDate() to it.value }
            }
            return ConfirmTickContext(loaded, grid, config.confirmTimeKst, config.fredLagDays)
        }
    }
}
