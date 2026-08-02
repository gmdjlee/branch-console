"""engine_ref.scoring — severity 판정(D-01) + composite(D-02, D-25 §3) + distinct_axes.

severity: 0=none, 1=watch, 2=warn, 3=crit. 임계 등호 포함(값 == 임계 → 해당 등급 발화).
결측(None/NaN)은 severity=None — composite 분모·분자 모두 제외(engine.missing_data_policy,
optional 지표 포함). float64 고정, 반올림 없음(K-07 — 표시 계층 몫).

D-25 §3: 전 지표 결측(유효 가중 0)은 GREEN이 아니라 "평가 불능"이다. compute_composite는
(score, coverage) 쌍을 반환하고, 유효 가중이 0이면 score=None — 상태기계는 이 틱에서
국면·스트릭·카운터를 동결한다(statemachine.run 참조).
"""

from __future__ import annotations

from collections.abc import Mapping
from typing import NamedTuple

import pandas as pd

_LEVELS = (("crit", 3), ("warn", 2), ("watch", 1))


def classify_severity(
    value: float | None,
    thresholds: Mapping[str, float],
    *,
    direction: str = "higher_is_risk",
) -> int | None:
    """값 하나를 thresholds(watch/warn/crit)에 대해 판정. 결측이면 None."""
    if value is None or pd.isna(value):
        return None
    v = abs(value) if direction == "abs" else value
    for name, level in _LEVELS:
        if v >= thresholds[name]:
            return level
    return 0


def combine_max_severity(
    value_a: float | None,
    thresholds_a: Mapping[str, float],
    value_b: float | None,
    thresholds_b: Mapping[str, float],
    *,
    direction: str = "higher_is_risk",
) -> int | None:
    """spx_drawdown_momentum: drawdown/neg_z 각 성분을 자체 임계로 판정 후 max.

    한쪽만 결측이면 있는 쪽으로 판정(결측 성분은 0 취급 아님 — 단순 배제).
    둘 다 결측이면 전체 결측.
    """
    sa = classify_severity(value_a, thresholds_a, direction=direction)
    sb = classify_severity(value_b, thresholds_b, direction=direction)
    if sa is None and sb is None:
        return None
    return max(sa or 0, sb or 0)


class CompositeResult(NamedTuple):
    score: float | None  # 유효 가중 0(전 지표 결측)이면 None — D-25 §3 "평가 불능"
    coverage: float  # 유효 가중 / 전체 가중 (0~1)


def compute_composite(
    severities: Mapping[str, int | None], weights: Mapping[str, float]
) -> CompositeResult:
    """D-02: score = 100 * Σ(w_i·s_i) / Σ(w_i·3). 결측(None) 지표는 분모·분자 모두 제외(부분
    결측은 이 제외만 적용 — D-02 불변). coverage = 유효 가중 / 전체 가중.

    D-25 §3: 전체 가중이 0이거나 유효 가중이 0(전 지표 결측)이면 score=None — "평가 불능".
    """
    num = 0.0
    den = 0.0
    for indicator_id, s in severities.items():
        if s is None:
            continue
        w = weights[indicator_id]
        num += w * s
        den += w * 3.0
    total = sum(weights.values()) * 3.0
    coverage = (den / total) if total else 0.0
    score = None if den == 0.0 else 100.0 * num / den
    return CompositeResult(score, coverage)


def distinct_axes(severities: Mapping[str, int | None], axes: Mapping[str, str]) -> int:
    """severity >= warn(2)인 지표가 존재하는 서로 다른 axis 개수 (D-01)."""
    fired = {axes[iid] for iid, s in severities.items() if s is not None and s >= 2}
    return len(fired)
