"""backtest/run_replay.py — BT-02 창×프로파일 리플레이 (근사-PIT).

    uv run python backtest/run_replay.py --profile server_intraday|mobile_daily|both \
        [--window <id|all>] [--config <indicators.yaml 경로, 기본 configs/indicators.yaml>]

입력은 backtest/fixtures/<window_id>.parquet(BT-01, build_fixtures.py 산출)만이다 —
네트워크 접근 없음. 산출은 backtest/results/metrics.json.

이 모듈이 정의하는 근사-PIT 리플레이 의미론(D-06 PIT 원장 규율, K-11 look-ahead 금지)은
셋:

1. **틱 그리드(경험적 거래일 달력)**: exchange_calendars 등 외부 달력을 쓰지 않는다
   (MT0-03/BT-01의 교훈 — 2026년 임시 휴장일을 못 따라잡는 드리프트가 유령 결측을 만든다).
   대신 창의 픽스처에서 calendar_kind(series_id)=="krx"인 계열들이 실제로 관측을 낸
   날짜의 합집합을 "거래일"로 쓴다(``trading_days``). mobile_daily는 거래일마다 확정 틱
   1개(``replay.yaml`` profiles.mobile_daily.confirm_time_kst, 기본 16:20 KST). 픽스처가
   일봉 근사이므로 server_intraday도 거래일마다 여러 틱(``configs/statemachine.yaml``
   schedules.evaluation의 us_close/kr_intraday/kr_close cron을 그 거래일에 전개)을 갖되,
   일중 새 정보는 없다 — 값 갱신은 아래 2의 가시성 시점에만 계단식으로 일어난다.

2. **가시성(근사-PIT)**: 픽스처 한 행(series_id, field, as_of=T, value)이 "최초로 알려지는"
   틱을 계열의 calendar_kind로 판정한다(K-05: naive datetime 금지, 전부 UTC-aware로 비교) —

   - ``us_market``(야후 미국 지수: ^VIX·^VIX3M·^MOVE·^GSPC·DX-Y.NYB): T *다음* 거래일의
     us_close 틱(server) / T 다음 거래일의 확정 틱(mobile). 미국 마감은 KST로 다음날
     새벽에야 반영된다는 사실을 인코딩한다.
   - ``fred``(BAMLH0A0HYM2·T10Y2Y): T + lag_days(indicators.yaml source.lag_days, FRED
     T+1) *이후* 첫 거래일의 us_close 틱(server) / 그 거래일의 확정 틱(mobile).
   - ``krx``·``fx``(KRX pykrx 계열·KRW=X): T *당일*의 kr_close 틱(server) / T 당일의
     확정 틱(mobile). 이보다 이른 그 날의 다른 server 틱(us_close·kr_intraday)에서 T의
     값을 보이면 look-ahead다 — 일봉 근사이므로 그 값은 장 마감에야 확정된다고 취급한다.

   T가 그 프로파일의 거래일 그리드에 없으면(예: KRW=X가 KRX 휴장일에도 관측을 내는 극단
   케이스), "T 이후 첫 그리드일"로 대체한다 — 항상 미래가 아니라 과거/현재 쪽으로만
   당겨지므로 look-ahead가 아니다. 2개 계열을 쓰는 지표(vix_term_structure·
   global_corr_break)는 두 계열 각자의 가시 시각 중 **늦은 쪽**을 쓴다(둘 다 알려져야
   그 날짜의 값을 안다 — worst-of-inputs, MT0-03 axis_coverage_weights와 동일 원칙).

   transform 자체(zscore·delta_bp 등)는 창의 전체 padding+평가구간 원계열에 대해 한 번만
   계산한다(인과적 — engine_ref.transforms의 rolling 계열은 항상 과거만 본다,
   test_prefix_stability_no_lookahead 참조). 각 틱에서는 "그 시각까지 가시화된 가장 최근
   as_of 행의 transform 출력값"을 조회할 뿐이다 — 이것이 근사-PIT의 핵심: 같은 원계열이라도
   틱마다 알려진 최신 시점이 다르므로 조회되는 값도 다르다.

3. **스테일**: 가시화된 값이라도 ``engine_ref.registry.is_stale``(configs/indicators.yaml
   engine.stale_profiles[profile][cadence])로 판정해 초과분은 결측(severity=None) 취급한다
   — cadence는 그 지표가 indicators.yaml에 선언한 값(source.cadence)이지, 위 가시성 규칙이
   쓰는 calendar_kind와는 별개다(threshold의 성격이 다르다: 가시성은 "언제 처음 아는가"라는
   물리적 사실, 스테일은 "그 값을 여전히 믿을 만한가"라는 정책 판단). ^MOVE·^VIX3M의
   2026-07-17 이후 절단(K-01/K-18, 야후 비공식 API)은 이 경로로 자연히 결측 처리된다 —
   특수 케이스 코드 없음.

지표 파이프라인은 D-01 활성 15종 그대로: enabled 지표만(``engine_ref.registry.
load_indicator_specs``), vkospi_z는 K-02 폴백(창의 KRX:VKOSPI 관측이 0건이면
realized_vol_kospi_20d 경로 — 창마다 데이터로 판정, 하드코딩 아님), krx_credit_spread_delta·
kr_cds_5y_delta는 픽스처 미수집(BT-01 collection plan에서 ecos/scrape_wgb 제외)이라 상시
결측(정상 동작, D-02 분모 제외). modifier 2종(hy_level_boost·usdkrw_intraday_force)은
engine_ref.modifiers 그대로 적용. usdkrw 일중 변동폭은 KRW=X high/low/전일 close로 계산한다
(open 미사용 — MT0-03 O-6, KRW=X open 퇴화 확인됨).

상태기계는 engine_ref.statemachine.run에 그대로 위임한다(재구현 없음, D-25 의미론).
"""

from __future__ import annotations

import argparse
import bisect
import json
import sys
from dataclasses import dataclass
from datetime import UTC, date, datetime, time, timedelta, timezone
from itertools import pairwise
from pathlib import Path
from typing import Any, Literal

import pandas as pd
import yaml

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from backtest.fixture_schema import (
    WindowDef,
    calendar_kind,
    eval_observed_dates,
    load_windows,
)
from engine_ref import modifiers, registry, scoring, statemachine, transforms
from engine_ref.registry import HyLevelBoost, IndicatorSpec, UsdkrwIntradayForce

REPO_ROOT = Path(__file__).resolve().parent.parent
CONFIGS_DIR = REPO_ROOT / "configs"
FIXTURES_DIR = REPO_ROOT / "backtest" / "fixtures"
RESULTS_DIR = REPO_ROOT / "backtest" / "results"
REPLAY_YAML_PATH = REPO_ROOT / "backtest" / "replay.yaml"
STATEMACHINE_YAML_PATH = CONFIGS_DIR / "statemachine.yaml"
DEFAULT_INDICATORS_PATH = CONFIGS_DIR / "indicators.yaml"

KST = timezone(timedelta(hours=9))
Profile = Literal["server_intraday", "mobile_daily"]

# indicator ids whose provider is out of M0 fixture-collection scope (ecos/scrape_wgb,
# see backtest/fixture_schema.py derive_collection_plan) — structurally missing every
# tick, every window (D-02 exclude-from-denominator handles this normally).
_ALWAYS_MISSING_INDICATORS = frozenset({"krx_credit_spread_delta", "kr_cds_5y_delta"})


def _load_yaml(path: Path) -> dict[str, Any]:
    # Windows cp949 함정 회피 — encoding 명시 필수.
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)


# -----------------------------------------------------------------------------
# KST <-> UTC + minimal cron-subset parsing (schedules.evaluation, statemachine.yaml)
# -----------------------------------------------------------------------------


def kst_to_utc(d: date, t: time) -> datetime:
    local = datetime(d.year, d.month, d.day, t.hour, t.minute, tzinfo=KST)
    return local.astimezone(UTC)


def _parse_cron_minute_field(field: str) -> list[int]:
    if field.startswith("*/"):
        step = int(field[2:])
        return list(range(0, 60, step))
    return [int(field)]


def _parse_cron_hour_field(field: str) -> list[int]:
    if "-" in field:
        lo, hi = field.split("-")
        return list(range(int(lo), int(hi) + 1))
    return [int(field)]


def parse_cron_kst_times(cron: str) -> list[time]:
    """5필드 cron('분 시 * * 요일')의 분/시 필드만 해석한다. day/month/dow 필드는 이
    SSOT(configs/statemachine.yaml schedules.evaluation)의 세 항목이 전부 '* * 1-5'
    고정이라 미해석 — 범용 cron 파서를 만들지 않는다(YAGNI, 이 세 항목만 커버하면 된다)."""
    fields = cron.split()
    minute_f, hour_f = fields[0], fields[1]
    minutes = _parse_cron_minute_field(minute_f)
    hours = _parse_cron_hour_field(hour_f)
    return sorted({time(h, m) for h in hours for m in minutes})


def load_schedule_times(
    statemachine_cfg: dict[str, Any],
) -> dict[str, list[time]]:
    """schedules.evaluation의 각 항목(id: kr_intraday/kr_close/us_close)을 KST 시각
    목록으로. SSOT: configs/statemachine.yaml — 시각을 이 모듈에 복제하지 않는다."""
    return {
        entry["id"]: parse_cron_kst_times(entry["cron"])
        for entry in statemachine_cfg["schedules"]["evaluation"]
    }


def load_mobile_confirm_time(replay_cfg: dict[str, Any]) -> time:
    raw = replay_cfg["profiles"]["mobile_daily"]["confirm_time_kst"]
    return datetime.strptime(raw, "%H:%M").time()  # noqa: DTZ007 - time-of-day only


# -----------------------------------------------------------------------------
# tick grid (empirical KRX trading-day calendar, MT0-03 precedent — no exchange_calendars)
# -----------------------------------------------------------------------------


def trading_days(fixture_df: pd.DataFrame, window: WindowDef) -> list[date]:
    """창의 거래일 그리드: calendar_kind=="krx"인 계열들이 [window.start, window.end]에서
    실제로 관측을 낸 날짜의 합집합(경험적 달력, MT0-03/BT-01과 동일 원칙 — 외부 달력
    패키지 미사용). window.end가 실제 수집 종료일보다 미래여도(w2026_structural처럼
    오늘 기준 클램프됨) 존재하지 않는 날짜는 자연히 빠진다 — meta.json을 별도로 볼 필요 없음."""
    if fixture_df.empty:
        return []
    krx_rows = fixture_df[fixture_df["series_id"].map(calendar_kind) == "krx"]
    return sorted(eval_observed_dates(krx_rows, window.start, window.end))


def build_tick_grid(
    grid_days: list[date],
    profile: Profile,
    schedule_times: dict[str, list[time]],
    mobile_confirm_time: time,
) -> list[tuple[datetime, date, str]]:
    """(evaluated_at_utc, kst_date, kst_time_label) 오름차순 리스트."""
    out: list[tuple[datetime, date, str]] = []
    if profile == "mobile_daily":
        for d in grid_days:
            out.append(
                (
                    kst_to_utc(d, mobile_confirm_time),
                    d,
                    mobile_confirm_time.strftime("%H:%M"),
                )
            )
        return out
    for d in grid_days:
        for sched_id in ("us_close", "kr_intraday", "kr_close"):
            for t in schedule_times[sched_id]:
                out.append((kst_to_utc(d, t), d, t.strftime("%H:%M")))
    out.sort(key=lambda row: row[0])
    return out


# -----------------------------------------------------------------------------
# visibility (approx-PIT) — see module docstring §2
# -----------------------------------------------------------------------------


def _first_grid_day_on_or_after(grid: list[date], d: date) -> date | None:
    i = bisect.bisect_left(grid, d)
    return grid[i] if i < len(grid) else None


def _first_grid_day_after(grid: list[date], d: date) -> date | None:
    i = bisect.bisect_right(grid, d)
    return grid[i] if i < len(grid) else None


def raw_visibility_grid_day(
    series_id: str, as_of: date, grid: list[date], fred_lag: dict[str, int]
) -> date | None:
    """as_of(T)가 최초로 "알려지는" 그리드일. calendar_kind별 규칙(모듈 docstring §2)."""
    kind = calendar_kind(series_id)
    if kind == "us_market":
        return _first_grid_day_after(grid, as_of)
    if kind == "fred":
        lag = fred_lag.get(series_id, 0)
        return _first_grid_day_on_or_after(grid, as_of + timedelta(days=lag))
    return _first_grid_day_on_or_after(grid, as_of)  # krx / fx


def visibility_tick_utc(
    series_id: str,
    as_of: date,
    grid: list[date],
    fred_lag: dict[str, int],
    schedule_times: dict[str, list[time]],
    profile: Profile,
    mobile_confirm_time: time,
) -> datetime | None:
    vis_day = raw_visibility_grid_day(series_id, as_of, grid, fred_lag)
    if vis_day is None:
        return None
    if profile == "mobile_daily":
        return kst_to_utc(vis_day, mobile_confirm_time)
    kind = calendar_kind(series_id)
    tick_id = "kr_close" if kind in ("krx", "fx") else "us_close"
    times = schedule_times[tick_id]
    return kst_to_utc(vis_day, times[0])


def combined_visibility_utc(
    series_ids: tuple[str, ...],
    row_date: date,
    grid: list[date],
    fred_lag: dict[str, int],
    schedule_times: dict[str, list[time]],
    profile: Profile,
    mobile_confirm_time: time,
) -> datetime | None:
    """2계열 이상을 쓰는 지표의 결합 가시 시각 = 각 계열 자기 kind 규칙의 최댓값
    (worst-of-inputs — 둘 다 알려져야 그 날짜의 결합값을 안다)."""
    timestamps = [
        visibility_tick_utc(
            sid, row_date, grid, fred_lag, schedule_times, profile, mobile_confirm_time
        )
        for sid in series_ids
    ]
    if any(ts is None for ts in timestamps):
        return None
    return max(timestamps)


# -----------------------------------------------------------------------------
# KnownSeries — precomputed (row_date, visibility_ts, value) lookup table per indicator
# -----------------------------------------------------------------------------


@dataclass(frozen=True)
class KnownSeries:
    row_dates: list[date]
    visibility_ts: list[datetime]  # ascending, monotonic with row_dates
    values: list[float]


def build_known_series(
    value_series: pd.Series,
    input_series_ids: tuple[str, ...],
    grid: list[date],
    fred_lag: dict[str, int],
    schedule_times: dict[str, list[time]],
    profile: Profile,
    mobile_confirm_time: time,
) -> KnownSeries:
    rows: list[tuple[date, datetime, float]] = []
    for row_date, val in value_series.items():
        if pd.isna(val):
            continue
        vis = combined_visibility_utc(
            input_series_ids,
            row_date,
            grid,
            fred_lag,
            schedule_times,
            profile,
            mobile_confirm_time,
        )
        if vis is None:
            continue
        rows.append((row_date, vis, float(val)))
    rows.sort(
        key=lambda r: r[0]
    )  # visibility_ts is monotonic non-decreasing in row_date
    return KnownSeries([r[0] for r in rows], [r[1] for r in rows], [r[2] for r in rows])


def lookup_known(
    ks: KnownSeries, evaluated_at: datetime
) -> tuple[date, datetime, float] | None:
    """가장 최근에 가시화된 (row_date, 가시화 시각, value). 없으면 None."""
    i = bisect.bisect_right(ks.visibility_ts, evaluated_at) - 1
    if i < 0:
        return None
    return ks.row_dates[i], ks.visibility_ts[i], ks.values[i]


def load_stale_windows(
    profiles: tuple[str, ...],
    cadences: set[str],
    indicators_path: Path | None,
) -> dict[tuple[str, str], timedelta]:
    """profile x cadence 조합별 스테일 창을 **run_replay() 1회 호출당 한 번만**
    engine_ref.registry.stale_window(path=...)로 파싱한다(F-1, aaa-critic 라운드1:
    --config 오버라이드가 engine.stale_profiles에도 적용돼야 함).

    이걸 틱마다(수만 회) 직접 registry.is_stale(path=...)로 호출하면, F-2가 오버라이드
    경로에 대해 의도적으로 캐시를 끈 대가로 매 틱 yaml을 새로 열어 파싱하게 되어
    실측 재현 중 리플레이 하나가 수분간 멈추는 성능 회귀가 났다 — weights/axes/modifiers와
    동일하게 "한 번 로드해 틱마다 재사용"하는 패턴으로 바로잡는다."""
    return {
        (profile, cadence): registry.stale_window(
            profile, cadence, path=indicators_path
        )
        for profile in profiles
        for cadence in cadences
    }


def is_stale_check(
    cadence: str,
    visible_at: datetime,
    evaluated_at: datetime,
    profile: Profile,
    stale_windows: dict[tuple[str, str], timedelta],
) -> bool:
    """스테일 판정의 as_of는 원계열의 관측일(달력일 자정)이 아니라 **가시화 시각**이다
    (그 값이 이 리플레이 시스템에 처음 들어온 순간 — 일봉 근사에서 가진 가장 세밀한
    타임스탬프). 달력일 자정을 as_of로 쓰면 예컨대 kr_close(17:00 KST)에 막 가시화된
    당일 값이 "자정 대비 8시간 지남"으로 오판되어 intraday_30m(90분) 임계를 즉시
    넘겨버리는 오류가 난다 — 실측 재현 중 발견.

    stale_windows: load_stale_windows()가 미리 파싱해 둔 (profile, cadence) -> timedelta
    맵(등호 미포함 — 초과만 stale, engine_ref.registry.is_stale과 동일 규약)."""
    if visible_at.tzinfo is None or evaluated_at.tzinfo is None:
        raise ValueError("naive datetime 금지 (K-05, CLAUDE.md §2) — tz-aware만 허용")
    return (evaluated_at - visible_at) > stale_windows[(profile, cadence)]


# -----------------------------------------------------------------------------
# raw series access
# -----------------------------------------------------------------------------


def series_values(fixture_df: pd.DataFrame, series_id: str, field: str) -> pd.Series:
    """fixture 롱포맷에서 (series_id, field) 하나를 as_of(date)-indexed float64 Series로."""
    sub = fixture_df[
        (fixture_df["series_id"] == series_id) & (fixture_df["field"] == field)
    ]
    if sub.empty:
        return pd.Series(dtype="float64")
    idx = sub["as_of"].dt.date
    return sub.set_index(idx)["value"].astype("float64").sort_index()


def fred_lag_days(specs: list[IndicatorSpec]) -> dict[str, int]:
    out: dict[str, int] = {}
    for spec in specs:
        if spec.source.get("provider") == "fred":
            out[spec.source["series_id"]] = int(spec.source["lag_days"])
    return out


# -----------------------------------------------------------------------------
# per-indicator runtime wiring (D-01 활성 15종 — engine_ref.transforms 조합은
# indicators.yaml의 transform 문자열을 그대로 반영한다. window/lookback 등 파라미터는
# 전부 engine_ref.registry.parse_call_kwargs/parse_fallback_window로 파싱한 값만 쓴다 —
# 코드 리터럴 금지, CLAUDE.md §1)
# -----------------------------------------------------------------------------


@dataclass(frozen=True)
class _Ctx:
    df: pd.DataFrame
    grid: list[date]
    fred_lag: dict[str, int]
    schedule_times: dict[str, list[time]]
    profile: Profile
    mobile_confirm_time: time


def _known(
    value_series: pd.Series, input_ids: tuple[str, ...], ctx: _Ctx
) -> KnownSeries:
    return build_known_series(
        value_series,
        input_ids,
        ctx.grid,
        ctx.fred_lag,
        ctx.schedule_times,
        ctx.profile,
        ctx.mobile_confirm_time,
    )


def _build_simple(
    spec: IndicatorSpec, value_series: pd.Series, input_ids: tuple[str, ...], ctx: _Ctx
) -> dict[str, Any]:
    return {"kind": "simple", "known": _known(value_series, input_ids, ctx)}


def _build_vix_level_z(spec: IndicatorSpec, ctx: _Ctx) -> dict[str, Any]:
    close = series_values(ctx.df, "^VIX", "close")
    kwargs = registry.parse_call_kwargs("zscore", spec.transform)
    value = transforms.zscore(close, window=kwargs["window"])
    return _build_simple(spec, value, ("^VIX",), ctx)


def _build_vix_term_structure(spec: IndicatorSpec, ctx: _Ctx) -> dict[str, Any]:
    vix = series_values(ctx.df, "^VIX", "close")
    vix3m = series_values(ctx.df, "^VIX3M", "close")
    value = transforms.ratio(vix, vix3m)
    return _build_simple(spec, value, ("^VIX", "^VIX3M"), ctx)


def _build_move_index_z(spec: IndicatorSpec, ctx: _Ctx) -> dict[str, Any]:
    close = series_values(ctx.df, "^MOVE", "close")
    kwargs = registry.parse_call_kwargs("zscore", spec.transform)
    value = transforms.zscore(close, window=kwargs["window"])
    return _build_simple(spec, value, ("^MOVE",), ctx)


def _build_hy_oas_delta(spec: IndicatorSpec, ctx: _Ctx) -> dict[str, Any]:
    level = series_values(ctx.df, "BAMLH0A0HYM2", "value")
    kwargs = registry.parse_call_kwargs("delta_bp", spec.transform)
    delta = transforms.delta_bp(level, lookback=kwargs["lookback"])
    return {
        "kind": "hy_oas",
        "known": _known(delta, ("BAMLH0A0HYM2",), ctx),
        "level_series": level,
    }


def _build_dxy_z(spec: IndicatorSpec, ctx: _Ctx) -> dict[str, Any]:
    close = series_values(ctx.df, "DX-Y.NYB", "close")
    kwargs = registry.parse_call_kwargs("zscore", spec.transform)
    value = transforms.zscore(
        transforms.pct_change_5d(close),
        window=kwargs["window"],
        absolute=bool(kwargs.get("absolute", False)),
    )
    return _build_simple(spec, value, ("DX-Y.NYB",), ctx)


def _build_ust_2s10s_move(spec: IndicatorSpec, ctx: _Ctx) -> dict[str, Any]:
    level = series_values(ctx.df, "T10Y2Y", "value")
    kwargs = registry.parse_call_kwargs("delta_bp", spec.transform)
    value = transforms.abs_(transforms.delta_bp(level, lookback=kwargs["lookback"]))
    return _build_simple(spec, value, ("T10Y2Y",), ctx)


def _build_spx_drawdown_momentum(spec: IndicatorSpec, ctx: _Ctx) -> dict[str, Any]:
    close = series_values(ctx.df, "^GSPC", "close")
    dd_kwargs = registry.parse_call_kwargs("drawdown_from_high", spec.transform)
    nz_kwargs = registry.parse_call_kwargs("neg_zscore", spec.transform)
    dd = transforms.drawdown_from_high(close, window=dd_kwargs["window"])
    nz = transforms.neg_zscore(
        transforms.pct_change_5d(close), window=nz_kwargs["window"]
    )
    return {
        "kind": "combine_max",
        "known_a": _known(dd, ("^GSPC",), ctx),
        "known_b": _known(nz, ("^GSPC",), ctx),
        "cadence_a": spec.source["cadence"],
        "cadence_b": spec.source["cadence"],
    }


def _align_to_ffill(source: pd.Series, target_index: pd.Index) -> pd.Series:
    """source(다른 달력)를 target_index(예: KOSPI 거래일)에 정렬 — 미래값 없이(causal)
    가장 최근 알려진 값을 이월(ffill)한다. US·KR 거래 달력이 정확히 일치하지 않아
    (휴장일이 다름) rolling_corr에 그대로 넣으면 두 계열의 index 합집합 정렬로 NaN이
    산발해 window(20)를 채우는 쌍이 거의 나오지 않는다(실측 재현 중 발견) — 이 정렬은
    global_corr_break 전용 재량 판단이며 engine_ref.transforms.rolling_corr 자체는
    이미 정렬된 두 계열을 받는다고 가정한다(변경 없음)."""
    union_index = sorted(set(source.index) | set(target_index))
    return source.reindex(union_index).ffill().reindex(target_index)


def _build_global_corr_break(spec: IndicatorSpec, ctx: _Ctx) -> dict[str, Any]:
    spx_close = series_values(ctx.df, "^GSPC", "close")
    kospi_close = series_values(ctx.df, "KRX:1001", "close")
    ret_spx = transforms.pct_change_1d(spx_close)
    ret_kospi = transforms.pct_change_1d(kospi_close)
    ret_spx_on_kr = _align_to_ffill(ret_spx, ret_kospi.index)
    corr_kwargs = registry.parse_call_kwargs("rolling_corr", spec.transform)
    mean_kwargs = registry.parse_call_kwargs("rolling_mean_corr", spec.transform)
    corr = transforms.rolling_corr(
        ret_kospi, ret_spx_on_kr, window=corr_kwargs["window"]
    )
    mean_corr = transforms.rolling_mean_corr(corr, window=mean_kwargs["window"])
    value = transforms.abs_(corr - mean_corr)
    return _build_simple(spec, value, ("^GSPC", "KRX:1001"), ctx)


def _build_vkospi_z(spec: IndicatorSpec, ctx: _Ctx) -> dict[str, Any]:
    zscore_kwargs = registry.parse_call_kwargs("zscore", spec.transform)
    vkospi_close = series_values(ctx.df, "KRX:VKOSPI", "close")
    if not vkospi_close.empty:
        value = transforms.zscore(vkospi_close, window=zscore_kwargs["window"])
        return _build_simple(spec, value, ("KRX:VKOSPI",), ctx)
    # K-02: pykrx has no resolvable VKOSPI ticker -> fixture always empty for this
    # series (confirmed BT-01, 9/9 windows) -> fallback (data-driven, not hardcoded).
    kospi_close = series_values(ctx.df, "KRX:1001", "close")
    daily_return = transforms.pct_change_1d(kospi_close)
    fb_window = registry.parse_fallback_window(spec.source["fallback"])
    realized_vol = transforms.realized_vol_kospi_20d(daily_return, window=fb_window)
    value = transforms.zscore(realized_vol, window=zscore_kwargs["window"])
    return _build_simple(spec, value, ("KRX:1001",), ctx)


def _build_kospi_drawdown(spec: IndicatorSpec, ctx: _Ctx) -> dict[str, Any]:
    close = series_values(ctx.df, "KRX:1001", "close")
    kwargs = registry.parse_call_kwargs("drawdown_from_high", spec.transform)
    value = transforms.drawdown_from_high(close, window=kwargs["window"])
    return _build_simple(spec, value, ("KRX:1001",), ctx)


def _build_foreign_net_sell_kospi(spec: IndicatorSpec, ctx: _Ctx) -> dict[str, Any]:
    net_buy = series_values(ctx.df, "KRX:investor_foreign_kospi", "net_buy_value")
    sum_kwargs = registry.parse_call_kwargs("rolling_sum", spec.transform)
    nz_kwargs = registry.parse_call_kwargs("neg_zscore", spec.transform)
    rolled = transforms.rolling_sum(net_buy, window=sum_kwargs["window"])
    value = transforms.neg_zscore(rolled, window=nz_kwargs["window"])
    return _build_simple(spec, value, ("KRX:investor_foreign_kospi",), ctx)


def _build_kospi_volume_distribution(spec: IndicatorSpec, ctx: _Ctx) -> dict[str, Any]:
    close = series_values(ctx.df, "KRX:1001", "close")
    trading_value = series_values(ctx.df, "KRX:1001", "trading_value")
    zscore_kwargs = registry.parse_call_kwargs("zscore", spec.transform)
    gated_kwargs = registry.parse_call_kwargs("gated", spec.transform)
    var, op, threshold = registry.parse_gate(gated_kwargs["gate"])
    assert var == "daily_return", var
    daily_return = transforms.pct_change_1d(close)
    mask = transforms.gate_mask(daily_return, op, threshold)
    z = transforms.zscore(trading_value, window=zscore_kwargs["window"])
    value = transforms.gated(z, mask)
    return _build_simple(spec, value, ("KRX:1001",), ctx)


def _build_usdkrw_z(spec: IndicatorSpec, ctx: _Ctx) -> dict[str, Any]:
    close = series_values(ctx.df, "KRW=X", "close")
    high = series_values(ctx.df, "KRW=X", "high")
    low = series_values(ctx.df, "KRW=X", "low")
    prev_close = close.shift(1)  # 직전 관측 행(포지션 기준, 달력일 아님) — causal
    kwargs = registry.parse_call_kwargs("zscore", spec.transform)
    value = transforms.zscore(transforms.pct_change_1d(close), window=kwargs["window"])
    return {
        "kind": "usdkrw",
        "known": _known(value, ("KRW=X",), ctx),
        "high": high,
        "low": low,
        "prev_close": prev_close,
    }


_ALWAYS_NONE: dict[str, Any] = {"kind": "always_none"}

_BUILDERS: dict[str, Any] = {
    "vix_level_z": _build_vix_level_z,
    "vix_term_structure": _build_vix_term_structure,
    "move_index_z": _build_move_index_z,
    "hy_oas_delta": _build_hy_oas_delta,
    "dxy_z": _build_dxy_z,
    "ust_2s10s_move": _build_ust_2s10s_move,
    "spx_drawdown_momentum": _build_spx_drawdown_momentum,
    "global_corr_break": _build_global_corr_break,
    "vkospi_z": _build_vkospi_z,
    "kospi_drawdown": _build_kospi_drawdown,
    "foreign_net_sell_kospi": _build_foreign_net_sell_kospi,
    "kospi_volume_distribution": _build_kospi_volume_distribution,
    "usdkrw_z": _build_usdkrw_z,
}


def build_indicator_runtime(spec: IndicatorSpec, ctx: _Ctx) -> dict[str, Any]:
    if spec.id in _ALWAYS_MISSING_INDICATORS:
        return _ALWAYS_NONE
    builder = _BUILDERS[spec.id]
    return builder(spec, ctx)


# -----------------------------------------------------------------------------
# per-tick severity resolution
# -----------------------------------------------------------------------------


def resolve_severity(
    spec: IndicatorSpec,
    runtime: dict[str, Any],
    evaluated_at: datetime,
    profile: Profile,
    hy_rule: HyLevelBoost,
    fx_rule: UsdkrwIntradayForce,
    stale_windows: dict[tuple[str, str], timedelta],
) -> tuple[int | None, bool]:
    """반환: (severity, is_extreme). is_extreme은 AD-7 옵션 A(or_any_extreme) 전용 부가
    신호 — spec.thresholds에 "extreme" 키가 없으면(프로덕션/옵션 B 기본) 항상 False다
    (엔진 기본 거동 비영향, AD-9(a)(i) 증인). "combine_max" kind(spx_drawdown_momentum,
    F-06 대응안 scope 밖)는 단일 원값이 없어 is_extreme을 정의하지 않고 항상 False —
    thresholds 구조 자체가 중첩(drawdown/neg_z)이라 최상위 "extreme" 키가 있을 수 없다."""
    kind = runtime["kind"]
    cadence = spec.source.get("cadence", "")

    def _stale(visible_at: datetime) -> bool:
        return is_stale_check(cadence, visible_at, evaluated_at, profile, stale_windows)

    if kind == "always_none":
        return None, False

    if kind == "simple":
        looked = lookup_known(runtime["known"], evaluated_at)
        if looked is None or _stale(looked[1]):
            return None, False
        raw = looked[2]
        severity = scoring.classify_severity(
            raw, spec.thresholds, direction=spec.direction, max_severity=spec.max_severity
        )
        extreme = scoring.is_extreme(raw, spec.thresholds, direction=spec.direction)
        return severity, extreme

    if kind == "combine_max":
        a = lookup_known(runtime["known_a"], evaluated_at)
        b = lookup_known(runtime["known_b"], evaluated_at)
        a_val = a[2] if a is not None and not _stale(a[1]) else None
        b_val = b[2] if b is not None and not _stale(b[1]) else None
        if a_val is None and b_val is None:
            return None, False
        severity = scoring.combine_max_severity(
            a_val,
            spec.thresholds["drawdown"],
            b_val,
            spec.thresholds["neg_z"],
            direction=spec.direction,
        )
        return severity, False

    if kind == "hy_oas":
        looked = lookup_known(runtime["known"], evaluated_at)
        if looked is None or _stale(looked[1]):
            return None, False
        row_date, _visible_at, delta_val = looked
        severity = scoring.classify_severity(
            delta_val, spec.thresholds, direction=spec.direction, max_severity=spec.max_severity
        )
        extreme = scoring.is_extreme(delta_val, spec.thresholds, direction=spec.direction)
        level = runtime["level_series"].get(row_date)
        if level is None or pd.isna(level):
            return (
                severity,
                extreme,
            )  # hy_oas_level 결측 -> boost 미적용, delta만의 severity 그대로
        boosted = modifiers.apply_hy_level_boost(severity, float(level), hy_rule)
        return boosted, extreme

    if kind == "usdkrw":
        looked = lookup_known(runtime["known"], evaluated_at)
        if looked is None or _stale(looked[1]):
            return None, False
        row_date, _visible_at, z_val = looked
        severity = scoring.classify_severity(
            z_val, spec.thresholds, direction=spec.direction, max_severity=spec.max_severity
        )
        extreme = scoring.is_extreme(z_val, spec.thresholds, direction=spec.direction)
        high = runtime["high"].get(row_date)
        low = runtime["low"].get(row_date)
        prev_close = runtime["prev_close"].get(row_date)
        if (
            high is None
            or low is None
            or prev_close is None
            or pd.isna(high)
            or pd.isna(low)
            or pd.isna(prev_close)
        ):
            return severity, extreme  # high/low/prev_close 결측 -> intraday_force 미적용
        range_pct = modifiers.usdkrw_intraday_range(
            float(high), float(low), float(prev_close)
        )
        boosted = modifiers.apply_usdkrw_intraday_force(severity, range_pct, fx_rule)
        return boosted, extreme

    raise ValueError(f"unknown runtime kind: {kind!r}")  # pragma: no cover - defensive


# -----------------------------------------------------------------------------
# window x profile replay
# -----------------------------------------------------------------------------


def summarize_ticks(tick_records: list[dict[str, Any]]) -> dict[str, Any]:
    phases = [r["phase"] for r in tick_records]
    transitions = sum(1 for a, b in pairwise(phases) if a != b)
    composites = [r["composite"] for r in tick_records if r["composite"] is not None]
    order = ["GREEN", "AMBER", "ORANGE", "RED"]
    first_orange = next(
        (
            r["date"]
            for r in tick_records
            if order.index(r["phase"]) >= order.index("ORANGE")
        ),
        None,
    )
    first_red = next(
        (r["date"] for r in tick_records if r["phase"] == "RED"),
        None,
    )
    return {
        "n_ticks": len(tick_records),
        "n_transitions": transitions,
        "max_phase": max(phases, key=order.index) if phases else None,
        "max_composite": max(composites) if composites else None,
        "min_coverage": min((r["coverage"] for r in tick_records), default=None),
        "first_orange_or_above_date": first_orange,
        "first_red_date": first_red,
    }


def replay_window_profile(
    window: WindowDef,
    profile: Profile,
    indicator_specs: list[IndicatorSpec],
    weights: dict[str, float],
    axes: dict[str, str],
    hy_rule: HyLevelBoost,
    fx_rule: UsdkrwIntradayForce,
    fred_lag: dict[str, int],
    schedule_times: dict[str, list[time]],
    mobile_confirm_time: time,
    statemachine_config: registry.StatemachineConfig,
    stale_windows: dict[tuple[str, str], timedelta],
    fixtures_dir: Path = FIXTURES_DIR,
    max_severities: dict[str, int] | None = None,
) -> dict[str, Any]:
    """max_severities(AD-7 옵션 B, 계량 전용): None(기본)이면 scoring.compute_composite에
    전달하지 않아 원래 3-tier 분모 산식과 비트 동일(AD-9(a)(i))."""
    fixture_path = fixtures_dir / f"{window.window_id}.parquet"
    df = pd.read_parquet(fixture_path) if fixture_path.exists() else pd.DataFrame()

    grid = trading_days(df, window)
    if not grid:
        return {"ticks": [], "summary": summarize_ticks([])}

    ctx = _Ctx(df, grid, fred_lag, schedule_times, profile, mobile_confirm_time)
    runtimes = {spec.id: build_indicator_runtime(spec, ctx) for spec in indicator_specs}

    tick_grid = build_tick_grid(grid, profile, schedule_times, mobile_confirm_time)
    engine_ticks: list[statemachine.Tick] = []
    tick_records: list[dict[str, Any]] = []

    for evaluated_at, kst_date, kst_label in tick_grid:
        resolved = {
            spec.id: resolve_severity(
                spec,
                runtimes[spec.id],
                evaluated_at,
                profile,
                hy_rule,
                fx_rule,
                stale_windows,
            )
            for spec in indicator_specs
        }
        severities = {iid: sev for iid, (sev, _ext) in resolved.items()}
        # s>=3(원래는 ==3과 동치 — 기본 3-tier에서 severity는 3을 넘지 못한다. AD-7 옵션
        # B(max_severity=4)에서만 실제로 갈라지며, 그때는 4(더 심각)도 "crit 이상"으로
        # 계속 or_any_crit 이스케이프에 잡혀야 한다는 게 자연스러운 일반화다.
        any_crit = any(s is not None and s >= 3 for s in severities.values())
        any_extreme = any(ext for _sev, ext in resolved.values())
        distinct = scoring.distinct_axes(severities, axes)
        composite = scoring.compute_composite(severities, weights, max_severities)
        fired_axes = sorted(
            {axes[i] for i, s in severities.items() if s is not None and s >= 2}
        )

        engine_ticks.append(
            statemachine.Tick(
                composite=composite.score,
                distinct_axes=distinct,
                any_crit=any_crit,
                any_extreme=any_extreme,
            )
        )
        tick_records.append(
            {
                "date": kst_date.isoformat(),
                "kst_time": kst_label,
                "evaluated_at_utc": evaluated_at.isoformat(),
                "composite": composite.score,
                "coverage": composite.coverage,
                "distinct_axes": distinct,
                "any_crit": any_crit,
                "any_extreme": any_extreme,
                "fired_axes": fired_axes,
            }
        )

    profile_params = statemachine_config.profiles[profile]
    timeline = statemachine.run(engine_ticks, profile_params, statemachine_config)
    for rec, phase in zip(tick_records, timeline, strict=True):
        rec["phase"] = phase

    return {"ticks": tick_records, "summary": summarize_ticks(tick_records)}


# -----------------------------------------------------------------------------
# CLI
# -----------------------------------------------------------------------------


def run_replay(
    profiles: list[Profile],
    window_ids: list[str],
    indicators_path: Path = DEFAULT_INDICATORS_PATH,
    fixtures_dir: Path = FIXTURES_DIR,
    statemachine_path: Path | None = None,
    replay_path: Path | None = None,
) -> dict[str, Any]:
    """statemachine_path/replay_path: None(기본)이면 기존 동작과 완전히 동일
    (configs/statemachine.yaml · backtest/replay.yaml). 명시 시 그 경로를 쓴다 —
    BT-03 스윕 대상 ③(mobile_daily 프로파일 파라미터·확정 틱 시각)을 CLI/in-process로
    흔들 수 있게 하는 배선(MT0-05 §11.2-1, MT0-04 F-1 --config 전면 배선과 동일 원칙).
    registry.load_statemachine(path=...)는 명시 path를 캐시하지 않는다(F-2 교훈)."""
    sm_path = statemachine_path or STATEMACHINE_YAML_PATH
    rp_path = replay_path or REPLAY_YAML_PATH

    indicators_cfg = _load_yaml(indicators_path)
    replay_cfg = _load_yaml(rp_path)
    statemachine_cfg = _load_yaml(sm_path)

    indicator_specs = registry.load_indicator_specs(
        enabled_only=True, path=indicators_path
    )
    weights = registry.weight_map(enabled_only=True, path=indicators_path)
    axes = registry.axis_map(enabled_only=True, path=indicators_path)
    hy_rule, fx_rule = registry.load_modifiers(path=indicators_path)
    fred_lag = fred_lag_days(indicator_specs)
    schedule_times = load_schedule_times(statemachine_cfg)
    mobile_confirm_time = load_mobile_confirm_time(replay_cfg)
    statemachine_config = registry.load_statemachine(path=statemachine_path)

    # F-1(aaa-critic 라운드1): 틱당 스테일 판정(resolve_severity 안에서 지표당 최대 2회
    # 호출되는 핫 패스)도 --config 레지스트리의 engine.stale_profiles를 봐야 한다. weights/
    # axes/modifiers와 동일하게 run_replay() 호출당 딱 한 번만(profile x cadence 조합 —
    # 실제 쓰이는 cadence만, indicator_specs에서 그대로 도출해 하드코딩 없음) 파싱해 틱마다
    # 재사용한다 — 틱마다 registry.stale_window(path=...)를 직접 부르면 F-2가 오버라이드
    # 경로에 대해 의도적으로 캐시를 끈 대가로 매 틱 yaml을 새로 열어 파싱하게 되어 실측
    # 재현 중 리플레이 하나가 수분간 멈추는 성능 회귀가 났다.
    cadences = {
        spec.source["cadence"] for spec in indicator_specs if "cadence" in spec.source
    }
    stale_windows = load_stale_windows(
        ("server_intraday", "mobile_daily"), cadences, indicators_path
    )

    # AD-7 옵션 B(계량 전용): max_severity가 전 지표 기본값(3)이면 None으로 정규화해
    # compute_composite가 원래 산식과 완전히 동일한 연산 순서를 taken 하도록 한다(비트
    # 동일, AD-9(a)(i)) — indicators.yaml에 max_severity 키를 쓴 지표가 실제로 하나라도
    # 있을 때만(옵션 B 샌드박스 후보) 지표별 맵을 실제로 넘긴다.
    max_severities_raw = registry.max_severity_map(enabled_only=True, path=indicators_path)
    max_severities = (
        None if all(v == 3 for v in max_severities_raw.values()) else max_severities_raw
    )

    all_windows = {w.window_id: w for w in load_windows()}
    targets = [all_windows[wid] for wid in window_ids]

    windows_out: dict[str, Any] = {}
    for window in targets:
        windows_out[window.window_id] = {}
        for profile in profiles:
            windows_out[window.window_id][profile] = replay_window_profile(
                window,
                profile,
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

    return {
        "schema": "backtest-replay-metrics/1",
        "registry_version": indicators_cfg.get("registry_version", "?"),
        "generated_at": datetime.now(UTC).isoformat(),
        "note": (
            "근사-PIT — C1에서 실측 확정 (docs/BACKTEST_PLAN.md §5). "
            "registry 0.3.1-rc(① 변형 채택, GATE_GM0 후속 결정 2026-08-04, MT0-08) "
            "+ D-26 pairing semantics 적용(MT0-07, AD-13 절차 준용)."
        ),
        "profiles_run": profiles,
        "windows": windows_out,
    }


def main(argv: list[str] | None = None) -> int:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # K-xx cp949 콘솔 함정

    parser = argparse.ArgumentParser(
        description="BT-02 window x profile replay (approx-PIT)"
    )
    parser.add_argument(
        "--profile", required=True, choices=["server_intraday", "mobile_daily", "both"]
    )
    parser.add_argument("--window", default="all", help="window_id or 'all'")
    parser.add_argument(
        "--config",
        default=str(DEFAULT_INDICATORS_PATH),
        help="indicators.yaml 경로 (기본 configs/indicators.yaml, BT-03 스윕 후보 대응)",
    )
    args = parser.parse_args(argv)

    profiles: list[Profile] = (
        ["server_intraday", "mobile_daily"]
        if args.profile == "both"
        else [args.profile]
    )
    all_window_ids = [w.window_id for w in load_windows()]
    window_ids = all_window_ids if args.window == "all" else [args.window]
    if args.window != "all" and args.window not in all_window_ids:
        parser.error(f"unknown window_id: {args.window}")

    result = run_replay(profiles, window_ids, indicators_path=Path(args.config))

    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    out_path = RESULTS_DIR / "metrics.json"
    out_path.write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    for window_id, per_profile in result["windows"].items():
        for profile, data in per_profile.items():
            s = data["summary"]
            print(
                f"[{window_id}][{profile}] n_ticks={s['n_ticks']} transitions={s['n_transitions']} "
                f"max_phase={s['max_phase']} max_composite={s['max_composite']} "
                f"first_orange={s['first_orange_or_above_date']} first_red={s['first_red_date']}"
            )
    print(f"metrics written: {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
