"""MT0-03 v3: fixture schema, window registry, collection-plan derivation, the
empirical (not external-calendar) calendar_kind/missing_rate/gap design, graded
axis coverage rollup, report rendering, and the build_fixtures pipeline with a
stub (network-free) fetcher.

No network access anywhere in this file — pykrx/yfinance/FRED calls are all
provided by StubFetcher, matching real provider payload shapes.
"""

from __future__ import annotations

import json
from datetime import date, timedelta
from pathlib import Path

import pandas as pd
import pytest
import yaml

from backtest import build_fixtures as bf_module
from backtest.build_fixtures import build_window_fixture
from backtest.fixture_schema import (
    FixtureValidationError,
    axis_coverage_weights,
    axis_weights,
    calendar_kind,
    derive_collection_plan,
    find_gaps,
    fx_advisory_gaps,
    load_windows,
    missing_rate,
    normalize_yfinance,
    validate_fixture,
)

REPO_ROOT = Path(__file__).resolve().parent.parent
CONFIGS_DIR = REPO_ROOT / "configs"


def _load_yaml(path: Path) -> dict:
    # K-xx Windows 함정: cp949 기본 인코딩 회피 — 반드시 utf-8 명시.
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)


def _indicators_cfg() -> dict:
    return _load_yaml(CONFIGS_DIR / "indicators.yaml")


def _sources_cfg() -> dict:
    return _load_yaml(CONFIGS_DIR / "sources.yaml")


# -----------------------------------------------------------------------------
# validate_fixture
# -----------------------------------------------------------------------------


def _valid_frame() -> pd.DataFrame:
    return (
        pd.DataFrame(
            {
                "series_id": ["^VIX", "^VIX", "^VIX3M"],
                "field": ["close", "close", "close"],
                "as_of": pd.to_datetime(
                    ["2024-05-01", "2024-05-02", "2024-05-01"], utc=True
                ),
                "value": [15.0, 16.0, 14.0],
            }
        )
        .sort_values(["series_id", "field", "as_of"])
        .reset_index(drop=True)
    )


def test_validate_fixture_accepts_valid_frame() -> None:
    validate_fixture(_valid_frame())  # must not raise


def test_validate_fixture_rejects_missing_column() -> None:
    df = _valid_frame().drop(columns=["value"])
    with pytest.raises(FixtureValidationError):
        validate_fixture(df)


def test_validate_fixture_rejects_naive_datetime() -> None:
    df = _valid_frame()
    df["as_of"] = df["as_of"].dt.tz_localize(None)
    with pytest.raises(FixtureValidationError):
        validate_fixture(df)


def test_validate_fixture_rejects_non_float64_value() -> None:
    df = _valid_frame()
    df["value"] = df["value"].astype("int64")
    with pytest.raises(FixtureValidationError):
        validate_fixture(df)


def test_validate_fixture_rejects_duplicate_rows() -> None:
    df = _valid_frame()
    dup = pd.concat([df, df.iloc[[0]]], ignore_index=True)
    with pytest.raises(FixtureValidationError):
        validate_fixture(dup)


def test_validate_fixture_rejects_unsorted_rows() -> None:
    df = _valid_frame().iloc[::-1].reset_index(drop=True)
    with pytest.raises(FixtureValidationError):
        validate_fixture(df)


# -----------------------------------------------------------------------------
# normalize_yfinance — MultiIndex column regression (MT0-03 결함 #2)
# -----------------------------------------------------------------------------


def _yfinance_multiindex_frame(
    symbol: str, periods: int = 3, end: str | date = "2024-07-31"
) -> pd.DataFrame:
    """Shaped exactly like the real yf.download(symbol, ...) return observed in
    MT0-03 live collection: MultiIndex(field, ticker) columns even for a single
    symbol — see docs/journal/2026-08-02_MT0-03_fixture_collection.md §2.
    Business days ending at `end`, so unit tests calling this directly can pick
    any small period count."""
    idx = pd.bdate_range(end=pd.Timestamp(end), periods=periods)
    columns = pd.MultiIndex.from_tuples(
        [
            ("Open", symbol),
            ("High", symbol),
            ("Low", symbol),
            ("Close", symbol),
            ("Adj Close", symbol),
            ("Volume", symbol),
        ]
    )
    data = [[16.2, 17.0, 16.0, 16.5, 16.5, 1000] for _ in range(periods)]
    return pd.DataFrame(data, index=idx, columns=columns)


def test_normalize_yfinance_handles_multiindex_columns() -> None:
    raw = _yfinance_multiindex_frame("^VIX")
    out = normalize_yfinance(raw, "^VIX", ("close",))
    validate_fixture(out)  # must not raise
    assert len(out) == 3
    assert set(out["series_id"]) == {"^VIX"}
    assert set(out["field"]) == {"close"}
    assert list(out["value"]) == [16.5, 16.5, 16.5]


def test_normalize_yfinance_still_handles_flat_columns() -> None:
    # older/alternate yfinance shape (flat columns) must keep working too.
    idx = pd.date_range("2024-07-25", periods=3, freq="B")
    raw = pd.DataFrame({"Open": [1.0] * 3, "Close": [1.1] * 3}, index=idx)
    out = normalize_yfinance(raw, "^VIX", ("close",))
    validate_fixture(out)
    assert len(out) == 3


# -----------------------------------------------------------------------------
# windows.yaml integrity (D-08 golden dates)
# -----------------------------------------------------------------------------


def test_windows_registry_counts_and_holdouts() -> None:
    windows = load_windows()
    assert len(windows) == 9
    assert sum(1 for w in windows if w.kind == "positive") == 7
    assert sum(1 for w in windows if w.kind == "negative") == 2
    holdout_ids = {w.window_id for w in windows if w.holdout}
    assert holdout_ids == {"w2015_cny_deval", "w2023_11_rally"}


def test_windows_registry_golden_dates() -> None:
    windows = {w.window_id: w for w in load_windows()}
    golden_positive = windows["w2024_carry_unwind"]
    assert golden_positive.start == date(2024, 7, 25)
    assert golden_positive.end == date(2024, 8, 9)
    golden_negative = windows["w2024_05_calm"]
    assert golden_negative.start == date(2024, 5, 13)
    assert golden_negative.end == date(2024, 5, 24)


# -----------------------------------------------------------------------------
# calendar_kind — grouping only, no external calendar package (Advisor v3 §A, NEW-1/NEW-2)
# -----------------------------------------------------------------------------


def test_calendar_kind_groups_by_provider_not_external_calendar() -> None:
    assert calendar_kind("KRX:1001") == "krx"
    assert calendar_kind("KRX:investor_foreign_kospi") == "krx"
    assert calendar_kind("KRX:VKOSPI") == "krx"
    assert calendar_kind("KRW=X") == "fx"
    # FRED kept separate from yfinance US indices (NEW-2): they publish on different days.
    assert calendar_kind("BAMLH0A0HYM2") == "fred"
    assert calendar_kind("T10Y2Y") == "fred"
    assert calendar_kind("^VIX") == "us_market"
    assert calendar_kind("^GSPC") == "us_market"


# -----------------------------------------------------------------------------
# missing_rate / find_gaps — empirical reference, witness tests (DEF-4/F)
# -----------------------------------------------------------------------------


def test_missing_rate_exact_value_for_partial_gap() -> None:
    reference = [date(2024, 5, 13) + timedelta(days=i) for i in range(5)]
    observed = {reference[0], reference[1], reference[4]}  # 2 of 5 missing (idx 2, 3)
    assert missing_rate(reference, observed) == pytest.approx(2 / 5)


def test_missing_rate_zero_when_fully_observed() -> None:
    reference = [date(2024, 5, 13) + timedelta(days=i) for i in range(3)]
    assert missing_rate(reference, set(reference)) == 0.0


def test_missing_rate_full_when_reference_is_empty() -> None:
    """F3-1 (aaa-critic round 3, blocking): an empty reference means every series
    in the calendar_kind observed nothing at all (e.g. KRX credentials missing) —
    there is no session basis, so this must be 1.0 (fully missing), never 0.0.
    0.0 here made total data loss look like perfect coverage and made axis
    coverage non-monotone in the amount of missing data."""
    assert missing_rate([], set()) == 1.0
    assert missing_rate([], {date(2024, 5, 13)}) == 1.0  # observed is irrelevant here


def test_find_gaps_returns_every_run_not_just_the_longest() -> None:
    # two separate one-day gaps, not one contiguous run (Advisor v3 §D-④).
    reference = [date(2024, 5, 13) + timedelta(days=i) for i in range(5)]
    observed = {reference[0], reference[2], reference[4]}  # idx 1 and 3 both missing
    assert find_gaps(reference, observed) == [
        (reference[1], reference[1], 1),
        (reference[3], reference[3], 1),
    ]


def test_find_gaps_empty_when_fully_observed() -> None:
    reference = [date(2024, 5, 13) + timedelta(days=i) for i in range(3)]
    assert find_gaps(reference, set(reference)) == []


# -----------------------------------------------------------------------------
# F-1: ghost-holiday immunity — a day NO kind member observed is simply not a
# reference session, no matter what an external calendar might claim (NEW-1).
# -----------------------------------------------------------------------------


def test_missing_rate_immune_to_universally_absent_day() -> None:
    # both series in this (synthetic) kind skip 2024-05-15/05-16 entirely — an
    # external calendar might insist those are trading days, but the empirical
    # union never claims a day nobody observed, so neither series is "missing" it.
    series_a = {date(2024, 5, 13), date(2024, 5, 14), date(2024, 5, 17)}
    series_b = {date(2024, 5, 13), date(2024, 5, 14), date(2024, 5, 17)}
    reference = sorted(series_a | series_b)
    assert missing_rate(reference, series_a) == 0.0
    assert missing_rate(reference, series_b) == 0.0
    assert find_gaps(reference, series_a) == []
    assert find_gaps(reference, series_b) == []


# -----------------------------------------------------------------------------
# F-2: cross-detection — one kind member missing a day the other has must show
# up as an exact, nonzero missing_rate for the affected series only.
# -----------------------------------------------------------------------------


def test_missing_rate_cross_detects_real_gap_within_kind() -> None:
    series_a = {date(2024, 5, 13) + timedelta(days=i) for i in range(5)}  # 5 days
    series_b = series_a - {date(2024, 5, 15)}  # missing one day series_a has
    reference = sorted(series_a | series_b)  # = series_a's 5 days
    assert len(reference) == 5
    assert missing_rate(reference, series_a) == 0.0
    assert missing_rate(reference, series_b) == pytest.approx(1 / 5)


# -----------------------------------------------------------------------------
# F-4: fx_advisory_gaps — single-member calendar_kind (e.g. KRW=X), advisory only
# -----------------------------------------------------------------------------


def test_fx_advisory_gaps_detects_head_gap() -> None:
    eval_start, eval_end = date(2024, 5, 13), date(2024, 5, 24)
    observed = [date(2024, 5, 16) + timedelta(days=i) for i in range(9)]
    observed = [d for d in observed if d.weekday() < 5]  # 05-16 .. 05-24 weekdays
    gaps = fx_advisory_gaps(observed, eval_start, eval_end)
    assert {
        "kind": "head_gap",
        "start": "2024-05-13",
        "end": "2024-05-15",
        "weekdays": 3,
    } in gaps


def test_fx_advisory_gaps_detects_tail_gap() -> None:
    eval_start, eval_end = date(2024, 5, 13), date(2024, 5, 24)
    observed = [
        date(2024, 5, 13),
        date(2024, 5, 14),
        date(2024, 5, 15),
        date(2024, 5, 16),
        date(2024, 5, 17),
    ]
    gaps = fx_advisory_gaps(observed, eval_start, eval_end)
    assert {
        "kind": "tail_gap",
        "start": "2024-05-20",
        "end": "2024-05-24",
        "weekdays": 5,
    } in gaps


def test_fx_advisory_gaps_detects_internal_gap() -> None:
    eval_start, eval_end = date(2024, 5, 13), date(2024, 5, 24)
    observed = [
        date(2024, 5, 13),
        date(2024, 5, 14),
        date(2024, 5, 20),
        date(2024, 5, 21),
        date(2024, 5, 22),
        date(2024, 5, 23),
        date(2024, 5, 24),
    ]
    gaps = fx_advisory_gaps(observed, eval_start, eval_end)
    assert {
        "kind": "internal_gap",
        "start": "2024-05-15",
        "end": "2024-05-17",
        "weekdays": 3,
    } in gaps


def test_fx_advisory_gaps_ignores_short_internal_gap() -> None:
    eval_start, eval_end = date(2024, 5, 13), date(2024, 5, 17)
    observed = [
        date(2024, 5, 13),
        date(2024, 5, 14),
        date(2024, 5, 16),
        date(2024, 5, 17),
    ]
    # between 05-14 and 05-16: only 05-15 (1 weekday) — below the >=3 threshold.
    assert fx_advisory_gaps(observed, eval_start, eval_end) == []


def test_fx_advisory_gaps_total_loss_when_no_observations() -> None:
    """F3-1 point 2: total failure (zero observations) must not be silent —
    silence here previously combined with the missing_rate empty-reference bug
    to make a fully-failed single-member kind look both 0% missing AND gap-free."""
    eval_start, eval_end = date(2024, 5, 13), date(2024, 5, 24)
    gaps = fx_advisory_gaps([], eval_start, eval_end)
    assert gaps == [
        {
            "kind": "no_observations",
            "start": "2024-05-13",
            "end": "2024-05-24",
            "weekdays": 10,
        }
    ]


# -----------------------------------------------------------------------------
# collection plan derivation (SSOT: configs/indicators.yaml)
# -----------------------------------------------------------------------------


def test_derive_collection_plan_from_indicators_yaml() -> None:
    plan = derive_collection_plan(_indicators_cfg())

    yfinance_symbols = {s.symbol for s in plan.yfinance}
    assert yfinance_symbols == {"^VIX", "^VIX3M", "^MOVE", "^GSPC", "DX-Y.NYB", "KRW=X"}
    krw = next(s for s in plan.yfinance if s.symbol == "KRW=X")
    assert set(krw.fields) == {"open", "high", "low", "close"}
    vix = next(s for s in plan.yfinance if s.symbol == "^VIX")
    assert vix.fields == ("close",)

    fred_ids = {s.series_id for s in plan.fred}
    assert fred_ids == {"BAMLH0A0HYM2", "T10Y2Y"}

    pykrx_ids = {s.series_id for s in plan.pykrx}
    assert pykrx_ids == {"KRX:1001", "KRX:investor_foreign_kospi", "KRX:VKOSPI"}

    excluded_ids = {e.indicator_id for e in plan.excluded}
    # ECOS/scrape_wgb: out of M0 scope (K-04 item_code unverified, unstable free source)
    assert "krx_credit_spread_delta" in excluded_ids
    assert "kr_cds_5y_delta" in excluded_ids
    # enabled: false in the registry (F-04 reserved, P2 news)
    assert {
        "krx_halt_events",
        "margin_leverage_stress",
        "news_volume_z",
        "news_novelty",
    } <= excluded_ids

    # indicator -> series mapping, used by axis coverage rollup (§C)
    assert plan.indicator_series["vix_term_structure"] == ("^VIX", "^VIX3M")
    assert set(plan.indicator_series["global_corr_break"]) == {"^GSPC", "KRX:1001"}
    assert (
        "krx_credit_spread_delta" not in plan.indicator_series
    )  # excluded -> never collectible


# -----------------------------------------------------------------------------
# F-3: axis_coverage_weights — graded, not binary (Advisor v3 §C, NEW-1 fix)
# -----------------------------------------------------------------------------


def test_axis_coverage_weights_graded_not_amplified() -> None:
    indicators_cfg = _indicators_cfg()
    plan = derive_collection_plan(indicators_cfg)
    total = axis_weights(indicators_cfg)["vol_global"]
    assert total == pytest.approx(
        7.0
    )  # vix_level_z 3.0 + vix_term_structure 2.5 + move_index_z 1.5

    # ^VIX missing just 1 of 53 reference sessions; ^VIX3M and ^MOVE fully covered.
    series_missing_rate = {"^VIX": 1 / 53, "^VIX3M": 0.0, "^MOVE": 0.0}
    coverage = axis_coverage_weights(
        indicators_cfg, plan.indicator_series, series_missing_rate
    )

    # vix_level_z depends only on ^VIX; vix_term_structure depends on BOTH ^VIX and
    # ^VIX3M (worst-of-inputs, so it's degraded by ^VIX's gap too); move_index_z is
    # unaffected.
    expected = 3.0 * (52 / 53) + 2.5 * (52 / 53) + 1.5 * 1.0
    assert coverage["vol_global"] == pytest.approx(expected)
    # the whole point: one missing session degrades proportionally, it does not
    # amplify to "the whole axis is 0%" (the old binary rule's failure mode).
    assert 0.0 < coverage["vol_global"] < total


def test_axis_coverage_weights_zero_for_uncollected_indicator() -> None:
    indicators_cfg = _indicators_cfg()
    plan = derive_collection_plan(indicators_cfg)
    # credit axis: krx_credit_spread_delta/kr_cds_5y_delta are never collectible
    # (ECOS/scrape_wgb, out of M0 scope) — even if hy_oas_delta is fully present,
    # those two contribute exactly 0, never a full-axis wipeout of the other leg.
    coverage = axis_coverage_weights(
        indicators_cfg, plan.indicator_series, {"BAMLH0A0HYM2": 0.0}
    )
    total = axis_weights(indicators_cfg)["credit"]
    assert (
        0.0 < coverage["credit"] < total
    )  # hy_oas_delta's 3.0 counted, other two don't


# -----------------------------------------------------------------------------
# build_window_fixture pipeline, stub fetcher (no network)
# -----------------------------------------------------------------------------


class StubFetcher:
    """Canned payloads shaped like real yfinance/FRED/pykrx responses. Dates are
    generated relative to the (start, end) actually requested so they land inside
    whatever window's evaluation range drives the call."""

    def __init__(self) -> None:
        self.calls: list[str] = []

    def yfinance(self, symbol: str, start: date, end: date) -> pd.DataFrame:
        self.calls.append(f"yfinance:{symbol}")
        # MultiIndex(field, ticker) columns, matching the real yfinance 1.5.2 shape
        # (MT0-03 결함 #2 — a flat-column stub let this regression through once).
        idx = pd.bdate_range(start=pd.Timestamp(start), end=pd.Timestamp(end))
        columns = pd.MultiIndex.from_tuples(
            [
                ("Open", symbol),
                ("High", symbol),
                ("Low", symbol),
                ("Close", symbol),
                ("Adj Close", symbol),
                ("Volume", symbol),
            ]
        )
        data = [[16.2, 17.0, 16.0, 16.5, 16.5, 1000] for _ in range(len(idx))]
        return pd.DataFrame(data, index=idx, columns=columns)

    def fred(self, series_id: str, start: date, end: date) -> list[dict]:
        self.calls.append(f"fred:{series_id}")
        return [
            {"date": (end - timedelta(days=2)).isoformat(), "value": "3.21"},
            {
                "date": (end - timedelta(days=1)).isoformat(),
                "value": ".",
            },  # FRED missing-value marker
            {"date": end.isoformat(), "value": "3.25"},
        ]

    def pykrx_index_ohlcv(self, symbol: str, start: date, end: date) -> pd.DataFrame:
        self.calls.append(f"pykrx_index_ohlcv:{symbol}")
        idx = pd.bdate_range(start=pd.Timestamp(start), end=pd.Timestamp(end))
        n = len(idx)
        return pd.DataFrame(
            {
                "시가": [2700.0] * n,
                "고가": [2720.0] * n,
                "저가": [2680.0] * n,
                "종가": [2710.0] * n,
                "거래량": [1_000_000] * n,
                "거래대금": [1.0e12] * n,
            },
            index=idx,
        )

    def pykrx_investor_value(
        self, market: str, investor: str, start: date, end: date
    ) -> pd.DataFrame:
        self.calls.append(f"pykrx_investor_value:{market}:{investor}")
        idx = pd.bdate_range(start=pd.Timestamp(start), end=pd.Timestamp(end))
        n = len(idx)
        return pd.DataFrame(
            {
                "기관합계": [1.0] * n,
                "기타법인": [1.0] * n,
                "개인": [-2.0] * n,
                "외국인합계": [1.5] * n,
                "전체": [0.0] * n,
            },
            index=idx,
        )

    def pykrx_vkospi(self, start: date, end: date) -> pd.DataFrame:
        self.calls.append("pykrx_vkospi")
        # K-02: pykrx has no confirmed VKOSPI ticker — real code raises, we mirror that here.
        raise RuntimeError(
            "VKOSPI ticker not resolvable via pykrx index metadata (K-02)"
        )


def _set_all_credentials(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("FRED_API_KEY", "dummy-test-key")
    monkeypatch.setenv("KRX_ID", "dummy-test-id")
    monkeypatch.setenv("KRX_PW", "dummy-test-pw")


def test_build_window_fixture_end_to_end(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _set_all_credentials(monkeypatch)
    plan = derive_collection_plan(_indicators_cfg())
    window = next(w for w in load_windows() if w.window_id == "w2024_05_calm")
    fetcher = StubFetcher()

    df, meta = build_window_fixture(
        window, plan, fetcher, tmp_path, _sources_cfg(), sleeper=lambda _seconds: None
    )

    validate_fixture(df)  # must not raise
    assert not df.empty

    assert meta["window_id"] == "w2024_05_calm"
    assert meta["definition"]["kind"] == "negative"
    assert meta["series"]["^VIX"]["status"] == "ok"
    # stub covers the whole [start, end] range for every us_market series, so the
    # kind's empirical reference union == full coverage for all of them.
    assert (
        meta["series"]["^VIX"]["distinct_days"]
        == meta["series"]["^VIX"]["reference_days"]
    )
    assert meta["series"]["^VIX"]["missing_rate"] == 0.0
    assert meta["series"]["^VIX"]["last_as_of"] == window.end.isoformat()
    assert meta["series"]["BAMLH0A0HYM2"]["status"] == "ok"
    assert (
        meta["series"]["BAMLH0A0HYM2"]["distinct_days"] == 2
    )  # one "." observation dropped
    assert meta["series"]["KRX:1001"]["status"] == "ok"
    assert meta["series"]["KRX:1001"]["missing_rate"] == 0.0
    assert meta["series"]["KRX:investor_foreign_kospi"]["status"] == "ok"
    # K-02: VKOSPI unresolved via pykrx -> recorded as error, pipeline still completes,
    # and its absence doesn't drag the other two krx-kind series' missing_rate down
    # (their own union coverage, not VKOSPI's failure, is what they're measured against).
    assert meta["series"]["KRX:VKOSPI"]["status"] == "error"
    # fx (KRW=X) is a single-member calendar_kind -> missing_rate structurally 0,
    # advisory gaps key present (empty here since the stub covers the full range).
    assert meta["series"]["KRW=X"]["missing_rate"] == 0.0
    assert meta["series"]["KRW=X"]["fx_advisory_gaps"] == []
    # excluded (ECOS/scrape_wgb, disabled indicators) recorded, not silently dropped
    assert meta["series"]["krx_credit_spread_delta"]["status"] == "uncollected"
    assert all(0.0 <= v["missing_rate"] <= 1.0 for v in meta["series"].values())

    out_path = tmp_path / f"{window.window_id}.parquet"
    df.to_parquet(out_path)
    reloaded = pd.read_parquet(out_path)
    validate_fixture(reloaded)  # round-trips through parquet without violating schema


class _KrwXEmptyFetcher(StubFetcher):
    """O4-A(MT0-03 이월 관찰, aaa-critic 라운드1): fx_advisory_gaps 자체(순수 함수)는
    total-loss 케이스가 테스트돼 있지만, build_window_fixture 배선(_series_meta_entry의
    is_fx=True 경로)이 실제로 그 결과를 meta에 붙이는지는 무증인이었다 — KRW=X만 완전
    공백으로 만들어 배선을 확인한다."""

    def yfinance(self, symbol: str, start: date, end: date) -> pd.DataFrame:
        if symbol == "KRW=X":
            self.calls.append("yfinance:KRW=X")
            return pd.DataFrame()
        return super().yfinance(symbol, start, end)


def test_build_window_fixture_wires_fx_no_observations_advisory(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _set_all_credentials(monkeypatch)
    plan = derive_collection_plan(_indicators_cfg())
    window = next(w for w in load_windows() if w.window_id == "w2024_05_calm")
    _df, meta = build_window_fixture(
        window,
        plan,
        _KrwXEmptyFetcher(),
        tmp_path,
        _sources_cfg(),
        sleeper=lambda _s: None,
    )
    assert meta["series"]["KRW=X"]["fx_advisory_gaps"][0]["kind"] == "no_observations"


def test_build_window_fixture_blocked_without_fred_api_key(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.delenv("FRED_API_KEY", raising=False)
    plan = derive_collection_plan(_indicators_cfg())
    window = next(w for w in load_windows() if w.window_id == "w2024_05_calm")
    fetcher = StubFetcher()

    _df, meta = build_window_fixture(
        window, plan, fetcher, tmp_path, _sources_cfg(), sleeper=lambda _seconds: None
    )

    assert meta["series"]["BAMLH0A0HYM2"]["status"] == "blocked_missing_api_key"
    assert not any(call.startswith("fred:") for call in fetcher.calls)


def test_build_window_fixture_blocked_without_krx_credentials(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """DEF-6: missing KRX_ID/KRX_PW must be its own status, not indistinguishable
    from an actual KRX server block (that ambiguity cost a full round already)."""
    monkeypatch.setenv("FRED_API_KEY", "dummy-test-key")
    monkeypatch.delenv("KRX_ID", raising=False)
    monkeypatch.delenv("KRX_PW", raising=False)
    plan = derive_collection_plan(_indicators_cfg())
    window = next(w for w in load_windows() if w.window_id == "w2024_05_calm")
    fetcher = StubFetcher()

    _df, meta = build_window_fixture(
        window, plan, fetcher, tmp_path, _sources_cfg(), sleeper=lambda _seconds: None
    )

    for series_id in ("KRX:1001", "KRX:investor_foreign_kospi", "KRX:VKOSPI"):
        assert meta["series"][series_id]["status"] == "blocked_missing_credentials"
        # F3-1: all three are the entire "krx" calendar_kind, so their reference
        # union is empty -- must read 100% missing, never 0% (false full coverage).
        assert meta["series"][series_id]["missing_rate"] == 1.0
    assert not any(call.startswith("pykrx_") for call in fetcher.calls)


def test_krx_blocked_credentials_report_has_no_status_coverage_contradiction(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """F3-1 ⓑ: with KRX_ID/KRX_PW missing, §2 shows blocked_missing_credentials for
    every KRX series — §3's kr_flow_price axis rollup (entirely pykrx-dependent)
    must then show (near) zero coverage, never a contradictory high/full number
    that the F3-1 empty-reference bug would have produced."""
    monkeypatch.setenv("FRED_API_KEY", "dummy-test-key")
    monkeypatch.delenv("KRX_ID", raising=False)
    monkeypatch.delenv("KRX_PW", raising=False)
    indicators_cfg = _indicators_cfg()
    plan = derive_collection_plan(indicators_cfg)
    window = next(w for w in load_windows() if w.window_id == "w2024_05_calm")
    fetcher = StubFetcher()

    _df, meta = build_window_fixture(
        window, plan, fetcher, tmp_path, _sources_cfg(), sleeper=lambda _seconds: None
    )
    (tmp_path / f"{window.window_id}.meta.json").write_text(
        json.dumps(meta, ensure_ascii=False), encoding="utf-8"
    )
    report = bf_module.render_report(tmp_path, CONFIGS_DIR)
    assert "blocked_missing_credentials" in report  # §2

    series_missing = {sid: e["missing_rate"] for sid, e in meta["series"].items()}
    coverage = axis_coverage_weights(
        indicators_cfg, plan.indicator_series, series_missing
    )
    # kr_flow_price is entirely pykrx-dependent (vkospi_z/kospi_drawdown/
    # foreign_net_sell_kospi/kospi_volume_distribution) -- all blocked -> 0, exactly.
    assert coverage["kr_flow_price"] == pytest.approx(0.0)


def test_build_window_fixture_cache_avoids_refetch(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _set_all_credentials(monkeypatch)
    plan = derive_collection_plan(_indicators_cfg())
    window = next(w for w in load_windows() if w.window_id == "w2024_05_calm")
    fetcher = StubFetcher()
    sources_cfg = _sources_cfg()

    build_window_fixture(
        window, plan, fetcher, tmp_path, sources_cfg, sleeper=lambda _seconds: None
    )
    assert any(c.startswith("yfinance:") for c in fetcher.calls)

    fetcher.calls.clear()
    build_window_fixture(
        window, plan, fetcher, tmp_path, sources_cfg, sleeper=lambda _seconds: None
    )
    # second run without --force must hit the on-disk cache, not the fetcher, for every
    # series that succeeded the first time. VKOSPI (K-02) never succeeds so is never
    # cached and is the only series expected to retry.
    assert not any(c.startswith("yfinance:") for c in fetcher.calls)
    assert not any(c.startswith("fred:") for c in fetcher.calls)
    assert not any(c.startswith("pykrx_index_ohlcv:") for c in fetcher.calls)
    assert not any(c.startswith("pykrx_investor_value:") for c in fetcher.calls)
    assert fetcher.calls == ["pykrx_vkospi"]


def test_build_window_fixture_force_bypasses_cache(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _set_all_credentials(monkeypatch)
    plan = derive_collection_plan(_indicators_cfg())
    window = next(w for w in load_windows() if w.window_id == "w2024_05_calm")
    fetcher = StubFetcher()
    sources_cfg = _sources_cfg()

    build_window_fixture(
        window, plan, fetcher, tmp_path, sources_cfg, sleeper=lambda _seconds: None
    )
    fetcher.calls.clear()
    build_window_fixture(
        window,
        plan,
        fetcher,
        tmp_path,
        sources_cfg,
        force=True,
        sleeper=lambda _seconds: None,
    )
    # --force must bypass the cache for every provider, not just skip silently.
    assert any(c.startswith("yfinance:") for c in fetcher.calls)
    assert any(c.startswith("fred:") for c in fetcher.calls)
    assert any(c.startswith("pykrx_index_ohlcv:") for c in fetcher.calls)
    assert any(c.startswith("pykrx_investor_value:") for c in fetcher.calls)


def test_cache_key_changes_on_request_range(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """DEF-5: the cache key must include the requested collection range, so a
    widened/shifted range is a cache miss (refetch), not a silent stale reuse."""
    _set_all_credentials(monkeypatch)
    plan = derive_collection_plan(_indicators_cfg())
    window = next(w for w in load_windows() if w.window_id == "w2024_05_calm")
    fetcher = StubFetcher()
    sources_cfg = _sources_cfg()

    build_window_fixture(
        window, plan, fetcher, tmp_path, sources_cfg, sleeper=lambda _seconds: None
    )
    assert any(c.startswith("yfinance:") for c in fetcher.calls)

    fetcher.calls.clear()
    original_padding = bf_module.padding_days()
    monkeypatch.setattr(bf_module, "padding_days", lambda: original_padding + 30)
    build_window_fixture(
        window, plan, fetcher, tmp_path, sources_cfg, sleeper=lambda _seconds: None
    )
    # widened padding -> different collect_start -> cache key must differ -> refetch
    assert any(c.startswith("yfinance:") for c in fetcher.calls)
    assert any(c.startswith("fred:") for c in fetcher.calls)
    assert any(c.startswith("pykrx_index_ohlcv:") for c in fetcher.calls)


# -----------------------------------------------------------------------------
# Degenerate-input sweep (round-4 standing rule): every derived number in the
# report needs a witness for its degenerate inputs (empty denominator, total
# loss, single series). Swept this round: zero-weight axis (division guard) and
# zero fixtures collected yet (empty metas) -- both render without crashing and
# without a fabricated percentage, exercised together since one meta.json-free
# fixtures_dir naturally is also the "no gaps anywhere" empty-list case for §4.
# -----------------------------------------------------------------------------


def test_render_report_handles_zero_weight_axis(tmp_path: Path) -> None:
    configs_dir = tmp_path / "configs"
    configs_dir.mkdir()
    (configs_dir / "indicators.yaml").write_text(
        yaml.safe_dump(
            {
                "registry_version": "test",
                "indicators": [
                    {
                        "id": "zero_weight_ind",
                        "axis": "zero_axis",
                        "weight": 0.0,
                        "source": {"provider": "yfinance", "symbol": "^VIX"},
                    }
                ],
            }
        ),
        encoding="utf-8",
    )
    fixtures_dir = tmp_path / "fixtures"
    fixtures_dir.mkdir()
    (fixtures_dir / "w_zero_axis.meta.json").write_text(
        json.dumps(
            {
                "window_id": "w_zero_axis",
                "definition": {
                    "start": "2024-05-13",
                    "end": "2024-05-24",
                    "anchor_hint": None,
                },
                "collected_range": {"start": "2022-11-15", "end": "2024-05-24"},
                "series": {
                    "^VIX": {
                        "status": "ok",
                        "distinct_days": 9,
                        "reference_days": 9,
                        "missing_rate": 0.0,
                        "last_as_of": "2024-05-24",
                        "padding_rows": 0,
                        "gaps": [],
                    }
                },
            }
        ),
        encoding="utf-8",
    )

    report = bf_module.render_report(
        fixtures_dir, configs_dir
    )  # must not raise (no ZeroDivisionError)

    assert "zero_axis" in report
    assert "0%" in report  # guarded division (total > 0 else 0.0), not a crash
    assert "(없음)" in report  # §4 empty-gaps fallback row, not an empty/broken table


def test_render_report_handles_no_fixtures_collected_yet(tmp_path: Path) -> None:
    fixtures_dir = tmp_path / "fixtures"
    fixtures_dir.mkdir()  # no *.meta.json at all yet

    report = bf_module.render_report(fixtures_dir, CONFIGS_DIR)  # must not raise

    assert "# BT-01 Fixture Collection Report" in report
    assert "(없음)" in report  # §4 empty-gaps fallback row, not an empty/broken table


def test_render_report_includes_legend_registry_stamp_and_full_gap_list(
    tmp_path: Path,
) -> None:
    meta = {
        "schema": "backtest-fixture-meta/1",
        "window_id": "w_test_synthetic",
        "definition": {
            "start": "2024-05-13",
            "end": "2024-05-24",
            "anchor_hint": None,
            "kind": "negative",
            "holdout": False,
            "character": "synthetic",
        },
        "collected_range": {"start": "2022-11-15", "end": "2024-05-24"},
        "collected_at": "2024-05-25T00:00:00+00:00",
        "series": {
            "^VIX": {
                "status": "ok",
                "distinct_days": 9,
                "reference_days": 9,
                "missing_rate": 0.0,
                "last_as_of": "2024-05-24",
                "padding_rows": 500,
                "gaps": [],
            },
            "^MOVE": {
                "status": "ok",
                "distinct_days": 5,
                "reference_days": 9,
                "missing_rate": 0.4444,
                "last_as_of": "2024-05-20",
                "padding_rows": 480,
                # two separate gaps -- both must appear, not just one (§D-④).
                "gaps": [
                    {"start": "2024-05-15", "end": "2024-05-15", "sessions": 1},
                    {"start": "2024-05-21", "end": "2024-05-23", "sessions": 3},
                ],
            },
            "KRW=X": {
                "status": "ok",
                "distinct_days": 7,
                "reference_days": 7,
                "missing_rate": 0.0,
                "last_as_of": "2024-05-24",
                "padding_rows": 490,
                "gaps": [],
                "fx_advisory_gaps": [
                    {
                        "kind": "tail_gap",
                        "start": "2024-05-23",
                        "end": "2024-05-24",
                        "weekdays": 2,
                    }
                ],
            },
        },
        "note": "근사-PIT — C1에서 실측 확정 (BACKTEST_PLAN.md §5)",
    }
    (tmp_path / "w_test_synthetic.meta.json").write_text(
        json.dumps(meta, ensure_ascii=False), encoding="utf-8"
    )

    report = bf_module.render_report(tmp_path, CONFIGS_DIR)

    # legend section (D-①): must explain the empirical-union rule, not exchange_calendars.
    assert "합집합" in report
    assert "exchange_calendars" in report  # named specifically as what's NOT used
    # registry version stamp (D-③)
    assert str(_indicators_cfg()["registry_version"]) in report
    # per-window evaluation range (D-②)
    assert "2024-05-13" in report and "2024-05-24" in report
    # window×series coverage rows
    assert "w_test_synthetic" in report
    assert "^VIX" in report
    assert "^MOVE" in report
    assert "vol_global" in report  # axis rollup section must name the axis
    assert "credit" in report  # ...for every axis, not just the ones with data
    # full gap span list (D-④): BOTH of ^MOVE's gaps must appear, not just one.
    assert "2024-05-15" in report
    assert "2024-05-21" in report and "2024-05-23" in report
    # fx advisory gap, labeled as having no independent reference
    assert "독립 기준 없음" in report
    assert "tail_gap" in report


# -----------------------------------------------------------------------------
# main() CLI — REPORT_fixtures.md must survive a console-encoding print failure
# (MT0-03 결함 #1: Windows cp949 console UnicodeEncodeError on the em-dash in the
# report text used to crash main() before the file write was reached).
# -----------------------------------------------------------------------------


def test_main_report_file_survives_print_failure(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _set_all_credentials(monkeypatch)
    monkeypatch.setattr(bf_module, "FIXTURES_DIR", tmp_path)
    monkeypatch.setattr(bf_module, "LiveFetcher", StubFetcher)

    def fake_print(*args: object, **_kwargs: object) -> None:
        text = " ".join(str(a) for a in args)
        if not text.isascii():
            # simulates the real cp949 console codec failure on non-ASCII text
            raise UnicodeEncodeError(
                "cp949", text, 0, 1, "simulated console codec failure"
            )

    monkeypatch.setattr("builtins.print", fake_print)

    with pytest.raises(UnicodeEncodeError):
        bf_module.main(["--window", "w2024_05_calm"])

    report_path = tmp_path / "REPORT_fixtures.md"
    assert (
        report_path.exists()
    )  # write-before-print: file must land regardless of print failing
    assert "근사-PIT" in report_path.read_text(encoding="utf-8")
