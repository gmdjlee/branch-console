package com.branchconsole.app.tick

import com.branchconsole.engine.pit.Visibility
import com.branchconsole.lake.RunLogDao
import com.branchconsole.lake.RunLogEntity
import com.branchconsole.lake.TickInputEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

/**
 * MT1-06 후보 거래일 산출 — [ConfirmTickRunner]에서 분리한 이유는 순수하게 함수 개수(detekt
 * `TooManyFunctions`, 클래스당 임계 11)다: "무엇을 커밋할지 고르는 규칙"과 "고른 것을 어떻게
 * 커밋하는지"는 어차피 서로 다른 책임이라 분리가 자연스럽다.
 */
internal object ConfirmTickCandidates {
    /**
     * D-D4 부트스트랩(§7): `tick_input`이 완전히 비어 있는 최초 실행은 "놓친 과거"가 없다 —
     * 그리드 전체를 후보로 두면 설치 첫날 실행이 20틱 소급 + 가짜 `CATCHUP_GAP_TRUNCATED`로
     * 둔갑한다(aaa F-4). 최신 거래일 1건만 후보로 좁힌다(소급 0·gap 행 0).
     */
    fun forBootstrap(
        grid: List<LocalDate>,
        today: LocalDate,
        ranAt: Instant,
        confirmTimeKst: LocalTime,
    ): List<LocalDate> = listOfNotNull(grid.lastOrNull { eligible(it, today, ranAt, confirmTimeKst) })

    /**
     * 이후(비부트스트랩)에는 순수 날짜 비교(`> lastCommittedDate`) 대신 **미커밋 집합 차집합**을
     * 쓴다(aaa F-5) — grid 공백으로 후보가 되지 못했던 날짜가 나중에 관측되면, 그 사이 더 늦은
     * 날짜가 이미 커밋돼 있어도 다시 후보가 될 수 있어야 한다.
     *
     * 그런데 이 차집합에는 **하한이 반드시 있어야 한다** — 두 가지 서로 다른 이유로 생기는
     * 두 하한을 [candidateFloor]에서 합친다(aaa F-4R, F-4가 2연속 FAIL 끝에 재분류 직전까지
     * 간 지점이라 경계를 여기 명문화한다):
     *
     * 1. **부트스트랩 경계(`bootstrapFloor`)** — [forBootstrap]은 최초 실행에서 그리드의 마지막
     *    날짜 **1건만** 커밋하고 gap 행을 만들지 않는다(D-D4, F-4). 그 이전 날짜들은 "공백"이
     *    아니라 애초에 추적 대상이 아니었던 **부트스트랩 이전 역사**다 — "놓친 적이 없다"가
     *    "놓친 적이 없으므로 영원히 후보가 아니다"로 이어지려면, `closedBoundary`(gap 행이
     *    있을 때만 존재) 하나로는 부족하다: gap 행이 0건인 상태(부트스트랩 직후)에서
     *    `closedBoundary == null`이면 하한이 통째로 사라져, 재실행마다 부트스트랩 이전 25일
     *    백로그가 다시 후보가 되고 20틱 소급 + 허위 `CATCHUP_GAP_TRUNCATED`를 만든다(비평가
     *    재현 확인). `committed`가 한 번이라도 비지 않은 이상 `committed.minOfOrNull(...)`은
     *    항상 그 첫 커밋일과 같다 — append-only라 그 최솟값은 절대 낮아지지 않으므로, 이 값이
     *    곧 "부트스트랩이 그은 영구 경계"다.
     * 2. **캐치업 절단 경계(`closedBoundary`)** — `catchup_max_ticks` 초과로 **의도적으로**
     *    영구 제외된 구간(gap 행의 날짜)까지 되살리면 M-34의 "공백은 공백으로 남긴다" 계약이
     *    깨진다.
     *
     * 실제 하한은 `max(bootstrapFloor, closedBoundary)`다(gap은 항상 부트스트랩 이후에만
     * 발생하므로 보통 `closedBoundary`가 있으면 그것이 더 크지만, `maxOf`로 어느 쪽이 이겨도
     * 안전하게 방어한다). **재편입(F-5)은 이 하한보다 늦은 날짜에서만 성립한다** — 하한 이하
     * 날짜는 이미 커밋됐든 아니든 구조적으로 후보 자격이 없다(criterion (a)). 하한보다 늦은
     * 미커밋 날짜는 그 사이 더 늦은 날짜가 커밋됐어도 재편입된다(criterion (b), F-5 그대로).
     */
    fun forOngoing(
        grid: List<LocalDate>,
        committed: List<TickInputEntity>,
        today: LocalDate,
        ranAt: Instant,
        confirmTimeKst: LocalTime,
    ): List<LocalDate> {
        val floor = candidateFloor(committed)
        val realDates = realDatesOf(committed)
        return grid.filter { d ->
            (floor == null || d > floor) &&
                d.toString() !in realDates &&
                eligible(d, today, ranAt, confirmTimeKst)
        }
    }

    /**
     * aaa F-5 — B §5.5 "안전 기본값"(휴장은 스킵, 실패는 이력)의 실행: 마지막 참고점(마지막 gap
     * 경계 또는 마지막 실틱) 이후 ~ 오늘까지의 **평일**인데 그리드에 없는 날짜는 "휴장인지 수집
     * 실패인지 이 계층에서 확신할 수 없다"(K-03, 실시간 영업일 API 미배선 —
     * [TradingDayGridProvider] 문서 참조) — 그래서 `CALENDAR_FALLBACK`(주말만 스킵하는 보수
     * 규칙, docs/plans/M1_PLAN_C.md §4.1 카탈로그 그대로)으로 매 실행 기록한다. **주말은 절대
     * 플래그하지 않는다**(수집 실패 오탐 방지 — witness가 이 구분을 고정한다). 부트스트랩(비교할
     * 과거 기준점이 없는 최초 실행)은 호출부에서 건너뛴다.
     */
    suspend fun logSuspectedGaps(
        grid: List<LocalDate>,
        committed: List<TickInputEntity>,
        today: LocalDate,
        ranAt: Instant,
        runLogDao: RunLogDao,
    ) {
        val scanFrom =
            closedBoundaryOf(committed)?.plusDays(1)
                ?: committed.minOfOrNull { LocalDate.parse(it.tradingDate) }
                ?: return
        if (scanFrom.isAfter(today)) return
        val gridSet = grid.toSet()
        val suspected =
            generateSequence(scanFrom) { it.plusDays(1) }
                .takeWhile { !it.isAfter(today) }
                .filter { it.dayOfWeek !in WEEKEND && it !in gridSet }
                .toList()
        if (suspected.isNotEmpty()) {
            runLogDao.insert(
                RunLogEntity(
                    tradingDate = null,
                    ranAt = ranAt.toEpochMilli(),
                    status = "CALENDAR_FALLBACK",
                    detail = "suspected gaps (weekday, no anchor observation, not confirmed as holiday): $suspected",
                ),
            )
        }
    }

    private fun eligible(
        date: LocalDate,
        today: LocalDate,
        ranAt: Instant,
        confirmTimeKst: LocalTime,
    ): Boolean = !date.isAfter(today) && !ranAt.isBefore(Visibility.kstToUtc(date, confirmTimeKst))

    /** aaa F-4R — [forOngoing]의 실제 하한. `committed`가 비어 있지 않은 한(=비부트스트랩 호출
     * 전제) 항상 non-null이다: 부트스트랩이 정확히 1행을 커밋하므로 `committed`가 절대 빌 수
     * 없고, append-only라 최솟값은 절대 낮아지지 않는다. */
    private fun candidateFloor(committed: List<TickInputEntity>): LocalDate? {
        val bootstrapFloor = committed.minOfOrNull { LocalDate.parse(it.tradingDate) }
        val closedBoundary = closedBoundaryOf(committed)
        return listOfNotNull(bootstrapFloor, closedBoundary).maxOrNull()
    }

    private fun closedBoundaryOf(committed: List<TickInputEntity>): LocalDate? =
        committed.filter { it.gapReason != null }.maxOfOrNull { LocalDate.parse(it.tradingDate) }

    private fun realDatesOf(committed: List<TickInputEntity>): Set<String> =
        committed.filter { it.gapReason == null }.map { it.tradingDate }.toSet()
}
