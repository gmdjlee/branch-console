"""backtest/test_golden.py — BT-02 골든 무회귀: D-08 2케이스 x 2프로파일(server_intraday·
mobile_daily). 완료 기준(BACKTEST_PLAN §BT-02): `uv run pytest backtest/test_golden.py -q`
green. 네트워크 없음 — backtest/fixtures/*.parquet(BT-01 산출)만 읽는다.

기대값은 backtest/golden_server.yaml(server_intraday, D-08 원문 판정)·
backtest/golden_mobile.yaml(mobile_daily, 동결된 틱별 타임라인)에서만 로드한다 — 날짜·축
이름·수치를 이 파일에 복제하지 않는다(SSOT 규율, CLAUDE.md §1).
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest
import yaml

from backtest.run_replay import STATEMACHINE_YAML_PATH, load_schedule_times, run_replay

REPO_ROOT = Path(__file__).resolve().parent.parent
GOLDEN_SERVER_PATH = REPO_ROOT / "backtest" / "golden_server.yaml"
GOLDEN_MOBILE_PATH = REPO_ROOT / "backtest" / "golden_mobile.yaml"

_PHASE_ORDER = ["GREEN", "AMBER", "ORANGE", "RED"]


def _load_yaml(path: Path) -> dict[str, Any]:
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)


def _check_tick_label(check_tick_id: str) -> str:
    """golden_server.yaml의 check_tick(예: "kr_close")을 실제 kst_time 라벨("17:00")로
    해석한다 — SSOT는 configs/statemachine.yaml schedules.evaluation이지 이 테스트가
    아니다(F-6, aaa-critic 라운드1: "17:00" 하드코딩 제거)."""
    schedule_times = load_schedule_times(_load_yaml(STATEMACHINE_YAML_PATH))
    times = schedule_times[check_tick_id]
    assert len(times) == 1, (
        f"{check_tick_id}: expected exactly one tick time, got {times}"
    )
    return times[0].strftime("%H:%M")


@pytest.fixture(scope="module")
def golden_server() -> dict[str, Any]:
    return _load_yaml(GOLDEN_SERVER_PATH)


@pytest.fixture(scope="module")
def golden_mobile() -> dict[str, Any]:
    return _load_yaml(GOLDEN_MOBILE_PATH)


@pytest.fixture(scope="module")
def replay_result() -> dict[str, Any]:
    """두 골든 창(양성 w2024_carry_unwind·음성 w2024_05_calm) x 두 프로파일을 실제
    픽스처로 리플레이한 결과. 모듈 스코프로 한 번만 실행(창 2개뿐이라 가볍다)."""
    return run_replay(
        profiles=["server_intraday", "mobile_daily"],
        window_ids=["w2024_carry_unwind", "w2024_05_calm"],
    )


# -----------------------------------------------------------------------------
# server_intraday — D-08 원문 판정 (golden_server.yaml)
# -----------------------------------------------------------------------------


def test_golden_positive_server_reaches_orange_with_required_axes(
    replay_result: dict[str, Any], golden_server: dict[str, Any]
) -> None:
    spec = golden_server["positive"]
    ticks = replay_result["windows"][spec["window_id"]]["server_intraday"]["ticks"]
    check_tick_label = _check_tick_label(spec["check_tick"])
    hit = next(
        t
        for t in ticks
        if t["date"] == spec["check_date"] and t["kst_time"] == check_tick_label
    )
    assert _PHASE_ORDER.index(hit["phase"]) >= _PHASE_ORDER.index(spec["min_phase"]), (
        f"expected >= {spec['min_phase']} at {spec['check_date']} kr_close, "
        f"got {hit['phase']}"
    )
    assert set(spec["required_fired_axes"]) <= set(hit["fired_axes"]), hit["fired_axes"]


def test_golden_negative_server_stays_at_or_below_amber(
    replay_result: dict[str, Any], golden_server: dict[str, Any]
) -> None:
    spec = golden_server["negative"]
    ticks = replay_result["windows"][spec["window_id"]]["server_intraday"]["ticks"]
    assert ticks, "negative golden window produced no ticks"
    max_allowed = _PHASE_ORDER.index(spec["max_phase"])
    offenders = [t for t in ticks if _PHASE_ORDER.index(t["phase"]) > max_allowed]
    assert not offenders, offenders


# -----------------------------------------------------------------------------
# mobile_daily — 동결된 틱별 타임라인 (golden_mobile.yaml)
# -----------------------------------------------------------------------------


def _assert_mobile_timeline_matches(
    replay_result: dict[str, Any], golden_mobile: dict[str, Any], window_id: str
) -> None:
    actual = replay_result["windows"][window_id]["mobile_daily"]["ticks"]
    expected = golden_mobile["windows"][window_id]["ticks"]
    assert len(actual) == len(expected), (
        f"{window_id}: tick count mismatch actual={len(actual)} expected={len(expected)}"
    )
    for a, e in zip(actual, expected, strict=True):
        assert a["date"] == e["date"]
        assert a["phase"] == e["phase"], (
            f"{window_id} {a['date']}: {a['phase']} != {e['phase']}"
        )
        assert a["composite"] == pytest.approx(e["composite"], rel=1e-9), (
            f"{window_id} {a['date']}: composite {a['composite']} != {e['composite']}"
        )
        assert a["coverage"] == pytest.approx(e["coverage"], rel=1e-9)
        assert a["fired_axes"] == e["fired_axes"]


def test_golden_positive_mobile_matches_frozen_timeline(
    replay_result: dict[str, Any], golden_mobile: dict[str, Any]
) -> None:
    _assert_mobile_timeline_matches(replay_result, golden_mobile, "w2024_carry_unwind")


def test_golden_negative_mobile_matches_frozen_timeline(
    replay_result: dict[str, Any], golden_mobile: dict[str, Any]
) -> None:
    _assert_mobile_timeline_matches(replay_result, golden_mobile, "w2024_05_calm")


# -----------------------------------------------------------------------------
# cross-check: mobile golden timeline itself must not silently violate D-08's
# positive/negative bounds (belt-and-suspenders — golden_mobile.yaml is a frozen
# snapshot, not re-derived from golden_server.yaml's rules).
# -----------------------------------------------------------------------------


def test_golden_mobile_positive_timeline_reaches_orange_with_required_axes(
    golden_mobile: dict[str, Any], golden_server: dict[str, Any]
) -> None:
    spec = golden_server["positive"]
    ticks = golden_mobile["windows"][spec["window_id"]]["ticks"]
    hit = next(t for t in ticks if t["date"] == spec["check_date"])
    assert _PHASE_ORDER.index(hit["phase"]) >= _PHASE_ORDER.index(spec["min_phase"])
    assert set(spec["required_fired_axes"]) <= set(hit["fired_axes"])


def test_golden_mobile_negative_timeline_stays_at_or_below_amber(
    golden_mobile: dict[str, Any], golden_server: dict[str, Any]
) -> None:
    spec = golden_server["negative"]
    ticks = golden_mobile["windows"][spec["window_id"]]["ticks"]
    max_allowed = _PHASE_ORDER.index(spec["max_phase"])
    offenders = [t for t in ticks if _PHASE_ORDER.index(t["phase"]) > max_allowed]
    assert not offenders, offenders
