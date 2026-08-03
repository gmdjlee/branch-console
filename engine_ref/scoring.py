"""engine_ref.scoring — severity 판정(D-01) + composite(D-02, D-25 §3) + distinct_axes.

severity: 0=none, 1=watch, 2=warn, 3=crit. 임계 등호 포함(값 == 임계 → 해당 등급 발화).
결측(None/NaN)은 severity=None — composite 분모·분자 모두 제외(engine.missing_data_policy,
optional 지표 포함). float64 고정, 반올림 없음(K-07 — 표시 계층 몫).

D-25 §3: 전 지표 결측(유효 가중 0)은 GREEN이 아니라 "평가 불능"이다. compute_composite는
(score, coverage) 쌍을 반환하고, 유효 가중이 0이면 score=None — 상태기계는 이 틱에서
국면·스트릭·카운터를 동결한다(statemachine.run 참조).

MT0-06/BT-04 Stage B(AD-7) 확장 — 둘 다 opt-in이며 기본 호출(인자 생략)은 확장 이전과
비트 동일(AD-9(a)(i) 증인, tests/test_engine_ref.py):
  - `is_extreme()`: 옵션 A(or_any_extreme, ORANGE 승격 한정) 전용. severity 사다리와
    완전히 분리된 별도 함수다 — thresholds에 "extreme" 키가 있어도 classify_severity의
    기본 호출(max_severity=3)은 그 키를 절대 보지 않는다(옵션 A의 "severity/composite
    100% 불변" 불변식, 설계 저널 §3-A(a)).
  - `classify_severity(..., max_severity=4)`: 옵션 B(severity 4단계, 계량 전용) 전용.
    호출자가 명시적으로 max_severity>=4를 넘길 때만 thresholds["extreme"]을 4번째 tier로
    본다 — 옵션 A의 sandbox candidate가 같은 "extreme" 키를 쓰더라도 max_severity를 넘기지
    않으므로(기본값 3) 이 확장에 영향받지 않는다. 두 옵션이 같은 yaml 키 이름
    (thresholds.extreme)을 공유해도 서로 격리되는 이유.
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
    max_severity: int = 3,
) -> int | None:
    """값 하나를 thresholds(watch/warn/crit)에 대해 판정. 결측이면 None.

    max_severity=4(AD-7 옵션 B 전용, 계량 목적)일 때만 thresholds["extreme"]을 4번째
    tier로 추가 판정한다(값 >= extreme -> 4). 기본값 3은 원래 3-tier 그대로이며
    "extreme" 키의 존재 여부와 무관하게 무시한다(모듈 docstring 참조 — 옵션 A와의 격리)."""
    if value is None or pd.isna(value):
        return None
    v = abs(value) if direction == "abs" else value
    if max_severity >= 4 and "extreme" in thresholds and v >= thresholds["extreme"]:
        return 4
    for name, level in _LEVELS:
        if v >= thresholds[name]:
            return level
    return 0


def is_extreme(
    value: float | None,
    thresholds: Mapping[str, float],
    *,
    direction: str = "higher_is_risk",
) -> bool:
    """AD-7 옵션 A 전용: 개별 지표의 원값(severity 아님)이 thresholds["extreme"]을 넘으면
    True. extreme 키 미설정 또는 값 결측이면 항상 False(엔진 기본 거동 비영향 — extreme
    키 부재 시 이 함수는 어떤 지표에서도 True를 낼 수 없다, O-a 등호 포함 "이상" 규약은
    classify_severity와 동일하게 >= 적용)."""
    extreme = thresholds.get("extreme")
    if extreme is None or value is None or pd.isna(value):
        return False
    v = abs(value) if direction == "abs" else value
    return v >= extreme


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
    severities: Mapping[str, int | None],
    weights: Mapping[str, float],
    max_severities: Mapping[str, int] | None = None,
) -> CompositeResult:
    """D-02: score = 100 * Σ(w_i·s_i) / Σ(w_i·3). 결측(None) 지표는 분모·분자 모두 제외(부분
    결측은 이 제외만 적용 — D-02 불변). coverage = 유효 가중 / 전체 가중.

    D-25 §3: 전체 가중이 0이거나 유효 가중이 0(전 지표 결측)이면 score=None — "평가 불능".

    max_severities(AD-7 옵션 B, 계량 전용): 지정하면 분모의 상수 3.0이 지표별
    max_severities.get(id, 3)로 대체된다(D-25 §3-B: composite = 100*Σ(w_i·s_i)/Σ(w_i·
    max_severity_i)). **§3-B(b) "순진한 vs 올바른" 분모 함정** — 이 파라미터는 지표별
    맵이라 "일부 지표만 4단계"를 표현할 수 있다. 전 지표를 동시에 4로 취급하는 오설계는
    이 함수를 호출하는 쪽이 max_severities에 모든 키를 4로 채워야만 재현되며, 이 함수
    자체는 그런 "전역 스케일" 지름길을 제공하지 않는다(회귀 테스트:
    tests/test_engine_ref.py test_compute_composite_max_severity_naive_global_vs_
    per_indicator_trap).

    max_severities=None(기본값)이면 원래 3-tier 산식과 **완전히 동일한 연산 순서**로
    계산된다(비트 동일, AD-9(a)(i) 증인) — 아래 두 갈래는 의도적으로 별도 경로다."""
    if max_severities is None:
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

    num = 0.0
    den = 0.0
    for indicator_id, s in severities.items():
        if s is None:
            continue
        w = weights[indicator_id]
        num += w * s
        den += w * float(max_severities.get(indicator_id, 3))
    total = sum(w * float(max_severities.get(i, 3)) for i, w in weights.items())
    coverage = (den / total) if total else 0.0
    score = None if den == 0.0 else 100.0 * num / den
    return CompositeResult(score, coverage)


def distinct_axes(severities: Mapping[str, int | None], axes: Mapping[str, str]) -> int:
    """severity >= warn(2)인 지표가 존재하는 서로 다른 axis 개수 (D-01)."""
    fired = {axes[iid] for iid, s in severities.items() if s is not None and s >= 2}
    return len(fired)
