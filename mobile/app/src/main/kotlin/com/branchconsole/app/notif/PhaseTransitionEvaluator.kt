package com.branchconsole.app.notif

/**
 * 국면 전이 알림 판정 — 순수 함수(IO 없음, [NotificationSync]가 Room/SharedPreferences를 읽어
 * 넘긴 값만 본다). D §3.9.1 트리거: "`phase_commit`의 오늘 국면 ≠ 직전 커밋 국면" — `phase_commit`
 * 테이블은 없으므로(AD-A11 "전량 fold") 호출부가 `tick_input` fold 결과([Tick] 시퀀스를
 * `StateMachine.run`한 timeline)에서 이미 알린 지점 이후의 새 구간만 골라 넘긴다.
 *
 * M1_PLAN_C.md §4.2 "캐치업 폭주 억제": 새 구간이 틱 여러 개(캐치업)여도 알림은 최대 1건 —
 * 경유 국면은 보지 않고 "이전 국면(배치 시작 전) -> 최종 국면(배치의 마지막 틱)"만 비교한다.
 */
internal object PhaseTransitionEvaluator {
    data class Decision(
        val shouldNotify: Boolean,
        val fromPhase: String,
        val toPhase: String,
        val batchSize: Int,
    )

    /**
     * @param previousPhase 마지막으로 알림을 보낸 시점의 국면(설치 후 첫 확인이면 엔진의
     *   `initial_phase`).
     * @param newPhases 아직 알리지 않은 새 확정 틱들의 국면(오름차순, 최소 1개 — 보통 1개,
     *   캐치업이면 여러 개).
     */
    fun evaluate(
        previousPhase: String,
        newPhases: List<String>,
    ): Decision {
        require(newPhases.isNotEmpty()) { "newPhases must not be empty" }
        val toPhase = newPhases.last()
        return Decision(
            shouldNotify = toPhase != previousPhase,
            fromPhase = previousPhase,
            toPhase = toPhase,
            batchSize = newPhases.size,
        )
    }
}
