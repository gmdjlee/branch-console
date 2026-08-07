package com.branchconsole.app.home

/**
 * MT1-08b — 기능판 홈의 상태 열거형 7종(D §3.9.1 정본, M1에서 고정 — M2 디자인 작업의 입력
 * 계약). M1은 이 분기 **로직**만 검증 대상이다(AAA §2.2 실패 경로 UX·§2.3 커버리지 — 시각
 * 언어는 §2.4/§2.5로 M2 전용).
 *
 * - [NORMAL]: 최근 확정 틱이 정상 커버리지로 판정됨.
 * - [PARTIAL]: 최근 확정 틱은 나왔으나 일부 지표 결측(`coverage < 1.0`) — 스테일 배지 대상.
 * - [SUPPRESSED]: 최근 프리뷰가 `raw_coverage` 임계 미달로 억제됨(D-23 §23.3-3) — "국면 판정
 *   불가", 확정 틱 자체가 아니라 프리뷰 상태다.
 * - [WARMUP]: 아직 확정 틱이 한 번도 커밋되지 않았고(부트스트랩 게이트 미통과), 백필/수집
 *   시도 흔적은 있음(`run_log`에 `WARMUP_INSUFFICIENT`).
 * - [GAP]: 최근 확정 틱이 캐치업 상한 초과로 절단된 공백 표시 행(`gap_reason` 존재,
 *   `composite=NULL`).
 * - [ERROR]: 가장 최근 실행이 실패(`run_log.status="failed"`/`"config_error"`)했거나, 자격증명
 *   미설정으로 수집이 스킵됐거나(`"not_configured"`, aaa N-1 — C §4.1 `KEY_MISSING`, "설정
 *   필요" 사유는 `HomeUiState.lastRunStatus`/`lastRunDetail`로 표시), 확정 틱이 평가 불능
 *   (`composite=NULL`, gap이 아닌 진짜 D-25 §3 전 지표 결측)으로 동결됨.
 * - [EMPTY]: 최초 실행 — 확정 틱도 실행 이력도 전혀 없음.
 */
enum class HomeState {
    NORMAL,
    PARTIAL,
    SUPPRESSED,
    WARMUP,
    GAP,
    ERROR,
    EMPTY,
}
