package com.branchconsole.app.collectors.fred

import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * FRED `series/observations` 응답 DTO (00a 저널 §9~§13 실측 계약 그대로).
 */
@Serializable
internal data class FredObservationsEnvelope(
    val count: Int = 0,
    val observations: List<FredObservationDto> = emptyList(),
)

@Serializable
internal data class FredObservationDto(
    val date: String,
    val value: String,
)

/**
 * 어댑터가 반환하는 관측치 하나. [value]가 `null`이면 FRED가 `"."`(명시적 결측, 00a §13 —
 * 예: `T10Y2Y`의 공휴일)로 응답한 것이다. 값이 있는 경우 그것이 신규 관측인지 전일값 반복
 * (`BAMLH0A0HYM2`의 공휴일 이연값, 00a §12.3-2)인지는 이 계층에서 구분하지 않는다 —
 * 추측으로 결측 처리하지 않고 FRED가 준 값 그대로 저장한다(브리프 규율, transform 로직은
 * 건드리지 않음). 응답 배열에 아예 없는 날짜(주말 등, §12.3-1)는 레코드 자체를 만들지 않는다
 * — 호출부가 달력일 기준으로 빈 칸을 채우려 하면 안 된다.
 */
data class FredObservation(
    val asOf: LocalDate,
    val value: Double?,
)

data class FredSeriesObservations(
    val seriesId: String,
    val observations: List<FredObservation>,
)
