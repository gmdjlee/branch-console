package com.branchconsole.app.collectors

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 계열 하나의 웜업 상태 (MT1-04g). `tick_input.warmup_status_json`(M-45/46 — `WARMUP_INSUFFICIENT`를
 * `MISSING`과 구분하는 3번째 상태)에 실제로 들어갈 입력 형태다. 이 서브태스크는 그 컬럼에 쓰지
 * 않는다(fold 배선은 MT1-06 소관) — 여기서는 백필 결과를 이 모양으로만 낸다.
 *
 * @property structuralAbsenceBefore null이 아니면, 요청 구간의 시작(`windowStart`)보다 이 값(UTC
 *   epoch millis) 이전 구간은 벤더가 애초에 제공하지 않는다는 뜻이다(예: `BAMLH0A0HYM2`의 3년
 *   롤링 윈도, 00a 저널 §12.2) — 오류가 아니라 구조적 부재로 구분한다(브리프 aaa 요건 3).
 * @property reason 사람이 읽는 보조 설명 — `ERROR`의 실패 사유 메시지, 또는 `NOT_COLLECTED`의
 *   미수집 확정 근거.
 */
@Serializable
data class WarmupSeriesStatus(
    val seriesId: String,
    val status: WarmupStatus,
    val rows: Int,
    val earliestAsOf: Long? = null,
    val latestAsOf: Long? = null,
    val structuralAbsenceBefore: Long? = null,
    val reason: String? = null,
)

@Serializable
enum class WarmupStatus { OK, PARTIAL, ERROR, NOT_COLLECTED }

/** 백필 1회 실행의 웜업 상태 리포트 전체 — 계열별 적재 행수·기간·결측 사유. */
@Serializable
data class WarmupReport(
    val windowStart: Long,
    val windowEnd: Long,
    val series: List<WarmupSeriesStatus>,
) {
    fun toJson(): String = Json.encodeToString(serializer(), this)
}
