"""tests/test_replay.py — backtest/run_replay.py 리플레이 계층 단위 테스트: 가시성
(근사-PIT)·스테일·워밍업·퇴화 입력. 네트워크 없음, 전부 합성 데이터(fixture 롱포맷 스키마
그대로 구성) — backtest/fixtures/*.parquet에 의존하지 않는다(골든 회귀는
backtest/test_golden.py가 실제 픽스처로 별도 수행).
"""

from __future__ import annotations

from datetime import UTC, date, datetime, time, timedelta

import pandas as pd
import pytest

from backtest import run_replay as rr
from backtest.fixture_schema import FIXTURE_COLUMNS
from engine_ref import registry

# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------


def _frame(rows: list[tuple[str, str, str, float]]) -> pd.DataFrame:
    """(series_id, field, as_of_iso_date, value) -> 픽스처 롱포맷 DataFrame."""
    if not rows:
        return pd.DataFrame(
            {
                "series_id": pd.Series(dtype="object"),
                "field": pd.Series(dtype="object"),
                "as_of": pd.Series(dtype="datetime64[ns, UTC]"),
                "value": pd.Series(dtype="float64"),
            }
        )
    df = pd.DataFrame(rows, columns=list(FIXTURE_COLUMNS))
    df["as_of"] = pd.to_datetime(df["as_of"], utc=True)
    df["value"] = df["value"].astype("float64")
    return df.sort_values(list(FIXTURE_COLUMNS)).reset_index(drop=True)


_SCHEDULE_TIMES = rr.load_schedule_times(rr._load_yaml(rr.STATEMACHINE_YAML_PATH))
_MOBILE_CONFIRM = rr.load_mobile_confirm_time(rr._load_yaml(rr.REPLAY_YAML_PATH))


# ---------------------------------------------------------------------------
# KST <-> UTC + cron parsing
# ---------------------------------------------------------------------------


def test_kst_to_utc_same_day_afternoon() -> None:
    got = rr.kst_to_utc(date(2024, 8, 5), time(16, 20))
    assert got == datetime(2024, 8, 5, 7, 20, tzinfo=UTC)


def test_kst_to_utc_morning_rolls_back_to_previous_utc_day() -> None:
    """07:30 KST = 전날 22:30 UTC — us_close 틱이 서버 틱 정렬에서 그날 09:00 KST보다
    앞서도록(즉 UTC로도 더 이른 값이도록) 보장하는 롤오버 처리."""
    got = rr.kst_to_utc(date(2024, 8, 5), time(7, 30))
    assert got == datetime(2024, 8, 4, 22, 30, tzinfo=UTC)
    assert got < rr.kst_to_utc(date(2024, 8, 5), time(9, 0))


def test_parse_cron_kst_times_covers_statemachine_schedule() -> None:
    # SSOT: configs/statemachine.yaml schedules.evaluation — 값을 여기 복제하지 않고
    # 실제 cron 문자열을 그대로 파싱해 개수만 단정한다.
    assert len(_SCHEDULE_TIMES["kr_intraday"]) == 14  # 09:00..15:30, 30분 간격
    assert _SCHEDULE_TIMES["kr_close"] == [time(17, 0)]
    assert _SCHEDULE_TIMES["us_close"] == [time(7, 30)]


def test_parse_cron_minute_field_fixed_value() -> None:
    assert rr.parse_cron_kst_times("15 8 * * 1-5") == [time(8, 15)]


def test_build_tick_grid_server_orders_us_close_before_kr_intraday_before_kr_close() -> (
    None
):
    grid_days = [date(2024, 8, 5)]
    ticks = rr.build_tick_grid(
        grid_days, "server_intraday", _SCHEDULE_TIMES, _MOBILE_CONFIRM
    )
    assert len(ticks) == 16  # 1 us_close + 14 kr_intraday + 1 kr_close
    labels = [t[2] for t in ticks]
    assert labels[0] == "07:30"
    assert labels[-1] == "17:00"
    # UTC 오름차순으로 정렬돼야 한다 (07:30 KST가 전날 UTC로 밀려도 여전히 최선두).
    assert [t[0] for t in ticks] == sorted(t[0] for t in ticks)


def test_build_tick_grid_mobile_one_tick_per_day() -> None:
    grid_days = [date(2024, 8, 5), date(2024, 8, 6)]
    ticks = rr.build_tick_grid(
        grid_days, "mobile_daily", _SCHEDULE_TIMES, _MOBILE_CONFIRM
    )
    assert len(ticks) == 2
    assert all(label == "16:20" for _ts, _d, label in ticks)


# ---------------------------------------------------------------------------
# trading_days — empirical krx-kind calendar (no exchange_calendars)
# ---------------------------------------------------------------------------


def test_trading_days_unions_krx_kind_series_within_window() -> None:
    df = _frame(
        [
            ("KRX:1001", "close", "2024-08-05", 2700.0),
            ("KRX:1001", "close", "2024-08-06", 2650.0),
            ("KRX:investor_foreign_kospi", "net_buy_value", "2024-08-07", 1.0),
            ("^VIX", "close", "2024-08-08", 20.0),  # us_market kind — must not count
        ]
    )
    window = next(w for w in rr.load_windows() if w.window_id == "w2024_carry_unwind")
    window = window.__class__(
        window_id="synthetic",
        start=date(2024, 8, 1),
        end=date(2024, 8, 9),
        anchor_hint=None,
        kind="positive",
        holdout=False,
        character="synthetic",
    )
    days = rr.trading_days(df, window)
    assert days == [date(2024, 8, 5), date(2024, 8, 6), date(2024, 8, 7)]


def test_trading_days_empty_fixture_yields_empty_grid() -> None:
    window = next(w for w in rr.load_windows() if w.window_id == "w2024_05_calm")
    assert rr.trading_days(_frame([]), window) == []


# ---------------------------------------------------------------------------
# visibility (approx-PIT, module docstring §2)
# ---------------------------------------------------------------------------


def test_visibility_us_market_is_next_grid_day_strictly_after_t() -> None:
    grid = [date(2024, 8, 5), date(2024, 8, 6), date(2024, 8, 7)]
    assert rr.raw_visibility_grid_day("^VIX", date(2024, 8, 5), grid, {}) == date(
        2024, 8, 6
    )
    # T가 그리드의 마지막 날이면 다음 날이 없다 -> None(이 창에서는 아직 안 보임)
    assert rr.raw_visibility_grid_day("^VIX", date(2024, 8, 7), grid, {}) is None


def test_visibility_krx_and_fx_is_same_day_on_or_after_t() -> None:
    grid = [date(2024, 8, 5), date(2024, 8, 7)]
    # T가 그리드에 있으면 그대로 그 날.
    assert rr.raw_visibility_grid_day("KRX:1001", date(2024, 8, 5), grid, {}) == date(
        2024, 8, 5
    )
    assert rr.raw_visibility_grid_day("KRW=X", date(2024, 8, 5), grid, {}) == date(
        2024, 8, 5
    )
    # T가 그리드에 없으면(예: FX가 KRX 휴장일에도 관측) 그 이후 첫 그리드일로 당겨진다
    # (미래 쪽이 아니라 과거/현재 쪽으로만 — look-ahead 아님).
    assert rr.raw_visibility_grid_day("KRW=X", date(2024, 8, 6), grid, {}) == date(
        2024, 8, 7
    )


def test_visibility_fred_daily_applies_lag_then_next_grid_day_on_or_after() -> None:
    grid = [date(2024, 8, 5), date(2024, 8, 6), date(2024, 8, 8)]
    fred_lag = {"BAMLH0A0HYM2": 1}
    # T=08-05, lag=1 -> 08-06이 그리드에 있으므로 그대로.
    assert rr.raw_visibility_grid_day(
        "BAMLH0A0HYM2", date(2024, 8, 5), grid, fred_lag
    ) == date(2024, 8, 6)
    # T=08-06, lag=1 -> 08-07은 그리드에 없음 -> 그 이후 첫 그리드일(08-08)로.
    assert rr.raw_visibility_grid_day(
        "BAMLH0A0HYM2", date(2024, 8, 6), grid, fred_lag
    ) == date(2024, 8, 8)


def test_visibility_tick_utc_picks_us_close_for_us_market_and_kr_close_for_krx_server() -> (
    None
):
    grid = [date(2024, 8, 5), date(2024, 8, 6)]
    us = rr.visibility_tick_utc(
        "^VIX",
        date(2024, 8, 5),
        grid,
        {},
        _SCHEDULE_TIMES,
        "server_intraday",
        _MOBILE_CONFIRM,
    )
    assert us == rr.kst_to_utc(date(2024, 8, 6), time(7, 30))
    krx = rr.visibility_tick_utc(
        "KRX:1001",
        date(2024, 8, 5),
        grid,
        {},
        _SCHEDULE_TIMES,
        "server_intraday",
        _MOBILE_CONFIRM,
    )
    assert krx == rr.kst_to_utc(date(2024, 8, 5), time(17, 0))


def test_visibility_tick_utc_mobile_uses_confirm_time_regardless_of_kind() -> None:
    grid = [date(2024, 8, 5), date(2024, 8, 6)]
    us = rr.visibility_tick_utc(
        "^VIX",
        date(2024, 8, 5),
        grid,
        {},
        _SCHEDULE_TIMES,
        "mobile_daily",
        _MOBILE_CONFIRM,
    )
    krx = rr.visibility_tick_utc(
        "KRX:1001",
        date(2024, 8, 5),
        grid,
        {},
        _SCHEDULE_TIMES,
        "mobile_daily",
        _MOBILE_CONFIRM,
    )
    assert us == rr.kst_to_utc(date(2024, 8, 6), _MOBILE_CONFIRM)
    assert krx == rr.kst_to_utc(date(2024, 8, 5), _MOBILE_CONFIRM)


def test_combined_visibility_is_worst_of_both_input_kinds() -> None:
    """global_corr_break류(^GSPC us_market + KRX:1001 krx): 결합 가시 시각은 두 계열의
    자기 kind 규칙 중 늦은 쪽 — 둘 다 알려져야 그 날짜의 결합값을 안다."""
    grid = [date(2024, 8, 5), date(2024, 8, 6)]
    combined = rr.combined_visibility_utc(
        ("^GSPC", "KRX:1001"),
        date(2024, 8, 5),
        grid,
        {},
        _SCHEDULE_TIMES,
        "server_intraday",
        _MOBILE_CONFIRM,
    )
    us_leg = rr.visibility_tick_utc(
        "^GSPC",
        date(2024, 8, 5),
        grid,
        {},
        _SCHEDULE_TIMES,
        "server_intraday",
        _MOBILE_CONFIRM,
    )
    krx_leg = rr.visibility_tick_utc(
        "KRX:1001",
        date(2024, 8, 5),
        grid,
        {},
        _SCHEDULE_TIMES,
        "server_intraday",
        _MOBILE_CONFIRM,
    )
    assert combined == max(us_leg, krx_leg)


def test_combined_visibility_none_if_either_leg_unresolvable() -> None:
    grid = [date(2024, 8, 5)]  # 마지막 날 -> us_market 계열은 "다음 날"이 없어 미해결
    combined = rr.combined_visibility_utc(
        ("^GSPC", "KRX:1001"),
        date(2024, 8, 5),
        grid,
        {},
        _SCHEDULE_TIMES,
        "server_intraday",
        _MOBILE_CONFIRM,
    )
    assert combined is None


# ---------------------------------------------------------------------------
# KnownSeries / lookup_known — warmup (NaN exclusion) + bisect lookup
# ---------------------------------------------------------------------------


def test_build_known_series_excludes_nan_rows_warmup() -> None:
    idx = [date(2024, 1, d) for d in range(1, 6)]
    value_series = pd.Series([float("nan"), float("nan"), 1.0, 2.0, 3.0], index=idx)
    grid = idx
    ks = rr.build_known_series(
        value_series,
        ("KRX:1001",),
        grid,
        {},
        _SCHEDULE_TIMES,
        "server_intraday",
        _MOBILE_CONFIRM,
    )
    assert ks.row_dates == idx[2:]  # 앞 2개(워밍업 미충족 NaN)는 제외
    assert ks.values == [1.0, 2.0, 3.0]


def test_lookup_known_returns_latest_visible_row_and_none_before_any() -> None:
    ks = rr.KnownSeries(
        row_dates=[date(2024, 1, 1), date(2024, 1, 2)],
        visibility_ts=[
            datetime(2024, 1, 1, 8, tzinfo=UTC),
            datetime(2024, 1, 2, 8, tzinfo=UTC),
        ],
        values=[1.0, 2.0],
    )
    assert rr.lookup_known(ks, datetime(2024, 1, 1, 7, tzinfo=UTC)) is None
    assert (
        rr.lookup_known(ks, datetime(2024, 1, 1, 8, tzinfo=UTC))[2] == 1.0
    )  # 등호 포함
    assert rr.lookup_known(ks, datetime(2024, 1, 1, 23, tzinfo=UTC))[2] == 1.0
    assert rr.lookup_known(ks, datetime(2024, 1, 2, 8, tzinfo=UTC))[2] == 2.0


# ---------------------------------------------------------------------------
# staleness — as_of는 가시화 시각이어야 한다(실측 재현 중 발견한 버그의 회귀 방지).
# ---------------------------------------------------------------------------


def test_is_stale_check_uses_visibility_timestamp_not_calendar_midnight() -> None:
    """회귀 방지: kr_close(17:00 KST)에 막 가시화된 당일 값을 UTC 자정 기준으로 재면
    "8시간 지남"으로 오판돼 intraday_30m(90분, server) 임계를 즉시 넘긴다 — 실제로는
    가시화 시각(kr_close 자체) 기준 0분이어야 옳다."""
    stale_windows = rr.load_stale_windows(("server_intraday",), {"intraday_30m"}, None)
    visible_at = rr.kst_to_utc(date(2024, 8, 5), time(17, 0))
    assert not rr.is_stale_check(
        "intraday_30m", visible_at, visible_at, "server_intraday", stale_windows
    )

    threshold = registry.stale_window("server_intraday", "intraday_30m")
    just_within = visible_at + threshold
    just_over = visible_at + threshold + timedelta(seconds=1)
    assert not rr.is_stale_check(
        "intraday_30m", visible_at, just_within, "server_intraday", stale_windows
    )
    assert rr.is_stale_check(
        "intraday_30m", visible_at, just_over, "server_intraday", stale_windows
    )


def test_is_stale_check_rejects_naive_datetime() -> None:
    stale_windows = rr.load_stale_windows(("server_intraday",), {"intraday_30m"}, None)
    aware = rr.kst_to_utc(date(2024, 8, 5), time(17, 0))
    naive = datetime(2024, 8, 5, 17, 0)  # noqa: DTZ001 - 의도적 naive (거부 경로 검증)
    with pytest.raises(ValueError, match="naive"):
        rr.is_stale_check(
            "intraday_30m", naive, aware, "server_intraday", stale_windows
        )


def test_load_stale_windows_respects_config_override(tmp_path) -> None:
    """F-1(aaa-critic 라운드1) 회귀 방지: --config 오버라이드가 engine.stale_profiles에도
    적용돼야 한다 — stale_window/is_stale에 path를 안 넘기면(과거 결함) 오버라이드
    레지스트리가 무시되고 항상 configs/indicators.yaml이 읽힌다."""
    import yaml

    base = yaml.safe_load(
        (rr.CONFIGS_DIR / "indicators.yaml").read_text(encoding="utf-8")
    )
    base["engine"]["stale_profiles"]["server_intraday"]["intraday_30m"] = "720h"
    mutant_path = tmp_path / "mutant_indicators.yaml"
    mutant_path.write_text(yaml.safe_dump(base, allow_unicode=True), encoding="utf-8")

    baseline = rr.load_stale_windows(("server_intraday",), {"intraday_30m"}, None)
    mutant = rr.load_stale_windows(("server_intraday",), {"intraday_30m"}, mutant_path)

    assert baseline[("server_intraday", "intraday_30m")] == timedelta(minutes=90)
    assert mutant[("server_intraday", "intraday_30m")] == timedelta(hours=720)


# ---------------------------------------------------------------------------
# vkospi_z fallback dispatch (K-02)
# ---------------------------------------------------------------------------


def test_vkospi_z_uses_raw_series_when_present() -> None:
    idx = pd.date_range("2024-01-01", periods=260, freq="D", tz="UTC").date
    rows = [("KRX:VKOSPI", "close", str(d), 20.0 + i * 0.01) for i, d in enumerate(idx)]
    rows += [("KRX:1001", "close", str(d), 2700.0) for d in idx]
    df = _frame(rows)
    spec = registry.indicator_spec("vkospi_z")
    ctx = rr._Ctx(
        df, list(idx), {}, _SCHEDULE_TIMES, "server_intraday", _MOBILE_CONFIRM
    )
    runtime = rr._build_vkospi_z(spec, ctx)
    assert runtime["known"].row_dates  # 값이 나옴 (raw 경로)


def test_vkospi_z_falls_back_to_realized_vol_when_vkospi_absent() -> None:
    """K-02: 창의 VKOSPI 관측이 0건이면 realized_vol_kospi_20d 폴백 — 데이터로 판정,
    하드코딩된 창 이름 분기 없음(9/9 실 픽스처가 이 경로를 탄다, BT-01 REPORT 확인됨)."""
    # realized_vol_kospi_20d(window=20) 워밍업 후에야 zscore(window=252)가 시작되므로
    # 20+252-1행 이상 필요 -- 여유 있게 300일.
    idx = pd.date_range("2024-01-01", periods=300, freq="D", tz="UTC").date
    rows = [("KRX:1001", "close", str(d), 2700.0 + i) for i, d in enumerate(idx)]
    df = _frame(rows)  # KRX:VKOSPI 행 없음
    spec = registry.indicator_spec("vkospi_z")
    ctx = rr._Ctx(
        df, list(idx), {}, _SCHEDULE_TIMES, "server_intraday", _MOBILE_CONFIRM
    )
    runtime = rr._build_vkospi_z(spec, ctx)
    assert runtime["known"].row_dates  # 폴백 경로로도 값이 나옴


# ---------------------------------------------------------------------------
# modifier 결측 폴백 2경로 (F-5, aaa-critic 라운드1: 뮤테이션 대상 — 분기 제거 시 사멸해야 함)
# ---------------------------------------------------------------------------


def test_resolve_severity_hy_oas_level_missing_skips_boost() -> None:
    """hy_oas_delta: delta 값 자체는 가시화됐는데(severity=warn=2) 그 시점의 원본 레벨
    (hy_oas_level)이 level_series에 없으면 부스트를 적용하지 않고 delta만의 severity를
    그대로 반환해야 한다 — 부스트가 (잘못) 적용됐다면 3(레벨 임계 초과 시 +1)이 나왔을
    값으로 골라 구분 가능하게 한다."""
    spec = registry.indicator_spec("hy_oas_delta")
    hy_rule, fx_rule = registry.load_modifiers()
    d = date(2024, 1, 10)
    visible_at = rr.kst_to_utc(d, time(7, 30))
    delta_val = spec.thresholds["warn"]  # severity=2 (부스트 없이)
    runtime = {
        "kind": "hy_oas",
        "known": rr.KnownSeries([d], [visible_at], [delta_val]),
        "level_series": pd.Series(dtype="float64"),  # 그 날짜의 레벨 결측
    }
    severity = rr.resolve_severity(
        spec, runtime, visible_at, "server_intraday", hy_rule, fx_rule, _STALE_WINDOWS
    )
    assert severity == 2  # 부스트 미적용 -> delta만의 severity(2)


def test_resolve_severity_usdkrw_ohlc_missing_skips_intraday_force() -> None:
    """usdkrw_z: z-score 자체는 가시화됐는데(severity=0, watch 미만) 그 시점의
    high/low/prev_close가 결측이면 intraday_force를 적용하지 않고 z만의 severity를
    그대로 반환해야 한다 — 적용됐다면(결측 기저도 강제 승급되는 modifier 자체 규칙 때문에)
    2 이상이 나왔을 것이므로 0과 뚜렷이 구분된다."""
    spec = registry.indicator_spec("usdkrw_z")
    hy_rule, fx_rule = registry.load_modifiers()
    d = date(2024, 1, 10)
    visible_at = rr.kst_to_utc(d, time(17, 0))
    z_val = 0.0  # severity=0 (watch 미만)
    runtime = {
        "kind": "usdkrw",
        "known": rr.KnownSeries([d], [visible_at], [z_val]),
        "high": pd.Series(dtype="float64"),  # 그 날짜의 OHLC 결측
        "low": pd.Series(dtype="float64"),
        "prev_close": pd.Series(dtype="float64"),
    }
    severity = rr.resolve_severity(
        spec, runtime, visible_at, "server_intraday", hy_rule, fx_rule, _STALE_WINDOWS
    )
    assert severity == 0  # intraday_force 미적용 -> z만의 severity(0)


# ---------------------------------------------------------------------------
# degenerate inputs — replay_window_profile 전체 파이프라인
# ---------------------------------------------------------------------------


def _minimal_ctx_args() -> tuple[dict[str, list[time]], time]:
    return _SCHEDULE_TIMES, _MOBILE_CONFIRM


_ALL_CADENCES = {
    spec.source["cadence"]
    for spec in registry.load_indicator_specs(enabled_only=True)
    if "cadence" in spec.source
}
_STALE_WINDOWS = rr.load_stale_windows(
    ("server_intraday", "mobile_daily"), _ALL_CADENCES, None
)


def _synthetic_window(window_id: str, start: date, end: date) -> rr.WindowDef:
    from backtest.fixture_schema import WindowDef

    return WindowDef(
        window_id=window_id,
        start=start,
        end=end,
        anchor_hint=None,
        kind="negative",
        holdout=False,
        character="synthetic",
    )


def test_replay_window_profile_empty_fixture_yields_empty_safe_result(
    tmp_path,
) -> None:
    """퇴화 입력 ①: 창의 픽스처가 아예 없으면(파일 미존재) 거래일 그리드가 비고,
    replay_window_profile은 예외 없이 빈 틱 리스트 + 안전한 summary를 낸다."""
    window = _synthetic_window("w_empty", date(2024, 1, 1), date(2024, 1, 10))
    specs = registry.load_indicator_specs(enabled_only=True)
    weights = registry.weight_map(enabled_only=True)
    axes = registry.axis_map(enabled_only=True)
    hy, fx = registry.load_modifiers()
    fred_lag = rr.fred_lag_days(specs)
    schedule_times, mobile_confirm = _minimal_ctx_args()
    sm_config = registry.load_statemachine()

    result = rr.replay_window_profile(
        window,
        "server_intraday",
        specs,
        weights,
        axes,
        hy,
        fx,
        fred_lag,
        schedule_times,
        mobile_confirm,
        sm_config,
        _STALE_WINDOWS,
        fixtures_dir=tmp_path,  # no parquet here
    )
    assert result["ticks"] == []
    s = result["summary"]
    assert s["n_ticks"] == 0
    assert s["n_transitions"] == 0
    assert s["max_phase"] is None
    assert s["max_composite"] is None
    assert s["min_coverage"] is None
    assert s["first_orange_or_above_date"] is None
    assert s["first_red_date"] is None


def test_replay_window_profile_all_indicators_missing_freezes_at_initial_phase(
    tmp_path,
) -> None:
    """퇴화 입력 ②: 거래일 그리드는 존재하지만(짧은 KRX:1001 계열로만 형성) 모든 지표가
    워밍업 미충족으로 결측이면(D-25 §3 "평가 불능") 국면은 initial_phase(GREEN)에서
    동결되고, coverage는 0.0이어야 한다 — 크래시하지 않는다."""
    window = _synthetic_window("w_all_missing", date(2024, 1, 1), date(2024, 1, 3))
    # KRX:1001 2행뿐 -> 모든 transform(zscore 252, drawdown 60, realized_vol 20 등)이
    # window 미충족으로 NaN -> 모든 KRX 계열 지표도 결측. 나머지(us_market/fred/fx)는
    # 원천 데이터 자체가 없음.
    df = _frame(
        [
            ("KRX:1001", "close", "2024-01-01", 2700.0),
            ("KRX:1001", "close", "2024-01-02", 2690.0),
        ]
    )
    fixtures_dir = tmp_path
    df.to_parquet(fixtures_dir / f"{window.window_id}.parquet")

    specs = registry.load_indicator_specs(enabled_only=True)
    weights = registry.weight_map(enabled_only=True)
    axes = registry.axis_map(enabled_only=True)
    hy, fx = registry.load_modifiers()
    fred_lag = rr.fred_lag_days(specs)
    schedule_times, mobile_confirm = _minimal_ctx_args()
    sm_config = registry.load_statemachine()

    result = rr.replay_window_profile(
        window,
        "mobile_daily",
        specs,
        weights,
        axes,
        hy,
        fx,
        fred_lag,
        schedule_times,
        mobile_confirm,
        sm_config,
        _STALE_WINDOWS,
        fixtures_dir=fixtures_dir,
    )
    assert result["ticks"]  # 그리드는 형성됨(2틱)
    for tick in result["ticks"]:
        assert (
            tick["phase"] == "GREEN"
        )  # initial_phase에서 동결, GREEN으로 "판정"된 게 아님
        assert tick["composite"] is None
        assert tick["coverage"] == 0.0
        assert tick["fired_axes"] == []
    assert result["summary"]["max_composite"] is None


def test_resolve_severity_always_none_for_uncollected_indicators() -> None:
    """krx_credit_spread_delta·kr_cds_5y_delta는 BT-01 collection plan에서 M0 수집
    범위 밖(ecos/scrape_wgb)이라 픽스처에 데이터가 없다 — 상시 결측이 정상 동작이며
    분모에서 제외된다(D-02)."""
    hy, fx = registry.load_modifiers()
    for ind_id in ("krx_credit_spread_delta", "kr_cds_5y_delta"):
        spec = registry.indicator_spec(ind_id)
        runtime = {"kind": "always_none"}
        assert (
            rr.resolve_severity(
                spec,
                runtime,
                datetime(2024, 1, 1, tzinfo=UTC),
                "server_intraday",
                hy,
                fx,
                _STALE_WINDOWS,
            )
            is None
        )


def test_global_corr_break_missing_one_leg_entirely_yields_no_crash_and_none() -> None:
    """퇴화 입력 ③: 2계열 지표(global_corr_break)에서 한쪽 계열(^GSPC)이 창에 전혀 없으면
    (예: yfinance 실패) KnownSeries가 그냥 비어 severity가 항상 None이어야 한다 —
    KeyError/크래시 없이."""
    idx = pd.date_range("2024-01-01", periods=260, freq="D", tz="UTC").date
    rows = [("KRX:1001", "close", str(d), 2700.0 + i) for i, d in enumerate(idx)]
    df = _frame(rows)  # ^GSPC 행 없음
    spec = registry.indicator_spec("global_corr_break")
    ctx = rr._Ctx(
        df, list(idx), {}, _SCHEDULE_TIMES, "server_intraday", _MOBILE_CONFIRM
    )
    runtime = rr._build_global_corr_break(spec, ctx)
    assert runtime["known"].row_dates == []
