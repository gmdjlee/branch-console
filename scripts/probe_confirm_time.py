"""MT1-00g instrument: probe whether the 4 datasets the 17:00 KST confirm tick
depends on have already finalized for "today", at the moment this script runs.

docs/plans/M1_PLAN_A.md §3 MT1-00g requires >=3 consecutive trading days polled
at 16:00/16:30/17:00/17:30/18:00/19:00 KST each. Run this script by hand once
per slot; it appends one record per run to a dated JSON file. This script only
records raw facts (does a row for today exist yet, and with what values) - the
pre-registered judgment rule (raise confirm time? golden rerun needed?) is
applied later, by hand, once enough samples exist. No judgment logic lives here.

Datasets probed (plan's item numbering):
  1 KOSPI index close + 거래대금 (pykrx get_index_ohlcv_by_date, ticker "1001")
  2 investor net buying, market-wide (pykrx get_market_trading_value_by_date,
    ticker "KOSPI", on="순매수")
  3 VKOSPI - K-02 confirmed unavailable via pykrx's index API (sources.yaml,
    2026-08-02). Fallback realized_vol_kospi_20d derives from the same KOSPI
    OHLCV series probed for item 1, so it inherits that item's confirm status
    instead of spending a redundant KRX call re-confirming the same fact.
  4 KRW=X daily (yfinance)

Usage:
    uv run python scripts/probe_confirm_time.py [--label 1700]

Requires KRX_ID/KRX_PW in the shell environment (pykrx 1.2.8 KRX login).
Missing credentials -> items 1/2/3 recorded as blocked_missing_credentials,
not an exception (K-01/K-02 spirit: record and continue, never guess).
Makes <=2 pykrx calls and 1 yfinance call per run, well under the "<=3 calls
per item" verification budget. K-03: >=1s between the two pykrx calls.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys
import time
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

KST = ZoneInfo("Asia/Seoul")
OUT_DIR = Path(__file__).resolve().parent / "out" / "confirm_time_probe"
PYKRX_MIN_INTERVAL_S = 1.0  # K-03: minimum spacing between pykrx calls
LOOKBACK_DAYS = 7  # enough buffer to always include the last trading day


def _to_jsonable(v: Any) -> Any:
    try:
        f = float(v)
        return None if math.isnan(f) else f
    except (TypeError, ValueError):
        return str(v)


def _row_status(df: Any, today: date) -> dict[str, Any]:
    """Did a row for `today` already land, and with what values."""
    if df is None or df.empty:
        return {"status": "no_data", "latest_date": None, "values": {}}
    import pandas as pd  # local import: keep module import light for --help

    if isinstance(df.columns, pd.MultiIndex):
        # yfinance MultiIndex quirk even for a single symbol, see
        # backtest/fixture_schema.py normalize_yfinance for the original repro.
        df = df.copy()
        df.columns = df.columns.get_level_values(0)
    latest_ts = df.index[-1]
    latest_date = latest_ts.date() if hasattr(latest_ts, "date") else latest_ts
    values = {str(k): _to_jsonable(v) for k, v in df.iloc[-1].items()}
    status = "confirmed_today" if latest_date == today else "not_yet_today"
    return {"status": status, "latest_date": str(latest_date), "values": values}


def probe_kospi_close(today: date, has_krx_creds: bool) -> dict[str, Any]:
    if not has_krx_creds:
        return {"status": "blocked_missing_credentials"}
    from pykrx import stock

    fromdate = (today - timedelta(days=LOOKBACK_DAYS)).strftime("%Y%m%d")
    todate = today.strftime("%Y%m%d")
    try:
        df = stock.get_index_ohlcv_by_date(fromdate, todate, "1001")
        return _row_status(df, today)
    except Exception as exc:  # noqa: BLE001 - record and continue, never raise
        return {"status": "error", "error": str(exc)}


def probe_investor_net_buying(today: date, has_krx_creds: bool) -> dict[str, Any]:
    if not has_krx_creds:
        return {"status": "blocked_missing_credentials"}
    from pykrx import stock

    fromdate = (today - timedelta(days=LOOKBACK_DAYS)).strftime("%Y%m%d")
    todate = today.strftime("%Y%m%d")
    try:
        df = stock.get_market_trading_value_by_date(
            fromdate, todate, "KOSPI", on="순매수"
        )
        return _row_status(df, today)
    except Exception as exc:  # noqa: BLE001
        return {"status": "error", "error": str(exc)}


def probe_vkospi_fallback(kospi_result: dict[str, Any]) -> dict[str, Any]:
    return {
        "status": "fallback_active",
        "fallback": "realized_vol_kospi_20d",
        "inherits_confirm_status_from": "kospi_close",
        "kospi_close_status": kospi_result.get("status"),
    }


def probe_krwusd(today: date) -> dict[str, Any]:
    import yfinance as yf

    start = (today - timedelta(days=LOOKBACK_DAYS)).isoformat()
    end = (today + timedelta(days=1)).isoformat()
    try:
        df = yf.download(
            "KRW=X", start=start, end=end, progress=False, auto_adjust=False
        )
        return _row_status(df, today)
    except Exception as exc:  # noqa: BLE001 - K-01: record and continue
        return {"status": "error", "error": str(exc)}


def run_probe(label: str | None) -> dict[str, Any]:
    now = datetime.now(KST)
    today = now.date()
    has_krx_creds = bool(os.environ.get("KRX_ID") and os.environ.get("KRX_PW"))

    kospi = probe_kospi_close(today, has_krx_creds)
    time.sleep(PYKRX_MIN_INTERVAL_S)  # K-03
    investor = probe_investor_net_buying(today, has_krx_creds)
    vkospi = probe_vkospi_fallback(kospi)
    fx = probe_krwusd(today)

    return {
        "polled_at_kst": now.isoformat(),
        "label": label,
        "today": today.isoformat(),
        "items": {
            "kospi_close": kospi,
            "investor_net_buying": investor,
            "vkospi": vkospi,
            "krwusd_fx": fx,
        },
    }


def append_record(record: dict[str, Any]) -> Path:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    out_path = OUT_DIR / f"{record['today']}.json"
    records: list[dict[str, Any]] = []
    if out_path.exists():
        records = json.loads(out_path.read_text(encoding="utf-8"))
    records.append(record)
    out_path.write_text(
        json.dumps(records, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return out_path


def main(argv: list[str] | None = None) -> int:
    # K-xx cp949 콘솔 함정 (backtest/build_fixtures.py 선례 재사용): argparse의
    # --help 자체도 이 시점 이전에 stdout에 쓰므로 parse_args보다 먼저 둔다.
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--label",
        default=None,
        help="poll slot marker for readability, e.g. 1700 (not used for logic)",
    )
    args = parser.parse_args(argv)

    record = run_probe(args.label)
    out_path = append_record(record)
    print(json.dumps(record, ensure_ascii=False, indent=2))
    print(f"appended -> {out_path}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
