"""scenario-snapshot/1 — 판단 계층 출력 계약 (SSOT).

이 pydantic 모델이 유일한 진실이다:
- judgment 서비스는 `ScenarioSnapshot.model_json_schema()`를 Claude API의
  구조화 출력(output_config.format=json_schema)에 그대로 전달한다.
- 저장소(Postgres JSONB), 리포트 렌더러, (향후) 앱 UI는 모두 이 모델로 검증한다.
- 필드 추가는 하위호환(Optional)으로만. 파괴적 변경 시 schema를 /2로 올린다.
"""

from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field, confloat, conint

Phase = Literal["GREEN", "AMBER", "ORANGE", "RED"]
Severity = conint(ge=0, le=3)


class FiredIndicator(BaseModel):
    id: str
    axis: str
    severity: Severity
    value: float
    z: float | None = None
    note: str | None = None


class TriggerBlock(BaseModel):
    phase: Phase
    prev_phase: Phase
    composite_score: confloat(ge=0, le=100)
    distinct_axes: conint(ge=0)
    fired_indicators: list[FiredIndicator]
    evaluated_at: datetime


class EventClassification(BaseModel):
    type: Literal[
        "monetary_policy",
        "credit_stress",
        "geopolitical",
        "growth_shock",
        "tech_supply_demand",
        "domestic_kr",
        "mixed",
        "unknown",
    ]
    severity: Literal["low", "medium", "high"]
    confidence: Literal["low", "medium", "high"]
    analogues: list[str] = Field(
        default_factory=list,
        description="analogue library 사건 ID (예: '2024-08-05_carry_unwind')",
    )
    rationale: str = Field(
        description="분류 근거. 반드시 fired_indicators 또는 analogue 인용"
    )


class KrImpact(BaseModel):
    kospi_range_pct: tuple[float, float] = Field(
        description="[하단, 상단] %. 반드시 analogue 통계 범위에 근거"
    )
    sectors_hit: list[str]
    sectors_defensive: list[str]
    usdkrw_bias: Literal["up", "down", "flat"]


class Scenario(BaseModel):
    name: str
    subjective_prob: confloat(ge=0, le=1) = Field(
        description="비보정 주관 확률. 서열 정보로만 사용"
    )
    narrative: str
    kr_impact: KrImpact
    leading_indicators: list[str] = Field(min_length=2)
    invalidation: str = Field(description="검증 가능한 무효화 조건 (지표·기간 명시)")
    horizon_days: conint(ge=1, le=120)


class ScenarioSnapshot(BaseModel):
    schema_id: Literal["scenario-snapshot/1"] = Field(
        "scenario-snapshot/1", alias="schema"
    )
    generated_at: datetime
    target_market: Literal["KR"] = "KR"
    trigger: TriggerBlock
    event_classification: EventClassification
    scenarios: list[Scenario] = Field(min_length=2, max_length=4)
    watchlist_next_7d: list[str]
    disclaimer: str = (
        "subjective_prob는 비보정 주관 확률. 투자 자문이 아닌 의사결정 보조 정보."
    )

    model_config = {"populate_by_name": True}
