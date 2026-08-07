package com.branchconsole.app.tick

import com.branchconsole.lake.ObservationDao
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * MT1-06b — 거래일 그리드. 정본 설계(docs/plans/M1_PLAN_B.md §5.5)는 3단계 폴백
 * (kotlin_krx business-day API → 로컬 캐시 → 관측된 KRX as_of 날짜 집합)을 규정하지만, 이
 * 구현은 **3단계(관측된 KRX as_of 날짜 집합)만** 채택한다 — KOSPI 종가는 확정 틱이 실행되기
 * 전에 이미 매일 수집되므로(WarmupBackfillOrchestrator·일일 수집 워커), "그 날짜의 KOSPI 관측이
 * lake에 있다"가 곧 "그날은 거래일이었고 우리가 그 사실을 안다"와 사실상 동치다. 휴장일에는
 * 애초에 KOSPI 관측이 존재하지 않으므로(휴장일 무커밋, 브리프 aaa 요건 6) 이 그리드만으로
 * 자동으로 충족된다.
 *
 * `ponytail`: 1단계 실시간 API 폴백은 의도적으로 생략한다 — 로그인·자격증명·네트워크가 필요해
 * 테스트 불가능(네트워크 금지 규율)해지고, KOSPI 수집이 실패한 실제 거래일과 진짜 휴장일을
 * 이 계층에서 구분하지 못하는 대가가 있다(K-19). 그 구분이 실제로 문제가 될 만큼(수집 실패가
 * 반복돼 그리드가 정체) 관측되면, `com.krxkt.KrxIndex.getBusinessDays`를 우선 조회하는 1단계
 * 소스를 추가한다 — 이 클래스의 시그니처(그리드 = `List<LocalDate>` 오름차순)는 그대로 둔 채
 * 내부 구현만 계층화하면 된다.
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
