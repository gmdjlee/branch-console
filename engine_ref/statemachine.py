"""engine_ref.statemachine — D-16 프로파일 주입형 국면 상태기계 (D-25 실행 의미론 확정판).

전이 구조(승격/강등 규칙)·composite 공식·distinct_axes 요건·skip_levels는 프로파일 무관
동일. 프로파일별로 달라지는 것은 틱 카운트 파라미터(promote_sustain_ticks,
demote_below_ticks, min_dwell_ticks, reentry_cooldown_ticks)뿐이다.

D-25 확정 의미론(docs/P0_DESIGN_DECISIONS.md D-25 — MT0-02 라운드 1 반려 D-1~D-3의 해소):
  1. **승격 sustain은 레벨별 연속 충족이다.** 레벨 L(AMBER/ORANGE/RED)은 각자 독립된 스트릭을
     가지며, 그 틱에 L 자신의 조건이 충족되면 +1, 아니면 0으로 리셋된다 — 현재 국면이나
     다른 레벨의 상태와 무관하다. 어떤 틱에 현재 국면보다 높은 레벨 중 스트릭이
     promote_sustain_ticks에 도달한 레벨이 하나 이상 있으면, skip_levels=true는 그 중
     **최고** 레벨로 직행한다("현재 국면보다 높은 아무 레벨이든 충족이면 스트릭 유지"
     해석은 기각됨 — 단일 틱 근거의 RED 오탐을 유발). cooldown 중에는 모든 레벨 스트릭이
     매 틱 0으로 정지·리셋되고 승격 커밋도 없다.
  2. **min_dwell_ticks는 명목=실효 체류다.** 전이가 커밋된 틱을 그 국면의 1틱째로 세고,
     강등은 min_dwell_ticks 틱을 채운 뒤(min_dwell_ticks+1틱째부터) 커밋 가능하다. 강등
     스트릭(demote_below_ticks) 자체는 dwell 충족 여부와 무관하게 누적되며, dwell 미달은
     커밋만 지연시킨다.
  3. **전 지표 결측(Tick.composite is None)은 평가 불능이다.** 국면·모든 스트릭·dwell
     카운터·cooldown을 그 틱에서 완전히 동결한다(전이 없음, 틱 미소비) — GREEN으로
     떨어뜨리지 않는다.
"""

from __future__ import annotations

from dataclasses import dataclass

from engine_ref.registry import ProfileParams, StatemachineConfig

_KNOWN_UPGRADE_KEYS = {"composite_gte", "distinct_axes_gte", "or_any_crit"}


@dataclass(frozen=True)
class Tick:
    composite: (
        float | None
    )  # None = 전 지표 결측("평가 불능", D-25 §3) — 그 틱은 완전 동결
    distinct_axes: int
    any_crit: bool = False


def _rule_satisfied(
    rule: dict, composite: float, distinct_axes: int, any_crit: bool
) -> bool:
    # O-1: 알려진 키가 하나도 없는 규칙은 설정 오류다 — 조용히 "항상 충족"으로 빠지지 않고
    # 즉시 실패한다(PRINCIPLES "Fail Fast / Never Suppress Silently").
    if not set(rule) & _KNOWN_UPGRADE_KEYS:
        raise ValueError(f"upgrade rule has no recognized keys: {rule!r}")
    conditions = []
    if "composite_gte" in rule:
        conditions.append(composite >= rule["composite_gte"])
    if "distinct_axes_gte" in rule:
        conditions.append(distinct_axes >= rule["distinct_axes_gte"])
    base = all(conditions) if conditions else True
    if rule.get("or_any_crit"):
        return base or any_crit
    return base


def _exit_satisfied(rule: dict, composite: float) -> bool:
    return composite < rule["composite_lt"]


def run(
    ticks: list[Tick], profile: ProfileParams, config: StatemachineConfig
) -> list[str]:
    """틱 시퀀스를 재생해 국면 타임라인(틱별 phase)을 산출한다."""
    order = config.phases  # 예: ["GREEN", "AMBER", "ORANGE", "RED"]
    idx = {name: i for i, name in enumerate(order)}
    levels = order[1:]  # GREEN을 제외한 승격 대상 레벨(오름차순)

    phase = config.initial_phase
    ticks_in_phase = (
        1  # 진입 틱을 1틱째로 계상(D-25 §2). GREEN은 강등 대상이 아니라 무해.
    )
    promote_streaks = dict.fromkeys(levels, 0)  # 레벨별 독립 연속 충족 스트릭(D-25 §1)
    demote_streak = 0
    cooldown = 0
    timeline: list[str] = []

    for tick in ticks:
        if tick.composite is None:
            # D-25 §3: 평가 불능 — 국면·스트릭·dwell·cooldown 전부 동결, 틱 미소비.
            timeline.append(phase)
            continue

        # 매 틱 시작 시 "현재 국면에 머문 지 몇 틱째인가"를 우선 갱신한다. 이번 틱에
        # 전이가 일어나면 아래에서 1로 덮어써 그 전이의 "1틱째"로 재정의된다.
        ticks_in_phase += 1

        if cooldown > 0:
            # 강등 직후 쿨다운: 모든 레벨 스트릭 정지·리셋, 승격 커밋 금지(D-25 §1).
            for level in levels:
                promote_streaks[level] = 0
        else:
            for level in levels:
                rule = config.upgrade[level]
                if _rule_satisfied(
                    rule, tick.composite, tick.distinct_axes, tick.any_crit
                ):
                    promote_streaks[level] += 1
                else:
                    promote_streaks[level] = 0

        phase_idx = idx[phase]
        transitioned = False

        if cooldown == 0:
            eligible = [
                lvl
                for lvl in levels
                if idx[lvl] > phase_idx
                and promote_streaks[lvl] >= profile.promote_sustain_ticks
            ]
            if eligible:
                target = (
                    max(eligible, key=lambda lvl: idx[lvl])
                    if config.skip_levels
                    else min(eligible, key=lambda lvl: idx[lvl])
                )
                phase = target
                ticks_in_phase = 1
                demote_streak = 0
                transitioned = True

        if not transitioned and phase != order[0]:
            exit_rule = config.downgrade[f"exit_{phase}"]
            if _exit_satisfied(exit_rule, tick.composite):
                demote_streak += 1
                if (
                    demote_streak >= profile.demote_below_ticks
                    and ticks_in_phase >= profile.min_dwell_ticks + 1
                ):
                    phase = order[idx[phase] - 1]
                    ticks_in_phase = 1
                    demote_streak = 0
                    cooldown = profile.reentry_cooldown_ticks
                    transitioned = True
            else:
                demote_streak = 0

        if cooldown > 0 and not transitioned:
            cooldown -= 1

        timeline.append(phase)

    return timeline
