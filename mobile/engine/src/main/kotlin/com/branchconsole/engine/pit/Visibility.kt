package com.branchconsole.engine.pit

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * `visible_at` 파생 함수 — `run_replay.py`의 `raw_visibility_grid_day`/`visibility_tick_utc`/
 * `combined_visibility_utc`를 프로덕션 파생 함수로 이식(docs/plans/M1_PLAN_A.md §2.8,
 * M1_PLAN_D.md §2.5). **저장하지 않는다** — `(kind, as_of, 거래일 그리드, lag, 확정 시각)`의
 * 순수 함수다: 거래일 그리드가 정정돼도 항상 현재 그리드로 옳은 값을 내고, 캐치업·프리뷰·
 * 리플레이·패리티가 같은 함수 하나를 공유한다.
 *
 * K-05: `java.time`만 사용한다(Instant/LocalDate/LocalTime/OffsetDateTime) —
 * kotlinx-datetime은 계약 미러 전용(브리프 지시). KST는 고정 오프셋(+9, DST 없음)이라
 * [ZoneOffset]으로 충분하다(K-06 — 크론은 KST 고정, 데이터는 as_of로 정렬).
 */
object Visibility {
    private val KST = ZoneOffset.ofHours(9)

    fun kstToUtc(
        day: LocalDate,
        timeOfDay: LocalTime,
    ): Instant = OffsetDateTime.of(day, timeOfDay, KST).toInstant()

    /** as_of(T)가 최초로 "알려지는" 그리드일. `calendarKind`별 규칙(§2.5.1 `L` 표). */
    fun visDay(
        kind: CalendarKind,
        asOf: LocalDate,
        grid: List<LocalDate>,
        fredLagDays: Long = 0,
    ): LocalDate? =
        when (kind) {
            CalendarKind.US_MARKET -> TradingDayGrid.firstAfter(grid, asOf)
            CalendarKind.FRED -> TradingDayGrid.firstOnOrAfter(grid, asOf.plusDays(fredLagDays))
            CalendarKind.KRX, CalendarKind.FX -> TradingDayGrid.firstOnOrAfter(grid, asOf)
        }

    /** 단일 계열의 가시 시각. `confirmTimeKst`는 mobile_daily 프로파일의 확정 틱 시각(SSOT —
     * 리터럴 금지, 호출자가 주입). 그리드 밖이면 null. */
    fun visibleAt(
        kind: CalendarKind,
        asOf: LocalDate,
        grid: List<LocalDate>,
        fredLagDays: Long,
        confirmTimeKst: LocalTime,
    ): Instant? = visDay(kind, asOf, grid, fredLagDays)?.let { kstToUtc(it, confirmTimeKst) }

    data class VisibilityInput(val kind: CalendarKind, val fredLagDays: Long = 0)

    /**
     * 2계열 이상을 쓰는 지표의 결합 가시 시각 = 각 계열 자기 kind 규칙의 최댓값(worst-of-
     * inputs — 둘 다 알려져야 그 날짜의 결합값을 안다). 하나라도 null이면 결합값도 null.
     * `combined_visibility_utc` 1:1 이식. 단일 계열 지표도 원소 1개인 특수 케이스로 이 경로를
     * 탄다(분기 없음).
     */
    fun combinedVisibleAt(
        inputs: List<VisibilityInput>,
        asOf: LocalDate,
        grid: List<LocalDate>,
        confirmTimeKst: LocalTime,
    ): Instant? {
        val timestamps = inputs.map { visibleAt(it.kind, asOf, grid, it.fredLagDays, confirmTimeKst) }
        if (timestamps.any { it == null }) return null
        return timestamps.filterNotNull().max()
    }

    /**
     * 스테일 판정 — **초과만 stale, 등호는 fresh**(`engine_ref/registry.py:323`,
     * `run_replay.py:369` 둘 다 `>`). 파리티 지뢰: `>=`로 구현하면 경과가 창과 정확히 같은
     * 경계 틱(확정 틱 간격이 24h 배수라 실제로 발생)에서 그 지표가 통째로 결측 처리된다.
     */
    fun isStale(
        evaluatedAt: Instant,
        visibleAt: Instant,
        window: Duration,
    ): Boolean = Duration.between(visibleAt, evaluatedAt) > window
}
