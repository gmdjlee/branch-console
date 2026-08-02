"""engine_ref.modifiers — configs/indicators.yaml engine.modifiers 규칙 적용.

수치(4.5, cap 3, 1.2%, 2.0%)는 전부 engine_ref.registry.load_modifiers()가 rule 문자열에서
파싱한 값이다. 이 모듈은 그 값을 받아 적용만 한다(코드 리터럴 금지, CLAUDE.md §1).
"""

from __future__ import annotations

from engine_ref.registry import HyLevelBoost, UsdkrwIntradayForce


def apply_hy_level_boost(
    severity: int | None, hy_oas_level: float, rule: HyLevelBoost
) -> int | None:
    """hy_oas_level > rule.level_threshold(초과, 등호 아님) → severity += rule.increment,
    rule.max_severity로 cap.

    severity가 결측(None)이면 레벨 부스트 대상이 없으므로 그대로 None.
    """
    if severity is None:
        return None
    if hy_oas_level > rule.level_threshold:
        return min(severity + rule.increment, rule.max_severity)
    return severity


def usdkrw_intraday_range(high: float, low: float, prev_close: float) -> float:
    """Advisor 지정 해석: 일중 변동폭 = (high - low) / 전일 close * 100 (%).

    O-2: prev_close == 0은 정의 불가(0으로 나눔) — 조용히 inf/NaN을 반환하는 대신
    즉시 ValueError로 실패한다(PRINCIPLES "Fail Fast / Never Suppress Silently").
    """
    if prev_close == 0:
        raise ValueError("usdkrw_intraday_range: prev_close must be non-zero")
    return (high - low) / prev_close * 100.0


def apply_usdkrw_intraday_force(
    severity: int | None, intraday_range_pct: float, rule: UsdkrwIntradayForce
) -> int | None:
    """일중 변동폭 >= rule.warn_threshold → severity>=warn(2) 강제; >= rule.crit_threshold → crit(3).

    등호 포함(">=" — rule 문자열 그대로). 강제이므로 결측(None) 기저도 게이트 충족 시 승급된다.
    """
    if intraday_range_pct >= rule.crit_threshold:
        return 3
    if intraday_range_pct >= rule.warn_threshold:
        base = severity if severity is not None else 0
        return max(base, 2)
    return severity
