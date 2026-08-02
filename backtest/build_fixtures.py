"""BT-01 fixture builder — retroactive collection CLI for the 9 backtest windows.

    uv run python backtest/build_fixtures.py --window <id|all> [--force]

근사-PIT (D-19, BACKTEST_PLAN §5): retroactive pulls can include revised values —
this is NOT a true point-in-time ledger. C1 will re-confirm against the real lake.
FRED lag_days and other availability delays are NOT applied here (that is the
replay layer's job, MT0-04, per D-06) — as_of records the observation date only.

Design: the fetch layer (Fetcher protocol / LiveFetcher) is injectable so tests can
substitute a stub with no network access. All schema/derivation/normalization logic
lives in backtest.fixture_schema (pure, no I/O); this module handles HTTP/pykrx calls,
on-disk raw-response caching, retry/rate-limit policy (from configs/sources.yaml —
SSOT, no hardcoded thresholds), and the CLI.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from collections.abc import Callable, Sequence
from datetime import UTC, date, datetime, timedelta
from pathlib import Path
from typing import Any, Protocol

if __package__ in (None, ""):
    # `uv run python backtest/build_fixtures.py` (the documented CLI form, see
    # .claude/skills/backtest-run/SKILL.md) runs this file with no package context,
    # so the repo root isn't on sys.path and `import backtest.fixture_schema` below
    # would fail. Put it there before that import runs.
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import pandas as pd
import requests
import yaml

from backtest.fixture_schema import (
    STATUS_BLOCKED_MISSING_API_KEY,
    STATUS_BLOCKED_MISSING_CREDENTIALS,
    STATUS_EMPTY,
    STATUS_ERROR,
    STATUS_OK,
    STATUS_UNCOLLECTED,
    CollectionPlan,
    PykrxSeries,
    WindowDef,
    axis_coverage_weights,
    axis_weights,
    calendar_kind,
    derive_collection_plan,
    empty_long_frame,
    eval_observed_dates,
    find_gaps,
    fx_advisory_gaps,
    load_windows,
    missing_rate,
    normalize_fred,
    normalize_pykrx_index_ohlcv,
    normalize_pykrx_investor_value,
    normalize_pykrx_vkospi,
    normalize_yfinance,
    padding_days,
    validate_fixture,
)

REPO_ROOT = Path(__file__).resolve().parent.parent
CONFIGS_DIR = REPO_ROOT / "configs"
FIXTURES_DIR = REPO_ROOT / "backtest" / "fixtures"
CACHE_DIR_NAME = "_cache"
FRED_OBSERVATIONS_URL = "https://api.stlouisfed.org/fred/series/observations"
FIXTURE_COLUMNS_KEY = ("series_id", "field", "as_of")


# -----------------------------------------------------------------------------
# injectable fetch layer
# -----------------------------------------------------------------------------


class Fetcher(Protocol):
    """Raw-payload fetch interface. Real network calls live in LiveFetcher; tests
    substitute a stub with canned DataFrames/lists (no network)."""

    def yfinance(self, symbol: str, start: date, end: date) -> pd.DataFrame: ...
    def fred(self, series_id: str, start: date, end: date) -> list[dict[str, Any]]: ...
    def pykrx_index_ohlcv(
        self, symbol: str, start: date, end: date
    ) -> pd.DataFrame: ...
    def pykrx_investor_value(
        self, market: str, investor: str, start: date, end: date
    ) -> pd.DataFrame: ...
    def pykrx_vkospi(self, start: date, end: date) -> pd.DataFrame: ...


class LiveFetcher:
    """Real yfinance/FRED/pykrx calls. pykrx is imported lazily inside each method:
    it requires setuptools/pkg_resources at import time, which is not guaranteed
    present in every venv (observed missing in the MT0-03 dev environment) — a
    module-level import would break importing this file (and thus the stub-based
    tests) even though no pykrx call is ever made in tests."""

    def yfinance(self, symbol: str, start: date, end: date) -> pd.DataFrame:
        import yfinance as yf

        return yf.download(
            symbol,
            start=start.isoformat(),
            end=(end + timedelta(days=1)).isoformat(),
            progress=False,
            auto_adjust=False,
        )

    def fred(self, series_id: str, start: date, end: date) -> list[dict[str, Any]]:
        resp = requests.get(
            FRED_OBSERVATIONS_URL,
            params={
                "series_id": series_id,
                "observation_start": start.isoformat(),
                "observation_end": end.isoformat(),
                "api_key": os.environ["FRED_API_KEY"],
                "file_type": "json",
            },
            timeout=30,
        )
        resp.raise_for_status()
        return resp.json().get("observations", [])

    def pykrx_index_ohlcv(self, symbol: str, start: date, end: date) -> pd.DataFrame:
        from pykrx import stock

        return stock.get_index_ohlcv_by_date(
            start.strftime("%Y%m%d"), end.strftime("%Y%m%d"), symbol
        )

    def pykrx_investor_value(
        self, market: str, investor: str, start: date, end: date
    ) -> pd.DataFrame:
        from pykrx import stock

        return stock.get_market_trading_value_by_date(
            start.strftime("%Y%m%d"), end.strftime("%Y%m%d"), market, on="순매수"
        )

    def pykrx_vkospi(self, start: date, end: date) -> pd.DataFrame:
        from pykrx import stock

        ticker = _resolve_vkospi_ticker(stock)
        if ticker is None:
            raise RuntimeError(
                "VKOSPI ticker not resolvable via pykrx index metadata (K-02) — "
                "needs data-verifier confirmation; falls back to engine-side realized_vol_kospi_20d"
            )
        return stock.get_index_ohlcv_by_date(
            start.strftime("%Y%m%d"), end.strftime("%Y%m%d"), ticker
        )


def _resolve_vkospi_ticker(stock_module: Any) -> str | None:
    """Best-effort VKOSPI ticker lookup by name over pykrx index metadata (K-02:
    no dedicated pykrx VKOSPI function is known to exist)."""
    for market in ("KRX", "KOSPI", "테마"):
        try:
            tickers = stock_module.get_index_ticker_list(market=market)
        except Exception:  # noqa: BLE001, S112 - K-01/K-02 style: probe and move on, never crash the run
            continue
        for ticker in tickers:
            try:
                name = stock_module.get_index_ticker_name(ticker)
            except Exception:  # noqa: BLE001, S112
                continue
            if "VKOSPI" in name.upper():
                return ticker
    return None


# -----------------------------------------------------------------------------
# retry / rate-limit policy (from configs/sources.yaml — SSOT, no hardcoding)
# -----------------------------------------------------------------------------


def _load_yaml(path: Path) -> dict[str, Any]:
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)


def _yfinance_retry_policy(sources_cfg: dict[str, Any]) -> tuple[int, list[float]]:
    retry = sources_cfg["providers"]["yfinance"]["retry"]
    return int(retry["attempts"]), [float(x) for x in retry["backoff_s"]]


def _pykrx_min_interval_s(sources_cfg: dict[str, Any]) -> float:
    return float(sources_cfg["providers"]["pykrx"]["rate_limit"]["min_interval_s"])


def _with_retry(
    fn: Callable[[], Any],
    attempts: int,
    backoff_s: Sequence[float],
    sleeper: Callable[[float], None],
) -> Any:
    """K-01: retries per configs/sources.yaml policy, then re-raises for the caller
    to record as a missing/error status (never propagate past the per-series boundary)."""
    last_exc: Exception | None = None
    for i in range(max(1, attempts)):
        try:
            return fn()
        except Exception as exc:  # noqa: BLE001 - deliberately broad: any provider failure is retried/recorded
            last_exc = exc
            if i < attempts - 1:
                sleeper(backoff_s[min(i, len(backoff_s) - 1)] if backoff_s else 0.0)
    assert last_exc is not None
    raise last_exc


# -----------------------------------------------------------------------------
# on-disk raw-response cache (per window, per series) — K-03: avoid re-hitting KRX
# -----------------------------------------------------------------------------


def _safe_key(key: str) -> str:
    for ch in ("/", ":", "=", "^", "."):
        key = key.replace(ch, "_")
    return key


def _range_key(collect_start: date, collect_end: date, pad_days: int) -> str:
    """MT0-03 aaa-critic DEF-5: cache key must include the requested collection
    range, so a shifted range (e.g. collect_end = min(window.end, today) advancing
    on a later run) is a cache miss, not a silent stale reuse whose meta then
    overstates actual coverage."""
    return f"{collect_start.isoformat()}_{collect_end.isoformat()}_p{pad_days}"


def _cache_path(
    fixtures_dir: Path, window_id: str, key: str, range_key: str, ext: str
) -> Path:
    cache_dir = fixtures_dir / CACHE_DIR_NAME / window_id
    cache_dir.mkdir(parents=True, exist_ok=True)
    return cache_dir / f"{_safe_key(key)}__{_safe_key(range_key)}.{ext}"


def _load_or_fetch_frame(
    path: Path, force: bool, fetch: Callable[[], pd.DataFrame]
) -> pd.DataFrame:
    if path.exists() and not force:
        return pd.read_parquet(path)
    df = fetch()
    if df is not None and not df.empty:
        df.to_parquet(path)
    return df if df is not None else pd.DataFrame()


def _load_or_fetch_json(
    path: Path, force: bool, fetch: Callable[[], list[dict[str, Any]]]
) -> list[dict[str, Any]]:
    if path.exists() and not force:
        return json.loads(path.read_text(encoding="utf-8"))
    data = fetch()
    path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    return data


# -----------------------------------------------------------------------------
# per-series pykrx dispatch
# -----------------------------------------------------------------------------


def _fetch_pykrx(
    fetcher: Fetcher, spec: PykrxSeries, start: date, end: date
) -> pd.DataFrame:
    if spec.dataset == "index_ohlcv":
        return fetcher.pykrx_index_ohlcv(spec.params["symbol"], start, end)
    if spec.dataset == "investor_trading_value":
        return fetcher.pykrx_investor_value(
            spec.params["market"], spec.params["investor"], start, end
        )
    if spec.dataset == "vkospi":
        return fetcher.pykrx_vkospi(start, end)
    raise ValueError(f"unknown pykrx dataset: {spec.dataset}")


def _normalize_pykrx(spec: PykrxSeries, raw: pd.DataFrame) -> pd.DataFrame:
    if spec.dataset == "index_ohlcv":
        return normalize_pykrx_index_ohlcv(raw, spec.series_id, spec.fields)
    if spec.dataset == "investor_trading_value":
        return normalize_pykrx_investor_value(
            raw, spec.series_id, spec.params["investor"]
        )
    if spec.dataset == "vkospi":
        return normalize_pykrx_vkospi(raw, spec.series_id)
    raise ValueError(f"unknown pykrx dataset: {spec.dataset}")


def _series_meta_entry(
    norm: pd.DataFrame,
    status: str,
    message: str,
    eval_start: date,
    eval_end: date,
    reference: Sequence[date],
    is_fx: bool = False,
) -> dict[str, Any]:
    """Per-series meta over the window's EVALUATION range [eval_start, eval_end]
    only — padding is excluded from both numerator and denominator (aaa-critic
    DEF-1). `reference` is the EMPIRICAL union of observed dates across every
    series sharing this one's calendar_kind (Advisor MT0-03 v3 §A) — never an
    external calendar package, which can silently drift out of date with reality
    (NEW-1: exchange_calendars' XKRX missing 2026 ad-hoc KR holidays turned into
    phantom gaps that the old binary axis rollup amplified into a false 0%).
    distinct_days counts unique as_of dates, not rows, so a multi-field series
    (e.g. KRW=X OHLC) can't multiply its own denominator away.

    is_fx: reference is trivially this series' own dates for a single-member
    calendar_kind (no independent basis), so missing_rate is 0 whenever it has
    at least one observation — attach advisory-only head/tail/internal (or, on
    total failure, no_observations) gap facts instead (§B, F3-1 point 2).
    """
    as_of_dates = sorted({ts.date() for ts in norm["as_of"]}) if not norm.empty else []
    eval_days = {d for d in as_of_dates if eval_start <= d <= eval_end}
    padding_rows = (
        int((norm["as_of"].dt.date < eval_start).sum()) if not norm.empty else 0
    )
    gaps = find_gaps(reference, eval_days)
    entry: dict[str, Any] = {
        "status": status,
        "distinct_days": len(eval_days),
        "reference_days": len(reference),
        "missing_rate": round(missing_rate(reference, eval_days), 4),
        "last_as_of": max(as_of_dates).isoformat() if as_of_dates else None,
        "padding_rows": padding_rows,
        "gaps": [
            {"start": g[0].isoformat(), "end": g[1].isoformat(), "sessions": g[2]}
            for g in gaps
        ],
    }
    if is_fx:
        entry["fx_advisory_gaps"] = fx_advisory_gaps(eval_days, eval_start, eval_end)
    if message:
        entry["message"] = message
    return entry


def _uncollected_meta_entry(reason: str) -> dict[str, Any]:
    """Meta for an indicator never attempted (disabled/out-of-M0-scope) — there is
    no series_id/calendar_kind to compute a real denominator against."""
    return {
        "status": STATUS_UNCOLLECTED,
        "distinct_days": 0,
        "reference_days": 0,
        "missing_rate": 1.0,
        "last_as_of": None,
        "padding_rows": 0,
        "gaps": [],
        "message": reason,
    }


# -----------------------------------------------------------------------------
# window pipeline
# -----------------------------------------------------------------------------


def build_window_fixture(
    window: WindowDef,
    plan: CollectionPlan,
    fetcher: Fetcher,
    fixtures_dir: Path,
    sources_cfg: dict[str, Any],
    force: bool = False,
    sleeper: Callable[[float], None] = time.sleep,
) -> tuple[pd.DataFrame, dict[str, Any]]:
    """Collect one window's fixture: yfinance + FRED + pykrx series, normalized to the
    long fixture schema, plus a meta dict (definition echo, coverage, per-series status).
    Never raises on a single series' failure (K-01) — records status and continues.
    """
    pad_days = padding_days()
    collect_start = window.start - timedelta(days=pad_days)
    collect_end = min(window.end, datetime.now(UTC).date())
    # evaluation range (MT0-03 aaa-critic DEF-1): padding is warmup only, never part
    # of the denominator/numerator that missing_rate/longest_gap are computed over.
    eval_start, eval_end = window.start, collect_end
    range_key = _range_key(collect_start, collect_end, pad_days)

    retry_attempts, retry_backoff = _yfinance_retry_policy(sources_cfg)
    pykrx_min_interval = _pykrx_min_interval_s(sources_cfg)

    frames: list[pd.DataFrame] = [empty_long_frame()]
    # Pass 1: fetch + normalize every series. Meta construction is deferred to pass
    # 2 (Advisor MT0-03 v3 §A) — missing_rate needs the empirical reference union
    # across a whole calendar_kind, which isn't known until every series in this
    # window has been fetched.
    raw_results: dict[str, tuple[str, str, pd.DataFrame]] = {}

    for spec in plan.yfinance:
        cache_path = _cache_path(
            fixtures_dir,
            window.window_id,
            f"yfinance_{spec.symbol}",
            range_key,
            "parquet",
        )
        try:
            raw = _load_or_fetch_frame(
                cache_path,
                force,
                lambda s=spec: _with_retry(
                    lambda: fetcher.yfinance(s.symbol, collect_start, collect_end),
                    retry_attempts,
                    retry_backoff,
                    sleeper,
                ),
            )
            norm = normalize_yfinance(raw, spec.series_id, spec.fields)
            status, message = (
                (STATUS_OK, "")
                if not norm.empty
                else (STATUS_EMPTY, "no rows returned")
            )
        except Exception as exc:  # noqa: BLE001 - K-01: record and continue, never propagate
            norm, status, message = empty_long_frame(), STATUS_ERROR, str(exc)
        raw_results[spec.series_id] = (status, message, norm)
        frames.append(norm)

    fred_api_key = os.environ.get("FRED_API_KEY")
    for spec in plan.fred:
        if not fred_api_key:
            raw_results[spec.series_id] = (
                STATUS_BLOCKED_MISSING_API_KEY,
                "FRED_API_KEY not set in environment",
                empty_long_frame(),
            )
            continue
        cache_path = _cache_path(
            fixtures_dir, window.window_id, f"fred_{spec.series_id}", range_key, "json"
        )
        try:
            raw = _load_or_fetch_json(
                cache_path,
                force,
                lambda s=spec: fetcher.fred(
                    s.fred_series_id, collect_start, collect_end
                ),
            )
            norm = normalize_fred(raw, spec.series_id)
            status, message = (
                (STATUS_OK, "")
                if not norm.empty
                else (STATUS_EMPTY, "no observations returned")
            )
        except Exception as exc:  # noqa: BLE001
            norm, status, message = empty_long_frame(), STATUS_ERROR, str(exc)
        raw_results[spec.series_id] = (status, message, norm)
        frames.append(norm)

    # K-03 + Advisor MT0-03 재위임 §D: pykrx 1.2.8 requires KRX login credentials.
    # Check before entering the pykrx path at all (same pattern as FRED's API key
    # gate above) so a missing-credentials environment never gets misdiagnosed as
    # a KRX server block (that oversight cost a full round in this task already).
    krx_id, krx_pw = os.environ.get("KRX_ID"), os.environ.get("KRX_PW")
    for spec in plan.pykrx:
        if not (krx_id and krx_pw):
            raw_results[spec.series_id] = (
                STATUS_BLOCKED_MISSING_CREDENTIALS,
                "KRX_ID/KRX_PW not set in environment",
                empty_long_frame(),
            )
            continue
        sleeper(pykrx_min_interval)  # K-03: rate limit before every KRX call
        cache_path = _cache_path(
            fixtures_dir,
            window.window_id,
            f"pykrx_{spec.series_id}",
            range_key,
            "parquet",
        )
        try:
            raw = _load_or_fetch_frame(
                cache_path,
                force,
                lambda s=spec: _fetch_pykrx(fetcher, s, collect_start, collect_end),
            )
            norm = _normalize_pykrx(spec, raw)
            status, message = (
                (STATUS_OK, "")
                if not norm.empty
                else (STATUS_EMPTY, "no rows returned")
            )
        except Exception as exc:  # noqa: BLE001 - K-01/K-02: record and continue
            norm, status, message = empty_long_frame(), STATUS_ERROR, str(exc)
        raw_results[spec.series_id] = (status, message, norm)
        frames.append(norm)

    # Pass 2: group by calendar_kind, build the empirical reference union per
    # kind, then the final per-series meta entry against that reference.
    kind_members: dict[str, list[str]] = {}
    kind_union: dict[str, set[date]] = {}
    for series_id, (_status, _message, norm) in raw_results.items():
        kind = calendar_kind(series_id)
        kind_members.setdefault(kind, []).append(series_id)
        kind_union.setdefault(kind, set()).update(
            eval_observed_dates(norm, eval_start, eval_end)
        )

    series_meta: dict[str, Any] = {}
    for series_id, (status, message, norm) in raw_results.items():
        kind = calendar_kind(series_id)
        reference = sorted(kind_union[kind])
        is_fx = len(kind_members[kind]) == 1
        series_meta[series_id] = _series_meta_entry(
            norm, status, message, eval_start, eval_end, reference, is_fx=is_fx
        )

    for ex in plan.excluded:
        series_meta[ex.indicator_id] = _uncollected_meta_entry(ex.reason)

    combined = pd.concat(frames, ignore_index=True)
    if not combined.empty:
        combined = (
            combined.sort_values(list(FIXTURE_COLUMNS_KEY))
            .drop_duplicates(subset=list(FIXTURE_COLUMNS_KEY), keep="last")
            .reset_index(drop=True)
        )
    validate_fixture(combined)

    meta = {
        "schema": "backtest-fixture-meta/1",
        "window_id": window.window_id,
        "definition": {
            "start": window.start.isoformat(),
            "end": window.end.isoformat(),
            "anchor_hint": window.anchor_hint.isoformat()
            if window.anchor_hint
            else None,
            "kind": window.kind,
            "holdout": window.holdout,
            "character": window.character,
        },
        "collected_range": {
            "start": collect_start.isoformat(),
            "end": collect_end.isoformat(),
        },
        "collected_at": datetime.now(UTC).isoformat(),
        "series": series_meta,
        "note": "근사-PIT — C1에서 실측 확정 (BACKTEST_PLAN.md §5)",
    }
    return combined, meta


# -----------------------------------------------------------------------------
# report
# -----------------------------------------------------------------------------


JOURNAL_LINK = "docs/journal/2026-08-02_MT0-03_fixture_collection.md"


def _reason_category(entry: dict[str, Any]) -> str:
    """Consumer-facing classification of why a series/indicator isn't (fully)
    collected (MT0-03 aaa-critic DEF-3). Both known code defects (cp949 print
    crash, yfinance MultiIndex) are fixed — see JOURNAL_LINK for the historical
    repro — so any remaining non-ok status here reflects an external condition,
    not a live code bug."""
    status = entry["status"]
    if status == STATUS_OK:
        return ""
    if status == STATUS_UNCOLLECTED:
        return (
            "disabled"
            if "enabled: false" in entry.get("message", "")
            else "out_of_scope"
        )
    if status in (STATUS_BLOCKED_MISSING_API_KEY, STATUS_BLOCKED_MISSING_CREDENTIALS):
        return "blocked_missing_credentials"
    return "external_data_limit"


def render_report(fixtures_dir: Path, configs_dir: Path) -> str:
    """Scan all *.meta.json currently in fixtures_dir and render the consumer-facing
    report BT-02~04 need (MT0-03 aaa-critic DEF-2/DEF-3, v3 재위임 §D): rule legend,
    per-window evaluation range, window×series coverage, graded axis-weighted
    rollup (SSOT: configs/indicators.yaml), and the full notable-gaps span list.
    Reflects the whole directory's current state, not just one run's windows."""
    indicators_cfg = _load_yaml(configs_dir / "indicators.yaml")
    plan = derive_collection_plan(indicators_cfg)
    total_weights = axis_weights(indicators_cfg)
    registry_version = indicators_cfg.get("registry_version", "?")
    metas = [
        json.loads(p.read_text(encoding="utf-8"))
        for p in sorted(fixtures_dir.glob("*.meta.json"))
    ]

    lines = [
        "# BT-01 Fixture Collection Report",
        "",
        "근사-PIT — C1에서 실측 확정 (BACKTEST_PLAN.md §5). "
        + "FRED lag_days 등 as-of 지연 적용은 리플레이(MT0-04)로 이연 — "
        + "이 리포트는 관측일(as_of) 기준 원자료 커버리지만 다룬다.",
        f"실수집 이력·결함 재현 절차: {JOURNAL_LINK}",
        f"지표 레지스트리 버전: {registry_version} (SSOT: configs/indicators.yaml)",
        "",
        "## 0. 규칙 범례",
        "",
        "- 기준 세션(missing_rate 분모)은 exchange_calendars 등 외부 달력이 아니라, "
        + "같은 calendar_kind(krx/us_market/fred/fx) 소속 전 계열이 평가구간 내 실제 "
        + "반환한 관측일의 합집합이다 — 경험적 기준. 외부 달력 패키지의 휴장일 드리프트가 "
        + "유령 결측으로 둔갑하는 경로를 원천 차단한다.",
        "- missing_rate = 1 − |합집합 ∩ 계열 관측일| / |합집합|.",
        "- 지표 가용도 = min(1 − missing_rate)(그 지표가 쓰는 계열들 중 최악값). "
        + "축 커버리지 = Σ(weight × 가용도) / Σweight — 비례 배분이며 이진(all-or-nothing) "
        + "판정이 아니다(SSOT: configs/indicators.yaml weight).",
        "- kind 내 전 계열이 평가구간 내 공백이면(예: KRX 자격증명 미설정) 합집합 자체가 "
        + "비어 기준 세션이 없다 — 이 경우 그 kind 소속 전 계열은 결측 100%로 계상한다 "
        + "(F3-1: 빈 합집합을 결측 0%로 두면 데이터 손실이 클수록 커버리지가 오르는 "
        + "비단조 오류가 된다).",
        "- 형제 계열이 관측을 발행한 날에 특정 계열만 미발행이면, 그 계열은 그 날 결측으로 "
        + "계상된다(보수적 편향 — 형제가 없으면 애초에 그 날이 기준 세션에 들어오지 않는다).",
        "- fx(KRW=X)처럼 kind에 계열이 하나뿐이면 합집합=자기 자신이라, 관측이 1건이라도 "
        + "있으면 missing_rate는 0이다 — 관측이 전혀 없으면(전멸) 위 규칙대로 100%로 잡히고, "
        + "그 사실은 advisory에도 별도로 표기된다. 대신 head/tail 절단·내부 평일(주 5일 기준) "
        + '공백은 "독립 기준 없음(단일 계열)" advisory로만 §4에 표기한다(비율 미반영).',
        "",
        "## 1. 창별 평가구간",
        "",
        "| window_id | 정의(start~end) | anchor_hint | 평가구간(eval) | 비고 |",
        "|---|---|---|---|---|",
    ]
    for meta in metas:
        d = meta["definition"]
        eval_end = meta["collected_range"]["end"]
        note = "정의상 종료일이 오늘 기준으로 클램프됨" if eval_end < d["end"] else ""
        anchor_hint = d.get("anchor_hint") or "-"
        lines.append(
            f"| {meta['window_id']} | {d['start']}~{d['end']} | {anchor_hint} | {d['start']}~{eval_end} | {note} |"
        )

    lines += [
        "",
        "## 2. 창×계열 커버리지 (평가구간 기준, padding 제외)",
        "",
        "| window_id | series_id | status | reason | missing_rate | last_as_of |",
        "|---|---|---|---|---|---|",
    ]
    for meta in metas:
        wid = meta["window_id"]
        for series_id, entry in sorted(meta["series"].items()):
            reason = _reason_category(entry)
            last_as_of = entry.get("last_as_of") or "-"
            lines.append(
                f"| {wid} | {series_id} | {entry['status']} | {reason} | "
                f"{entry['missing_rate']:.2%} | {last_as_of} |"
            )

    lines += [
        "",
        "## 3. 축 커버리지 롤업 (비례 배분, 가중치: configs/indicators.yaml SSOT)",
        "",
        "| window_id | axis | coverage weight/total | coverage |",
        "|---|---|---|---|",
    ]
    for meta in metas:
        wid = meta["window_id"]
        series_missing = {sid: e["missing_rate"] for sid, e in meta["series"].items()}
        coverage_weights = axis_coverage_weights(
            indicators_cfg, plan.indicator_series, series_missing
        )
        for axis in sorted(total_weights):
            total = total_weights[axis]
            got = coverage_weights.get(axis, 0.0)
            pct = (got / total) if total > 0 else 0.0
            lines.append(f"| {wid} | {axis} | {got:.2f}/{total:.2f} | {pct:.0%} |")

    lines += [
        "",
        "## 4. 주목 공백 (notable gaps — 평가구간 내 전체 공백 span 목록)",
        "",
        "| window_id | series_id | gap_start | gap_end | sessions | last_as_of | 비고 |",
        "|---|---|---|---|---|---|---|",
    ]
    gap_rows = [
        f"| {meta['window_id']} | {series_id} | {g['start']} | {g['end']} | {g['sessions']} | "
        f"{entry.get('last_as_of') or '-'} | |"
        for meta in metas
        for series_id, entry in sorted(meta["series"].items())
        if entry["status"] == STATUS_OK
        for g in entry.get("gaps", [])
    ]
    gap_rows += [
        f"| {meta['window_id']} | {series_id} | {g['start']} | {g['end']} | {g['weekdays']} | "
        f"{entry.get('last_as_of') or '-'} | 독립 기준 없음(단일 계열), {g['kind']} |"
        for meta in metas
        for series_id, entry in sorted(meta["series"].items())
        for g in entry.get("fx_advisory_gaps", [])
    ]
    lines += gap_rows if gap_rows else ["| (없음) | | | | | | |"]

    return "\n".join(lines) + "\n"


# -----------------------------------------------------------------------------
# CLI
# -----------------------------------------------------------------------------


def main(argv: Sequence[str] | None = None) -> int:
    # K-xx cp949 콘솔 함정: Windows 콘솔 코드페이지가 em-dash/한글을 인코딩하지 못해
    # print()가 UnicodeEncodeError로 죽는 사례가 반복 관측됨(MT0-03 실수집 결함 #1).
    # argparse's own --help handler can write to stdout *during* parse_args() below
    # (before any code after it runs) — this must happen first, not after parsing,
    # or --help itself reproduces the crash (found via manual verification here).
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    parser = argparse.ArgumentParser(
        description="BT-01 9-window fixture builder (approx-PIT)",
        epilog=(
            "Required environment variables: FRED_API_KEY (FRED series; missing -> "
            "status=blocked_missing_api_key), KRX_ID and KRX_PW (pykrx 1.2.8 KRX "
            "login; missing -> status=blocked_missing_credentials). Both are read "
            "from the process environment (.env loaded by the shell) - never logged."
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--window", required=True, help="window_id or 'all'")
    parser.add_argument(
        "--force", action="store_true", help="bypass cache, re-fetch from network"
    )
    args = parser.parse_args(argv)

    FIXTURES_DIR.mkdir(parents=True, exist_ok=True)

    indicators_cfg = _load_yaml(CONFIGS_DIR / "indicators.yaml")
    sources_cfg = _load_yaml(CONFIGS_DIR / "sources.yaml")
    plan = derive_collection_plan(indicators_cfg)
    all_windows = load_windows()

    if args.window == "all":
        targets = all_windows
    else:
        targets = tuple(w for w in all_windows if w.window_id == args.window)
        if not targets:
            parser.error(f"unknown window_id: {args.window}")

    fetcher = LiveFetcher()
    for window in targets:
        df, meta = build_window_fixture(
            window, plan, fetcher, FIXTURES_DIR, sources_cfg, force=args.force
        )
        df.to_parquet(FIXTURES_DIR / f"{window.window_id}.parquet")
        (FIXTURES_DIR / f"{window.window_id}.meta.json").write_text(
            json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        print(f"[{window.window_id}] rows={len(df)} series={len(meta['series'])}")

    report = render_report(FIXTURES_DIR, CONFIGS_DIR)
    # write before print: a console-encoding crash on print() must never cost the report file.
    (FIXTURES_DIR / "REPORT_fixtures.md").write_text(report, encoding="utf-8")
    print(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
