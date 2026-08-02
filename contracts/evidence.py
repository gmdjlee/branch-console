"""evidence-pack/1 — 판단 계층 입력 계약 (SSOT).

detection 서비스가 조립하고 judgment 서비스가 소비한다.
서브에이전트/LLM은 대화 이력에 접근할 수 없으므로,
판단에 필요한 모든 맥락은 이 팩에 물질화되어야 한다.
"""

from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field

from contracts.snapshot import FiredIndicator, Phase


class MarketSnapshot(BaseModel):
    """평가 틱 시점의 주요 시세 요약 (as_of 기준, PIT 보장)."""

    as_of: datetime
    kospi: float
    kospi_chg_1d_pct: float
    kospi_drawdown_60d_pct: float
    usdkrw: float
    usdkrw_chg_1d_pct: float
    vix: float | None = None
    vkospi: float | None = None
    hy_oas: float | None = None
    spx_chg_1d_pct: float | None = None


class NewsCluster(BaseModel):
    """P2부터 채워짐. P1에서는 빈 리스트."""

    topic: str
    article_count: int
    novelty_z: float | None = None
    representative_headlines: list[str] = Field(max_length=5)


class MacroEvent(BaseModel):
    date: str  # YYYY-MM-DD
    name: str  # 예: "FOMC", "금통위", "미 CPI"


class AnalogueRef(BaseModel):
    """P3 analogue library 조회 결과. P1에서는 빈 리스트."""

    event_id: str
    similarity: float
    outcome_summary: str  # 예: "KOSPI 5d -6.2%, 20d -3.1%, 60d +4.0%"


class EvidencePack(BaseModel):
    schema_id: Literal["evidence-pack/1"] = Field("evidence-pack/1", alias="schema")
    built_at: datetime
    phase: Phase
    prev_phase: Phase
    composite_score: float
    fired_indicators: list[FiredIndicator]
    market: MarketSnapshot
    news_clusters: list[NewsCluster] = Field(default_factory=list)
    macro_calendar_7d: list[MacroEvent] = Field(default_factory=list)
    analogues: list[AnalogueRef] = Field(default_factory=list)

    model_config = {"populate_by_name": True}
