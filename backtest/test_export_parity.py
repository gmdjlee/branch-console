"""backtest/test_export_parity.py — MT1-05e: BT-05 패리티 export 하니스 테스트.

브리프 정본: docs/plans/M1_PLAN_C.md §9-C(4파일 규격) + docs/plans/M1_PLAN_B.md §8.
네트워크 없음 — backtest/fixtures/*.parquet(BT-01 산출)만 읽는다.

완료 기준(브리프 §2):
① 규격 검증 — 9창 전체에 4파일이 존재하고 각 파일이 §9-C 스키마를 만족.
② 결정론 — 동일 입력을 두 번 export해 4파일 모두 바이트 동일.
③ 표본 창 1개(w2024_05_calm, 골든 음성)의 expected.jsonl이 (a) run_replay 직접 실행
   결과와 문자 그대로 일치하고 (b) 저장된 severity로 scoring.compute_composite/
   distinct_axes를 재계산하면 같은 파일의 composite/coverage/distinct_axes와 다시
   일치한다(indicators 계층과 최상위 계층이 서로 다른 코드 경로로 얻어졌다는 사실
   자체가 상호검증 근거 — 하나가 깨지면 이 재계산이 어긋난다).
④ 퇴화 증인 — 존재하지 않는 창(빈 픽스처)은 export_window에서 명시적으로 실패한다
   (조용한 빈 산출물 금지, AAA §2.2).
"""

from __future__ import annotations

import hashlib
import json
from datetime import UTC, datetime
from pathlib import Path

import pytest

from backtest import export_parity, run_replay
from backtest.fixture_schema import load_windows
from engine_ref import registry, scoring

ALL_WINDOW_IDS = [w.window_id for w in load_windows()]
SAMPLE_WINDOW_ID = "w2024_05_calm"  # 골든 음성, 가장 작은 창(9틱) — 빠른 표본 비교용


def _sample_window():
    return next(w for w in load_windows() if w.window_id == SAMPLE_WINDOW_ID)


# -----------------------------------------------------------------------------
# fixture: 9창 전체를 두 번 별도 디렉터리로 export (①②의 공통 전제)
# -----------------------------------------------------------------------------


@pytest.fixture(scope="module")
def parity_two_runs(tmp_path_factory: pytest.TempPathFactory) -> tuple[Path, Path]:
    out_a = tmp_path_factory.mktemp("parity_a")
    out_b = tmp_path_factory.mktemp("parity_b")
    assert export_parity.main(["--window", "all", "--out-dir", str(out_a)]) == 0
    assert export_parity.main(["--window", "all", "--out-dir", str(out_b)]) == 0
    return out_a, out_b


# -----------------------------------------------------------------------------
# ① 규격 검증 — 4파일 존재 + 스키마
# -----------------------------------------------------------------------------


@pytest.mark.parametrize("window_id", ALL_WINDOW_IDS)
def test_window_produces_four_files_with_expected_schema(
    parity_two_runs: tuple[Path, Path], window_id: str
) -> None:
    out_a, _ = parity_two_runs
    wdir = out_a / window_id
    for name in ("raw.jsonl", "grid.json", "expected.jsonl", "MANIFEST.sha256"):
        assert (wdir / name).exists(), f"{window_id}: missing {name}"

    grid = json.loads((wdir / "grid.json").read_text(encoding="utf-8"))
    assert set(grid) == {
        "trading_days",
        "eval_start",
        "eval_end",
        "padding_days",
        "confirm_time_kst",
        "profile",
        "registry_version",
    }
    assert grid["trading_days"], f"{window_id}: empty trading_days"
    assert grid["profile"] == "mobile_daily"
    assert grid["padding_days"] == 550

    raw_lines = (wdir / "raw.jsonl").read_text(encoding="utf-8").splitlines()
    assert raw_lines, f"{window_id}: empty raw.jsonl"
    raw_row = json.loads(raw_lines[0])
    assert set(raw_row) == {"series_id", "field", "as_of", "value"}

    expected_lines = (wdir / "expected.jsonl").read_text(encoding="utf-8").splitlines()
    assert len(expected_lines) == len(grid["trading_days"]), (
        f"{window_id}: expected.jsonl tick count != trading_days"
    )
    rec = json.loads(expected_lines[0])
    assert set(rec) == {
        "evaluated_at",
        "kst_date",
        "indicators",
        "composite",
        "coverage",
        "distinct_axes",
        "any_crit",
        "any_extreme",
        "fired_axes",
        "phase",
    }
    assert rec["indicators"], f"{window_id}: empty indicators map"
    for layer in rec["indicators"].values():
        assert set(layer) == {"value", "as_of", "visible_at", "stale", "severity"}


@pytest.mark.parametrize("window_id", ALL_WINDOW_IDS)
def test_manifest_hashes_match_file_contents(
    parity_two_runs: tuple[Path, Path], window_id: str
) -> None:
    out_a, _ = parity_two_runs
    wdir = out_a / window_id
    lines = (wdir / "MANIFEST.sha256").read_text(encoding="utf-8").splitlines()
    assert len(lines) == 3
    seen: set[str] = set()
    for line in lines:
        digest, name = line.split("  ")
        seen.add(name)
        content = (wdir / name).read_bytes()
        assert hashlib.sha256(content).hexdigest() == digest, f"{window_id}/{name}"
    assert seen == {"raw.jsonl", "grid.json", "expected.jsonl"}


# -----------------------------------------------------------------------------
# ② 결정론 — 동일 입력 재실행 바이트 동일
# -----------------------------------------------------------------------------


@pytest.mark.parametrize("window_id", ALL_WINDOW_IDS)
def test_determinism_byte_identical_across_two_runs(
    parity_two_runs: tuple[Path, Path], window_id: str
) -> None:
    out_a, out_b = parity_two_runs
    for name in ("raw.jsonl", "grid.json", "expected.jsonl", "MANIFEST.sha256"):
        a = (out_a / window_id / name).read_bytes()
        b = (out_b / window_id / name).read_bytes()
        assert a == b, f"{window_id}/{name}: non-deterministic export output"


# -----------------------------------------------------------------------------
# ③ 표본 창 1개 — run_replay 직접 실행과 일치 + 내부 상호검증
# -----------------------------------------------------------------------------


def test_sample_window_expected_matches_direct_run_replay() -> None:
    window = next(w for w in load_windows() if w.window_id == SAMPLE_WINDOW_ID)
    records = export_parity.expected_records(
        window, export_parity.FIXTURES_DIR, export_parity.DEFAULT_INDICATORS_PATH
    )
    direct_ticks = run_replay.run_replay(
        profiles=["mobile_daily"], window_ids=[SAMPLE_WINDOW_ID]
    )["windows"][SAMPLE_WINDOW_ID]["mobile_daily"]["ticks"]

    assert len(records) == len(direct_ticks)
    for rec, tick in zip(records, direct_ticks, strict=True):
        assert rec["evaluated_at"] == tick["evaluated_at_utc"]
        assert rec["kst_date"] == tick["date"]
        assert rec["composite"] == tick["composite"]
        assert rec["coverage"] == tick["coverage"]
        assert rec["distinct_axes"] == tick["distinct_axes"]
        assert rec["any_crit"] == tick["any_crit"]
        assert rec["any_extreme"] == tick["any_extreme"]
        assert rec["fired_axes"] == tick["fired_axes"]
        assert rec["phase"] == tick["phase"]


def test_sample_window_indicator_severities_recompose_composite_and_coverage() -> None:
    """indicators 계층(개별 severity)과 최상위 composite/coverage/distinct_axes는
    export_parity 내부에서 서로 다른 호출 경로로 채워진다(§9-C L1~L3 vs L4~L5) —
    저장된 severity만으로 engine_ref.scoring을 재호출해도 같은 값이 나와야 두
    경로가 어긋나지 않았다는 증거가 된다."""
    window = next(w for w in load_windows() if w.window_id == SAMPLE_WINDOW_ID)
    records = export_parity.expected_records(
        window, export_parity.FIXTURES_DIR, export_parity.DEFAULT_INDICATORS_PATH
    )
    weights = registry.weight_map(
        enabled_only=True, path=export_parity.DEFAULT_INDICATORS_PATH
    )
    axes = registry.axis_map(
        enabled_only=True, path=export_parity.DEFAULT_INDICATORS_PATH
    )
    assert records
    for rec in records:
        severities = {
            iid: layer["severity"] for iid, layer in rec["indicators"].items()
        }
        recomputed = scoring.compute_composite(severities, weights)
        assert recomputed.score == rec["composite"]
        assert recomputed.coverage == rec["coverage"]
        assert scoring.distinct_axes(severities, axes) == rec["distinct_axes"]


# -----------------------------------------------------------------------------
# ④ 퇴화 증인 — 빈 픽스처는 조용히 성공하지 않고 명시적으로 실패한다
# -----------------------------------------------------------------------------


def test_export_window_raises_on_missing_fixture(tmp_path: Path) -> None:
    """실재하는 창(windows.yaml에 등록됨)이지만 그 픽스처 parquet가 아직 수집되지
    않은 상황(빈 fixtures_dir)을 재현한다 — 등록되지 않은 window_id를 지어내면
    run_replay.run_replay()의 내부 창 조회(KeyError)가 먼저 터져 이 테스트가
    검증하려는 "빈 픽스처" 경로 자체에 도달하지 못한다."""
    with pytest.raises(ValueError, match="empty trading-day grid"):
        export_parity.export_window(_sample_window(), tmp_path, fixtures_dir=tmp_path)


def test_raw_records_empty_for_missing_fixture(tmp_path: Path) -> None:
    assert export_parity.raw_records(_sample_window(), tmp_path) == []


def test_expected_records_empty_for_missing_fixture(tmp_path: Path) -> None:
    assert (
        export_parity.expected_records(
            _sample_window(), tmp_path, export_parity.DEFAULT_INDICATORS_PATH
        )
        == []
    )


def test_main_rejects_unknown_window_id(tmp_path: Path) -> None:
    with pytest.raises(SystemExit):
        export_parity.main(["--window", "w_does_not_exist", "--out-dir", str(tmp_path)])


# -----------------------------------------------------------------------------
# _indicator_layer 단위 테스트 — visible_at 이전 평가 시점(아직 알려지지 않은 값)
# -----------------------------------------------------------------------------


def test_indicator_layer_all_none_when_never_visible() -> None:
    specs = registry.load_indicator_specs(
        enabled_only=True, path=export_parity.DEFAULT_INDICATORS_PATH
    )
    spec = next(s for s in specs if s.id == "vix_level_z")
    empty_known = run_replay.KnownSeries(row_dates=[], visibility_ts=[], values=[])
    runtime = {"kind": "simple", "known": empty_known}
    hy_rule, fx_rule = registry.load_modifiers(
        path=export_parity.DEFAULT_INDICATORS_PATH
    )
    stale_windows = run_replay.load_stale_windows(
        ("mobile_daily",), {"daily_us"}, export_parity.DEFAULT_INDICATORS_PATH
    )
    layer = export_parity._indicator_layer(
        spec,
        runtime,
        datetime(2024, 1, 1, tzinfo=UTC),
        hy_rule,
        fx_rule,
        stale_windows,
    )
    assert layer == {
        "value": None,
        "as_of": None,
        "visible_at": None,
        "stale": False,
        "severity": None,
    }


def test_indicator_layer_none_for_combine_max_and_always_missing_kinds() -> None:
    """spx_drawdown_momentum(combine_max)·kr_cds_5y_delta(always_none, G-4 미수집)는
    단일 원값이 없다 — value/as_of/visible_at은 None, severity만 채워진다."""
    specs = {
        s.id: s
        for s in registry.load_indicator_specs(
            enabled_only=True, path=export_parity.DEFAULT_INDICATORS_PATH
        )
    }
    hy_rule, fx_rule = registry.load_modifiers(
        path=export_parity.DEFAULT_INDICATORS_PATH
    )
    stale_windows = run_replay.load_stale_windows(
        ("mobile_daily",), {"daily_kr"}, export_parity.DEFAULT_INDICATORS_PATH
    )

    always_none_layer = export_parity._indicator_layer(
        specs["kr_cds_5y_delta"],
        {"kind": "always_none"},
        datetime(2024, 1, 1, tzinfo=UTC),
        hy_rule,
        fx_rule,
        stale_windows,
    )
    assert always_none_layer == {
        "value": None,
        "as_of": None,
        "visible_at": None,
        "stale": False,
        "severity": None,
    }

    combine_max_runtime = {
        "kind": "combine_max",
        "known_a": run_replay.KnownSeries([], [], []),
        "known_b": run_replay.KnownSeries([], [], []),
        "cadence_a": "daily_us",
        "cadence_b": "daily_us",
    }
    combine_max_layer = export_parity._indicator_layer(
        specs["spx_drawdown_momentum"],
        combine_max_runtime,
        datetime(2024, 1, 1, tzinfo=UTC),
        hy_rule,
        fx_rule,
        stale_windows,
    )
    assert combine_max_layer["value"] is None
    assert combine_max_layer["stale"] is False
    assert combine_max_layer["severity"] is None
