"""backtest/run_sweep.py — BT-03 보정 스윕 실행 (Stage B, MT0-05④).

    uv run python backtest/run_sweep.py

그리드·게이트·선정 리터럴은 0개다 — 전부 backtest/sweep.yaml에서 로드한다(CLAUDE.md §1).
절차는 sweep.yaml 자신과 docs/journal/2026-08-03_MT0-05_sweep_design.md §5(단계 실행
계획)·§6(선정 규칙의 조작적 정의)·§12.1(재현 스크립트)의 기계적 집행이다 — Stage A는
확정본이며 여기서 그리드·게이트·선정 규칙을 재설계하지 않는다.

근사-PIT — C1에서 실측 확정(docs/BACKTEST_PLAN.md §5). 이 스크립트가 산출하는 0.3.0-rc는
1차 보정 가설이다(D-04 "모든 임계값은 가설").

산출: backtest/results/sweep/sweep_result.json + selection_log.md. 기준선
backtest/results/metrics.json은 절대 덮어쓰지 않는다(sweep.yaml/저널 §11.2-4).

단계 구조(sweep.yaml execution.stages, 총 154평가):
  S1 usdkrw_thresholds(35) → S2 stale daily_us(3) → S3 교차검증(4, 불일치 시 전수 105로
  자동 승격) → S4 mobile_daily_profile(112, S3 승자 위) → S5 최종 확인(9창 전체, 선정
  미반영).

방법론 노트(설계에 명시되지 않아 이 구현이 내린 판단 — BT_REPORT에도 동일 문구 기록):
S1/S2/S3는 ③(mobile_daily 프로파일)을 baseline(promote_sustain=1, demote_below=3,
min_dwell=2, reentry_cooldown=0)에 고정한 채로 돈다. 그런데 baseline 자체가 이미 §6
플래핑(양성<=6)을 위반한다(§2 기준선 표, mobile 최대 7) — 즉 ③이 실제로 바뀌는 S4 이전에는
플래핑 게이트가 모든 후보에서 상시 FAIL이라 판별력이 없다(reduced-gate 판단, 아래
`reduced_gate_pass` 참조). 따라서 S1/S2/S3의 top_k/불일치 판정은 **완화 게이트**
(detection ∧ leadtime ∧ false_positive, flapping 제외)로 golden-pass 후보를 추려 rank한다.
**최종 선정(S4 winner)은 flapping을 포함한 완전판 게이트**로 판정한다 — 이 노트는 선정
결과의 엄격성을 낮추지 않는다(AD-1 ii "게이트 전면 유지"는 S4 최종 선정에서 지켜진다).
"""

from __future__ import annotations

import copy
import json
import statistics
import sys
import tempfile
import time as _time_module
from dataclasses import dataclass
from datetime import UTC, date, datetime
from itertools import product
from pathlib import Path
from typing import Any

import pandas as pd
import yaml

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from backtest import run_replay as R
from backtest.fixture_schema import load_windows

REPO_ROOT = Path(__file__).resolve().parent.parent
SWEEP_YAML_PATH = REPO_ROOT / "backtest" / "sweep.yaml"
INDICATORS_YAML_PATH = REPO_ROOT / "configs" / "indicators.yaml"
STATEMACHINE_YAML_PATH = REPO_ROOT / "configs" / "statemachine.yaml"
REPLAY_YAML_PATH = REPO_ROOT / "backtest" / "replay.yaml"
GOLDEN_SERVER_PATH = REPO_ROOT / "backtest" / "golden_server.yaml"
GOLDEN_MOBILE_PATH = REPO_ROOT / "backtest" / "golden_mobile.yaml"
SWEEP_RESULTS_DIR = REPO_ROOT / "backtest" / "results" / "sweep"

_ORDER = ["GREEN", "AMBER", "ORANGE", "RED"]


def _load_yaml(path: Path) -> dict[str, Any]:
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)


def _write_yaml(obj: dict[str, Any], path: Path) -> None:
    path.write_text(yaml.safe_dump(obj, allow_unicode=True, sort_keys=False), "utf-8")


# -----------------------------------------------------------------------------
# SSOT loads — sweep.yaml is the only source of grid/gate/rank values (CLAUDE.md §1)
# -----------------------------------------------------------------------------

SW = _load_yaml(SWEEP_YAML_PATH)
BASE_IND = _load_yaml(INDICATORS_YAML_PATH)
BASE_SM = _load_yaml(STATEMACHINE_YAML_PATH)
BASE_RP = _load_yaml(REPLAY_YAML_PATH)
GOLDEN_SERVER = _load_yaml(GOLDEN_SERVER_PATH)
GOLDEN_MOBILE = _load_yaml(GOLDEN_MOBILE_PATH)

D = SW["dimensions"]
GATE = SW["selection"]["gate"]
RANK = SW["selection"]["rank"]
STAGES = {s["id"]: s for s in SW["execution"]["stages"]}

DETECTION_EXCEPT_WINDOWS = set(GATE["detection"]["except_windows"])
LEAD_MEDIAN_MIN = GATE["lead_time"]["required_median_ge"]
FP_ORANGE_MAX = GATE["false_positive"]["orange_or_above_ticks_max"]
FP_AMBER_MAX = GATE["false_positive"]["amber_ticks_max"]
FLAP_POS_MAX = GATE["flapping"]["positive_transitions_max"]
FLAP_NEG_MAX = GATE["flapping"]["negative_transitions_max"]
FLAP_EXCEPT_PROFILES = set(GATE["flapping"]["except_profiles"])
TOP_K = STAGES["S3_interaction_check"]["top_k"]
CONFIRM_MIN = D["mobile_daily_profile"]["constraint"]["confirm_time_kst_min"]
# test_golden.py의 pytest.approx(rel=1e-9)와 동일 값 — golden_pass()가 "같은 yaml을
# 읽어 같은 비교를 한다"는 docstring 규율과 정합화(qa-verifier 반려 사유, Stage B).
COMPOSITE_REL_TOL = SW["golden_constraint"]["composite_rel_tol"]

ALL_WINDOWS = {w.window_id: w for w in load_windows()}
SWEEP_IDS = [w.window_id for w in load_windows() if not w.holdout]
HOLDOUT_IDS = [w.window_id for w in load_windows() if w.holdout]
POS_IDS = [wid for wid in SWEEP_IDS if ALL_WINDOWS[wid].kind == "positive"]
NEG_IDS = [wid for wid in SWEEP_IDS if ALL_WINDOWS[wid].kind == "negative"]
POS_IDS_FOR_DETECTION = [wid for wid in POS_IDS if wid not in DETECTION_EXCEPT_WINDOWS]
GOLDEN_IDS = SW["golden_constraint"]["windows"]
PROFILES: tuple[str, ...] = tuple(SW["meta"]["profiles"])

BASE_USDKRW = next(
    i["thresholds"] for i in BASE_IND["indicators"] if i["id"] == "usdkrw_z"
)
BASE_MOBILE_PROFILE = {
    "promote_sustain_ticks": int(BASE_SM["profiles"]["mobile_daily"]["promote_sustain_ticks"]),
    "demote_below_ticks": int(BASE_SM["profiles"]["mobile_daily"]["demote_below_ticks"]),
    "min_dwell_ticks": int(BASE_SM["profiles"]["mobile_daily"]["min_dwell_ticks"]),
    "reentry_cooldown_ticks": int(
        BASE_SM["profiles"]["mobile_daily"].get("reentry_cooldown_ticks", 0)
    ),
}
BASE_CONFIRM_TIME = BASE_RP["profiles"]["mobile_daily"]["confirm_time_kst"]
BASE_DAILY_US = BASE_IND["engine"]["stale_profiles"]["mobile_daily"]["daily_us"]


# -----------------------------------------------------------------------------
# window context (fixture-derived, constant across all candidates — computed once)
# -----------------------------------------------------------------------------


@dataclass(frozen=True)
class WindowCtx:
    grid: list[date]
    anchor_date: date | None  # F-6 러닝피크 기준 최대낙폭일 (양성 창만)


def _max_drawdown_day(df: pd.DataFrame, window) -> date | None:
    close = R.series_values(df, "KRX:1001", "close")
    close = close[(close.index >= window.start) & (close.index <= window.end)]
    if close.empty:
        return None
    peak = close.cummax()
    dd = (peak - close) / peak
    return dd.idxmax()


def build_window_ctx() -> dict[str, WindowCtx]:
    out: dict[str, WindowCtx] = {}
    for wid, window in ALL_WINDOWS.items():
        path = R.FIXTURES_DIR / f"{wid}.parquet"
        df = pd.read_parquet(path) if path.exists() else pd.DataFrame()
        grid = R.trading_days(df, window)
        anchor = _max_drawdown_day(df, window) if window.kind == "positive" else None
        out[wid] = WindowCtx(grid=grid, anchor_date=anchor)
    return out


WINDOW_CTX = build_window_ctx()


# -----------------------------------------------------------------------------
# candidate config builders (dims ①③④ + replay confirm-time)
# -----------------------------------------------------------------------------


def with_usdkrw(base_ind: dict, thresholds: dict[str, float]) -> dict:
    ind = copy.deepcopy(base_ind)
    for item in ind["indicators"]:
        if item["id"] == "usdkrw_z":
            item["thresholds"] = dict(thresholds)
    return ind


def with_daily_us(ind: dict, value: str) -> dict:
    ind = copy.deepcopy(ind)
    ind["engine"]["stale_profiles"]["mobile_daily"]["daily_us"] = value
    return ind


def with_mobile_profile(base_sm: dict, params: dict[str, int]) -> dict:
    sm = copy.deepcopy(base_sm)
    sm["profiles"]["mobile_daily"] = {
        "tick": base_sm["profiles"]["mobile_daily"]["tick"],
        "promote_sustain_ticks": params["promote_sustain_ticks"],
        "demote_below_ticks": params["demote_below_ticks"],
        "min_dwell_ticks": params["min_dwell_ticks"],
        "reentry_cooldown_ticks": params["reentry_cooldown_ticks"],
    }
    return sm


def with_confirm_time(base_rp: dict, confirm_time_kst: str) -> dict:
    rp = copy.deepcopy(base_rp)
    rp["profiles"]["mobile_daily"]["confirm_time_kst"] = confirm_time_kst
    return rp


# -----------------------------------------------------------------------------
# dimension candidate generators (grid values read from sweep.yaml only)
# -----------------------------------------------------------------------------


def dim1_candidates() -> list[dict[str, float]]:
    g = D["usdkrw_thresholds"]["grid"]
    return [
        {"watch": w, "warn": wa, "crit": c}
        for w, wa, c in product(g["watch"], g["warn"], g["crit"])
        if w < wa < c
    ]


def dim4_candidates() -> list[str]:
    return list(D["stale_windows"]["mobile_daily"]["grid"]["daily_us"])


def dim3_candidates() -> list[dict[str, Any]]:
    """sweep.yaml dedupe.collapse_inert_min_dwell(D-25 O3-1) 적용 — 144 -> 112."""
    g = D["mobile_daily_profile"]["grid"]
    seen: set[tuple] = set()
    out: list[dict[str, Any]] = []
    for p, de, dw, co, cf in product(
        g["promote_sustain_ticks"],
        g["demote_below_ticks"],
        g["min_dwell_ticks"],
        g["reentry_cooldown_ticks"],
        g["confirm_time_kst"],
    ):
        dedupe_dw = max(de, dw)
        key = (p, de, dedupe_dw, co, cf)
        if key in seen:
            continue
        seen.add(key)
        out.append(
            {
                "promote_sustain_ticks": p,
                "demote_below_ticks": de,
                "min_dwell_ticks": dw,
                "reentry_cooldown_ticks": co,
                "confirm_time_kst": cf,
            }
        )
    return out


# -----------------------------------------------------------------------------
# run + golden check (backtest/test_golden.py와 동일 의미 — 재구현이 아니라 같은 yaml을
# 읽어 같은 비교를 한다, sweep.yaml golden_constraint 주석 규율)
# -----------------------------------------------------------------------------


def run_candidate(
    tmp_dir: Path, ind: dict, sm: dict, rp: dict, window_ids: list[str]
) -> dict[str, Any]:
    ip, sp, rpp = tmp_dir / "cand_indicators.yaml", tmp_dir / "cand_statemachine.yaml", tmp_dir / "cand_replay.yaml"
    _write_yaml(ind, ip)
    _write_yaml(sm, sp)
    _write_yaml(rp, rpp)
    return R.run_replay(
        list(PROFILES),
        window_ids,
        indicators_path=ip,
        statemachine_path=sp,
        replay_path=rpp,
    )


def golden_pass(result: dict[str, Any], sm: dict[str, Any]) -> tuple[bool, str]:
    schedule_times = R.load_schedule_times(sm)
    sp = GOLDEN_SERVER["positive"]
    labels = schedule_times[sp["check_tick"]]
    assert len(labels) == 1, f"{sp['check_tick']}: expected exactly one tick time"
    label = labels[0].strftime("%H:%M")

    ticks = result["windows"][sp["window_id"]]["server_intraday"]["ticks"]
    hit = next(
        (t for t in ticks if t["date"] == sp["check_date"] and t["kst_time"] == label),
        None,
    )
    if hit is None:
        return False, "server_positive_check_tick_missing"
    if _ORDER.index(hit["phase"]) < _ORDER.index(sp["min_phase"]):
        return False, f"server_positive_phase_{hit['phase']}"
    if not set(sp["required_fired_axes"]) <= set(hit["fired_axes"]):
        return False, f"server_positive_axes_{hit['fired_axes']}"

    sn = GOLDEN_SERVER["negative"]
    for t in result["windows"][sn["window_id"]]["server_intraday"]["ticks"]:
        if _ORDER.index(t["phase"]) > _ORDER.index(sn["max_phase"]):
            return False, f"server_negative_{t['date']}_{t['phase']}"

    for wid, gm_window in GOLDEN_MOBILE["windows"].items():
        actual = result["windows"][wid]["mobile_daily"]["ticks"]
        expected = gm_window["ticks"]
        if len(actual) != len(expected):
            return False, f"mobile_{wid}_tick_count"
        for a, e in zip(actual, expected, strict=True):
            if a["phase"] != e["phase"]:
                return False, f"mobile_{wid}_{a['date']}_phase_{a['phase']}!={e['phase']}"
            tol = COMPOSITE_REL_TOL * max(1.0, abs(e["composite"]))
            if abs(a["composite"] - e["composite"]) > tol:
                return False, f"mobile_{wid}_{a['date']}_composite"
            if abs(a["coverage"] - e["coverage"]) > COMPOSITE_REL_TOL:
                return False, f"mobile_{wid}_{a['date']}_coverage"
            if a["fired_axes"] != e["fired_axes"]:
                return False, f"mobile_{wid}_{a['date']}_axes"
    return True, "-"


# -----------------------------------------------------------------------------
# per-window-profile derived stats + gate/rank
# -----------------------------------------------------------------------------


def window_profile_stats(
    result: dict[str, Any], window_id: str, profile: str, ctx: WindowCtx
) -> dict[str, Any]:
    data = result["windows"][window_id][profile]
    ticks = data["ticks"]
    summary = data["summary"]
    amber_ticks = sum(1 for t in ticks if t["phase"] == "AMBER")
    orange_or_above_ticks = sum(1 for t in ticks if t["phase"] in ("ORANGE", "RED"))
    max_phase = summary["max_phase"]
    detected = max_phase is not None and _ORDER.index(max_phase) >= _ORDER.index("ORANGE")
    lead = None
    fo = summary["first_orange_or_above_date"]
    if fo is not None and ctx.anchor_date is not None:
        try:
            i_anchor = ctx.grid.index(ctx.anchor_date)
            i_fo = ctx.grid.index(date.fromisoformat(fo))
            lead = i_anchor - i_fo
        except ValueError:
            lead = None
    return {
        "max_phase": max_phase,
        "n_transitions": summary["n_transitions"],
        "first_orange_or_above_date": fo,
        "amber_ticks": amber_ticks,
        "orange_or_above_ticks": orange_or_above_ticks,
        "detected": detected,
        "lead": lead,
    }


def compute_gates(stats: dict[tuple[str, str], dict[str, Any]]) -> dict[str, Any]:
    per_profile: dict[str, Any] = {}
    for profile in PROFILES:
        detection_pass = all(
            stats[(wid, profile)]["detected"] for wid in POS_IDS_FOR_DETECTION
        )
        leads = [
            stats[(wid, profile)]["lead"]
            for wid in POS_IDS
            if stats[(wid, profile)]["lead"] is not None
        ]
        lead_median = statistics.median(leads) if leads else None
        lead_sum = sum(leads) if leads else None
        leadtime_pass = lead_median is not None and lead_median >= LEAD_MEDIAN_MIN
        fp_orange_sum = sum(stats[(wid, profile)]["orange_or_above_ticks"] for wid in NEG_IDS)
        fp_amber_sum = sum(stats[(wid, profile)]["amber_ticks"] for wid in NEG_IDS)
        fp_pass = fp_orange_sum <= FP_ORANGE_MAX and fp_amber_sum <= FP_AMBER_MAX
        pos_transitions_max = max(
            (stats[(wid, profile)]["n_transitions"] for wid in POS_IDS), default=0
        )
        neg_transitions_max = max(
            (stats[(wid, profile)]["n_transitions"] for wid in NEG_IDS), default=0
        )
        flap_pass_raw = pos_transitions_max <= FLAP_POS_MAX and neg_transitions_max <= FLAP_NEG_MAX
        flap_gated = profile not in FLAP_EXCEPT_PROFILES
        per_profile[profile] = {
            "detection_pass": detection_pass,
            "lead_median": lead_median,
            "lead_sum": lead_sum,
            "leadtime_pass": leadtime_pass,
            "fp_orange_sum": fp_orange_sum,
            "fp_amber_sum": fp_amber_sum,
            "fp_pass": fp_pass,
            "pos_transitions_max": pos_transitions_max,
            "neg_transitions_max": neg_transitions_max,
            "flap_pass_raw": flap_pass_raw,
            "flap_gated": flap_gated,
            "gate_pass": detection_pass
            and leadtime_pass
            and fp_pass
            and (flap_pass_raw or not flap_gated),
        }
    overall_pass = all(per_profile[p]["gate_pass"] for p in PROFILES)
    return {"per_profile": per_profile, "overall_pass": overall_pass}


def reduced_gate_pass(gates: dict[str, Any]) -> bool:
    """S1/S2/S3(③ baseline 고정 구간) 전용 완화 게이트 — 모듈 docstring 방법론 노트 참조.
    flapping을 제외한 detection ∧ leadtime ∧ false_positive만 본다."""
    return all(
        gates["per_profile"][p]["detection_pass"]
        and gates["per_profile"][p]["leadtime_pass"]
        and gates["per_profile"][p]["fp_pass"]
        for p in PROFILES
    )


def evaluate_candidate(tmp_dir: Path, ind: dict, sm: dict, rp: dict) -> dict[str, Any]:
    golden_result = run_candidate(tmp_dir, ind, sm, rp, GOLDEN_IDS)
    ok, reason = golden_pass(golden_result, sm)
    if not ok:
        return {"golden_pass": False, "golden_reason": reason}

    full_result = run_candidate(tmp_dir, ind, sm, rp, SWEEP_IDS)
    stats = {
        (wid, profile): window_profile_stats(full_result, wid, profile, WINDOW_CTX[wid])
        for wid in SWEEP_IDS
        for profile in PROFILES
    }
    gates = compute_gates(stats)
    mobile_signature = tuple(
        (
            stats[(wid, "mobile_daily")]["max_phase"],
            stats[(wid, "mobile_daily")]["n_transitions"],
            stats[(wid, "mobile_daily")]["first_orange_or_above_date"],
        )
        for wid in SWEEP_IDS
    )
    w2026_lead_report = None
    if "w2026_structural" in POS_IDS:
        w2026_first = {
            profile: stats[("w2026_structural", profile)]["first_orange_or_above_date"]
            for profile in PROFILES
        }
        w2026_lead_report = {
            "first_orange_or_above_date": w2026_first,
            "reachable_before_2026_07_28": {
                p: (w2026_first[p] is not None and w2026_first[p] < "2026-07-28")
                for p in PROFILES
            },
        }
    return {
        "golden_pass": True,
        "stats": stats,
        "gates": gates,
        "mobile_selection_signature": mobile_signature,
        "w2026_lead_report": w2026_lead_report,
    }


# -----------------------------------------------------------------------------
# simplicity (rank 3순위) — 현행 0.2.0 대비 변경 파라미터 개수 -> 소수 자릿수 합 ->
# 정규화 yaml 사전식
# -----------------------------------------------------------------------------


def _decimal_places(x: float) -> int:
    s = repr(float(x))
    if "." not in s:
        return 0
    frac = s.split(".")[1].rstrip("0")
    return len(frac)


def diff_spec(
    usdkrw: dict[str, float] | None = None,
    daily_us: str | None = None,
    mobile_profile: dict[str, int] | None = None,
    confirm_time: str | None = None,
) -> dict[str, Any]:
    return {
        "usdkrw": usdkrw if usdkrw is not None else dict(BASE_USDKRW),
        "daily_us": daily_us if daily_us is not None else BASE_DAILY_US,
        "mobile_profile": mobile_profile if mobile_profile is not None else dict(BASE_MOBILE_PROFILE),
        "confirm_time_kst": confirm_time if confirm_time is not None else BASE_CONFIRM_TIME,
    }


def changed_param_count(spec: dict[str, Any]) -> int:
    n = sum(1 for k in ("watch", "warn", "crit") if spec["usdkrw"][k] != BASE_USDKRW[k])
    n += sum(
        1
        for k, v in spec["mobile_profile"].items()
        if v != BASE_MOBILE_PROFILE[k]
    )
    n += int(spec["confirm_time_kst"] != BASE_CONFIRM_TIME)
    n += int(spec["daily_us"] != BASE_DAILY_US)
    return n


def total_decimal_places(spec: dict[str, Any]) -> int:
    return sum(
        _decimal_places(spec["usdkrw"][k])
        for k in ("watch", "warn", "crit")
        if spec["usdkrw"][k] != BASE_USDKRW[k]
    )


def canonical_key(spec: dict[str, Any]) -> str:
    return yaml.safe_dump(spec, sort_keys=True, default_flow_style=True)


def simplicity_key(spec: dict[str, Any]) -> tuple[int, int, str]:
    return (changed_param_count(spec), total_decimal_places(spec), canonical_key(spec))


def rank_sort_key(entry: dict[str, Any]) -> tuple:
    gates = entry["eval"]["gates"]["per_profile"]
    fp_total = (
        sum(gates[p]["fp_orange_sum"] for p in PROFILES),
        sum(gates[p]["fp_amber_sum"] for p in PROFILES),
    )
    mean_median = sum(gates[p]["lead_median"] for p in PROFILES) / len(PROFILES)
    mean_sum = sum(gates[p]["lead_sum"] for p in PROFILES) / len(PROFILES)
    return (fp_total, (-mean_median, -mean_sum), entry["simplicity"])


def top_k_signature_reps(ranked: list[dict[str, Any]], k: int) -> list[dict[str, Any]]:
    """MR2-2: top_k = 랭킹 상위 k개 후보가 아니라 선정 서명 기준 서로 다른 상위 k개 등가류."""
    seen: set[tuple] = set()
    reps: list[dict[str, Any]] = []
    for entry in ranked:
        sig = entry["eval"]["mobile_selection_signature"]
        if sig in seen:
            continue
        seen.add(sig)
        reps.append(entry)
        if len(reps) >= k:
            break
    return reps


def count_equivalence_classes(golden_pass_entries: list[dict[str, Any]]) -> int:
    return len({e["eval"]["mobile_selection_signature"] for e in golden_pass_entries})


def _rank_golden_pool(golden_entries: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], bool]:
    """완화 게이트 통과분이 있으면 그걸로, 없으면(방법론 노트) golden-pass 전체로 rank.
    반환: (rank된 리스트, 완화게이트 사용 여부)"""
    reduced_ok = [e for e in golden_entries if reduced_gate_pass(e["eval"]["gates"])]
    if reduced_ok:
        return sorted(reduced_ok, key=rank_sort_key), True
    return sorted(golden_entries, key=rank_sort_key), False


# -----------------------------------------------------------------------------
# stage runners
# -----------------------------------------------------------------------------


def run_s1(tmp_dir: Path) -> dict[str, Any]:
    entries = []
    for t in dim1_candidates():
        ind = with_usdkrw(BASE_IND, t)
        ev = evaluate_candidate(tmp_dir, ind, BASE_SM, BASE_RP)
        spec = diff_spec(usdkrw=t)
        entries.append(
            {"usdkrw": t, "eval": ev, "simplicity": simplicity_key(spec) if ev["golden_pass"] else None}
        )
    golden_ok = [e for e in entries if e["eval"]["golden_pass"]]
    ranked, used_reduced_gate = _rank_golden_pool(golden_ok)
    top = top_k_signature_reps(ranked, TOP_K)
    return {
        "entries": entries,
        "golden_pass_count": len(golden_ok),
        "golden_fail_count": len(entries) - len(golden_ok),
        "equivalence_classes_full_summary": None,  # computed separately below for [T7] parity
        "equivalence_classes_selection_signature": count_equivalence_classes(golden_ok),
        "used_reduced_gate": used_reduced_gate,
        "ranked": ranked,
        "top_k": top,
    }


def run_s2(tmp_dir: Path) -> dict[str, Any]:
    entries = []
    for v in dim4_candidates():
        ind = with_daily_us(BASE_IND, v)
        ev = evaluate_candidate(tmp_dir, ind, BASE_SM, BASE_RP)
        spec = diff_spec(daily_us=v)
        entries.append(
            {"daily_us": v, "eval": ev, "simplicity": simplicity_key(spec) if ev["golden_pass"] else None}
        )
    golden_ok = [e for e in entries if e["eval"]["golden_pass"]]
    ranked, used_reduced_gate = _rank_golden_pool(golden_ok)
    top = top_k_signature_reps(ranked, TOP_K)
    return {
        "entries": entries,
        "golden_pass_count": len(golden_ok),
        "golden_fail_count": len(entries) - len(golden_ok),
        "equivalence_classes_selection_signature": count_equivalence_classes(golden_ok),
        "used_reduced_gate": used_reduced_gate,
        "ranked": ranked,
        "top_k": top,
    }


def _cross_entry(tmp_dir: Path, usdkrw: dict, daily_us: str) -> dict[str, Any]:
    ind = with_daily_us(with_usdkrw(BASE_IND, usdkrw), daily_us)
    ev = evaluate_candidate(tmp_dir, ind, BASE_SM, BASE_RP)
    spec = diff_spec(usdkrw=usdkrw, daily_us=daily_us)
    return {
        "usdkrw": usdkrw,
        "daily_us": daily_us,
        "eval": ev,
        "simplicity": simplicity_key(spec) if ev["golden_pass"] else None,
    }


def run_s3(tmp_dir: Path, s1: dict[str, Any], s2: dict[str, Any]) -> dict[str, Any]:
    cross_entries = [
        _cross_entry(tmp_dir, e1["usdkrw"], e2["daily_us"])
        for e1 in s1["top_k"]
        for e2 in s2["top_k"]
    ]
    golden_ok = [e for e in cross_entries if e["eval"]["golden_pass"]]
    ranked, _ = _rank_golden_pool(golden_ok)
    cross_winner = ranked[0] if ranked else None

    s1_indiv_pool = s1["ranked"] if s1["ranked"] else [e for e in s1["entries"] if e["eval"]["golden_pass"]]
    s2_indiv_pool = s2["ranked"] if s2["ranked"] else [e for e in s2["entries"] if e["eval"]["golden_pass"]]
    s1_best = s1_indiv_pool[0] if s1_indiv_pool else None
    s2_best = s2_indiv_pool[0] if s2_indiv_pool else None

    agrees = (
        cross_winner is not None
        and s1_best is not None
        and s2_best is not None
        and cross_winner["usdkrw"] == s1_best["usdkrw"]
        and cross_winner["daily_us"] == s2_best["daily_us"]
    )

    escalated = False
    escalation_entries: list[dict[str, Any]] | None = None
    winner = cross_winner
    if not agrees:
        escalated = True
        escalation_entries = [
            _cross_entry(tmp_dir, t, v) for t in dim1_candidates() for v in dim4_candidates()
        ]
        esc_golden_ok = [e for e in escalation_entries if e["eval"]["golden_pass"]]
        esc_ranked, _ = _rank_golden_pool(esc_golden_ok)
        winner = esc_ranked[0] if esc_ranked else cross_winner

    return {
        "cross_entries": cross_entries,
        "cross_winner": cross_winner,
        "s1_individual_best": s1_best,
        "s2_individual_best": s2_best,
        "agrees": agrees,
        "escalated": escalated,
        "escalation_evaluations": len(escalation_entries) if escalation_entries else 0,
        "winner": winner,
    }


def run_s4(tmp_dir: Path, s3_winner: dict[str, Any]) -> dict[str, Any]:
    final_ind = with_daily_us(with_usdkrw(BASE_IND, s3_winner["usdkrw"]), s3_winner["daily_us"])
    entries = []
    for params in dim3_candidates():
        sm = with_mobile_profile(BASE_SM, params)
        rp = with_confirm_time(BASE_RP, params["confirm_time_kst"])
        ev = evaluate_candidate(tmp_dir, final_ind, sm, rp)
        spec = diff_spec(
            usdkrw=s3_winner["usdkrw"],
            daily_us=s3_winner["daily_us"],
            mobile_profile={k: params[k] for k in BASE_MOBILE_PROFILE},
            confirm_time=params["confirm_time_kst"],
        )
        entries.append(
            {
                "params": params,
                "eval": ev,
                "simplicity": simplicity_key(spec) if ev["golden_pass"] else None,
            }
        )
    golden_ok = [e for e in entries if e["eval"]["golden_pass"]]
    full_gate_ok = [e for e in golden_ok if e["eval"]["gates"]["overall_pass"]]
    eligible = [
        e for e in full_gate_ok if e["params"]["confirm_time_kst"] >= CONFIRM_MIN
    ]
    ranked = sorted(eligible, key=rank_sort_key)
    winner = ranked[0] if ranked else None
    return {
        "final_ind_usdkrw": s3_winner["usdkrw"],
        "final_ind_daily_us": s3_winner["daily_us"],
        "entries": entries,
        "golden_pass_count": len(golden_ok),
        "golden_fail_count": len(entries) - len(golden_ok),
        "full_gate_pass_count": len(full_gate_ok),
        "confirm_time_rejected_count": len(full_gate_ok) - len(eligible),
        "ranked": ranked,
        "winner": winner,
    }


def run_s5(tmp_dir: Path, final_ind: dict, final_sm: dict, final_rp: dict) -> dict[str, Any]:
    all_ids = list(ALL_WINDOWS.keys())
    result = run_candidate(tmp_dir, final_ind, final_sm, final_rp, all_ids)
    ok, reason = golden_pass(result, final_sm)
    summaries = {
        wid: {p: result["windows"][wid][p]["summary"] for p in PROFILES} for wid in all_ids
    }
    return {
        "windows_evaluated": all_ids,
        "holdout_windows_included": HOLDOUT_IDS,
        "golden_pass": ok,
        "golden_reason": reason,
        "summaries": summaries,
    }


# -----------------------------------------------------------------------------
# JSON-safety
# -----------------------------------------------------------------------------


def _redact_ind(entry: dict[str, Any]) -> dict[str, Any]:
    """entries에서 eval의 원시 tick 배열 등 대용량 필드 없이 JSON 직렬화 가능한 요약만
    남긴다(stats/gates/signature는 이미 원시 dict라 그대로 직렬화 가능; ticks는 애초에
    entry에 저장하지 않았으므로 그대로 반환)."""
    return entry


def to_jsonable(obj: Any) -> Any:
    if isinstance(obj, dict):
        return {str(k): to_jsonable(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple, set)):
        return [to_jsonable(v) for v in obj]
    return obj


# -----------------------------------------------------------------------------
# main
# -----------------------------------------------------------------------------


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # K-xx cp949 콘솔 함정
    t0 = _time_module.monotonic()
    tmp_dir = Path(tempfile.mkdtemp(prefix="bt03_sweep_"))

    print(f"SWEEP windows (holdout excluded, {len(SWEEP_IDS)}): {SWEEP_IDS}")
    print(f"HOLDOUT windows (excluded from selection): {HOLDOUT_IDS}")

    print("\n[S1] usdkrw_thresholds ...")
    s1 = run_s1(tmp_dir)
    print(
        f"  evaluations={len(s1['entries'])} golden_pass={s1['golden_pass_count']} "
        f"golden_fail={s1['golden_fail_count']} "
        f"selection_signature_classes={s1['equivalence_classes_selection_signature']} "
        f"used_reduced_gate={s1['used_reduced_gate']} top_k={len(s1['top_k'])}"
    )

    print("\n[S2] stale_windows.mobile_daily.daily_us ...")
    s2 = run_s2(tmp_dir)
    print(
        f"  evaluations={len(s2['entries'])} golden_pass={s2['golden_pass_count']} "
        f"golden_fail={s2['golden_fail_count']} "
        f"selection_signature_classes={s2['equivalence_classes_selection_signature']} "
        f"used_reduced_gate={s2['used_reduced_gate']} top_k={len(s2['top_k'])}"
    )

    print("\n[S3] interaction check (usdkrw x daily_us) ...")
    s3 = run_s3(tmp_dir, s1, s2)
    print(
        f"  cross_evaluations={len(s3['cross_entries'])} agrees={s3['agrees']} "
        f"escalated={s3['escalated']} escalation_evaluations={s3['escalation_evaluations']}"
    )
    if s3["winner"] is None:
        print("  FATAL: no golden+gate-eligible candidate in S3 — halting per brief "
              "(설계 임의 수정 금지, 원인 규명 필요)")
        return 1

    print("\n[S4] mobile_daily_profile (S3 winner held) ...")
    s4 = run_s4(tmp_dir, s3["winner"])
    print(
        f"  evaluations={len(dim3_candidates())} golden_pass={s4['golden_pass_count']} "
        f"golden_fail={s4['golden_fail_count']} full_gate_pass={s4['full_gate_pass_count']} "
        f"confirm_time_rejected={s4['confirm_time_rejected_count']}"
    )
    if s4["winner"] is None:
        print("  FATAL: no gate-eligible mobile_daily_profile candidate — halting per "
              "brief (설계 임의 수정 금지, 원인 규명 필요)")
        return 1

    winner_params = s4["winner"]["params"]
    final_ind = with_daily_us(with_usdkrw(BASE_IND, s3["winner"]["usdkrw"]), s3["winner"]["daily_us"])
    final_sm = with_mobile_profile(BASE_SM, winner_params)
    final_rp = with_confirm_time(BASE_RP, winner_params["confirm_time_kst"])

    print("\n[S5] final confirmation (9 windows incl. holdout, not fed back to selection) ...")
    s5 = run_s5(tmp_dir, final_ind, final_sm, final_rp)
    print(f"  golden_pass={s5['golden_pass']} ({s5['golden_reason']})")

    elapsed = _time_module.monotonic() - t0
    print(f"\nTotal evaluations executed (incl. S3 escalation if any); wall time {elapsed:.1f}s")

    selection = {
        "usdkrw_thresholds": s3["winner"]["usdkrw"],
        "daily_us": s3["winner"]["daily_us"],
        "mobile_daily_profile": {
            k: winner_params[k] for k in BASE_MOBILE_PROFILE
        },
        "confirm_time_kst": winner_params["confirm_time_kst"],
        "changed_param_count": changed_param_count(
            diff_spec(
                usdkrw=s3["winner"]["usdkrw"],
                daily_us=s3["winner"]["daily_us"],
                mobile_profile={k: winner_params[k] for k in BASE_MOBILE_PROFILE},
                confirm_time=winner_params["confirm_time_kst"],
            )
        ),
        "result_label": SW["selection"]["reporting"]["result_label"],
        "rank1_false_positive_degenerate": True,  # F-8, 실측 재확인 아래 T1/T2 참조
    }
    print("\nSELECTION:")
    print(json.dumps(selection, ensure_ascii=False, indent=2))

    SWEEP_RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    out = {
        "schema": "backtest-sweep-result/1",
        "generated_at": datetime.now(UTC).isoformat(),
        "note": "근사-PIT — C1에서 실측 확정 (docs/BACKTEST_PLAN.md §5)",
        "baseline_registry_version": SW["meta"]["baseline_registry_version"],
        "target_registry_version": SW["meta"]["target_registry_version"],
        "windows": {
            "sweep_ids": SWEEP_IDS,
            "holdout_ids": HOLDOUT_IDS,
            "positive_ids": POS_IDS,
            "negative_ids": NEG_IDS,
            "detection_except_windows": sorted(DETECTION_EXCEPT_WINDOWS),
        },
        "stages": {
            "S1": to_jsonable(s1),
            "S2": to_jsonable(s2),
            "S3": to_jsonable(s3),
            "S4": to_jsonable(s4),
            "S5": to_jsonable(s5),
        },
        "selection": selection,
        "wall_time_seconds": elapsed,
    }
    out_path = SWEEP_RESULTS_DIR / "sweep_result.json"
    out_path.write_text(json.dumps(out, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    print(f"\nsweep result written: {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
