"""engine_ref.transforms — pandas Series 기반 순수 변환 함수 (D-01 활성 지표 15종이 요구하는 전부).

규율(CLAUDE.md §1): 이 모듈은 숫자를 모른다. window·lookback 등 파라미터는 전부 호출부
(engine_ref.registry가 configs/indicators.yaml의 transform 문자열을 파싱한 값)에서 주입받는다.
전부 float64 고정(K-07 — 반올림은 표시 계층 몫), 부작용 없는 순수 함수.
"""

from __future__ import annotations

import operator

import numpy as np
import pandas as pd

_OPS = {
    "<": operator.lt,
    "<=": operator.le,
    ">": operator.gt,
    ">=": operator.ge,
    "==": operator.eq,
}


def zscore(x: pd.Series, window: int, *, absolute: bool = False) -> pd.Series:
    """rolling z-score. absolute=True면 절대값 변형(dxy_z 등 direction=higher_is_risk용)."""
    x = x.astype("float64")
    mean = x.rolling(window, min_periods=window).mean()
    std = x.rolling(window, min_periods=window).std()
    z = (x - mean) / std
    return z.abs() if absolute else z


def ratio(a: pd.Series, b: pd.Series) -> pd.Series:
    return a.astype("float64") / b.astype("float64")


def delta_bp(x: pd.Series, lookback: int) -> pd.Series:
    """% 단위 시계열의 lookback일 변화를 bp로 (1%p = 100bp)."""
    x = x.astype("float64")
    return (x - x.shift(lookback)) * 100.0


def pct_change_1d(x: pd.Series) -> pd.Series:
    return x.astype("float64").pct_change(1, fill_method=None) * 100.0


def pct_change_5d(x: pd.Series) -> pd.Series:
    return x.astype("float64").pct_change(5, fill_method=None) * 100.0


def abs_(x: pd.Series) -> pd.Series:
    return x.astype("float64").abs()


def drawdown_from_high(x: pd.Series, window: int) -> pd.Series:
    """롤링 고점 대비 낙폭 %(양수 = 하락, 0 = 신고가)."""
    x = x.astype("float64")
    rolling_high = x.rolling(window, min_periods=window).max()
    return (rolling_high - x) / rolling_high * 100.0


def neg_zscore(x: pd.Series, window: int) -> pd.Series:
    """하락(음의 변화)이 위험(+)이 되도록 부호 반전한 z-score."""
    return -zscore(x, window)


def rolling_corr(a: pd.Series, b: pd.Series, window: int) -> pd.Series:
    return (
        a.astype("float64")
        .rolling(window, min_periods=window)
        .corr(b.astype("float64"))
    )


def rolling_mean_corr(corr: pd.Series, window: int) -> pd.Series:
    """장기 평균 상관(예: mean_corr120) — corr 시계열 자체의 롤링 평균."""
    return corr.astype("float64").rolling(window, min_periods=window).mean()


def rolling_sum(x: pd.Series, window: int) -> pd.Series:
    return x.astype("float64").rolling(window, min_periods=window).sum()


def gate_mask(series: pd.Series, op: str, threshold: float) -> pd.Series:
    """게이트 조건 불리언 마스크. op는 engine_ref.registry.parse_gate가 파싱한 비교 연산자."""
    return _OPS[op](series.astype("float64"), threshold)


def gated(z: pd.Series, mask: pd.Series) -> pd.Series:
    """mask가 False인 지점을 0으로 마스킹(결측 아님 — severity 0으로 이어짐). z·mask는 같은 인덱스 전제."""
    return z.astype("float64").where(mask, 0.0)


def realized_vol_kospi_20d(daily_return_pct: pd.Series, window: int) -> pd.Series:
    """K-02 VKOSPI 폴백: KOSPI 일수익률(%) N일 실현변동성 연율화(%).

    window은 하드코딩하지 않는다 — 호출부가 폴백 식별자 문자열
    (source.fallback: "realized_vol_kospi_20d")에서 engine_ref.registry.parse_fallback_window로
    파싱해 주입한다. 이 출력에 이후 zscore(window=252)를 적용하는 것은 registry/scoring 계층의 몫이다.
    """
    r = daily_return_pct.astype("float64") / 100.0
    return r.rolling(window, min_periods=window).std() * np.sqrt(252) * 100.0
