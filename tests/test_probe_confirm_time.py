"""MT1-00g instrument self-check: pure logic in scripts/probe_confirm_time.py,
no network (repo rule - real polling is a manual `uv run python
scripts/probe_confirm_time.py` invocation, not something CI should ever run)."""

from __future__ import annotations

from datetime import date

import pandas as pd

from scripts.probe_confirm_time import (
    _row_status,
    _to_jsonable,
    probe_vkospi_fallback,
)


def test_row_status_confirmed_today() -> None:
    today = date(2026, 8, 7)
    df = pd.DataFrame(
        {"종가": [3000.0], "거래대금": [1.0e13]},
        index=pd.to_datetime(["2026-08-07"]),
    )
    result = _row_status(df, today)
    assert result["status"] == "confirmed_today"
    assert result["latest_date"] == "2026-08-07"
    assert result["values"]["종가"] == 3000.0


def test_row_status_not_yet_today() -> None:
    today = date(2026, 8, 7)
    df = pd.DataFrame({"종가": [2990.0]}, index=pd.to_datetime(["2026-08-06"]))
    result = _row_status(df, today)
    assert result["status"] == "not_yet_today"
    assert result["latest_date"] == "2026-08-06"


def test_row_status_empty_frame_is_no_data() -> None:
    assert _row_status(pd.DataFrame(), date(2026, 8, 7))["status"] == "no_data"


def test_row_status_drops_yfinance_multiindex() -> None:
    """yfinance returns (field, ticker) MultiIndex columns even for one symbol
    (backtest/fixture_schema.py normalize_yfinance has the original repro)."""
    today = date(2026, 8, 7)
    df = pd.DataFrame(
        {("Close", "KRW=X"): [1350.0]}, index=pd.to_datetime(["2026-08-07"])
    )
    df.columns = pd.MultiIndex.from_tuples(df.columns)
    result = _row_status(df, today)
    assert result["status"] == "confirmed_today"
    assert result["values"]["Close"] == 1350.0


def test_to_jsonable_nan_becomes_none() -> None:
    assert _to_jsonable(float("nan")) is None
    assert _to_jsonable(3.5) == 3.5


def test_vkospi_inherits_kospi_close_status() -> None:
    result = probe_vkospi_fallback({"status": "confirmed_today"})
    assert result["status"] == "fallback_active"
    assert result["kospi_close_status"] == "confirmed_today"
