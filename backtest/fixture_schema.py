"""Pure logic for BT-01 fixtures: schema validation, window registry, collection-plan
derivation (from configs/indicators.yaml, the SSOT), and raw-payload normalization.

No I/O (no network, no file writes beyond reading windows.yaml/configs at call time).
This module is the network-free half of build_fixtures.py — see that module for the
injectable fetch layer and CLI orchestration.

근사-PIT: as_of is the observation date only (K-05: naive datetime forbidden — always
UTC-aware). FRED lag_days and other availability delays are NOT applied here — that is
the replay layer's job (MT0-04), per D-06.
"""

from __future__ import annotations

from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from datetime import date, timedelta
from itertools import pairwise
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd
import yaml

# -----------------------------------------------------------------------------
# fixture schema
# -----------------------------------------------------------------------------

FIXTURE_COLUMNS: tuple[str, str, str, str] = ("series_id", "field", "as_of", "value")

STATUS_OK = "ok"
STATUS_EMPTY = "empty"
STATUS_BLOCKED_MISSING_API_KEY = "blocked_missing_api_key"
STATUS_BLOCKED_MISSING_CREDENTIALS = "blocked_missing_credentials"
STATUS_UNCOLLECTED = "uncollected"
STATUS_ERROR = "error"


class FixtureValidationError(ValueError):
    """Raised when a fixture DataFrame violates the long-format schema."""


def validate_fixture(df: pd.DataFrame) -> None:
    """Validate a fixture DataFrame against the long-format schema (MT0-03 brief).

    Checks: exact column set, tz-aware UTC as_of (K-05), float64 value (K-07),
    no duplicate (series_id, field, as_of), rows sorted ascending by that key.
    Raises FixtureValidationError on the first violation found.
    """
    actual_cols = set(df.columns)
    expected_cols = set(FIXTURE_COLUMNS)
    if actual_cols != expected_cols:
        missing = expected_cols - actual_cols
        extra = actual_cols - expected_cols
        raise FixtureValidationError(
            f"column mismatch: missing={sorted(missing)} extra={sorted(extra)}"
        )

    as_of = df["as_of"]
    if not isinstance(as_of.dtype, pd.DatetimeTZDtype):
        raise FixtureValidationError(
            "as_of must be tz-aware datetime64 (K-05: naive datetime forbidden)"
        )
    if str(as_of.dtype.tz) != "UTC":
        raise FixtureValidationError(f"as_of must be UTC tz, got {as_of.dtype.tz}")

    if df["value"].dtype != np.float64:
        raise FixtureValidationError(
            f"value must be float64 (K-07), got {df['value'].dtype}"
        )

    key_cols = ["series_id", "field", "as_of"]
    if df.duplicated(subset=key_cols).any():
        dups = df.loc[df.duplicated(subset=key_cols, keep=False), key_cols]
        raise FixtureValidationError(
            f"duplicate (series_id, field, as_of) rows: {dups.head().to_dict('records')}"
        )

    sorted_key = df[key_cols].sort_values(key_cols).reset_index(drop=True)
    if not df[key_cols].reset_index(drop=True).equals(sorted_key):
        raise FixtureValidationError(
            "rows must be sorted ascending by (series_id, field, as_of)"
        )


def empty_long_frame() -> pd.DataFrame:
    """An empty, correctly-typed fixture frame (safe validate_fixture input)."""
    return pd.DataFrame(
        {
            "series_id": pd.Series(dtype="object"),
            "field": pd.Series(dtype="object"),
            "as_of": pd.Series(dtype="datetime64[ns, UTC]"),
            "value": pd.Series(dtype="float64"),
        }
    )


def rows_to_frame(rows: Sequence[tuple[str, str, Any, float]]) -> pd.DataFrame:
    """Build a fixture frame from (series_id, field, as_of, value) tuples."""
    if not rows:
        return empty_long_frame()
    df = pd.DataFrame(list(rows), columns=list(FIXTURE_COLUMNS))
    df["as_of"] = pd.to_datetime(df["as_of"], utc=True)
    df["value"] = df["value"].astype("float64")
    return df


def to_utc_midnight(ts: Any) -> pd.Timestamp:
    """Normalize any date-like value to UTC-midnight of its calendar date (K-05)."""
    return pd.Timestamp(pd.Timestamp(ts).date(), tz="UTC")


# -----------------------------------------------------------------------------
# window registry
# -----------------------------------------------------------------------------

WINDOWS_YAML_PATH = Path(__file__).resolve().parent / "windows.yaml"


@dataclass(frozen=True)
class WindowDef:
    window_id: str
    start: date
    end: date
    anchor_hint: date | None
    kind: str  # "positive" | "negative"
    holdout: bool
    character: str


def _load_windows_yaml(path: Path) -> dict[str, Any]:
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)


def load_windows(path: Path = WINDOWS_YAML_PATH) -> tuple[WindowDef, ...]:
    raw = _load_windows_yaml(path)
    return tuple(
        WindowDef(
            window_id=w["window_id"],
            start=w["start"],
            end=w["end"],
            anchor_hint=w.get("anchor_hint"),
            kind=w["kind"],
            holdout=bool(w["holdout"]),
            character=w["character"],
        )
        for w in raw["windows"]
    )


def padding_days(path: Path = WINDOWS_YAML_PATH) -> int:
    return int(_load_windows_yaml(path)["padding_days"])


def calendar_kind(series_id: str) -> str:
    """Provider-derived grouping (Advisor MT0-03 v3 §A): series sharing a kind are
    expected to observe on the same days, so their union forms that kind's
    empirical reference calendar. pykrx KR series -> "krx", yfinance US indices ->
    "us_market", FRED US series -> "fred" (kept separate from us_market: FRED
    publishes on some exchange holidays/weekends and skips some bond-market-only
    holidays XNYS doesn't — NEW-2), USD/KRW FX -> "fx" (single-member kind, see
    fx_advisory_gaps).

    No external calendar package is used anywhere in this module (NEW-1): a
    package's holiday schedule can drift out of date relative to reality (e.g.
    exchange_calendars' XKRX missing 2026 ad-hoc KR market holidays), and any
    such drift gets counted as a phantom gap. The empirical union is exactly
    what was actually observed, so it can't drift.
    """
    if series_id.startswith("KRX:"):
        return "krx"
    if series_id == "KRW=X":
        return "fx"
    if series_id in ("BAMLH0A0HYM2", "T10Y2Y"):
        return "fred"
    return "us_market"


def eval_observed_dates(
    norm: pd.DataFrame, eval_start: date, eval_end: date
) -> set[date]:
    """Distinct as_of calendar dates in [eval_start, eval_end] a normalized series
    frame actually has a row for (any field counts once — K-07/multi-field series
    like KRW=X OHLC must not multiply their own coverage)."""
    if norm.empty:
        return set()
    return {d for d in norm["as_of"].dt.date if eval_start <= d <= eval_end}


def missing_rate(reference: Sequence[date], observed: Iterable[date]) -> float:
    """Fraction of the reference session set a series has no observation for.
    `reference` is the empirical union for the series' calendar_kind (§A) — not an
    external calendar — so it can only ever contain days at least one real series
    in that kind actually reported. Naturally bounded to [0, 1] by set
    intersection (no clamp needed).

    An empty reference means every series in the kind observed nothing at all
    (e.g. blocked credentials, or a single-member kind whose one series failed) —
    there is no session basis to call anything "present" against, so this is 1.0
    (fully missing), matching `_uncollected_meta_entry`'s convention. F3-1
    (aaa-critic round 3): returning 0.0 here made data loss look like 100%
    coverage and made axis coverage non-monotone in the amount of missing data.
    """
    if not reference:
        return 1.0
    observed_set = set(observed)
    matched = sum(1 for d in reference if d in observed_set)
    return 1.0 - (matched / len(reference))


def find_gaps(
    reference: Sequence[date], observed: Iterable[date]
) -> list[tuple[date, date, int]]:
    """All contiguous runs of reference sessions with no observation, as a list of
    (gap_start, gap_end, session_count) — not just the longest (Advisor MT0-03 v3
    §D-④: a short window can afford to list every gap, not just the worst one)."""
    observed_set = set(observed)
    missing_idx = [i for i, d in enumerate(reference) if d not in observed_set]
    if not missing_idx:
        return []
    runs: list[list[int]] = [[missing_idx[0]]]
    for i in missing_idx[1:]:
        if i == runs[-1][-1] + 1:
            runs[-1].append(i)
        else:
            runs.append([i])
    return [(reference[run[0]], reference[run[-1]], len(run)) for run in runs]


def weekdays_between(start: date, end: date) -> tuple[date, ...]:
    """Plain Mon-Fri weekday listing in [start, end] inclusive — pure date
    arithmetic, not an external holiday calendar, so it can't drift (NEW-1's
    lesson) the way a package's schedule can. Only used for the FX advisory
    check (§B), never for missing_rate."""
    if end < start:
        return ()
    n_days = (end - start).days + 1
    return tuple(
        d for i in range(n_days) if (d := start + timedelta(days=i)).weekday() < 5
    )


def fx_advisory_gaps(
    observed: Iterable[date], eval_start: date, eval_end: date
) -> list[dict[str, Any]]:
    """Advisory-only gap detection for a single-member calendar_kind (e.g. FX/
    KRW=X): with no independent series to form a reference union against,
    missing_rate is 0 only when the series has at least one observation
    (Advisor MT0-03 v3 §B; total failure falls through missing_rate's own
    empty-reference rule instead, F3-1) — but head/tail truncation and internal
    weekday gaps are still worth surfacing as facts here. Never affects
    missing_rate or axis coverage (advisory only — O3-D: the >2/>=3 weekday
    thresholds below are report-readability judgment calls, not SSOT-governed
    decision thresholds, so they stay literal rather than moving to a config)."""
    obs_sorted = sorted(observed)
    if not obs_sorted:
        # F3-1 point 2: total loss (zero observations) must not be silent —
        # missing_rate already reads 1.0 for this via the empty-reference rule,
        # but notable-gaps would otherwise show nothing for this series at all.
        weekdays = weekdays_between(eval_start, eval_end)
        return [
            {
                "kind": "no_observations",
                "start": eval_start.isoformat(),
                "end": eval_end.isoformat(),
                "weekdays": len(weekdays),
            }
        ]
    gaps: list[dict[str, Any]] = []

    head = weekdays_between(eval_start, obs_sorted[0] - timedelta(days=1))
    if len(head) > 2:  # advisory threshold only — see module docstring above
        # span is the actual weekday bounds (consistent with internal_gap below),
        # not the raw calendar-day boundary, which may fall on a weekend.
        gaps.append(
            {
                "kind": "head_gap",
                "start": head[0].isoformat(),
                "end": head[-1].isoformat(),
                "weekdays": len(head),
            }
        )

    tail = weekdays_between(obs_sorted[-1] + timedelta(days=1), eval_end)
    if len(tail) > 2:  # advisory threshold only — see module docstring above
        gaps.append(
            {
                "kind": "tail_gap",
                "start": tail[0].isoformat(),
                "end": tail[-1].isoformat(),
                "weekdays": len(tail),
            }
        )

    for prev_day, next_day in pairwise(obs_sorted):
        internal = weekdays_between(
            prev_day + timedelta(days=1), next_day - timedelta(days=1)
        )
        if len(internal) >= 3:  # advisory threshold only — see module docstring above
            gaps.append(
                {
                    "kind": "internal_gap",
                    "start": internal[0].isoformat(),
                    "end": internal[-1].isoformat(),
                    "weekdays": len(internal),
                }
            )

    return gaps


# -----------------------------------------------------------------------------
# collection plan (derived from configs/indicators.yaml — SSOT)
# -----------------------------------------------------------------------------


@dataclass(frozen=True)
class YfinanceSeries:
    series_id: str
    symbol: str
    fields: tuple[str, ...]


@dataclass(frozen=True)
class FredSeries:
    series_id: str
    fred_series_id: str


@dataclass(frozen=True)
class PykrxSeries:
    series_id: str
    dataset: str  # "index_ohlcv" | "investor_trading_value" | "vkospi"
    params: dict[str, str]
    fields: tuple[str, ...]


@dataclass(frozen=True)
class ExcludedIndicator:
    indicator_id: str
    provider: str
    reason: str


@dataclass(frozen=True)
class CollectionPlan:
    yfinance: tuple[YfinanceSeries, ...]
    fred: tuple[FredSeries, ...]
    pykrx: tuple[PykrxSeries, ...]
    excluded: tuple[ExcludedIndicator, ...]
    # indicator_id -> the series_id(s) its transform needs (empty/absent = never
    # collectible, e.g. excluded). Used for axis coverage rollup (MT0-03 재위임 §B-2):
    # an indicator counts as "collected" for a window only if ALL of its series
    # have zero gap there — a transform needing two inputs is only as good as its
    # worst input.
    indicator_series: dict[str, tuple[str, ...]]


_EXCLUDED_PROVIDERS = {"ecos", "scrape_wgb"}
_KRW_X_FIELDS = ("open", "high", "low", "close")
_PYKRX_DATASET_FIELDS: dict[str, tuple[str, ...]] = {
    "index_ohlcv": ("open", "high", "low", "close", "trading_value"),
    "investor_trading_value": ("net_buy_value",),
    "vkospi": ("close",),
}
_INVESTOR_LABELS = {"외국인": "foreign", "기관": "institution", "개인": "individual"}


def derive_collection_plan(indicators_cfg: dict[str, Any]) -> CollectionPlan:
    """Derive the M0 collection target list from configs/indicators.yaml (SSOT).

    Includes: enabled != false indicators with provider in {yfinance, fred, pykrx},
    plus the derived indicator global_corr_break's inputs (^GSPC, KOSPI).
    Excludes (recorded, not fetched): enabled: false indicators, and
    provider in {ecos, scrape_wgb} (M0 out of scope — see MT0-03 brief).
    """
    yfinance_fields: dict[str, set[str]] = {}
    fred_ids: set[str] = set()
    pykrx_specs: dict[str, PykrxSeries] = {}
    excluded: list[ExcludedIndicator] = []
    indicator_series: dict[str, tuple[str, ...]] = {}

    def add_yfinance(symbol: str) -> None:
        fields = _KRW_X_FIELDS if symbol == "KRW=X" else ("close",)
        yfinance_fields.setdefault(symbol, set()).update(fields)

    def add_pykrx(dataset: str, series_id: str, params: dict[str, str]) -> None:
        pykrx_specs[series_id] = PykrxSeries(
            series_id, dataset, params, _PYKRX_DATASET_FIELDS[dataset]
        )

    for ind in indicators_cfg["indicators"]:
        ind_id = ind["id"]
        source = ind.get("source", {})
        provider = source.get("provider", "unknown")

        if ind.get("enabled") is False:
            excluded.append(
                ExcludedIndicator(
                    ind_id, provider, "disabled in indicators.yaml (enabled: false)"
                )
            )
            continue
        if provider in _EXCLUDED_PROVIDERS:
            excluded.append(
                ExcludedIndicator(
                    ind_id, provider, "out of M0 collection scope (see MT0-03 brief)"
                )
            )
            continue

        if provider == "yfinance":
            symbols = source["symbol"]
            symbol_list = [symbols] if isinstance(symbols, str) else list(symbols)
            for sym in symbol_list:
                add_yfinance(sym)
            indicator_series[ind_id] = tuple(symbol_list)
        elif provider == "fred":
            fred_ids.add(source["series_id"])
            indicator_series[ind_id] = (source["series_id"],)
        elif provider == "pykrx":
            dataset = source["dataset"]
            if dataset == "index_ohlcv":
                series_id = f"KRX:{source['symbol']}"
                add_pykrx(dataset, series_id, {"symbol": source["symbol"]})
                indicator_series[ind_id] = (series_id,)
            elif dataset == "investor_trading_value":
                label = _INVESTOR_LABELS.get(source["investor"], source["investor"])
                series_id = f"KRX:investor_{label}_{source['market'].lower()}"
                add_pykrx(
                    dataset,
                    series_id,
                    {"market": source["market"], "investor": source["investor"]},
                )
                indicator_series[ind_id] = (series_id,)
            elif dataset == "vkospi":
                add_pykrx(dataset, "KRX:VKOSPI", {})
                indicator_series[ind_id] = ("KRX:VKOSPI",)
            else:
                excluded.append(
                    ExcludedIndicator(
                        ind_id, provider, f"unknown pykrx dataset: {dataset}"
                    )
                )
        elif provider == "derived":
            input_series: list[str] = []
            for inp in source.get("inputs", []):
                if inp == "KOSPI":
                    add_pykrx("index_ohlcv", "KRX:1001", {"symbol": "1001"})
                    input_series.append("KRX:1001")
                else:
                    add_yfinance(inp)
                    input_series.append(inp)
            indicator_series[ind_id] = tuple(input_series)
        else:
            excluded.append(
                ExcludedIndicator(
                    ind_id, provider, "out of M0 collection scope (see MT0-03 brief)"
                )
            )

    yfinance_specs = tuple(
        YfinanceSeries(series_id=sym, symbol=sym, fields=tuple(sorted(fields)))
        for sym, fields in sorted(yfinance_fields.items())
    )
    fred_specs = tuple(
        FredSeries(series_id=s, fred_series_id=s) for s in sorted(fred_ids)
    )
    pykrx_out = tuple(pykrx_specs[k] for k in sorted(pykrx_specs))
    return CollectionPlan(
        yfinance_specs, fred_specs, pykrx_out, tuple(excluded), indicator_series
    )


def axis_weights(indicators_cfg: dict[str, Any]) -> dict[str, float]:
    """Total weight per axis for enabled indicators (SSOT: configs/indicators.yaml)."""
    totals: dict[str, float] = {}
    for ind in indicators_cfg["indicators"]:
        if ind.get("enabled") is False:
            continue
        totals[ind["axis"]] = totals.get(ind["axis"], 0.0) + float(ind["weight"])
    return totals


def axis_coverage_weights(
    indicators_cfg: dict[str, Any],
    indicator_series: dict[str, tuple[str, ...]],
    series_missing_rate: dict[str, float],
) -> dict[str, float]:
    """Graded (not binary) axis coverage weight sum (Advisor MT0-03 v3 §C — replaces
    the all-or-nothing rule that let a single missing session amplify into a whole
    axis reading 0%, NEW-1). An indicator's availability = min(1 - missing_rate)
    over its underlying series (a transform is only as good as its worst input);
    axis coverage weight = Σ(weight * availability). An indicator with no mapped
    series (excluded from M0 collection, e.g. ECOS/scrape_wgb) has availability 0."""
    totals: dict[str, float] = {}
    for ind in indicators_cfg["indicators"]:
        if ind.get("enabled") is False:
            continue
        series_ids = indicator_series.get(ind["id"], ())
        availability = min(
            (1.0 - series_missing_rate.get(sid, 1.0) for sid in series_ids), default=0.0
        )
        totals[ind["axis"]] = (
            totals.get(ind["axis"], 0.0) + float(ind["weight"]) * availability
        )
    return totals


# -----------------------------------------------------------------------------
# raw payload normalization (pure — no I/O, operates on already-fetched data)
# -----------------------------------------------------------------------------

_PYKRX_OHLCV_COLUMN_MAP = {
    "시가": "open",
    "고가": "high",
    "저가": "low",
    "종가": "close",
    "거래대금": "trading_value",
}
_INVESTOR_COLUMN_MAP = {"외국인": "외국인합계", "기관": "기관합계", "개인": "개인"}


def normalize_yfinance(
    raw: pd.DataFrame | None, series_id: str, fields: Sequence[str]
) -> pd.DataFrame:
    """raw: yfinance.download()-style frame — DatetimeIndex, price-field columns.

    yfinance (observed: 1.5.2) returns a MultiIndex (field, ticker) even for a
    single-symbol request, e.g. columns=[('Close', '^VIX'), ...] — drop the ticker
    level before matching field names, or every field lookup below silently misses
    (real bug found via MT0-03 live collection: all 6 series came back "empty").
    """
    if raw is None or raw.empty:
        return empty_long_frame()
    frame = raw.copy()
    columns = (
        frame.columns.get_level_values(0)
        if isinstance(frame.columns, pd.MultiIndex)
        else frame.columns
    )
    frame.columns = [str(c).lower() for c in columns]
    rows: list[tuple[str, str, Any, float]] = []
    for field in fields:
        if field not in frame.columns:
            continue
        for ts, v in frame[field].dropna().items():
            rows.append((series_id, field, to_utc_midnight(ts), float(v)))
    return rows_to_frame(rows)


def normalize_fred(
    observations: Sequence[dict[str, Any]], series_id: str
) -> pd.DataFrame:
    """observations: FRED API json['observations'] — [{"date": "YYYY-MM-DD", "value": "x"|"."}, ...]."""
    rows: list[tuple[str, str, Any, float]] = []
    for obs in observations:
        raw_value = obs.get("value")
        if raw_value in (None, ".", ""):
            continue
        try:
            value = float(raw_value)
        except (TypeError, ValueError):
            continue
        rows.append((series_id, "value", to_utc_midnight(obs["date"]), value))
    return rows_to_frame(rows)


def normalize_pykrx_index_ohlcv(
    raw: pd.DataFrame | None, series_id: str, fields: Sequence[str]
) -> pd.DataFrame:
    """raw: pykrx.stock.get_index_ohlcv_by_date result — DatetimeIndex, 시가/고가/저가/종가/거래대금."""
    if raw is None or raw.empty:
        return empty_long_frame()
    frame = raw.rename(columns=_PYKRX_OHLCV_COLUMN_MAP)
    rows: list[tuple[str, str, Any, float]] = []
    for field in fields:
        if field not in frame.columns:
            continue
        for ts, v in frame[field].dropna().items():
            rows.append((series_id, field, to_utc_midnight(ts), float(v)))
    return rows_to_frame(rows)


def normalize_pykrx_investor_value(
    raw: pd.DataFrame | None,
    series_id: str,
    investor: str,
    field: str = "net_buy_value",
) -> pd.DataFrame:
    """raw: pykrx.stock.get_market_trading_value_by_date(..., ticker=market, on='순매수') result."""
    if raw is None or raw.empty:
        return empty_long_frame()
    col = _INVESTOR_COLUMN_MAP.get(investor, investor)
    if col not in raw.columns:
        return empty_long_frame()
    rows = [
        (series_id, field, to_utc_midnight(ts), float(v))
        for ts, v in raw[col].dropna().items()
    ]
    return rows_to_frame(rows)


def normalize_pykrx_vkospi(
    raw: pd.DataFrame | None, series_id: str, field: str = "close"
) -> pd.DataFrame:
    """raw: VKOSPI level/OHLCV-shaped frame (K-02: no confirmed pykrx VKOSPI ticker as of
    this writing — kept for when data-verifier resolves one)."""
    if raw is None or raw.empty:
        return empty_long_frame()
    frame = raw.rename(columns=_PYKRX_OHLCV_COLUMN_MAP)
    if "close" not in frame.columns:
        return empty_long_frame()
    rows = [
        (series_id, field, to_utc_midnight(ts), float(v))
        for ts, v in frame["close"].dropna().items()
    ]
    return rows_to_frame(rows)
