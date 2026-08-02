"""engine_ref 단위 테스트 — transforms · severity/composite(D-02, D-25 §3) · modifiers ·
statemachine(D-16, D-25 §1~§2).

네트워크 금지, 전부 합성 데이터. 임계값·가중치는 코드에 복제하지 않고 configs/*.yaml에서
engine_ref.registry를 통해 읽어 상대적으로 입력을 구성한다(D-23 수치 예 재현 부분은 브리프가
명시적으로 허가한 리터럴 재현).

REVIEW_M0 MT0-02 라운드 1(aaa-critic FAIL) 반영: D-1~D-9(결함) + O-1~O-5(관찰) 전건 해소.
D-25(docs/P0_DESIGN_DECISIONS.md)가 승격 sustain·min_dwell·전 지표 결측의 실행 의미론을
확정했으므로, 그 확정판을 그대로 단정한다.
"""

from __future__ import annotations

import dataclasses
import re
from datetime import UTC, datetime, timedelta
from pathlib import Path

import numpy as np
import pandas as pd
import pytest
import yaml

from engine_ref import modifiers, registry, scoring, statemachine, transforms
from engine_ref.registry import ProfileParams

# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------


def _dates(n: int, start: str = "2024-01-01") -> pd.DatetimeIndex:
    return pd.date_range(start, periods=n, freq="D", tz="UTC")


# ---------------------------------------------------------------------------
# transforms
# ---------------------------------------------------------------------------


def test_zscore_matches_independent_formula_and_insufficient_history_is_nan() -> None:
    window = 5
    s = pd.Series([1.0, 2.0, 3.0, 4.0, 5.0], index=_dates(5))
    z = transforms.zscore(s, window)

    vals = s.to_numpy()
    expected_last = (vals[-1] - vals.mean()) / vals.std(ddof=1)
    assert z.iloc[-1] == pytest.approx(expected_last)
    assert pd.isna(z.iloc[0])  # window 미충족 구간은 결측


def test_zscore_absolute_variant() -> None:
    window = 4
    s = pd.Series([10.0, 10.0, 10.0, 0.0], index=_dates(4))  # 마지막 값이 큰 음의 z
    z_signed = transforms.zscore(s, window)
    z_abs = transforms.zscore(s, window, absolute=True)
    assert z_signed.iloc[-1] < 0
    assert z_abs.iloc[-1] == pytest.approx(abs(z_signed.iloc[-1]))


def test_ratio() -> None:
    a = pd.Series([1.0, 2.0, 4.0], index=_dates(3))
    b = pd.Series([2.0, 2.0, 2.0], index=_dates(3))
    r = transforms.ratio(a, b)
    assert list(r) == pytest.approx([0.5, 1.0, 2.0])


def test_delta_bp_converts_pct_points_to_bp() -> None:
    x = pd.Series([1.0, 1.0, 1.0, 1.0, 1.0, 1.5], index=_dates(6))
    d = transforms.delta_bp(x, lookback=5)
    assert pd.isna(d.iloc[3])  # lookback 미충족
    assert d.iloc[-1] == pytest.approx(50.0)  # 0.5%p = 50bp


def test_pct_change_1d_and_5d() -> None:
    x1 = pd.Series([100.0, 110.0], index=_dates(2))
    assert transforms.pct_change_1d(x1).iloc[-1] == pytest.approx(10.0)

    x5 = pd.Series([100.0, 100.0, 100.0, 100.0, 100.0, 90.0], index=_dates(6))
    assert transforms.pct_change_5d(x5).iloc[-1] == pytest.approx(-10.0)


def test_abs_wrapper() -> None:
    x = pd.Series([-3.0, 2.0, -1.0], index=_dates(3))
    assert list(transforms.abs_(x)) == pytest.approx([3.0, 2.0, 1.0])


def test_drawdown_from_high() -> None:
    x = pd.Series([100.0, 110.0, 90.0], index=_dates(3))
    dd = transforms.drawdown_from_high(x, window=2)
    assert dd.iloc[1] == pytest.approx(0.0)  # 직전 대비 신고가 지점은 낙폭 0
    assert dd.iloc[-1] == pytest.approx((110.0 - 90.0) / 110.0 * 100.0)


def test_neg_zscore_is_sign_flipped_zscore() -> None:
    window = 4
    x = pd.Series([1.0, 1.0, 1.0, -5.0], index=_dates(4))
    z = transforms.zscore(x, window)
    nz = transforms.neg_zscore(x, window)
    assert nz.iloc[-1] == pytest.approx(-z.iloc[-1])
    assert nz.iloc[-1] > 0  # 급락이 양(+)의 위험으로 표현됨


def test_rolling_corr_and_rolling_mean_corr() -> None:
    window = 3
    a = pd.Series([1.0, 2.0, 3.0, 4.0, 5.0], index=_dates(5))
    b = pd.Series([2.0, 4.0, 6.0, 8.0, 10.0], index=_dates(5))  # 완전 선형(상관 1.0)
    corr = transforms.rolling_corr(a, b, window)
    assert corr.iloc[-1] == pytest.approx(1.0)

    mean_corr = transforms.rolling_mean_corr(corr, window=2)
    assert mean_corr.iloc[-1] == pytest.approx(1.0)
    # global_corr_break 조합: abs(corr - mean_corr) — 완전 정상 구간에서는 0에 근접
    assert abs(corr.iloc[-1] - mean_corr.iloc[-1]) == pytest.approx(0.0)


def test_rolling_sum() -> None:
    x = pd.Series([1.0, 2.0, 3.0, 4.0], index=_dates(4))
    s = transforms.rolling_sum(x, window=2)
    assert s.iloc[-1] == pytest.approx(7.0)
    assert s.iloc[1] == pytest.approx(3.0)


def test_gate_mask_and_gated() -> None:
    z = pd.Series([5.0, 5.0, 5.0, 5.0], index=_dates(4))
    gate_series = pd.Series([1.0, -1.0, 1.0, -1.0], index=_dates(4))
    mask = transforms.gate_mask(gate_series, "<", 0.0)
    assert list(mask) == [False, True, False, True]
    gated = transforms.gated(z, mask)
    assert list(gated) == pytest.approx([0.0, 5.0, 0.0, 5.0])


def test_realized_vol_kospi_20d_fallback() -> None:
    vkospi = registry.indicator_spec("vkospi_z")
    window = registry.parse_fallback_window(vkospi.source["fallback"])
    assert window == 20

    # window일 수익률이 전부 동일 -> 표준편차 0 -> 실현변동성 0
    flat = pd.Series([1.0] * window, index=_dates(window))
    assert transforms.realized_vol_kospi_20d(flat, window).iloc[-1] == pytest.approx(
        0.0
    )

    rng = np.random.default_rng(0)
    varied = pd.Series(rng.normal(0.0, 1.0, window), index=_dates(window))
    expected = varied.to_numpy().std(ddof=1) / 100.0 * np.sqrt(252) * 100.0
    assert transforms.realized_vol_kospi_20d(varied, window).iloc[-1] == pytest.approx(
        expected
    )


def test_parse_fallback_window_invalid_raises() -> None:
    with pytest.raises(ValueError, match="cannot extract"):
        registry.parse_fallback_window("no_window_here")


def test_transforms_output_dtype_is_always_float64() -> None:
    """D-5/O2-3: float32 입력이 들어와도 출력은 항상 float64(K-07). astype("float64")가
    실제로 기여하는 pct_change_5d·gated도 포함(O2-3)."""
    idx = _dates(6)
    s32 = pd.Series([1, 2, 3, 4, 5, 6], index=idx, dtype="float32")

    assert transforms.zscore(s32, window=3).dtype == np.float64
    assert transforms.delta_bp(s32, lookback=2).dtype == np.float64
    assert transforms.drawdown_from_high(s32, window=3).dtype == np.float64
    assert transforms.neg_zscore(s32, window=3).dtype == np.float64
    assert transforms.rolling_sum(s32, window=2).dtype == np.float64
    assert transforms.abs_(s32).dtype == np.float64
    assert transforms.ratio(s32, s32).dtype == np.float64
    assert transforms.pct_change_1d(s32).dtype == np.float64
    assert transforms.pct_change_5d(s32).dtype == np.float64
    mask32 = pd.Series([True, False, True, False, True, False], index=idx)
    assert transforms.gated(s32, mask32).dtype == np.float64


def test_prefix_stability_no_lookahead() -> None:
    """O-4: 뒤에 데이터를 더 붙여도 앞부분 N개의 transform 결과는 불변(인과성 — 미래 데이터 미참조)."""
    full = pd.Series(np.linspace(1.0, 20.0, 20), index=_dates(20))
    prefix = full.iloc[:10]

    pd.testing.assert_series_equal(
        transforms.zscore(prefix, window=5), transforms.zscore(full, window=5).iloc[:10]
    )
    pd.testing.assert_series_equal(
        transforms.drawdown_from_high(prefix, window=5),
        transforms.drawdown_from_high(full, window=5).iloc[:10],
    )


# ---------------------------------------------------------------------------
# transform 문자열 파싱 (registry) — D-4, D-8
# ---------------------------------------------------------------------------


def test_parse_call_kwargs_gated_does_not_leak_nested_zscore_window() -> None:
    """D-4 재현 ①: gated(zscore(trading_value, window=60), gate="...")에서 gated 자신의
    kwargs는 gate 하나뿐이어야 한다 — 중첩된 zscore의 window=60이 새어나오면 안 된다."""
    spec = registry.indicator_spec("kospi_volume_distribution")
    gated_kwargs = registry.parse_call_kwargs("gated", spec.transform)
    assert gated_kwargs == {"gate": "daily_return < 0"}

    zscore_kwargs = registry.parse_call_kwargs("zscore", spec.transform)
    assert zscore_kwargs == {"window": 60}


def test_parse_call_kwargs_max_severity_dual_window_no_cross_leak() -> None:
    """D-4 재현 ②: max_severity(drawdown_from_high(window=60), neg_zscore(..., window=252))
    에서 두 내부 호출의 window가 서로 섞이면 안 된다."""
    spec = registry.indicator_spec("spx_drawdown_momentum")
    dd_kwargs = registry.parse_call_kwargs("drawdown_from_high", spec.transform)
    nz_kwargs = registry.parse_call_kwargs("neg_zscore", spec.transform)
    assert dd_kwargs == {"window": 60}
    assert nz_kwargs == {"window": 252}


def test_parse_call_kwargs_abs_global_corr_break_has_no_own_kwargs() -> None:
    """D-4 재현 ③: abs(rolling_corr(...) - rolling_mean_corr(window=120))에서 abs 자신은
    직접 kwargs가 없다 — 내부 rolling_mean_corr의 window=120이 새어나오면 안 된다."""
    spec = registry.indicator_spec("global_corr_break")
    assert registry.parse_call_kwargs("abs", spec.transform) == {}


def test_parse_call_kwargs_word_boundary_rejects_substring_match() -> None:
    """D-4 재현 ④: foreign_net_sell_kospi의 transform엔 "zscore("가 독립 호출로 없고
    "neg_zscore("의 일부로만 등장한다 — call_name="zscore"는 그럴듯한 값(예: 252) 대신
    "부재" ValueError를 내야 한다."""
    spec = registry.indicator_spec("foreign_net_sell_kospi")
    assert "neg_zscore(" in spec.transform
    with pytest.raises(ValueError, match="not found"):
        registry.parse_call_kwargs("zscore", spec.transform)


def test_parse_call_kwargs_all_active_indicators_parse_without_error() -> None:
    """D-4/O2-5: "전수 검증" — 활성 15지표 전부, transform 문자열의 최상위 호출명을 그 문자열
    자체에서 뽑아(하드코딩된 매핑 없이) parse_call_kwargs가 예외 없이 처리하는지 확인한다.

    O2-5 구조적 단정: isinstance(kwargs, dict)(항진, 항상 참)이던 단정을 대체 — 최외곽 호출
    자신의 인자 목록에서 paren-depth 분리(parse_call_kwargs 내부와 동일 헬퍼 재사용, 최종
    key=value 판별만 독립된 느슨한 정규식으로 재도출)한 "기대 키 집합"과 실제 반환 키 집합이
    정확히 일치해야 한다(임계값 등 수치 복제 없이 구조만 비교). gated는 gate 키만, window=가
    최외곽에 직접 있는 지표는 window 키만, 중첩 호출뿐인 지표(abs/max_severity)는 빈 dict —
    세 모양이 실제로 전부 등장하는지까지 확인해 이 단정 자체가 항진이 되지 않게 한다.
    """
    specs = registry.load_indicator_specs()
    assert len(specs) == 15
    seen_shapes = {"has_window": False, "has_gate": False, "empty": False}
    for spec in specs:
        outer_call = re.match(r"(\w+)\s*\(", spec.transform.strip()).group(1)
        kwargs = registry.parse_call_kwargs(outer_call, spec.transform)

        body = registry._extract_call_body(outer_call, spec.transform)
        expected_keys = {
            m.group(1)
            for arg in registry._split_top_level_args(body)
            if (m := re.match(r"(\w+)\s*=", arg))
        }
        assert set(kwargs) == expected_keys, spec.id

        seen_shapes["has_window"] |= "window" in kwargs
        seen_shapes["has_gate"] |= "gate" in kwargs
        seen_shapes["empty"] |= not kwargs

    assert all(seen_shapes.values()), (
        seen_shapes
    )  # 항진 방지: 세 모양이 실제로 다 나타남


def test_parse_call_kwargs_parses_boolean_literal() -> None:
    spec = registry.indicator_spec(
        "dxy_z"
    )  # zscore(pct_change_5d, window=252, absolute=true)
    kwargs = registry.parse_call_kwargs("zscore", spec.transform)
    assert kwargs["absolute"] is True
    assert kwargs["window"] == 252


def test_parse_call_kwargs_parses_false_literal() -> None:
    # indicators.yaml 실사용 예는 없지만 파서 자체의 false 분기를 검증 (합성 문자열)
    kwargs = registry.parse_call_kwargs(
        "zscore", "zscore(close, window=10, absolute=false)"
    )
    assert kwargs["absolute"] is False


def test_parse_call_kwargs_missing_call_raises() -> None:
    with pytest.raises(ValueError, match="not found"):
        registry.parse_call_kwargs("nope", "zscore(close, window=252)")


def test_parse_call_kwargs_unbalanced_parens_raises_value_error() -> None:
    """D-8: 괄호 불균형은 IndexError가 아니라 다른 파서들과 동일한 ValueError+진단 메시지."""
    with pytest.raises(ValueError, match="unbalanced"):
        registry.parse_call_kwargs("zscore", "zscore(close, window=252")


def test_parse_gate_expression() -> None:
    assert registry.parse_gate("daily_return < 0") == ("daily_return", "<", 0.0)
    with pytest.raises(ValueError, match="unrecognized"):
        registry.parse_gate("garbage")


def test_parse_duration_units_and_invalid() -> None:
    assert registry.parse_duration("90m") == timedelta(minutes=90)
    assert registry.parse_duration("36h") == timedelta(hours=36)
    assert registry.parse_duration("2d") == timedelta(days=2)
    with pytest.raises(ValueError, match="unrecognized"):
        registry.parse_duration("2w")


def test_indicator_spec_unknown_id_raises() -> None:
    with pytest.raises(KeyError):
        registry.indicator_spec("does_not_exist")


def test_load_indicator_specs_enabled_only_toggle() -> None:
    active = registry.load_indicator_specs(enabled_only=True)
    everything = registry.load_indicator_specs(enabled_only=False)
    assert len(active) == 15  # D-01: 5축 15지표
    assert len(everything) > len(active)
    disabled_ids = {s.id for s in everything} - {s.id for s in active}
    assert "krx_halt_events" in disabled_ids


def test_load_yaml_returns_independent_copy_not_polluted_by_mutation() -> None:
    """D-7: @cache가 돌려주는 dict를 호출부가 변형해도(별칭 공유) 이후 로드에 전파되면 안 된다."""
    spec1 = registry.indicator_spec("vix_level_z")
    spec1.thresholds["warn"] = 999999.0  # 반환된 dict를 직접 오염 시도
    spec2 = registry.indicator_spec("vix_level_z")
    assert spec2.thresholds["warn"] != 999999.0


def _write_indicators_yaml_with_weight(path: Path, weight: float) -> None:
    base = yaml.safe_load(
        (registry._CONFIGS_DIR / "indicators.yaml").read_text(encoding="utf-8")
    )
    for ind in base["indicators"]:
        if ind["id"] == "vix_level_z":
            ind["weight"] = weight
    path.write_text(yaml.safe_dump(base, allow_unicode=True), encoding="utf-8")


def test_path_override_reflects_file_overwrite_not_stuck_on_first_load(
    tmp_path: Path,
) -> None:
    """F-2(aaa-critic 라운드1): --config로 준 경로에 후보 yaml을 덮어쓰며 반복 호출하는
    것은 BT-03 스윕의 실제 접근 패턴이다. path 인자를 넘긴 로드는 캐시하면 안 된다 —
    캐시하면 같은 경로를 재사용할 때 최초 로드 내용에 영영 고착된다(F-2 결함 재현: 후보
    0.5 -> 원본으로 되돌려 써도 여전히 0.5가 나오면 버그)."""
    cand_path = tmp_path / "candidate_indicators.yaml"

    _write_indicators_yaml_with_weight(cand_path, 0.5)
    assert registry.weight_map(path=cand_path)["vix_level_z"] == pytest.approx(0.5)

    _write_indicators_yaml_with_weight(
        cand_path, 3.0
    )  # 같은 경로에 덮어쓰기(스윕 패턴)
    assert registry.weight_map(path=cand_path)["vix_level_z"] == pytest.approx(3.0)

    # 경로 없이(SSOT 기본) 부른 호출은 여전히 정상적으로 캐시되고 오버라이드에 오염되지 않는다.
    assert registry.weight_map()["vix_level_z"] == pytest.approx(
        3.0
    )  # configs/indicators.yaml 원값


# ---------------------------------------------------------------------------
# severity 판정 (D-01)
# ---------------------------------------------------------------------------


def test_classify_severity_equal_boundaries_and_below() -> None:
    spec = registry.indicator_spec("vix_level_z")
    t = spec.thresholds
    assert scoring.classify_severity(t["crit"], t) == 3
    assert scoring.classify_severity(t["warn"], t) == 2
    assert scoring.classify_severity(t["watch"], t) == 1
    assert scoring.classify_severity(t["watch"] - 0.01, t) == 0
    assert scoring.classify_severity(None, t) is None
    assert scoring.classify_severity(float("nan"), t) is None


def test_classify_severity_direction_abs_fires_both_signs() -> None:
    spec = registry.indicator_spec("usdkrw_z")
    assert spec.direction == "abs"
    t = spec.thresholds
    assert scoring.classify_severity(t["crit"], t, direction=spec.direction) == 3
    assert scoring.classify_severity(-t["crit"], t, direction=spec.direction) == 3
    assert (
        scoring.classify_severity(-(t["watch"] - 0.01), t, direction=spec.direction)
        == 0
    )


def test_combine_max_severity_spx_drawdown_momentum() -> None:
    spec = registry.indicator_spec("spx_drawdown_momentum")
    dd_t, nz_t = spec.thresholds["drawdown"], spec.thresholds["neg_z"]

    assert scoring.combine_max_severity(dd_t["crit"], dd_t, 0.0, nz_t) == 3
    assert scoring.combine_max_severity(None, dd_t, nz_t["warn"], nz_t) == 2
    assert scoring.combine_max_severity(None, dd_t, None, nz_t) is None


# ---------------------------------------------------------------------------
# composite (D-02, D-25 §3) + distinct_axes
# ---------------------------------------------------------------------------


def test_compute_composite_missing_excluded_from_denominator_d23() -> None:
    """D-23 수치 예 재현: 가중 21.0 서브셋만 유효(전부 severity=2)일 때 score 66.67,
    전 지표(31.0) 유효(나머지는 severity=0)로 확장 시 45.16 — 동일 발화 패턴에서
    결측 지표가 분모에 들어오면 composite가 희석됨을 보인다. coverage도 함께 확인(D-25 §3)."""
    weights = registry.weight_map()
    assert sum(weights.values()) == pytest.approx(31.0)

    excluded = ["vix_level_z", "hy_oas_delta", "usdkrw_z", "ust_2s10s_move"]
    assert sum(weights[i] for i in excluded) == pytest.approx(10.0)
    present = [i for i in weights if i not in excluded]
    assert sum(weights[i] for i in present) == pytest.approx(21.0)

    only_present = {i: 2 for i in present}
    subset = scoring.compute_composite(only_present, weights)
    assert round(subset.score, 2) == pytest.approx(66.67)
    assert subset.coverage == pytest.approx(21.0 / 31.0)

    all_valid = dict(only_present)
    for i in excluded:
        all_valid[i] = 0
    full = scoring.compute_composite(all_valid, weights)
    assert round(full.score, 2) == pytest.approx(45.16)
    assert full.coverage == pytest.approx(1.0)


def test_compute_composite_all_missing_is_evaluation_incapable() -> None:
    """D-25 §3: 전 지표 결측(유효 가중 0) -> score=None("평가 불능"), coverage=0.0.
    구 라운드1의 "score=0.0(사실상 GREEN)" 해석은 기각됨."""
    weights = registry.weight_map()
    all_missing = dict.fromkeys(weights, None)
    result = scoring.compute_composite(all_missing, weights)
    assert result.score is None
    assert result.coverage == 0.0


def test_distinct_axes_counts_unique_axes_at_warn_or_above() -> None:
    axes = registry.axis_map()
    severities: dict[str, int | None] = dict.fromkeys(axes, 0)

    vol_ids = [i for i, a in axes.items() if a == "vol_global"]
    severities[vol_ids[0]] = 2
    severities[vol_ids[1]] = 3  # 같은 축 — 1개로만 카운트
    credit_ids = [i for i, a in axes.items() if a == "credit"]
    severities[credit_ids[0]] = 1  # watch — 미카운트

    assert scoring.distinct_axes(severities, axes) == 1

    rates_ids = [i for i, a in axes.items() if a == "rates_fx"]
    severities[rates_ids[0]] = 2
    assert scoring.distinct_axes(severities, axes) == 2


# ---------------------------------------------------------------------------
# modifiers
# ---------------------------------------------------------------------------


def test_hy_level_boost_boundary_and_cap() -> None:
    hy, _ = registry.load_modifiers()
    assert (
        modifiers.apply_hy_level_boost(1, hy.level_threshold, hy) == 1
    )  # 등호는 미부스트(초과만)
    assert (
        modifiers.apply_hy_level_boost(1, hy.level_threshold + 0.01, hy)
        == 1 + hy.increment
    )
    assert modifiers.apply_hy_level_boost(3, hy.level_threshold + 0.01, hy) == 3  # cap
    assert modifiers.apply_hy_level_boost(None, hy.level_threshold + 1, hy) is None


def test_hy_level_boost_increment_parsed_from_rule_string() -> None:
    """D-9: 증분값(+1)도 rule 문자열에서 파싱된 값이지 코드 리터럴이 아니다.
    가상 "+= 2" rule 문자열로 파서 자체의 일반성을 확인한다."""
    hy, _ = registry.load_modifiers()
    assert hy.increment == 1  # 실제 configs/indicators.yaml의 "+= 1"

    fake_rule = "hy_oas_level > 4.5 -> hy_oas_delta.severity += 2 (max 3)"
    fake_hy = registry._parse_hy_level_boost(fake_rule)
    assert fake_hy.increment == 2
    assert (
        modifiers.apply_hy_level_boost(1, fake_hy.level_threshold + 0.01, fake_hy) == 3
    )


def test_parse_hy_level_boost_malformed_rule_raises_value_error() -> None:
    """O2-4: rule 문자열이 파손돼 정규식이 매치하지 못하면 AttributeError(.group on None)가
    아니라 다른 파서들과 동일한 ValueError+진단 메시지로 실패해야 한다."""
    with pytest.raises(ValueError, match="malformed hy_level_boost rule"):
        registry._parse_hy_level_boost(
            "this rule string has no numbers or operators at all"
        )


def test_parse_usdkrw_intraday_force_malformed_rule_raises_value_error() -> None:
    """O2-4: %가 붙은 숫자가 2개 미만이면 IndexError가 아니라 ValueError+진단 메시지."""
    with pytest.raises(ValueError, match="malformed usdkrw_intraday_force rule"):
        registry._parse_usdkrw_intraday_force("only one number here: 1.2%")


def test_usdkrw_intraday_force_boundary() -> None:
    _, fx = registry.load_modifiers()
    assert modifiers.apply_usdkrw_intraday_force(0, fx.warn_threshold - 0.01, fx) == 0
    assert modifiers.apply_usdkrw_intraday_force(0, fx.warn_threshold, fx) == 2
    assert modifiers.apply_usdkrw_intraday_force(3, fx.warn_threshold, fx) == 3
    assert modifiers.apply_usdkrw_intraday_force(None, fx.crit_threshold, fx) == 3


def test_usdkrw_intraday_force_nan_range_is_noop() -> None:
    """O-3: intraday_range가 NaN(예: prev_close 결측 등)이면 비교가 전부 False가 되어
    modifier는 아무 동작도 하지 않아야 한다(severity 그대로)."""
    _, fx = registry.load_modifiers()
    assert modifiers.apply_usdkrw_intraday_force(2, float("nan"), fx) == 2
    assert modifiers.apply_usdkrw_intraday_force(None, float("nan"), fx) is None


def test_usdkrw_intraday_range_formula() -> None:
    # Advisor 지정 해석: (high-low)/전일 close * 100
    assert modifiers.usdkrw_intraday_range(
        high=1310.0, low=1290.0, prev_close=1300.0
    ) == pytest.approx(20.0 / 1300.0 * 100.0)


def test_usdkrw_intraday_range_zero_prev_close_raises() -> None:
    """O-2: prev_close==0은 정의 불가 — 조용히 inf/NaN이 아니라 즉시 ValueError."""
    with pytest.raises(ValueError, match="non-zero"):
        modifiers.usdkrw_intraday_range(high=10.0, low=5.0, prev_close=0.0)


# ---------------------------------------------------------------------------
# stale 판정
# ---------------------------------------------------------------------------


def test_stale_window_differs_by_profile_and_falls_back_for_mobile() -> None:
    server_daily_kr = registry.stale_window("server_intraday", "daily_kr")
    mobile_daily_kr = registry.stale_window("mobile_daily", "daily_kr")
    assert server_daily_kr != mobile_daily_kr
    # mobile_daily엔 intraday_30m 키가 없음 -> 그 프로파일의 daily_kr 창으로 대체 (Advisor 지정 해석)
    assert registry.stale_window("mobile_daily", "intraday_30m") == mobile_daily_kr


def test_is_stale_boundary_not_stale_and_over_window_is_stale() -> None:
    profile, cadence = "server_intraday", "daily_kr"
    window = registry.stale_window(profile, cadence)
    evaluated_at = datetime(2026, 1, 10, tzinfo=UTC)

    exactly_at = evaluated_at - window
    assert (
        registry.is_stale(exactly_at, evaluated_at, profile=profile, cadence=cadence)
        is False
    )

    just_over = exactly_at - timedelta(seconds=1)
    assert (
        registry.is_stale(just_over, evaluated_at, profile=profile, cadence=cadence)
        is True
    )


def test_is_stale_rejects_naive_datetime() -> None:
    with pytest.raises(ValueError, match="naive"):
        registry.is_stale(
            datetime(2026, 1, 1),  # noqa: DTZ001 — 의도적 naive datetime(거부 경로 검증)
            datetime(2026, 1, 2, tzinfo=UTC),
            profile="server_intraday",
            cadence="daily_kr",
        )


# ---------------------------------------------------------------------------
# statemachine (D-16, D-25 §1~§3)
# ---------------------------------------------------------------------------


def test_rule_satisfied_raises_on_no_recognized_keys() -> None:
    """O-1: 알려진 키(composite_gte/distinct_axes_gte/or_any_crit)가 하나도 없는 규칙은
    "항상 충족"으로 조용히 통과시키지 않고 즉시 실패한다."""
    with pytest.raises(ValueError, match="no recognized keys"):
        statemachine._rule_satisfied(
            {"typo_key": 1}, composite=100.0, distinct_axes=5, any_crit=False
        )


def test_promote_requires_sustain_ticks_server() -> None:
    config = registry.load_statemachine()
    profile = config.profiles["server_intraday"]
    amber = config.upgrade["AMBER"]
    ticks = [statemachine.Tick(composite=amber["composite_gte"], distinct_axes=0)] * 2
    timeline = statemachine.run(ticks, profile, config)
    assert timeline == ["GREEN", "AMBER"]  # 1틱째는 미승격, 2틱 연속에서 승격


def test_promote_immediate_mobile_single_tick_sustain() -> None:
    config = registry.load_statemachine()
    profile = config.profiles["mobile_daily"]
    amber = config.upgrade["AMBER"]
    ticks = [statemachine.Tick(composite=amber["composite_gte"], distinct_axes=0)]
    timeline = statemachine.run(ticks, profile, config)
    assert timeline == ["AMBER"]  # mobile은 1틱 즉시 승격


def test_skip_levels_gap_event_direct_to_red() -> None:
    config = registry.load_statemachine()
    profile = config.profiles["mobile_daily"]
    red = config.upgrade["RED"]
    ticks = [
        statemachine.Tick(
            composite=red["composite_gte"], distinct_axes=red["distinct_axes_gte"]
        )
    ]
    timeline = statemachine.run(ticks, profile, config)
    assert timeline == ["RED"]  # GREEN -> RED 직행 (2024-08-05형 갭 이벤트 대응)


def test_distinct_axes_gate_blocks_orange_but_not_amber() -> None:
    config = registry.load_statemachine()
    profile = config.profiles["mobile_daily"]
    orange = config.upgrade["ORANGE"]
    ticks = [
        statemachine.Tick(
            composite=orange["composite_gte"],
            distinct_axes=orange["distinct_axes_gte"] - 1,
        )
    ]
    timeline = statemachine.run(ticks, profile, config)
    assert timeline == [
        "AMBER"
    ]  # composite는 ORANGE선 충족하지만 distinct_axes 미달로 차단


def test_any_crit_triggers_amber_even_at_low_composite() -> None:
    config = registry.load_statemachine()
    profile = config.profiles["mobile_daily"]
    ticks = [statemachine.Tick(composite=0.0, distinct_axes=0, any_crit=True)]
    timeline = statemachine.run(ticks, profile, config)
    assert timeline == ["AMBER"]


def test_promote_streak_is_per_level_not_any_level_above_current_d25() -> None:
    """D-1 재현 ①(aaa-critic 라운드1): [AMBER조건만, RED조건] 서버 프로파일(sustain=2) —
    AMBER 자신의 조건만 2틱 연속 충족되므로 AMBER 승격이 정답이다. RED는 1틱만 충족돼
    스트릭 1에 불과하므로 승격되면 안 된다("현재보다 높은 아무 레벨 충족이면 스트릭 유지"
    해석은 D-25로 기각됨)."""
    config = registry.load_statemachine()
    profile = config.profiles["server_intraday"]
    amber = config.upgrade["AMBER"]
    red = config.upgrade["RED"]

    ticks = [
        statemachine.Tick(composite=amber["composite_gte"], distinct_axes=0),
        statemachine.Tick(
            composite=red["composite_gte"], distinct_axes=red["distinct_axes_gte"]
        ),
    ]
    timeline = statemachine.run(ticks, profile, config)
    assert timeline == ["GREEN", "AMBER"]
    assert timeline[-1] != "RED"


def test_promote_streak_is_per_level_reverse_order_d25() -> None:
    """D-1 재현 ②: 동일 구성의 역순 [RED조건, AMBER조건만] -> 여전히 AMBER.
    RED/ORANGE는 2틱째에 자신의 조건이 깨져 스트릭이 0으로 리셋되고, AMBER만 2틱 연속
    (composite>=20은 두 틱 모두 충족) 유지되어 승격된다."""
    config = registry.load_statemachine()
    profile = config.profiles["server_intraday"]
    amber = config.upgrade["AMBER"]
    red = config.upgrade["RED"]

    ticks = [
        statemachine.Tick(
            composite=red["composite_gte"], distinct_axes=red["distinct_axes_gte"]
        ),
        statemachine.Tick(composite=amber["composite_gte"], distinct_axes=0),
    ]
    timeline = statemachine.run(ticks, profile, config)
    assert timeline == ["GREEN", "AMBER"]


def test_min_dwell_nominal_equals_effective_commit_on_second_tick_d25() -> None:
    """D-2: min_dwell_ticks=1(명목)이면 실효 체류도 1틱 — 전이 커밋 틱을 1틱째로 세므로
    강등은 2틱째부터 커밋 가능하다(라운드1의 명목+1 오프바이원은 기각됨)."""
    config = registry.load_statemachine()
    profile = ProfileParams(
        promote_sustain_ticks=1,
        demote_below_ticks=1,
        min_dwell_ticks=1,
        reentry_cooldown_ticks=0,
    )
    amber = config.upgrade["AMBER"]
    exit_amber = config.downgrade["exit_AMBER"]
    up_tick = statemachine.Tick(composite=amber["composite_gte"], distinct_axes=0)
    down_tick = statemachine.Tick(
        composite=exit_amber["composite_lt"] - 1, distinct_axes=0
    )

    timeline = statemachine.run([up_tick, down_tick], profile, config)
    assert timeline == ["AMBER", "GREEN"]  # 2번째 틱(진입 후 2틱째)에서 강등 커밋


def test_min_dwell_blocks_demotion_independent_of_streak() -> None:
    """min_dwell_ticks는 강등에만 적용됨을 격리 검증: demote_below_ticks(2) < min_dwell_ticks(5)인
    합성 프로파일로, 강등 스트릭은 진작 충족돼도 dwell 미달이면 강등이 보류됨을 보인다.
    D-2 수정 반영: 필요한 down-tick 수는 min_dwell_ticks(끼워맞춤이었던 +1 아님)."""
    config = registry.load_statemachine()
    profile = ProfileParams(
        promote_sustain_ticks=1,
        demote_below_ticks=2,
        min_dwell_ticks=5,
        reentry_cooldown_ticks=0,
    )
    amber = config.upgrade["AMBER"]
    exit_amber = config.downgrade["exit_AMBER"]
    up_tick = statemachine.Tick(composite=amber["composite_gte"], distinct_axes=0)
    down_tick = statemachine.Tick(
        composite=exit_amber["composite_lt"] - 1, distinct_axes=0
    )

    n_down = (
        profile.min_dwell_ticks
    )  # dwell이 병목이 되도록: streak(2)는 진작 충족, dwell(5)만 남음
    ticks = [up_tick] + [down_tick] * n_down
    timeline = statemachine.run(ticks, profile, config)

    assert timeline[0] == "AMBER"
    assert timeline[1:-1] == ["AMBER"] * (
        n_down - 1
    )  # 강등 스트릭은 충족돼도 dwell 미달로 보류
    assert timeline[-1] == "GREEN"  # dwell 충족되는 순간(5틱째) 강등


def test_server_demote_streak_min_dwell_and_reentry_cooldown_full_cycle() -> None:
    """서버 프로파일 실제 파라미터로: 승격(레벨별 sustain=2, RED 직행) -> 강등(streak=6,
    dwell=4 충족 후 발화) -> 강등 직후 reentry_cooldown_ticks(6)간 재승격 억제(모든 레벨
    스트릭 정지·리셋) -> 쿨다운 해제 후 sustain(2) 재충족 시 재승격(RED 직행)."""
    config = registry.load_statemachine()
    profile = config.profiles["server_intraday"]
    red = config.upgrade["RED"]
    exit_red = config.downgrade["exit_RED"]

    up_tick = statemachine.Tick(
        composite=red["composite_gte"], distinct_axes=red["distinct_axes_gte"]
    )
    down_tick = statemachine.Tick(
        composite=exit_red["composite_lt"] - 1, distinct_axes=0
    )

    ticks = (
        [up_tick] * profile.promote_sustain_ticks
        + [down_tick] * profile.demote_below_ticks
        + [up_tick] * (profile.reentry_cooldown_ticks + profile.promote_sustain_ticks)
    )
    timeline = statemachine.run(ticks, profile, config)

    assert timeline[0] == "GREEN"
    assert (
        timeline[1] == "RED"
    )  # AMBER/ORANGE/RED 조건이 2틱 모두 동시 충족 -> RED 직행

    demote_start = profile.promote_sustain_ticks
    demote_fire_idx = demote_start + profile.demote_below_ticks - 1
    assert timeline[demote_start:demote_fire_idx] == ["RED"] * (
        demote_fire_idx - demote_start
    )
    assert (
        timeline[demote_fire_idx] == "ORANGE"
    )  # demote_below_ticks 연속 충족 + dwell 충족 시점에 강등

    cooldown_end = demote_fire_idx + 1 + profile.reentry_cooldown_ticks
    assert (
        timeline[demote_fire_idx + 1 : cooldown_end]
        == ["ORANGE"] * profile.reentry_cooldown_ticks
    )
    assert timeline[-1] == "RED"  # 쿨다운 해제 후 sustain 재충족 -> 재승격


def test_mobile_demote_path_and_immediate_reentry_no_cooldown() -> None:
    """D-6: mobile_daily 강등 경로(demote_below=3, dwell=2) + 재승격 —
    reentry_cooldown_ticks 미정의 -> 기본값 0(Advisor 지정 해석) 확인 + 강등 직후에도
    쿨다운 없이 sustain(1)만 재충족되면 즉시 재승격됨을 확인. "양 프로파일" 완료 기준의
    나머지 절반(서버는 test_server_demote_...full_cycle에서 이미 검증됨)."""
    config = registry.load_statemachine()
    profile = config.profiles["mobile_daily"]
    assert profile.reentry_cooldown_ticks == 0  # yaml에 키 없음 -> 기본값 0

    red = config.upgrade["RED"]
    exit_red = config.downgrade["exit_RED"]
    up_tick = statemachine.Tick(
        composite=red["composite_gte"], distinct_axes=red["distinct_axes_gte"]
    )
    down_tick = statemachine.Tick(
        composite=exit_red["composite_lt"] - 1, distinct_axes=0
    )

    ticks = (
        [up_tick] * profile.promote_sustain_ticks
        + [down_tick] * profile.demote_below_ticks
        + [up_tick] * profile.promote_sustain_ticks
    )
    timeline = statemachine.run(ticks, profile, config)

    assert timeline[0] == "RED"  # sustain=1 즉시 승격

    demote_fire_idx = profile.promote_sustain_ticks + profile.demote_below_ticks - 1
    assert (
        timeline[demote_fire_idx] == "ORANGE"
    )  # streak(3)·dwell(2+1=3) 동시 충족 시점 강등
    assert (
        timeline[-1] == "RED"
    )  # cooldown=0 -> 강등 직후 sustain 재충족만으로 즉시 재승격


def test_none_composite_tick_freezes_phase_and_streak_d25() -> None:
    """D-3 재현: 전 지표 결측(Tick.composite=None) 틱은 국면·스트릭·카운터를 완전히
    동결한다(전이 없음, 틱 미소비) — GREEN으로 떨어뜨리지 않는다. 동결 틱을 사이에 두고도
    AMBER 조건 충족 스트릭이 끊기지 않고 이어짐을 함께 확인한다."""
    config = registry.load_statemachine()
    profile = config.profiles["server_intraday"]
    amber = config.upgrade["AMBER"]
    real_tick = statemachine.Tick(composite=amber["composite_gte"], distinct_axes=0)
    frozen_tick = statemachine.Tick(composite=None, distinct_axes=0)

    timeline = statemachine.run([real_tick, frozen_tick, real_tick], profile, config)
    assert timeline == [
        "GREEN",
        "GREEN",
        "AMBER",
    ]  # 동결 틱을 사이에 두고도 스트릭 2 도달 시 승격


# ---------------------------------------------------------------------------
# REVIEW_M0 MT0-02 라운드 2(aaa-critic FAIL, 변이 생존 5건) — 증인 테스트
# 구현 결함은 0건(비평가 차등 테스트로 확인)이었다. 이 섹션은 각 변이를 실제로 손으로
# 적용해 스위트가 죽는 것을 확인한 뒤 되돌린 결과를 그대로 코드화한 것이다(보고 참조).
# ---------------------------------------------------------------------------


def test_f2_1_promote_streak_resets_on_non_consecutive_miss() -> None:
    """F2-1 증인: statemachine.py의 미충족 리셋(`promote_streaks[level] = 0`)을 `pass`로
    바꾸면(누적화) [RED조건, AMBER전용, RED조건] 시퀀스가 ['GREEN','AMBER','RED']가 된다
    (RED 조건이 비연속 2회만 충족돼도 승격되는 오류). 정상 구현은 RED 스트릭이 중간 틱에서
    끊기므로 AMBER까지만 승격해야 한다."""
    config = registry.load_statemachine()
    profile = config.profiles["server_intraday"]
    amber = config.upgrade["AMBER"]
    red = config.upgrade["RED"]

    ticks = [
        statemachine.Tick(
            composite=red["composite_gte"], distinct_axes=red["distinct_axes_gte"]
        ),
        statemachine.Tick(composite=amber["composite_gte"], distinct_axes=0),
        statemachine.Tick(
            composite=red["composite_gte"], distinct_axes=red["distinct_axes_gte"]
        ),
    ]
    timeline = statemachine.run(ticks, profile, config)
    assert timeline == ["GREEN", "AMBER", "AMBER"]


def test_f2_2_cooldown_stops_and_resets_all_promote_streaks() -> None:
    """F2-2 증인: cooldown 분기의 스트릭 리셋 루프를 `pass`로 바꾸면(정지·리셋 안 됨), 강등
    직전까지 쌓인 스트릭이 쿨다운 내내 얼어붙은 채로 남아 쿨다운 해제 직후 첫 틱에 조기
    재승격이 일어난다. any_crit=True로 AMBER 자신의 조건은 계속 충족시키면서 동시에
    composite는 exit_AMBER 미만으로 유지해 강등 압박도 함께 거는 구성(server, 실제 SSOT 값)."""
    config = registry.load_statemachine()
    profile = config.profiles["server_intraday"]
    amber = config.upgrade["AMBER"]
    exit_amber = config.downgrade["exit_AMBER"]

    up_tick = statemachine.Tick(composite=amber["composite_gte"], distinct_axes=0)
    down_crit_tick = statemachine.Tick(
        composite=exit_amber["composite_lt"] - 1, distinct_axes=0, any_crit=True
    )

    n_down = (
        profile.demote_below_ticks
        + profile.reentry_cooldown_ticks
        + profile.promote_sustain_ticks
    )
    ticks = [up_tick] * profile.promote_sustain_ticks + [down_crit_tick] * n_down
    timeline = statemachine.run(ticks, profile, config)

    demote_idx = profile.promote_sustain_ticks + profile.demote_below_ticks - 1
    still_frozen_through = (
        demote_idx + profile.reentry_cooldown_ticks + 1
    )  # 쿨다운 + 첫 해제틱(streak=1)
    assert timeline[demote_idx : still_frozen_through + 1] == ["GREEN"] * (
        still_frozen_through - demote_idx + 1
    )
    assert timeline[still_frozen_through + 1] == "AMBER"  # sustain(2)틱째에만 재승격
    assert (
        len(timeline) == still_frozen_through + 2
    )  # 시퀀스 길이가 정확히 이 지점에서 끝남


def test_f2_3_promote_streak_survives_transition_across_levels() -> None:
    """F2-3 증인: 승격 커밋 시 전체 스트릭을 리셋하는 변이가 있으면, RED 스트릭이 ORANGE
    전이를 가로질러 누적되지 못해 RED 승격이 1틱 늦어진다. 정상 구현은 D-25 §1대로 "전이와
    무관하게 누적"되므로 [ORANGE조건, RED조건, RED조건, RED조건]에서 RED가 ORANGE 다음 틱에
    바로 온다(RED 자신의 조건이 그 시점까지 2틱 연속 충족되기 때문)."""
    config = registry.load_statemachine()
    profile = config.profiles["server_intraday"]
    orange = config.upgrade["ORANGE"]
    red = config.upgrade["RED"]

    ticks = [
        statemachine.Tick(
            composite=orange["composite_gte"], distinct_axes=orange["distinct_axes_gte"]
        ),
        statemachine.Tick(
            composite=red["composite_gte"], distinct_axes=red["distinct_axes_gte"]
        ),
        statemachine.Tick(
            composite=red["composite_gte"], distinct_axes=red["distinct_axes_gte"]
        ),
        statemachine.Tick(
            composite=red["composite_gte"], distinct_axes=red["distinct_axes_gte"]
        ),
    ]
    timeline = statemachine.run(ticks, profile, config)
    assert timeline == ["GREEN", "ORANGE", "RED", "RED"]


def test_f2_4a_frozen_tick_does_not_consume_cooldown() -> None:
    """F2-4 증인 ⓐ: 동결 분기에 `cooldown -= 1`이 섞여 들어가면, 동결 틱 하나가 쿨다운을
    한 틱만큼 몰래 갉아먹어 재승격이 1틱 조기화된다. 동결 틱을 쿨다운 구간 한복판에 끼워
    넣고도 재승격 시점이 밀리지 않아야(오히려 동결 틱만큼 뒤로 미뤄져야) 한다."""
    config = registry.load_statemachine()
    profile = config.profiles["server_intraday"]
    red = config.upgrade["RED"]
    exit_red = config.downgrade["exit_RED"]

    up_tick = statemachine.Tick(
        composite=red["composite_gte"], distinct_axes=red["distinct_axes_gte"]
    )
    down_tick = statemachine.Tick(
        composite=exit_red["composite_lt"] - 1, distinct_axes=0
    )
    frozen_tick = statemachine.Tick(composite=None, distinct_axes=0)

    ticks = (
        [up_tick] * profile.promote_sustain_ticks
        + [down_tick] * profile.demote_below_ticks
        + [frozen_tick]
        + [up_tick] * (profile.reentry_cooldown_ticks + profile.promote_sustain_ticks)
    )
    timeline = statemachine.run(ticks, profile, config)

    demote_idx = profile.promote_sustain_ticks + profile.demote_below_ticks - 1
    assert timeline[demote_idx] == "ORANGE"
    assert timeline[demote_idx + 1] == "ORANGE"  # 동결 틱: 미소비, 국면 그대로
    # 동결 틱 이후 cooldown(6)+sustain(2)=8 "실제" 틱이 그대로 다 필요해야 재승격
    assert timeline[demote_idx + 1 : -1] == ["ORANGE"] * (
        len(timeline) - demote_idx - 2
    )
    assert timeline[-1] == "RED"


def test_f2_4b_frozen_tick_does_not_advance_dwell() -> None:
    """F2-4 증인 ⓑ: 동결 분기에 `ticks_in_phase += 1`이 섞여 들어가면, 동결 틱이 dwell
    카운트를 몰래 진행시켜 강등이 1틱 조기화된다. demote_below_ticks(2) < min_dwell_ticks(5)인
    합성 프로파일로 dwell이 병목인 상황을 만들고, 그 중간에 동결 틱을 끼워 넣는다."""
    config = registry.load_statemachine()
    profile = ProfileParams(
        promote_sustain_ticks=1,
        demote_below_ticks=2,
        min_dwell_ticks=5,
        reentry_cooldown_ticks=0,
    )
    amber = config.upgrade["AMBER"]
    exit_amber = config.downgrade["exit_AMBER"]
    up_tick = statemachine.Tick(composite=amber["composite_gte"], distinct_axes=0)
    down_tick = statemachine.Tick(
        composite=exit_amber["composite_lt"] - 1, distinct_axes=0
    )
    frozen_tick = statemachine.Tick(composite=None, distinct_axes=0)

    ticks = [up_tick] + [down_tick] * 2 + [frozen_tick] + [down_tick] * 3
    timeline = statemachine.run(ticks, profile, config)

    assert timeline == ["AMBER"] * 6 + ["GREEN"]  # 동결 틱 포함 7틱째에 정확히 강등


def test_f2_4c_frozen_tick_does_not_reset_demote_streak() -> None:
    """F2-4 증인 ⓒ: 동결 분기에 `demote_streak = 0`이 섞여 들어가면, 진행 중이던 강등
    스트릭이 동결 틱 하나로 지워져 강등이 훨씬 늦어지거나(또는 이 테스트 예산 안에서
    아예) 일어나지 않는다. demote_below_ticks(6)-1=5개의 실제 하락 틱 다음에 동결 틱을
    끼우고, 마지막 1개의 실제 하락 틱으로 스트릭이 완성되어야 한다."""
    config = registry.load_statemachine()
    profile = config.profiles["server_intraday"]
    red = config.upgrade["RED"]
    exit_red = config.downgrade["exit_RED"]

    up_tick = statemachine.Tick(
        composite=red["composite_gte"], distinct_axes=red["distinct_axes_gte"]
    )
    down_tick = statemachine.Tick(
        composite=exit_red["composite_lt"] - 1, distinct_axes=0
    )
    frozen_tick = statemachine.Tick(composite=None, distinct_axes=0)

    ticks = (
        [up_tick] * profile.promote_sustain_ticks
        + [down_tick] * (profile.demote_below_ticks - 1)
        + [frozen_tick]
        + [down_tick]
    )
    timeline = statemachine.run(ticks, profile, config)
    assert (
        timeline[-1] == "ORANGE"
    )  # 동결 틱을 사이에 두고도 강등 스트릭이 이어져 커밋됨


def test_f2_5_demote_streak_resets_on_non_consecutive_miss() -> None:
    """F2-5 증인: `else: demote_streak = 0`을 `pass`로 바꾸면(연속 리셋 안 됨), exit선
    아래/위를 교대하는 틱에서도 하락 틱 수가 비연속으로 누적돼 결국 강등이 일어난다.
    정상 구현은 exit선 위 틱마다 스트릭이 즉시 리셋되므로 아무리 반복해도 강등되지 않고
    RED를 유지해야 한다(server, 실제 SSOT 값)."""
    config = registry.load_statemachine()
    profile = config.profiles["server_intraday"]
    red = config.upgrade["RED"]
    exit_red = config.downgrade["exit_RED"]

    up_tick = statemachine.Tick(
        composite=red["composite_gte"], distinct_axes=red["distinct_axes_gte"]
    )
    low_tick = statemachine.Tick(
        composite=exit_red["composite_lt"] - 1, distinct_axes=0
    )
    high_tick = statemachine.Tick(
        composite=exit_red["composite_lt"] + 1, distinct_axes=0
    )

    ticks = [up_tick] * profile.promote_sustain_ticks + [low_tick, high_tick] * (
        profile.demote_below_ticks * 2
    )
    timeline = statemachine.run(ticks, profile, config)
    assert all(p == "RED" for p in timeline[profile.promote_sustain_ticks - 1 :])


def test_o2_8_skip_levels_false_promotes_one_level_at_a_time() -> None:
    """O2-8: skip_levels=false 분기(`min(eligible)`)가 실제로 실행됨을 확인 — RED 조건이
    통째로 충족돼도 GREEN에서 한 단계(AMBER)만 승격해야 한다(합성 설정, 실 config는
    skip_levels=true라 이 분기는 별도로 구성하지 않으면 커버되지 않는다)."""
    config = registry.load_statemachine()
    no_skip_config = dataclasses.replace(config, skip_levels=False)
    profile = config.profiles["mobile_daily"]
    red = config.upgrade["RED"]

    tick = statemachine.Tick(
        composite=red["composite_gte"], distinct_axes=red["distinct_axes_gte"]
    )
    timeline = statemachine.run([tick], profile, no_skip_config)
    assert timeline == ["AMBER"]
