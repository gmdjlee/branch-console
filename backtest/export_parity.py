"""backtest/export_parity.py — MT1-05e: BT-05 Kotlin 패리티 주입 산출물 export.

브리프 정본: docs/plans/M1_PLAN_C.md §9-C(4파일 규격) + docs/plans/M1_PLAN_B.md §8
(패리티 판정). 9창(backtest/windows.yaml) x mobile_daily 프로파일을 리플레이해
Kotlin `:core:engine` 패리티 테스트가 주입받을 4개 파일을 창별로 만든다:

    backtest/parity/<window_id>/
        raw.jsonl        — 픽스처 parquet 롱포맷 그대로 (L0)
        grid.json         — 거래일 그리드 + confirm_time_kst + padding_days (전 계층 공용)
        expected.jsonl    — 틱별 기대값: 지표별 value/as_of/visible_at/stale/severity (L1~L3)
                            + composite/coverage/distinct_axes/any_crit/any_extreme/
                            fired_axes(L4~L5) + phase(L6 타임라인)
        MANIFEST.sha256   — 위 3파일의 SHA-256 (K-16류 드리프트 방어)

로직 재구현 없음: 가시성·스테일·severity·composite·상태기계는 전부
`backtest.run_replay`의 기존 공개 함수(resolve_severity·lookup_known·is_stale_check·
build_indicator_runtime·build_tick_grid·trading_days·run_replay)를 그대로 호출해
얻는다 — 이 모듈은 그 출력을 계층별로 분해해 파일로 내보내는 배선만 한다.

산출물은 회귀 재생성 가능한 빌드 산출물이라 git에 커밋하지 않는다(.gitignore —
backtest/results/도 동일 관례). 재생성:

    uv run python backtest/export_parity.py --window all

결정론: 실행 시각·난수 등 비결정 입력 없음(모든 JSON은 sort_keys=True로 직렬화,
raw.jsonl은 (series_id, field, as_of) 정렬 고정) — 동일 입력 재실행은 바이트 동일.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime
from pathlib import Path
from typing import Any

import pandas as pd

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from backtest import run_replay
from backtest.fixture_schema import WindowDef, load_windows
from backtest.fixture_schema import padding_days as fixture_padding_days
from backtest.run_replay import Profile
from engine_ref import registry
from engine_ref.registry import HyLevelBoost, IndicatorSpec, UsdkrwIntradayForce

REPO_ROOT = Path(__file__).resolve().parent.parent
FIXTURES_DIR = REPO_ROOT / "backtest" / "fixtures"
PARITY_DIR = REPO_ROOT / "backtest" / "parity"
DEFAULT_INDICATORS_PATH = run_replay.DEFAULT_INDICATORS_PATH
REPLAY_YAML_PATH = run_replay.REPLAY_YAML_PATH
STATEMACHINE_YAML_PATH = run_replay.STATEMACHINE_YAML_PATH

# 앱은 mobile_daily만 실행한다 — 패리티 게이트도 이 프로파일 하나만 필요
# (M1_PLAN_B §8.2: server_intraday 9창은 선택/진단 전용, 게이트 아님).
PROFILE: Profile = "mobile_daily"


def _dumps_compact(obj: Any) -> str:
    """jsonl 1행용 결정론 직렬화(줄바꿈 없는 컴팩트 표현, 키 정렬)."""
    return json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _dumps_pretty(obj: Any) -> str:
    """단일 JSON 오브젝트 파일용 결정론 직렬화(가독성 + 키 정렬)."""
    return json.dumps(obj, ensure_ascii=False, sort_keys=True, indent=2)


# -----------------------------------------------------------------------------
# raw.jsonl — 픽스처 parquet 롱포맷 그대로 (§9-C L0, 웜업 포함 범위는 픽스처가 이미
# window.start - padding_days부터 수집돼 있다, backtest/build_fixtures.py 산출물)
# -----------------------------------------------------------------------------


def raw_records(window: WindowDef, fixtures_dir: Path) -> list[dict[str, Any]]:
    fixture_path = fixtures_dir / f"{window.window_id}.parquet"
    if not fixture_path.exists():
        return []
    df = pd.read_parquet(fixture_path)
    df = df.sort_values(["series_id", "field", "as_of"]).reset_index(drop=True)
    return [
        {
            "series_id": row.series_id,
            "field": row.field,
            "as_of": row.as_of.date().isoformat(),
            "value": float(row.value),
        }
        for row in df.itertuples(index=False)
    ]


# -----------------------------------------------------------------------------
# grid.json — 거래일 그리드 + confirm_time_kst + padding_days (전 계층 공용, §9-C)
# -----------------------------------------------------------------------------


def grid_record(
    window: WindowDef, fixtures_dir: Path, indicators_path: Path
) -> dict[str, Any]:
    fixture_path = fixtures_dir / f"{window.window_id}.parquet"
    df = pd.read_parquet(fixture_path) if fixture_path.exists() else pd.DataFrame()
    trading_days = run_replay.trading_days(df, window)
    indicators_cfg = run_replay._load_yaml(indicators_path)
    replay_cfg = run_replay._load_yaml(REPLAY_YAML_PATH)
    return {
        "trading_days": [d.isoformat() for d in trading_days],
        "eval_start": window.start.isoformat(),
        "eval_end": window.end.isoformat(),
        "padding_days": fixture_padding_days(),
        "confirm_time_kst": replay_cfg["profiles"]["mobile_daily"]["confirm_time_kst"],
        "profile": PROFILE,
        "registry_version": indicators_cfg.get("registry_version", "?"),
    }


# -----------------------------------------------------------------------------
# expected.jsonl — 계층별 기대값 (§9-C L1~L6). 전부 run_replay의 기존 함수 재사용:
# composite/coverage/distinct_axes/any_crit/any_extreme/fired_axes/phase는
# run_replay.run_replay()(골든·metrics.json과 동일 경로)에서, 지표별 value/as_of/
# visible_at/stale/severity는 resolve_severity/lookup_known/is_stale_check(같은
# 함수, resolve_severity가 내부적으로 쓰는 것과 동일 호출)에서 얻는다.
# -----------------------------------------------------------------------------


def _indicator_layer(
    spec: IndicatorSpec,
    runtime: dict[str, Any],
    evaluated_at: datetime,
    hy_rule: HyLevelBoost,
    fx_rule: UsdkrwIntradayForce,
    stale_windows: dict[tuple[str, str], Any],
) -> dict[str, Any]:
    """지표 1개 x 틱 1개의 L1~L3 원자료.

    "known"이 없는 runtime은 "always_none"(픽스처 미수집, K-04/G-4) 또는
    "combine_max"(spx_drawdown_momentum — dd/nz 두 원계열이라 단일 원값이 없다,
    M1_PLAN_B §8.5)뿐이다 — 그 경우 value/as_of/visible_at은 없고 severity만
    resolve_severity(재사용)로 채운다.
    """
    severity, _is_extreme = run_replay.resolve_severity(
        spec, runtime, evaluated_at, PROFILE, hy_rule, fx_rule, stale_windows
    )
    known = runtime.get("known")
    if known is None:
        return {
            "value": None,
            "as_of": None,
            "visible_at": None,
            "stale": False,
            "severity": severity,
        }
    looked = run_replay.lookup_known(known, evaluated_at)
    if looked is None:
        return {
            "value": None,
            "as_of": None,
            "visible_at": None,
            "stale": False,
            "severity": severity,
        }
    row_date, visible_at, value = looked
    stale = run_replay.is_stale_check(
        spec.source.get("cadence", ""), visible_at, evaluated_at, PROFILE, stale_windows
    )
    return {
        "value": value,
        "as_of": row_date.isoformat(),
        "visible_at": visible_at.isoformat(),
        "stale": stale,
        "severity": severity,
    }


def _load_indicator_layer_inputs(
    indicators_path: Path,
) -> tuple[
    list[IndicatorSpec],
    HyLevelBoost,
    UsdkrwIntradayForce,
    dict[str, int],
    dict[str, list[Any]],
    Any,
    dict[tuple[str, str], Any],
]:
    """지표별 L1~L3 계산에 필요한 입력을 run_replay의 기존 로더로 채운다(재구현
    없음 — registry.load_indicator_specs/load_modifiers + run_replay의
    fred_lag_days/load_schedule_times/load_mobile_confirm_time/load_stale_windows를
    run_replay.run_replay()와 동일하게 호출)."""
    indicator_specs = registry.load_indicator_specs(
        enabled_only=True, path=indicators_path
    )
    hy_rule, fx_rule = registry.load_modifiers(path=indicators_path)
    fred_lag = run_replay.fred_lag_days(indicator_specs)
    statemachine_cfg = run_replay._load_yaml(STATEMACHINE_YAML_PATH)
    schedule_times = run_replay.load_schedule_times(statemachine_cfg)
    replay_cfg = run_replay._load_yaml(REPLAY_YAML_PATH)
    mobile_confirm_time = run_replay.load_mobile_confirm_time(replay_cfg)
    cadences = {
        spec.source["cadence"] for spec in indicator_specs if "cadence" in spec.source
    }
    stale_windows = run_replay.load_stale_windows((PROFILE,), cadences, indicators_path)
    return (
        indicator_specs,
        hy_rule,
        fx_rule,
        fred_lag,
        schedule_times,
        mobile_confirm_time,
        stale_windows,
    )


def expected_records(
    window: WindowDef, fixtures_dir: Path, indicators_path: Path
) -> list[dict[str, Any]]:
    replay_out = run_replay.run_replay(
        profiles=[PROFILE],
        window_ids=[window.window_id],
        indicators_path=indicators_path,
        fixtures_dir=fixtures_dir,
    )
    tick_records = replay_out["windows"][window.window_id][PROFILE]["ticks"]
    if not tick_records:
        return []

    (
        indicator_specs,
        hy_rule,
        fx_rule,
        fred_lag,
        schedule_times,
        mobile_confirm_time,
        stale_windows,
    ) = _load_indicator_layer_inputs(indicators_path)

    fixture_path = fixtures_dir / f"{window.window_id}.parquet"
    df = pd.read_parquet(fixture_path) if fixture_path.exists() else pd.DataFrame()
    grid = run_replay.trading_days(df, window)
    ctx = run_replay._Ctx(
        df, grid, fred_lag, schedule_times, PROFILE, mobile_confirm_time
    )
    runtimes = {
        spec.id: run_replay.build_indicator_runtime(spec, ctx)
        for spec in indicator_specs
    }
    tick_grid = run_replay.build_tick_grid(
        grid, PROFILE, schedule_times, mobile_confirm_time
    )

    if len(tick_grid) != len(tick_records):  # pragma: no cover - defensive
        raise ValueError(
            f"{window.window_id}: tick grid length mismatch "
            f"({len(tick_grid)} vs {len(tick_records)}) — replay/export config drift"
        )

    records: list[dict[str, Any]] = []
    for (evaluated_at, _kst_date, _kst_label), rec in zip(
        tick_grid, tick_records, strict=True
    ):
        if (
            rec["evaluated_at_utc"] != evaluated_at.isoformat()
        ):  # pragma: no cover - defensive
            raise ValueError(
                f"{window.window_id}: tick order mismatch at {evaluated_at.isoformat()}"
            )
        indicators = {
            spec.id: _indicator_layer(
                spec, runtimes[spec.id], evaluated_at, hy_rule, fx_rule, stale_windows
            )
            for spec in indicator_specs
        }
        records.append(
            {
                "evaluated_at": rec["evaluated_at_utc"],
                "kst_date": rec["date"],
                "indicators": indicators,
                "composite": rec["composite"],
                "coverage": rec["coverage"],
                "distinct_axes": rec["distinct_axes"],
                "any_crit": rec["any_crit"],
                "any_extreme": rec["any_extreme"],
                "fired_axes": rec["fired_axes"],
                "phase": rec["phase"],
            }
        )
    return records


# -----------------------------------------------------------------------------
# 창 1개 -> 4파일
# -----------------------------------------------------------------------------


def export_window(
    window: WindowDef,
    out_dir: Path,
    fixtures_dir: Path = FIXTURES_DIR,
    indicators_path: Path = DEFAULT_INDICATORS_PATH,
) -> Path:
    """퇴화 증인: 창의 거래일 그리드가 비어 있으면(픽스처 결측/공백) 조용히 빈
    산출물을 쓰지 않고 즉시 예외로 실패한다 — AAA §2.2 "침묵의 실패 금지"."""
    grid = grid_record(window, fixtures_dir, indicators_path)
    if not grid["trading_days"]:
        raise ValueError(
            f"{window.window_id}: empty trading-day grid (no fixture rows in "
            "[start, end]) — refusing to emit a silently-empty parity export"
        )

    raw = raw_records(window, fixtures_dir)
    expected = expected_records(window, fixtures_dir, indicators_path)

    raw_bytes = "".join(_dumps_compact(r) + "\n" for r in raw).encode("utf-8")
    expected_bytes = "".join(_dumps_compact(r) + "\n" for r in expected).encode("utf-8")
    grid_bytes = (_dumps_pretty(grid) + "\n").encode("utf-8")

    # write_bytes (not write_text): write_text uses platform-default newline
    # translation, which on Windows rewrites "\n" -> "\r\n" on disk — the digest
    # below must be computed over the exact bytes that land on disk (M1_PLAN_B
    # §7.2 "다이제스트는 바이트 기준 — BOM·줄바꿈 문제를 원천 차단, Windows 필수").
    window_dir = out_dir / window.window_id
    window_dir.mkdir(parents=True, exist_ok=True)
    (window_dir / "raw.jsonl").write_bytes(raw_bytes)
    (window_dir / "grid.json").write_bytes(grid_bytes)
    (window_dir / "expected.jsonl").write_bytes(expected_bytes)

    manifest_lines = [
        f"{hashlib.sha256(raw_bytes).hexdigest()}  raw.jsonl",
        f"{hashlib.sha256(grid_bytes).hexdigest()}  grid.json",
        f"{hashlib.sha256(expected_bytes).hexdigest()}  expected.jsonl",
    ]
    (window_dir / "MANIFEST.sha256").write_bytes(
        ("\n".join(manifest_lines) + "\n").encode("utf-8")
    )
    return window_dir


# -----------------------------------------------------------------------------
# CLI
# -----------------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # K-xx cp949 콘솔 함정

    parser = argparse.ArgumentParser(
        description="MT1-05e: BT-05 Kotlin parity injection export"
    )
    parser.add_argument("--window", default="all", help="window_id or 'all'")
    parser.add_argument("--out-dir", default=str(PARITY_DIR))
    parser.add_argument(
        "--config",
        default=str(DEFAULT_INDICATORS_PATH),
        help="indicators.yaml 경로 (기본 configs/indicators.yaml)",
    )
    args = parser.parse_args(argv)

    all_windows = {w.window_id: w for w in load_windows()}
    if args.window != "all" and args.window not in all_windows:
        parser.error(f"unknown window_id: {args.window}")
    window_ids = list(all_windows) if args.window == "all" else [args.window]

    out_dir = Path(args.out_dir)
    indicators_path = Path(args.config)
    for wid in window_ids:
        window_dir = export_window(
            all_windows[wid], out_dir, indicators_path=indicators_path
        )
        print(f"[{wid}] exported -> {window_dir}")
    return 0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
