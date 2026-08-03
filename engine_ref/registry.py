"""engine_ref.registry — configs/*.yaml 로더 + transform/modifier 문자열 파서 (SSOT 경계).

CLAUDE.md §1 규율: 임계값·가중치·transform 파라미터·modifier 수치는 전부 이 모듈이
configs/indicators.yaml·configs/statemachine.yaml에서 로드하거나, 문자열을 정규식으로
파싱해 얻는다. 다른 engine_ref 모듈은 여기서 나온 값만 사용하고 숫자를 직접 알지 못한다.
"""

from __future__ import annotations

import copy
import re
from dataclasses import dataclass, field
from datetime import timedelta
from functools import cache
from pathlib import Path
from typing import Any

import yaml

_CONFIGS_DIR = Path(__file__).resolve().parent.parent / "configs"
_NUM_RE = r"[-+]?\d+(?:\.\d+)?"


@cache
def _load_yaml_cached(name: str) -> dict[str, Any]:
    # Windows cp949 함정 회피 — encoding 명시 필수.
    with open(_CONFIGS_DIR / name, encoding="utf-8") as f:
        return yaml.safe_load(f)


def _load_yaml(name: str) -> dict[str, Any]:
    # D-7: @cache는 동일 dict 객체를 재사용한다 — 호출부가 반환된 중첩 dict를 변형하면
    # (예: spec.thresholds["warn"]=99) 캐시가 오염돼 이후 모든 로드에 전파된다.
    # 매 호출마다 얕은 참조가 아닌 독립 사본을 반환해 차단한다.
    return copy.deepcopy(_load_yaml_cached(name))


def _indicators_yaml(path: Path | None = None) -> dict[str, Any]:
    """indicators.yaml 로드, 경로 오버라이드 가능(MT0-04 — backtest/run_replay.py의
    --config 플래그, BT-03 스윕 후보 레지스트리용).

    path=None: 기존 동작과 완전히 동일(configs/indicators.yaml, `_load_yaml_cached`의
    이름 키 캐시 그대로 재사용). path 지정 시: **캐시하지 않는다** — BT-03 스윕은 같은
    경로에 후보 yaml을 반복 덮어쓰며 재호출하는 패턴이라(F-2, aaa-critic 라운드1), 경로만
    보고 캐싱하면 첫 로드 내용에 고착되어 이후 덮어쓴 내용을 영영 못 본다. 매 호출 새로
    읽는 비용은 yaml 하나 파싱뿐이라 무시할 만하다."""
    if path is None:
        return _load_yaml("indicators.yaml")
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)


# ----------------------------------------------------------------- indicators


@dataclass(frozen=True)
class IndicatorSpec:
    id: str
    axis: str
    weight: float
    direction: str  # "higher_is_risk" | "abs"
    thresholds: dict[
        str, Any
    ]  # 평평한 {watch,warn,crit} 이거나 spx_drawdown_momentum류 중첩 dict
    transform: str
    source: dict[str, Any]
    optional: bool = False
    max_severity: int = 3  # AD-7 옵션 B(계량 전용) — indicators.yaml에 없으면 3(하위 호환)


def load_indicator_specs(
    *, enabled_only: bool = True, path: Path | None = None
) -> list[IndicatorSpec]:
    d = _indicators_yaml(path)
    specs = []
    for item in d["indicators"]:
        if enabled_only and not item.get("enabled", True):
            continue
        specs.append(
            IndicatorSpec(
                id=item["id"],
                axis=item["axis"],
                weight=float(item["weight"]),
                direction=item["direction"],
                thresholds=item["thresholds"],
                transform=item["transform"],
                source=item["source"],
                optional=bool(item.get("optional", False)),
                max_severity=int(item.get("max_severity", 3)),
            )
        )
    return specs


def indicator_spec(indicator_id: str, *, path: Path | None = None) -> IndicatorSpec:
    for spec in load_indicator_specs(enabled_only=False, path=path):
        if spec.id == indicator_id:
            return spec
    raise KeyError(f"unknown indicator id: {indicator_id!r}")


def weight_map(
    *, enabled_only: bool = True, path: Path | None = None
) -> dict[str, float]:
    return {
        s.id: s.weight
        for s in load_indicator_specs(enabled_only=enabled_only, path=path)
    }


def axis_map(*, enabled_only: bool = True, path: Path | None = None) -> dict[str, str]:
    return {
        s.id: s.axis for s in load_indicator_specs(enabled_only=enabled_only, path=path)
    }


def max_severity_map(
    *, enabled_only: bool = True, path: Path | None = None
) -> dict[str, int]:
    """AD-7 옵션 B(계량 전용) — scoring.compute_composite(max_severities=...)에 그대로
    전달할 지표별 분모 상한 맵. indicators.yaml에 max_severity 키가 없는 지표는 3(기본,
    하위 호환)."""
    return {
        s.id: s.max_severity
        for s in load_indicator_specs(enabled_only=enabled_only, path=path)
    }


# ------------------------------------------------------------- transform 파싱


def _extract_call_body(call_name: str, transform: str) -> str:
    """`call_name(...)` 자신의 여는 '('에 대응하는 닫는 ')' 사이 본문을 반환한다.

    - 단어 경계(\\b) 요구: call_name="zscore"가 "neg_zscore("의 접미사로 오매칭되지 않는다
      (밑줄은 \\w이므로 "g"와 "z" 사이엔 경계가 없어 \\b가 자연히 걸러낸다).
    - 괄호 깊이를 세어 자신의 짝을 찾는다(gated(zscore(...), gate=...)처럼 인자 목록이
      다른 호출을 중첩할 수 있으므로 "다음 첫 ')'"로는 부족하다).
    - 괄호가 불균형이면(문자열 끝까지 짝을 못 찾으면) IndexError 대신 진단 메시지를 담은
      ValueError를 낸다(D-8 — 다른 파서들과 동일한 실패 방식).
    """
    m = re.search(rf"\b{re.escape(call_name)}\s*\(", transform)
    if not m:
        raise ValueError(f"{call_name!r} not found in transform: {transform!r}")
    start = m.end()
    depth = 1
    i = start
    n = len(transform)
    while depth > 0:
        if i >= n:
            raise ValueError(
                f"unbalanced parentheses for {call_name!r} in transform: {transform!r}"
            )
        if transform[i] == "(":
            depth += 1
        elif transform[i] == ")":
            depth -= 1
        i += 1
    return transform[start : i - 1]


def _split_top_level_args(body: str) -> list[str]:
    """콤마로 인자 목록을 분리하되, 중첩 괄호 안의 콤마는 분리 지점으로 보지 않는다."""
    args = []
    depth = 0
    current: list[str] = []
    for ch in body:
        if ch == "(":
            depth += 1
            current.append(ch)
        elif ch == ")":
            depth -= 1
            current.append(ch)
        elif ch == "," and depth == 0:
            args.append("".join(current))
            current = []
        else:
            current.append(ch)
    args.append("".join(current))
    return [a.strip() for a in args]


_KWARG_RE = re.compile(rf'^(\w+)\s*=\s*(true|false|"[^"]*"|{_NUM_RE})$')


def _coerce_kwarg_value(val: str) -> float | int | bool | str:
    if val == "true":
        return True
    if val == "false":
        return False
    if val.startswith('"'):
        return val.strip('"')
    return float(val) if "." in val else int(val)


def parse_call_kwargs(
    call_name: str, transform: str
) -> dict[str, float | int | bool | str]:
    """`call_name(...)`의 **최상위(직접) 인자**에서만 kwargs(key=value)를 추출한다.

    D-4: 중첩된 다른 호출(예: gated(zscore(..., window=60), gate=...) 안의 zscore의
    window)이 바깥 호출의 kwargs로 누출되지 않도록, 콤마로 분리한 최상위 인자 각각이
    통째로 "key=value" 형태일 때만 채택한다 — 인자 하나가 다른 함수 호출 전체라면
    (예: "zscore(trading_value, window=60)") 그 안에 "="가 있어도 최상위 인자 자체가
    key=value 형태가 아니므로 채택되지 않는다.
    """
    body = _extract_call_body(call_name, transform)
    out: dict[str, float | int | bool | str] = {}
    for arg in _split_top_level_args(body):
        m = _KWARG_RE.match(arg)
        if m:
            out[m.group(1)] = _coerce_kwarg_value(m.group(2))
    return out


_FALLBACK_WINDOW_RE = re.compile(r"_(\d+)d$")


def parse_fallback_window(fallback_id: str) -> int:
    """예: "realized_vol_kospi_20d" -> 20. K-02 폴백 식별자에 내장된 윈도우를 정규식으로 추출
    (하드코딩 금지 — transforms.realized_vol_kospi_20d의 window 인자는 여기서만 나온다)."""
    m = _FALLBACK_WINDOW_RE.search(fallback_id)
    if not m:
        raise ValueError(f"cannot extract window from fallback id: {fallback_id!r}")
    return int(m.group(1))


_GATE_RE = re.compile(rf"(\w+)\s*(<=|>=|==|<|>)\s*({_NUM_RE})")


def parse_gate(gate_expr: str) -> tuple[str, str, float]:
    """ "daily_return < 0" 형태의 gate 문자열을 (변수명, 연산자, 임계값)으로 분해."""
    m = _GATE_RE.search(gate_expr)
    if not m:
        raise ValueError(f"unrecognized gate expression: {gate_expr!r}")
    var, op, threshold = m.group(1), m.group(2), float(m.group(3))
    return var, op, threshold


# ---------------------------------------------------------------- modifiers


@dataclass(frozen=True)
class HyLevelBoost:
    level_threshold: float  # rule 문자열의 "> X" 값 (등호 미포함 — 초과만 발화)
    increment: int  # rule 문자열의 "+= N" 값 (D-9 — 하드코딩 금지)
    max_severity: int  # "(max N)" 캡


@dataclass(frozen=True)
class UsdkrwIntradayForce:
    warn_threshold: float  # ">= X%" → severity >= warn 강제
    crit_threshold: float  # ">= Y%" → severity crit 강제


def _parse_hy_level_boost(rule: str) -> HyLevelBoost:
    # 예: "hy_oas_level > 4.5 -> hy_oas_delta.severity += 1 (max 3)"
    # O2-4: 정규식이 매치 못하면 AttributeError(.group on None)가 아니라 다른 파서들과
    # 동일한 ValueError+진단 메시지로 실패한다.
    level_m = re.search(rf">\s*({_NUM_RE})", rule)
    increment_m = re.search(r"\+=\s*(\d+)", rule)  # D-9 — 하드코딩 금지
    cap_m = re.search(r"max\s+(\d+)", rule)
    if not (level_m and increment_m and cap_m):
        raise ValueError(f"malformed hy_level_boost rule: {rule!r}")
    return HyLevelBoost(
        float(level_m.group(1)), int(increment_m.group(1)), int(cap_m.group(1))
    )


def _parse_usdkrw_intraday_force(rule: str) -> UsdkrwIntradayForce:
    # 예: "... >= 1.2% -> severity max(warn); >= 2.0% -> crit"
    # O2-4: 숫자가 2개 미만이면 IndexError가 아니라 ValueError+진단 메시지로 실패한다.
    nums = re.findall(rf"({_NUM_RE})%", rule)
    if len(nums) < 2:
        raise ValueError(f"malformed usdkrw_intraday_force rule: {rule!r}")
    return UsdkrwIntradayForce(float(nums[0]), float(nums[1]))


def load_modifiers(
    *, path: Path | None = None
) -> tuple[HyLevelBoost, UsdkrwIntradayForce]:
    d = _indicators_yaml(path)
    rules = {m["id"]: m["rule"] for m in d["engine"]["modifiers"]}
    return (
        _parse_hy_level_boost(rules["hy_level_boost"]),
        _parse_usdkrw_intraday_force(rules["usdkrw_intraday_force"]),
    )


# ----------------------------------------------------------------- stale 판정

_DURATION_RE = re.compile(r"^(\d+)([mhd])$")


def parse_duration(s: str) -> timedelta:
    m = _DURATION_RE.match(s.strip())
    if not m:
        raise ValueError(f"unrecognized duration string: {s!r}")
    n, unit = int(m.group(1)), m.group(2)
    return {"m": timedelta(minutes=n), "h": timedelta(hours=n), "d": timedelta(days=n)}[
        unit
    ]


def stale_window(profile: str, cadence: str, *, path: Path | None = None) -> timedelta:
    """engine.stale_profiles[profile][cadence]를 duration으로 파싱.

    Advisor 지정 해석: 프로파일 맵에 해당 cadence 키가 없으면(예: mobile_daily에
    intraday_30m 없음) 그 프로파일의 daily_kr 창을 대신 적용한다.
    """
    d = _indicators_yaml(path)
    windows = d["engine"]["stale_profiles"][profile]
    raw = windows.get(cadence, windows["daily_kr"])
    return parse_duration(raw)


def is_stale(
    as_of, evaluated_at, *, profile: str, cadence: str, path: Path | None = None
) -> bool:
    """평가 시각 대비 as_of가 stale 창을 초과했는가(등호 미포함 — 초과만 stale)."""
    if as_of.tzinfo is None or evaluated_at.tzinfo is None:
        raise ValueError("naive datetime 금지 (K-05, CLAUDE.md §2) — tz-aware만 허용")
    return (evaluated_at - as_of) > stale_window(profile, cadence, path=path)


# ------------------------------------------------------------- statemachine


@dataclass(frozen=True)
class ProfileParams:
    promote_sustain_ticks: int
    demote_below_ticks: int
    min_dwell_ticks: int
    reentry_cooldown_ticks: int = (
        0  # 미정의 시 0 (Advisor 지정 해석 — mobile_daily는 MT0-05④에서 2로 확정, statemachine.yaml 명시)
    )


@dataclass(frozen=True)
class StatemachineConfig:
    phases: list[str]
    initial_phase: str
    upgrade: dict[str, dict[str, Any]]
    downgrade: dict[str, dict[str, Any]]
    skip_levels: bool
    profiles: dict[str, ProfileParams] = field(default_factory=dict)


def _statemachine_yaml(path: Path | None = None) -> dict[str, Any]:
    """statemachine.yaml 로드, 경로 오버라이드 가능(MT0-05 BT-03 스윕 ③ mobile_daily
    프로파일 파라미터 대상). 규약은 `_indicators_yaml`과 동일: path=None이면 기존
    이름 키 캐시 그대로, path 지정 시 **캐시하지 않는다**(F-2 교훈 — 스윕이 같은 경로에
    후보 yaml을 반복 덮어쓰며 재호출하는 패턴이라 캐시하면 첫 로드 내용에 고착된다)."""
    if path is None:
        return _load_yaml("statemachine.yaml")
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)


def load_statemachine(*, path: Path | None = None) -> StatemachineConfig:
    d = _statemachine_yaml(path)
    profiles = {
        name: ProfileParams(
            promote_sustain_ticks=int(p["promote_sustain_ticks"]),
            demote_below_ticks=int(p["demote_below_ticks"]),
            min_dwell_ticks=int(p["min_dwell_ticks"]),
            reentry_cooldown_ticks=int(p.get("reentry_cooldown_ticks", 0)),
        )
        for name, p in d["profiles"].items()
    }
    return StatemachineConfig(
        phases=list(d["phases"]),
        initial_phase=d["initial_phase"],
        upgrade=d["upgrade"]["rules"],
        downgrade=d["downgrade"]["rules"],
        skip_levels=bool(d["skip_levels"]),
        profiles=profiles,
    )
