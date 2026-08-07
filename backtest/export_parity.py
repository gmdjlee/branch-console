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
from datetime import date, datetime
from pathlib import Path
from typing import Any

import pandas as pd

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from backtest import run_replay
from backtest.fixture_schema import (
    WindowDef,
    load_windows,
    rows_to_frame,
    validate_fixture,
)
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

# MT1-05j aaa CONDITIONAL 해소: §9-C가 "고정 포함"으로 못박은 퇴화 입력 증인 4종
# (전 지표 결측·단일 지표만 유효·스테일 경계 등호·임계 경계 등호)은 9개 실측 창에
# 자연 발화가 0~우연 3회뿐이라 패리티 스위트의 실질 증거가 되지 못한다(aaa 지적).
# 아래 합성 창이 이 4종을 실제 engine_ref 계산 경로로 강제 유도한다 — **실데이터 아님**,
# backtest/windows.yaml(BT-01 9창 SSOT)에는 등재하지 않는다(BT-03 스윕·BT-04 리포트
# 실측 통계 오염 방지). 픽스처도 real 9창과 분리된 backtest/fixtures_synthetic/에 둔다.
SYNTHETIC_WINDOW_ID = "wsynth_degenerate"
SYNTHETIC_FIXTURES_DIR = REPO_ROOT / "backtest" / "fixtures_synthetic"

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
    dict[str, float],
    dict[str, str],
    registry.StatemachineConfig,
    dict[str, int] | None,
]:
    """L1~L3(지표별 레이어) + L4~L6(composite/coverage/phase) 양쪽이 필요로 하는 입력
    전부를 run_replay의 기존 로더로 채운다(재구현 없음 — registry.load_indicator_specs/
    weight_map/axis_map/load_modifiers/load_statemachine/max_severity_map + run_replay의
    fred_lag_days/load_schedule_times/load_mobile_confirm_time/load_stale_windows를
    run_replay.run_replay()와 동일한 순서로 호출).

    MT1-05j aaa CONDITIONAL 해소: 예전에는 이 함수가 L1~L3 몫만 채우고, L4~L6은
    `run_replay.run_replay()`를 통째로 호출해 따로 얻었다 — 그런데 `run_replay.run_replay()`
    는 window_id를 `backtest/windows.yaml` 레지스트리(`registry.load_windows()`)에서 찾는다.
    BT-05 합성 퇴화 증인 창(`wsynth_degenerate`)은 그 9창 SSOT에 등재하지 않는다(BT-03 스윕·
    BT-04 리포트의 실측 통계에 합성 창이 섞이면 안 되므로) — 따라서 `expected_records`가
    레지스트리를 거치지 않고 `run_replay.replay_window_profile()`을 직접 호출할 수 있도록
    이 함수가 그 나머지 인자(weights/axes/statemachine_config/max_severities)도 채운다.
    9개 실측 창은 `replay_window_profile()`이 `run_replay.run_replay()` 내부에서 호출되던
    것과 완전히 동일한 함수·동일한 인자이므로 산출값이 이전과 비트 동일하다(회귀 없음 —
    export_parity.py 재실행 후 9창 expected.jsonl sha256 무변경으로 실측 확인)."""
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
    weights = registry.weight_map(enabled_only=True, path=indicators_path)
    axes = registry.axis_map(enabled_only=True, path=indicators_path)
    statemachine_config = registry.load_statemachine()
    max_severities_raw = registry.max_severity_map(
        enabled_only=True, path=indicators_path
    )
    # run_replay.run_replay()와 동일한 정규화: 전 지표 max_severity==3(기본, 옵션 B 미사용)
    # 이면 None으로 둬 compute_composite가 원래 3-tier 산식과 완전히 같은 연산 순서를 탄다.
    max_severities = (
        None if all(v == 3 for v in max_severities_raw.values()) else max_severities_raw
    )
    return (
        indicator_specs,
        hy_rule,
        fx_rule,
        fred_lag,
        schedule_times,
        mobile_confirm_time,
        stale_windows,
        weights,
        axes,
        statemachine_config,
        max_severities,
    )


def expected_records(
    window: WindowDef, fixtures_dir: Path, indicators_path: Path
) -> list[dict[str, Any]]:
    (
        indicator_specs,
        hy_rule,
        fx_rule,
        fred_lag,
        schedule_times,
        mobile_confirm_time,
        stale_windows,
        weights,
        axes,
        statemachine_config,
        max_severities,
    ) = _load_indicator_layer_inputs(indicators_path)

    # window.window_id를 backtest/windows.yaml 레지스트리에서 찾지 않는다(MT1-05j aaa
    # CONDITIONAL 해소) — replay_window_profile()은 run_replay.run_replay()가 그 레지스트리
    # 조회 후 내부에서 호출하던 것과 완전히 동일한 함수·인자라 9개 실측 창 산출은 불변이고,
    # 합성 퇴화 증인 창(레지스트리 미등재)도 같은 경로로 흐른다.
    replay_out = run_replay.replay_window_profile(
        window,
        PROFILE,
        indicator_specs,
        weights,
        axes,
        hy_rule,
        fx_rule,
        fred_lag,
        schedule_times,
        mobile_confirm_time,
        statemachine_config,
        stale_windows,
        fixtures_dir=fixtures_dir,
        max_severities=max_severities,
    )
    tick_records = replay_out["ticks"]
    if not tick_records:
        return []

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
# synthetic degenerate-witness window (MT1-05j aaa CONDITIONAL) — §9-C 퇴화 입력 증인
# 4종을 실제 engine_ref 계산으로 강제 유도하는 5거래일짜리 합성 픽스처. 하드코딩된 기대값은
# 없다 — expected_records()가 다른 창과 완전히 같은 코드 경로로 값을 도출한다.
# -----------------------------------------------------------------------------


def _synthetic_degenerate_window() -> WindowDef:
    """D0..D4(2030-01-01..05, 순수 합성 날짜) 5거래일 그리드.

    유도 메커니즘(전부 engine_ref의 기존 규칙에서 자연히 나온다, 특수 케이스 코드 없음):
      - D0: ^VIX/^VIX3M as_of가 아직 어느 그리드일에도 가시화되지 않음(us_market은 T
        *다음* 그리드일부터 가시) -> vix_term_structure 포함 전 15지표 결측
        -> composite=None, 상태기계 그 틱 완전 동결(D-25 §3) — **증인 (i)**.
      - D1~D3: vix_term_structure만 유효(다른 14종은 원계열 부재 또는 웜업 부족 —
        window>=20~252 롤링에 5행으로는 절대 못 채운다 — 로 상시 결측)
        -> coverage = weight(vix_term_structure)/31.0, 단 하나의 지표만 분모·분자에
        기여 — **증인 (ii)**. 값=107.0/100.0=1.07(부동소수 정확히 crit 임계와 동일,
        `>=`가 "이상"이므로 severity=3) — **증인 (iv, 임계 경계 등호)**.
      - D3: evaluated_at - visible_at == 48h(daily_us의 mobile_daily stale 창) **정확히**
        -> "초과만 stale"이라 등호는 fresh 그대로 -> D3에도 severity=3 유지 — **증인
        (iii, 스테일 경계 등호)**.
      - D4: 위 경과가 72h로 48h를 넘겨 stale -> vix_term_structure도 결측으로 전환
        -> 전 15지표 결측 재현, composite=None 재동결 — 증인 (i) 재확인.

    그리드 뼈대는 KRX:investor_foreign_kospi(5행, 값 불변)다 — **KRX:1001이 아니다**
    (실측으로 발견: KRX:1001.close를 주면 kospi_volume_distribution의
    `gated(z, mask)`가 "게이트 거짓 -> 0.0"을 결측이 아니라 확정값으로 만들어(engine_ref.
    transforms.gated 규약 그대로) 이 지표가 D0부터 상시 활성화돼 증인 (i)(단일/전무 결측)를
    깨뜨린다). investor_foreign_kospi는 foreign_net_sell_kospi 하나만 쓰는데,
    `rolling_sum(window=5)`가 이 5행에서 값 1개를 내더라도 후속
    `neg_zscore(window=252)`가 그 1개로는 어차피 못 채워 항상 결측이라 완전히 잠잠하다 —
    kospi_drawdown·kospi_volume_distribution·vkospi_z·global_corr_break은 KRX:1001 자체가
    없으므로(빈 시리즈) 자연히 결측(Kotlin `gated()`도 0==0 크기 일치라 안전).
    """
    return WindowDef(
        window_id=SYNTHETIC_WINDOW_ID,
        start=date(2030, 1, 1),
        end=date(2030, 1, 5),
        anchor_hint=None,
        kind="synthetic",
        holdout=False,
        character="BT-05 합성 퇴화 입력 증인 — 실데이터 아님, C1/BT-03/BT-04 통계 대상 제외",
    )


def _write_synthetic_fixture() -> None:
    """합성 원계열을 `SYNTHETIC_FIXTURES_DIR`에 parquet으로 쓴다 — 이후 `raw_records`/
    `grid_record`/`expected_records`는 real 9창과 **완전히 동일한 코드**로 이 파일을
    읽는다(신규 export 경로 없음, `fixtures_dir` 인자를 바꿔 주는 것뿐)."""
    days = [date(2030, 1, d) for d in range(1, 6)]
    # KRX:investor_foreign_kospi를 그리드 뼈대로 쓴다 — KRX:1001은 쓰지 않는다(위
    # _synthetic_degenerate_window docstring의 gated() 함정 참조). KRX:1001을 아예
    # 안 주면 close/trading_value 둘 다 빈 시리즈(길이 0)라 Kotlin gated()의 크기
    # 일치 요구도 0==0으로 자동 통과한다.
    rows: list[tuple[str, str, Any, float]] = [
        ("KRX:investor_foreign_kospi", "net_buy_value", d, 0.0) for d in days
    ]
    rows += [
        ("^VIX", "close", days[0], 107.0),
        ("^VIX3M", "close", days[0], 100.0),
    ]
    df = (
        rows_to_frame(rows)
        .sort_values(["series_id", "field", "as_of"])
        .reset_index(drop=True)
    )
    validate_fixture(
        df
    )  # 자기 검증 — 스키마를 벗어난 합성 데이터를 조용히 내보내지 않는다.
    SYNTHETIC_FIXTURES_DIR.mkdir(parents=True, exist_ok=True)
    df.to_parquet(
        SYNTHETIC_FIXTURES_DIR / f"{SYNTHETIC_WINDOW_ID}.parquet", index=False
    )


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
    synthetic_window = _synthetic_degenerate_window()
    known_ids = {*all_windows, synthetic_window.window_id}
    if args.window != "all" and args.window not in known_ids:
        parser.error(f"unknown window_id: {args.window}")
    window_ids = list(known_ids) if args.window == "all" else [args.window]

    out_dir = Path(args.out_dir)
    indicators_path = Path(args.config)
    for wid in window_ids:
        if wid == synthetic_window.window_id:
            _write_synthetic_fixture()
            window_dir = export_window(
                synthetic_window,
                out_dir,
                fixtures_dir=SYNTHETIC_FIXTURES_DIR,
                indicators_path=indicators_path,
            )
            print(
                f"[{wid}] exported (SYNTHETIC witness window, not in windows.yaml SSOT) -> {window_dir}"
            )
        else:
            window_dir = export_window(
                all_windows[wid], out_dir, indicators_path=indicators_path
            )
            print(f"[{wid}] exported -> {window_dir}")
    return 0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
