package com.branchconsole.app.collectors.ecos

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * ECOS `StatisticSearch` 응답 DTO (00b 저널 §7.3/§7.5 실측 계약 그대로).
 *
 * 정상 응답은 최상위 키가 `StatisticSearch`이고, 오류·무데이터 응답은 `RESULT`(`CODE`/`MESSAGE`)
 * 봉투만 온다(§7.5 — 이번 실측 4회로 직접 재현하지 못한 방어적 분기, 추측과 구분해 기록). 두
 * 키가 상호배타적으로 실려 오므로 하나의 DTO에 둘 다 옵션으로 선언해 어느 쪽이 왔는지로
 * 분기한다(`ignoreUnknownKeys`와 함께 kotlinx.serialization이 부재 키를 null 기본값으로 채운다).
 */
@Serializable
internal data class EcosSearchEnvelope(
    @SerialName("StatisticSearch") val statisticSearch: EcosSearchBody? = null,
    @SerialName("RESULT") val result: EcosResultEnvelope? = null,
)

@Serializable
internal data class EcosSearchBody(
    @SerialName("list_total_count") val listTotalCount: Int = 0,
    val row: List<EcosRowDto> = emptyList(),
)

@Serializable
internal data class EcosRowDto(
    @SerialName("TIME") val time: String,
    @SerialName("DATA_VALUE") val dataValue: String,
)

@Serializable
internal data class EcosResultEnvelope(
    @SerialName("CODE") val code: String = "",
    @SerialName("MESSAGE") val message: String = "",
)

/**
 * 어댑터가 반환하는 관측치 하나. FRED와 달리(§7.3 "결측/휴장 표기 방식... FRED와 다름") 값이
 * `"."` 같은 결측 마커로 오지 않는다 — 그 날짜의 행 자체가 없으면 결측이고, 온 행은 항상 실제
 * 숫자값을 담는다. 그래서 [value]는 non-null이다(`FredObservation`과의 의도적 비대칭).
 */
data class EcosObservation(val asOf: LocalDate, val value: Double)

data class EcosSeriesObservations(val itemCode: String, val observations: List<EcosObservation>)
