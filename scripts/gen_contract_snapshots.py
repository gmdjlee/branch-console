"""MT1-02a: contract schema snapshot generator (Python side, docs/plans/M1_PLAN_B.md §6).

`contracts/{snapshot,evidence}.py` are the pydantic SSOT for the two wire payloads
(`ScenarioSnapshot`, `EvidencePack`) but until now had no frozen snapshot and no
snapshot test on either language side (§6.1). This script is the single source that
produces every file under `contracts/snapshots/` (§6.2 candidate B: canonical
instance round-trip + a structural shape digest, not a JSON-Schema mirror - pydantic's
`$defs`/`anyOf` output is a Python dialect kotlinx.serialization has no equivalent
for, so cross-language comparison instead happens over each side's own native field
introspection, §6.2 (A) rejection).

This script does not modify contracts/*.py (K-xx/CLAUDE.md §1 SSOT rule: contracts
are the frozen truth here, not something this generator is allowed to shape).

Usage:
    uv run python scripts/gen_contract_snapshots.py            # (re)write snapshot files
    uv run python scripts/gen_contract_snapshots.py --check     # exit 1 iff on-disk != regenerated

Wire format regulated by §6.3:
  1. field names: by_alias=True ("schema", not "schema_id" - M-16/D-B6, docs/plans/
     M1_PLAN_FINAL.md §1.3).
  2. datetime: RFC-3339 UTC, no milliseconds, "Z" suffix. pydantic-core already emits
     "Z" directly for aware-UTC datetimes in the pinned pydantic version (verified by
     hand); `_normalize` is a cheap regex safety net against a future version reverting
     to "+00:00", not a normalization this version currently needs to do any work.
  3. tuple[float, float] -> JSON 2-element array (mode="json" does this for free).
  4. confloat/conint/min_length constraints are NOT part of the shape digest (see
     `_type_repr` docstring) - they are instead proven by the positive fixtures
     (satisfy them) and the invalid/ fixtures (violate them one at a time).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import types
import typing
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

if __package__ in (None, ""):
    # `uv run python scripts/gen_contract_snapshots.py` (the documented CLI form,
    # §6.5) runs this file with no package context, so the repo root isn't on
    # sys.path and `import contracts.*` below would fail (backtest/build_fixtures.py
    # precedent for this exact fix).
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from pydantic import BaseModel

from contracts.evidence import (
    AnalogueRef,
    EvidencePack,
    MacroEvent,
    MarketSnapshot,
    NewsCluster,
)
from contracts.snapshot import (
    EventClassification,
    FiredIndicator,
    KrImpact,
    Scenario,
    ScenarioSnapshot,
    TriggerBlock,
)

REPO_ROOT = Path(__file__).resolve().parent.parent
SNAPSHOTS_DIR = REPO_ROOT / "contracts" / "snapshots"
INVALID_DIR = SNAPSHOTS_DIR / "invalid"
ASYMMETRIC_DIR = SNAPSHOTS_DIR / "asymmetric"
SHAPE_SHA256_PATH = SNAPSHOTS_DIR / "shape.sha256"

# Models whose structural shape is digested (§6.2 candidate B "형상 다이제스트").
# Order is fixed source but the digest itself sorts keys, so declaration order here
# has no effect on the resulting hash.
SHAPE_MODELS: tuple[type[BaseModel], ...] = (
    FiredIndicator,
    TriggerBlock,
    EventClassification,
    KrImpact,
    Scenario,
    ScenarioSnapshot,
    MarketSnapshot,
    NewsCluster,
    MacroEvent,
    AnalogueRef,
    EvidencePack,
)


def _dt(y: int, mo: int, d: int, h: int = 8, mi: int = 0, s: int = 0) -> datetime:
    """Aware UTC datetime, no microseconds (§6.3-2: no fractional seconds on the wire)."""
    return datetime(y, mo, d, h, mi, s, tzinfo=UTC)


# -----------------------------------------------------------------------------
# canonical positive instances (§6.2 산출물 목록)
# -----------------------------------------------------------------------------


def build_scenario_snapshot_min() -> ScenarioSnapshot:
    """Smallest legal instance: exactly the lower bounds each constraint allows
    (2 scenarios, 2 leading_indicators each, empty fired_indicators/watchlist
    where no minimum applies)."""
    scenario_common = {
        "kr_impact": KrImpact(
            kospi_range_pct=(-1.0, 1.0),
            sectors_hit=["tech"],
            sectors_defensive=["utilities"],
            usdkrw_bias="flat",
        ),
        "leading_indicators": ["ind_a", "ind_b"],
        "invalidation": "placeholder invalidation condition",
        "horizon_days": 30,
    }
    return ScenarioSnapshot(
        generated_at=_dt(2026, 8, 6),
        trigger=TriggerBlock(
            phase="GREEN",
            prev_phase="GREEN",
            composite_score=0.0,
            distinct_axes=0,
            fired_indicators=[],
            evaluated_at=_dt(2026, 8, 6),
        ),
        event_classification=EventClassification(
            type="unknown",
            severity="low",
            confidence="low",
            rationale="minimal fixture: no material evidence",
        ),
        scenarios=[
            Scenario(
                name="scenario_a",
                subjective_prob=0.5,
                narrative="placeholder narrative a",
                **scenario_common,
            ),
            Scenario(
                name="scenario_b",
                subjective_prob=0.5,
                narrative="placeholder narrative b",
                **scenario_common,
            ),
        ],
        watchlist_next_7d=["placeholder_watch_item"],
    )


def build_scenario_snapshot_full() -> ScenarioSnapshot:
    """Every optional field populated + boundary values spread across the maximum
    (4) scenarios: subjective_prob 0.0/1.0, horizon_days 1/120 (§6.2 산출물 목록)."""
    kr_impact = KrImpact(
        kospi_range_pct=(-8.5, -2.0),
        sectors_hit=["semis", "banks"],
        sectors_defensive=["utilities", "telecom"],
        usdkrw_bias="up",
    )
    scenario_specs = [
        ("scenario_worst", 0.0, "narrative: tail-risk unwind", 1),
        ("scenario_bear", 0.35, "narrative: broad risk-off continuation", 30),
        ("scenario_base", 0.65, "narrative: contained, range-bound", 90),
        ("scenario_bull", 1.0, "narrative: relief rally", 120),
    ]
    return ScenarioSnapshot(
        generated_at=_dt(2026, 8, 6, 17, 0, 0),
        trigger=TriggerBlock(
            phase="ORANGE",
            prev_phase="AMBER",
            composite_score=62.5,
            distinct_axes=3,
            fired_indicators=[
                FiredIndicator(
                    id="vix_level_z",
                    axis="vol",
                    severity=2,
                    value=28.4,
                    z=2.1,
                    note="above warn threshold",
                ),
                FiredIndicator(
                    id="global_corr_break",
                    axis="global_price",
                    severity=3,
                    value=0.91,
                    z=3.4,
                    note="crit: correlation regime break",
                ),
            ],
            evaluated_at=_dt(2026, 8, 6, 17, 0, 0),
        ),
        event_classification=EventClassification(
            type="mixed",
            severity="high",
            confidence="medium",
            analogues=["2024-08-05_carry_unwind", "2020-03-09_covid_crash"],
            rationale="full fixture: exercises non-default analogues + long rationale text",
        ),
        scenarios=[
            Scenario(
                name=name,
                subjective_prob=prob,
                narrative=narrative,
                kr_impact=kr_impact,
                leading_indicators=["vix_level_z", "global_corr_break", "usdkrw_z"],
                invalidation="invalidated if composite_score reverts below 40 for 3 ticks",
                horizon_days=horizon,
            )
            for name, prob, narrative, horizon in scenario_specs
        ],
        watchlist_next_7d=["FOMC minutes", "KR CPI", "usdkrw_z reversal"],
    )


def build_evidence_pack_min() -> EvidencePack:
    return EvidencePack(
        built_at=_dt(2026, 8, 6),
        phase="GREEN",
        prev_phase="GREEN",
        composite_score=0.0,
        fired_indicators=[],
        market=MarketSnapshot(
            as_of=_dt(2026, 8, 6),
            kospi=2500.0,
            kospi_chg_1d_pct=0.0,
            kospi_drawdown_60d_pct=0.0,
            usdkrw=1300.0,
            usdkrw_chg_1d_pct=0.0,
        ),
    )


def build_evidence_pack_full() -> EvidencePack:
    return EvidencePack(
        built_at=_dt(2026, 8, 6, 17, 0, 0),
        phase="ORANGE",
        prev_phase="AMBER",
        composite_score=62.5,
        fired_indicators=[
            FiredIndicator(
                id="vix_level_z", axis="vol", severity=2, value=28.4, z=2.1, note="warn"
            ),
            FiredIndicator(
                id="global_corr_break",
                axis="global_price",
                severity=3,
                value=0.91,
                z=3.4,
                note="crit",
            ),
        ],
        market=MarketSnapshot(
            as_of=_dt(2026, 8, 6, 17, 0, 0),
            kospi=2410.5,
            kospi_chg_1d_pct=-2.3,
            kospi_drawdown_60d_pct=-9.8,
            usdkrw=1365.2,
            usdkrw_chg_1d_pct=1.1,
            vix=28.4,
            vkospi=24.7,
            hy_oas=4.35,
            spx_chg_1d_pct=-1.8,
        ),
        news_clusters=[
            NewsCluster(
                topic="carry_unwind",
                article_count=42,
                novelty_z=3.1,
                representative_headlines=["headline one", "headline two"],
            )
        ],
        macro_calendar_7d=[MacroEvent(date="2026-08-10", name="FOMC")],
        analogues=[
            AnalogueRef(
                event_id="2024-08-05_carry_unwind",
                similarity=0.82,
                outcome_summary="KOSPI 5d -6.2%, 20d -3.1%, 60d +4.0%",
            )
        ],
    )


# -----------------------------------------------------------------------------
# invalid/ - one violated constraint each, everything else valid (§6.2.1 채택 (a):
# naive_datetime은 여기 포함하지 않는다 - 현행 스키마에서 실제로 거부되지 않는다)
# -----------------------------------------------------------------------------


def _base_trigger_block() -> TriggerBlock:
    return TriggerBlock(
        phase="GREEN",
        prev_phase="GREEN",
        composite_score=10.0,
        distinct_axes=1,
        fired_indicators=[],
        evaluated_at=_dt(2026, 8, 6),
    )


def _base_scenario() -> Scenario:
    return Scenario(
        name="scenario_a",
        subjective_prob=0.5,
        narrative="placeholder narrative",
        kr_impact=KrImpact(
            kospi_range_pct=(-1.0, 1.0),
            sectors_hit=["tech"],
            sectors_defensive=["utilities"],
            usdkrw_bias="flat",
        ),
        leading_indicators=["ind_a", "ind_b"],
        invalidation="placeholder invalidation condition",
        horizon_days=30,
    )


def _to_wire(model: BaseModel) -> dict[str, Any]:
    return model.model_dump(mode="json", by_alias=True)


def _build_invalid_cases() -> dict[str, tuple[type[BaseModel], dict[str, Any]]]:
    trigger = _to_wire(_base_trigger_block())
    scenario = _to_wire(_base_scenario())
    snapshot = _to_wire(build_scenario_snapshot_min())

    composite_out_of_range = dict(trigger)
    composite_out_of_range["composite_score"] = 150.0  # confloat(ge=0, le=100) 위반

    phase_unknown = dict(trigger)
    phase_unknown["phase"] = "YELLOW"  # Literal[...] 위반

    subjective_prob_over_one = dict(scenario)
    subjective_prob_over_one["subjective_prob"] = 1.5  # confloat(ge=0, le=1) 위반

    horizon_days_zero = dict(scenario)
    horizon_days_zero["horizon_days"] = 0  # conint(ge=1, le=120) 위반

    leading_indicators_one = dict(scenario)
    leading_indicators_one["leading_indicators"] = ["only_one"]  # min_length=2 위반

    scenarios_too_few = dict(snapshot)
    scenarios_too_few["scenarios"] = [snapshot["scenarios"][0]]  # min_length=2 위반

    return {
        "composite_out_of_range": (TriggerBlock, composite_out_of_range),
        "subjective_prob_over_one": (Scenario, subjective_prob_over_one),
        "horizon_days_zero": (Scenario, horizon_days_zero),
        "phase_unknown": (TriggerBlock, phase_unknown),
        "scenarios_too_few": (ScenarioSnapshot, scenarios_too_few),
        "leading_indicators_one": (Scenario, leading_indicators_one),
    }


def _build_asymmetric_cases() -> dict[str, tuple[type[BaseModel], dict[str, Any]]]:
    """§6.2.1: naive datetime은 현재 pydantic(AwareDatetime 미적용)에서 수용된다 - 이
    비대칭을 여기서 고정한다. Kotlin `Instant.parse`는 오프셋 없는 문자열을 거부할
    예정이다(MT1-02b/c 소관, 이 파일은 그 사실을 예고하는 것이며 검증하지 않는다)."""
    trigger = _to_wire(_base_trigger_block())
    trigger["evaluated_at"] = "2026-08-06T08:00:00"  # no offset, no "Z"
    return {"naive_datetime": (TriggerBlock, trigger)}


INVALID_CASES = _build_invalid_cases()
ASYMMETRIC_CASES = _build_asymmetric_cases()


# -----------------------------------------------------------------------------
# shape digest (§6.2 candidate B "형상 다이제스트") - field name -> (type, required)
# only. Numeric/length constraints (confloat/conint/min_length) are deliberately
# excluded: they already have direct executable proof via the positive fixtures
# (satisfy them) and invalid/ fixtures (violate them one at a time), and folding
# them into this digest too would just be re-encoding the same fact twice for no
# extra defect coverage - the digest's only job is to catch field additions/removals
# that neither of those catches (§6.2 rationale, §6.3 item 1).
# -----------------------------------------------------------------------------


def _type_repr(annotation: Any) -> str:
    if annotation is type(None):
        return "None"
    origin = typing.get_origin(annotation)
    if origin is typing.Literal:
        return (
            "Literal["
            + ",".join(sorted(str(a) for a in typing.get_args(annotation)))
            + "]"
        )
    if origin is types.UnionType or origin is typing.Union:
        return "|".join(sorted(_type_repr(a) for a in typing.get_args(annotation)))
    if origin is not None:
        name = getattr(origin, "__name__", str(origin))
        args = ",".join(_type_repr(a) for a in typing.get_args(annotation))
        return f"{name}[{args}]"
    if isinstance(annotation, type):
        return annotation.__name__
    return str(annotation)  # pragma: no cover - defensive, no such field exists today


def model_shape(model: type[BaseModel]) -> dict[str, Any]:
    fields = {
        (info.alias or name): {
            "type": _type_repr(info.annotation),
            "required": info.is_required(),
        }
        for name, info in model.model_fields.items()
    }
    return {"fields": dict(sorted(fields.items()))}


def build_shape_digest() -> dict[str, Any]:
    return dict(sorted((m.__name__, model_shape(m)) for m in SHAPE_MODELS))


def shape_digest_hex() -> str:
    canonical = json.dumps(build_shape_digest(), sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


# -----------------------------------------------------------------------------
# dump + drift check
# -----------------------------------------------------------------------------

_OFFSET_Z_RE = re.compile(r"(\d{2}:\d{2}:\d{2})\+00:00\b")


def _normalize(value: Any) -> Any:
    """Replace an aware-UTC '+00:00' suffix with 'Z' (§6.3-2). See module docstring:
    the pinned pydantic version already emits 'Z' directly, so this is a safety net,
    not currently load-bearing - kept because reverting it silently on a version bump
    would be a wire-format regression this script would otherwise not catch."""
    if isinstance(value, str):
        return _OFFSET_Z_RE.sub(r"\1Z", value)
    if isinstance(value, list):
        return [_normalize(v) for v in value]
    if isinstance(value, dict):
        return {k: _normalize(v) for k, v in value.items()}
    return value


def _dump_dict(payload: dict[str, Any]) -> str:
    return json.dumps(_normalize(payload), indent=2, ensure_ascii=False) + "\n"


def _dump_model(model: BaseModel) -> str:
    return _dump_dict(_to_wire(model))


def build_targets() -> dict[Path, str]:
    """Every file this generator owns, mapped to its exact expected text."""
    targets = {
        SNAPSHOTS_DIR / "scenario_snapshot.min.json": _dump_model(
            build_scenario_snapshot_min()
        ),
        SNAPSHOTS_DIR / "scenario_snapshot.full.json": _dump_model(
            build_scenario_snapshot_full()
        ),
        SNAPSHOTS_DIR / "evidence_pack.min.json": _dump_model(
            build_evidence_pack_min()
        ),
        SNAPSHOTS_DIR / "evidence_pack.full.json": _dump_model(
            build_evidence_pack_full()
        ),
        SHAPE_SHA256_PATH: shape_digest_hex() + "\n",
    }
    for name, (_model, payload) in INVALID_CASES.items():
        targets[INVALID_DIR / f"{name}.json"] = _dump_dict(payload)
    for name, (_model, payload) in ASYMMETRIC_CASES.items():
        targets[ASYMMETRIC_DIR / f"{name}.json"] = _dump_dict(payload)
    return targets


def main(argv: list[str] | None = None) -> int:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # K-xx cp949 콘솔 함정

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="verify on-disk snapshots byte-match regeneration; exit 1 on any drift",
    )
    args = parser.parse_args(argv)

    targets = build_targets()

    if args.check:
        drift = [
            f"{'MISSING' if not path.exists() else 'DRIFT'} {path}"
            for path, content in sorted(targets.items())
            if not path.exists() or path.read_text(encoding="utf-8") != content
        ]
        if drift:
            for line in drift:
                print(line, file=sys.stderr)
            return 1
        print(f"OK: {len(targets)} snapshot files match generator output")
        return 0

    for path, content in targets.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    print(f"wrote {len(targets)} snapshot files under {SNAPSHOTS_DIR}")
    return 0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
