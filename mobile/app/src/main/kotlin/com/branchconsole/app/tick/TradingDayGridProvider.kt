package com.branchconsole.app.tick

import com.branchconsole.lake.ObservationDao
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * MT1-06b — 거래일 그리드. 정본 설계(docs/plans/M1_PLAN_B.md §5.5)는 3단계 폴백
 * (kotlin_krx business-day API → 로컬 캐시 → 관측된 KRX as_of 날짜 집합)을 규정하지만, 이
 * 구현은 **3단계(관측된 KRX as_of 날짜 집합)만** 채택한다.
 *
 * **정정(qa 반려 후속, f48d838의 해당 서술은 허위였다)**: 이전 판은 "KOSPI 종가는 확정 틱이
 * 실행되기 전에 이미 매일 수집되므로(WarmupBackfillOrchestrator·일일 수집 워커)"라고 적었으나,
 * 같은 시점 [ConfirmTickWorker.dailyCollect]는 no-op이고 실제 일일 수집 배선은 **MT1-08c/08d로
 * 이관 확정**된 상태다(aaa F-3) — "일일 수집 워커가 이미 있다"는 전제 자체가 그 시점에 사실이
 * 아니었다. 배선 전에는 이 그리드가 실제로 보는 것은 (a) [com.branchconsole.app.collectors.
 * WarmupBackfillOrchestrator]의 초기 백필 적재분과 (b) 캐치업 시 수동/외부 트리거로 들어온
 * 수집분뿐이다 — "그 날짜의 KOSPI 관측이 lake에 있다"가 "그날은 거래일이었다"의 필요조건이지,
 * "매일 수집이 반드시 성공해 그 사실을 안다"는 보장이 아니다. 수집이 실패해 생기는 그리드
 * 공백은 [ConfirmTickCandidates.logSuspectedGaps]가 `CALENDAR_FALLBACK`으로 기록하고, 나중에
 * 관측이 도착하면 [ConfirmTickCandidates.forOngoing]이 재편입한다(F-5) — "무기록·비가역 결손"이
 * 아니라 "지연 허용 + 기록 + 재시도"다. 일일 수집이 실제로 배선되면(MT1-08c/08d) 이 문단의
 * (b) 의존도가 낮아질 뿐 클래스 계약 자체는 바뀌지 않는다.
 *
 * `ponytail`: 1단계 실시간 API 폴백은 의도적으로 생략한다 — 로그인·자격증명·네트워크가 필요해
 * 테스트 불가능(네트워크 금지 규율)해지고, KOSPI 수집이 실패한 실제 거래일과 진짜 휴장일을
 * 이 계층에서 확신 있게 구분하지 못하는 대가가 있다(K-19, 위 CALENDAR_FALLBACK이 그 불확실성을
 * 감사 가능하게만 만든다). 그 구분이 실제로 문제가 될 만큼(수집 실패가 반복돼 그리드가 정체)
 * 관측되면, `com.krxkt.KrxIndex.getBusinessDays`를 우선 조회하는 1단계 소스를 추가한다 — 이
 * 클래스의 시그니처(그리드 = `List<LocalDate>` 오름차순)는 그대로 둔 채 내부 구현만
 * 계층화하면 된다.
 */
internal class TradingDayGridProvider(private val observationDao: ObservationDao) {
    /** [to] 이하의 관측된 거래일 전체(오름차순, 중복 없음). */
    suspend fun tradingDaysUpTo(to: LocalDate): List<LocalDate> {
        val toAsOf = to.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val rows =
            observationDao.confirmSeries(ConfirmSeriesIds.KOSPI, ConfirmSeriesIds.FIELD_CLOSE, Long.MIN_VALUE, toAsOf)
        return rows.map { Instant.ofEpochMilli(it.asOf).atZone(ZoneOffset.UTC).toLocalDate() }.distinct().sorted()
    }
}
