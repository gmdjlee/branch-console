"""engine_ref — branch-console 계산 명세의 실행 가능한 정의 (D-18).

순수 함수만 포함: transforms · severity/composite(D-02) · modifiers · statemachine(D-16,
프로파일 주입형). 임계값·가중치·윈도우 등 숫자 리터럴은 전부 configs/*.yaml에서 온다 —
engine_ref.registry가 로드·파싱을 전담하고, 다른 서브모듈은 숫자를 직접 알지 못한다.

이후 서버 detection과 backtest 하니스가 이 패키지를 import하고, Kotlin 엔진이 패리티
대상으로 삼는다(docs/BACKTEST_PLAN.md §2).
"""

from __future__ import annotations

from engine_ref import modifiers, registry, scoring, statemachine, transforms
from engine_ref.registry import (
    HyLevelBoost,
    IndicatorSpec,
    ProfileParams,
    StatemachineConfig,
    UsdkrwIntradayForce,
    indicator_spec,
    is_stale,
    load_indicator_specs,
    load_modifiers,
    load_statemachine,
    parse_call_kwargs,
    parse_duration,
    parse_fallback_window,
    parse_gate,
    stale_window,
)
from engine_ref.scoring import (
    CompositeResult,
    classify_severity,
    combine_max_severity,
    compute_composite,
    distinct_axes,
)
from engine_ref.statemachine import Tick
from engine_ref.statemachine import run as run_statemachine

__all__ = [
    "CompositeResult",
    "HyLevelBoost",
    "IndicatorSpec",
    "ProfileParams",
    "StatemachineConfig",
    "Tick",
    "UsdkrwIntradayForce",
    "classify_severity",
    "combine_max_severity",
    "compute_composite",
    "distinct_axes",
    "indicator_spec",
    "is_stale",
    "load_indicator_specs",
    "load_modifiers",
    "load_statemachine",
    "modifiers",
    "parse_call_kwargs",
    "parse_duration",
    "parse_fallback_window",
    "parse_gate",
    "registry",
    "run_statemachine",
    "scoring",
    "stale_window",
    "statemachine",
    "transforms",
]
