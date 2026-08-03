"""backtest/run_f06_variants.py — BT-04 Stage B: F-06(해상도) 대응안 3종 실측 시뮬레이션
(MT0-06, AD-7~11).

    uv run python backtest/run_f06_variants.py

그리드·게이트·랭킹 리터럴은 0개다 — 전부 backtest/f06_variants.yaml(selection 블록)에서
로드한다(CLAUDE.md §1, 저널 §6 4항 "f06_variants.yaml만 읽는다" 문언 그대로). "2026-07-28"
마감일은 selection.rank.steps[2].first_orange_before(date 타입, R5 비구속 권고 "구조화
키로 승격" 이행 — 저널 §8.7-2)에서 로드한다. 초판은 이 값을 f06_variants.yaml에 구조화
필드가 없다는 이유로 backtest/sweep.yaml에서 대신 읽었으나, 이는 위 문언과 어긋나는
크로스 파일 결합이었다(aaa-critic Stage B 라운드 2 S-3) — f06_variants.yaml 자체에
정본 키를 추가해 해소했다(sweep.yaml의 동일 값은 그대로 두되 이 스크립트는 더 이상
참조하지 않는다).

설계 근거: docs/journal/2026-08-03_MT0-06_bt04_design.md §3(설계)·§4(비교 프로토콜)·
§6(구현 요건) — AD-7~11로 확정. 후보값·게이트 구성은 Stage A(5라운드 aaa-critic PASS)의
확정 산출물이며 이 스크립트는 그 확정을 실행할 뿐 재설계하지 않는다.

근사-PIT — C1에서 실측 확정(docs/BACKTEST_PLAN.md §5). 여기서 산출하는 수치는 설계 저널
§3의 산술 투사에 대한 **실측 대조**다(AD-9(e)) — 산술과 다르면 그 차이를 그대로 기록한다
(둘을 조용히 일치시키지 않는다).

실행은 전부 샌드박스(임시 디렉터리 candidate configs)로만 이뤄진다 — configs/*.yaml
프로덕션 무반영, backtest/results/metrics.json 불변(실행 전후 SHA-256 대조로 증명). 산출은
backtest/results/f06/f06_variants_result.json.

골든 대조는 backtest.run_sweep.golden_pass()를 그대로 재사용한다(재구현 금지, AD-7 F-7
정정: 이 대조의 측정 주체는 이 스크립트 내부의 샌드박스 실행이며, `pytest
backtest/test_golden.py`(프로덕션 configs 대상)와는 별개 — 후자는 이 스크립트 실행
전후로 계속 green이어야 한다).

게이트 구성(AD-7 §0.3, 사전 명문화):
  - A(선정 후보, 3값 전부): 골든 사전 필터 = 하드 게이트(위반 시 성능 계산 생략, 즉시
    탈락) + mobile 플래핑(9창, AD-11 i — 하드) 둘 다 gate_pass 판정에 들어간다.
  - B(계량 전용, 1값): 골든 사전 필터 **미적용** — 실패해도 계속 실행하고 결과를 그대로
    기록한다(§3-B(b) 산술 -1.84의 실측 대조가 목적).
  - C(표시 파생값): 리플레이 자체를 건드리지 않으므로 게이트 무관.
"""

from __future__ import annotations

import copy
import hashlib
import json
import re
import sys
import tempfile
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import yaml

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from backtest import run_replay as R
from backtest import run_sweep as RS

REPO_ROOT = Path(__file__).resolve().parent.parent
F06_YAML_PATH = REPO_ROOT / "backtest" / "f06_variants.yaml"
METRICS_JSON_PATH = REPO_ROOT / "backtest" / "results" / "metrics.json"
F06_RESULTS_DIR = REPO_ROOT / "backtest" / "results" / "f06"

_PHASE_ORDER = ["GREEN", "AMBER", "ORANGE", "RED"]
_PROFILES = ("server_intraday", "mobile_daily")

# -----------------------------------------------------------------------------
# SSOT loads — f06_variants.yaml is the only source of candidate grids/gate/rank
# constants for this script (CLAUDE.md §1, journal §6 4항). No other yaml is read
# for constants (aaa-critic Stage B round 2 S-3 — sweep.yaml cross-reference removed).
# -----------------------------------------------------------------------------


def _load_yaml(path: Path) -> dict[str, Any]:
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)


F06 = _load_yaml(F06_YAML_PATH)
_SEL = F06["selection"]
_GATE = _SEL["gate"]
_RANK = _SEL["rank"]

_GOLDEN_APPLIES_TO = set(_GATE["golden"]["applies_to"])
_FLAP_POS_MAX = _GATE["flapping"]["positive_transitions_max"]
_FLAP_NEG_MAX = _GATE["flapping"]["negative_transitions_max"]
_FLAP_APPLIES_TO = set(_GATE["flapping"]["applies_to"])
_RANK_APPLIES_TO = set(_RANK["applies_to"])

_W2026_RANK_STEP = next(s for s in _RANK["steps"] if s["key"] == "w2026_target")
W2026_DEADLINE_STR: str = _W2026_RANK_STEP["first_orange_before"].isoformat()  # "2026-07-28"

ALL_WINDOWS = RS.ALL_WINDOWS  # 9창 전체(홀드아웃 포함) — BT-04 §1.1
ALL_WINDOW_IDS = list(ALL_WINDOWS)
POS_IDS = [wid for wid, w in ALL_WINDOWS.items() if w.kind == "positive"]  # 7 (incl. w2015 H)
NEG_IDS = [wid for wid, w in ALL_WINDOWS.items() if w.kind == "negative"]  # 2 (incl. w2023_11 H)
OTHER_POS_IDS = [wid for wid in POS_IDS if wid != "w2026_structural"]

_RED_RANGE_RE = re.compile(r"\[(\d+(?:\.\d+)?)")


def red_breakpoint() -> float:
    """§3-C 분기점(80.0)을 f06_variants.yaml의 candidate_split.red_2_range 문자열
    ("[80, 100]")에서 파싱한다 — Stage A가 종결한 f06_variants.yaml에 새 숫자 키를
    추가하지 않고도(그 파일은 5라운드 aaa-critic PASS로 닫힌 SSOT다) 코드 리터럴을
    피한다(CLAUDE.md §1)."""
    range_str = F06["variants"]["C_red_sublevel"]["candidate_split"]["red_2_range"]
    m = _RED_RANGE_RE.search(range_str)
    if not m:
        raise ValueError(f"cannot parse red_2_range: {range_str!r}")
    return float(m.group(1))


def red_sublevel(composite: float, breakpoint: float) -> str:
    """AD-7 옵션 C — 표시 계층 파생 함수. phase=="RED"인 틱에서만 의미가 있다(호출부
    책임 — engine_ref는 무변경, 이 함수는 순수 매핑일 뿐 판정에 재입력되지 않는다)."""
    return "RED-2" if composite >= breakpoint else "RED-1"


# -----------------------------------------------------------------------------
# candidate config builders (target keys per f06_variants.yaml variants.*.target)
# -----------------------------------------------------------------------------


def with_kospi_extreme_threshold(base_ind: dict, extreme_pct: float) -> dict:
    """옵션 A/B 공통: indicators[id=kospi_drawdown].thresholds.extreme."""
    ind = copy.deepcopy(base_ind)
    for item in ind["indicators"]:
        if item["id"] == "kospi_drawdown":
            item["thresholds"] = dict(item["thresholds"], extreme=extreme_pct)
    return ind


def with_kospi_max_severity_4(base_ind: dict, extreme_pct: float) -> dict:
    """옵션 B 전용: max_severity=4를 추가로 얹는다(옵션 A의 sandbox는 이 필드를 건드리지
    않으므로 severity 사다리가 3-tier로 남는다 — engine_ref/scoring.py 모듈 docstring의
    격리 불변식)."""
    ind = with_kospi_extreme_threshold(base_ind, extreme_pct)
    for item in ind["indicators"]:
        if item["id"] == "kospi_drawdown":
            item["max_severity"] = 4
    return ind


def with_or_any_extreme_orange(base_sm: dict) -> dict:
    """옵션 A 전용: upgrade.ORANGE.or_any_extreme = true. AD-10 — upgrade.RED는 대상 아님."""
    sm = copy.deepcopy(base_sm)
    sm["upgrade"]["rules"]["ORANGE"] = dict(
        sm["upgrade"]["rules"]["ORANGE"], or_any_extreme=True
    )
    return sm


# -----------------------------------------------------------------------------
# baseline (0.3.0-rc as-is, in-process, no file writes — Stage A §0.1/bt04_repro.py 패턴)
# -----------------------------------------------------------------------------


def compute_baseline() -> tuple[dict[str, Any], dict[tuple[str, str], dict[str, Any]]]:
    result = R.run_replay(list(_PROFILES), ALL_WINDOW_IDS)
    stats = {
        (wid, profile): RS.window_profile_stats(result, wid, profile, RS.WINDOW_CTX[wid])
        for wid in ALL_WINDOW_IDS
        for profile in _PROFILES
    }
    return result, stats


# -----------------------------------------------------------------------------
# shared metric helpers (baseline-relative — need BASELINE_STATS as reference)
# -----------------------------------------------------------------------------


def mobile_flapping_pass(stats: dict[tuple[str, str], dict[str, Any]]) -> bool:
    pos_max = max(stats[(wid, "mobile_daily")]["n_transitions"] for wid in POS_IDS)
    neg_max = max(stats[(wid, "mobile_daily")]["n_transitions"] for wid in NEG_IDS)
    return pos_max <= _FLAP_POS_MAX and neg_max <= _FLAP_NEG_MAX


def w2026_target(stats: dict[tuple[str, str], dict[str, Any]]) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for profile in _PROFILES:
        fo = stats[("w2026_structural", profile)]["first_orange_or_above_date"]
        out[profile] = {
            "first_orange_or_above_date": fo,
            "detected": fo is not None,
            "before_0728": fo is not None and fo < W2026_DEADLINE_STR,
        }
    return out


def other_positive_windows_damage(
    stats: dict[tuple[str, str], dict[str, Any]],
    baseline_stats: dict[tuple[str, str], dict[str, Any]],
) -> int:
    """§4.3(3)(a) tie-break: 나머지 6양성 창(w2026 제외)의 (창,프로파일) 쌍 중 baseline
    대비 탐지 여부·첫 ORANGE 도달일이 달라진 개수(0이 "무손상")."""
    damaged = 0
    for wid in OTHER_POS_IDS:
        for profile in _PROFILES:
            b = baseline_stats[(wid, profile)]
            c = stats[(wid, profile)]
            if c["detected"] != b["detected"] or (
                c["detected"]
                and c["first_orange_or_above_date"] != b["first_orange_or_above_date"]
            ):
                damaged += 1
    return damaged


def holdout_negative_new_false_positive(
    stats: dict[tuple[str, str], dict[str, Any]],
    baseline_stats: dict[tuple[str, str], dict[str, Any]],
) -> int:
    """§4.3(3)(b) tie-break: 홀드아웃 음성창(w2023_11_rally)의 ORANGE+/AMBER 틱이 baseline
    대비 늘어난 만큼(0이 "신규 오탐 없음"). 기존 FAIL(§2.3 AMBER 18틱)은 baseline 자체에
    이미 있으므로 여기서 계량되지 않는다 — AD-8 정직성 조항(재보정 금지)과 정합."""
    wid = "w2023_11_rally"
    total = 0
    for profile in _PROFILES:
        b = baseline_stats[(wid, profile)]
        c = stats[(wid, profile)]
        total += max(0, c["orange_or_above_ticks"] - b["orange_or_above_ticks"])
        total += max(0, c["amber_ticks"] - b["amber_ticks"])
    return total


def server_delta_transitions(
    stats: dict[tuple[str, str], dict[str, Any]],
    baseline_stats: dict[tuple[str, str], dict[str, Any]],
) -> dict[str, int]:
    """AD-11 iii 보고 의무: server는 게이트 제외(AD-5 승계)이나 후보별·창별 악화량은
    보고해야 한다."""
    return {
        wid: stats[(wid, "server_intraday")]["n_transitions"]
        - baseline_stats[(wid, "server_intraday")]["n_transitions"]
        for wid in ALL_WINDOW_IDS
    }


def _stats_to_jsonable(stats: dict[tuple[str, str], dict[str, Any]]) -> dict[str, Any]:
    return {f"{wid}|{profile}": v for (wid, profile), v in stats.items()}


# -----------------------------------------------------------------------------
# variant A — threshold ladder extension (or_any_extreme, ORANGE only, AD-10)
# -----------------------------------------------------------------------------


def evaluate_variant_a(
    tmp_dir: Path,
    extreme_pct: float,
    baseline_stats: dict[tuple[str, str], dict[str, Any]],
) -> dict[str, Any]:
    ind = with_kospi_extreme_threshold(RS.BASE_IND, extreme_pct)
    sm = with_or_any_extreme_orange(RS.BASE_SM)

    golden_gated = "A_threshold_ladder_extension" in _GOLDEN_APPLIES_TO
    if golden_gated:
        golden_result = RS.run_candidate(tmp_dir, ind, sm, RS.BASE_RP, RS.GOLDEN_IDS)
        golden_ok, golden_reason = RS.golden_pass(golden_result, sm)
        if not golden_ok:
            return {
                "candidate_pct": extreme_pct,
                "golden_pass": False,
                "golden_reason": golden_reason,
                "gate_pass": False,
            }

    result = RS.run_candidate(tmp_dir, ind, sm, RS.BASE_RP, ALL_WINDOW_IDS)
    stats = {
        (wid, profile): RS.window_profile_stats(result, wid, profile, RS.WINDOW_CTX[wid])
        for wid in ALL_WINDOW_IDS
        for profile in _PROFILES
    }
    flap_ok = mobile_flapping_pass(stats) if "A_threshold_ladder_extension" in _FLAP_APPLIES_TO else True
    target = w2026_target(stats)
    other_damage = other_positive_windows_damage(stats, baseline_stats)
    new_fp = holdout_negative_new_false_positive(stats, baseline_stats)
    delta_srv = server_delta_transitions(stats, baseline_stats)

    return {
        "candidate_pct": extreme_pct,
        "golden_pass": True,
        "mobile_flapping_pass": flap_ok,
        "gate_pass": bool(flap_ok),
        "w2026_target": target,
        "other_6_positive_windows_damage": other_damage,
        "holdout_negative_new_false_positive": new_fp,
        "server_delta_transitions": delta_srv,
        "kotlin_parity_burden": F06["variants"]["A_threshold_ladder_extension"][
            "kotlin_parity_burden"
        ],
        "stats": _stats_to_jsonable(stats),
    }


def _achieved_w2026(entry: dict[str, Any]) -> bool:
    t = entry["w2026_target"]
    return all(t[p]["detected"] and t[p]["before_0728"] for p in _PROFILES)


def rank_variant_a(results: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """rank.steps 기계적 집행(§4.3): 1=하드 게이트(golden AND mobile_flapping) 2=w2026
    목표 달성(desc) 3=tie_break(other_damage asc, new_fp asc, kotlin_parity_burden asc)."""
    survivors = [r for r in results if r["gate_pass"]]

    def sort_key(r: dict[str, Any]) -> tuple:
        return (
            not _achieved_w2026(r),  # achieved(True) 먼저 오도록 desc를 not(bool)로 인코딩
            r["other_6_positive_windows_damage"],
            r["holdout_negative_new_false_positive"],
            r["kotlin_parity_burden"],
        )

    return sorted(survivors, key=sort_key)


# -----------------------------------------------------------------------------
# variant B — severity 4-tier (measurement only, AD-7: golden pre-filter NOT applied)
# -----------------------------------------------------------------------------


def window_max_composite(result: dict[str, Any], window_id: str, profile: str) -> float | None:
    """S-1(aaa-critic Stage B 라운드 2): 옵션 B의 w2026 효과는 detected/lead만으로는
    보이지 않는다(둘 다 미탐지라 baseline과 겉보기엔 "변화 없음") — 실제로는 분모
    재정의가 최고 composite 자체를 끌어내린다(§3-D "해결" 주장에 대한 실측 반증,
    아래 evaluate_variant_b에서 baseline과 비교)."""
    composites = [
        t["composite"] for t in result["windows"][window_id][profile]["ticks"]
        if t["composite"] is not None
    ]
    return max(composites) if composites else None


def evaluate_variant_b(
    tmp_dir: Path,
    extreme_pct: float,
    baseline_result: dict[str, Any],
    baseline_stats: dict[tuple[str, str], dict[str, Any]],
) -> dict[str, Any]:
    ind = with_kospi_max_severity_4(RS.BASE_IND, extreme_pct)
    sm = RS.BASE_SM  # B는 상태기계 무변경 — severity/composite 분모만 재정의

    golden_result = RS.run_candidate(tmp_dir, ind, sm, RS.BASE_RP, RS.GOLDEN_IDS)
    golden_ok, golden_reason = RS.golden_pass(golden_result, sm)
    # AD-7: B는 사전 필터 비적용 — 실패해도 계속 실행하고 결과를 그대로 기록한다.

    full_result = RS.run_candidate(tmp_dir, ind, sm, RS.BASE_RP, ALL_WINDOW_IDS)
    stats = {
        (wid, profile): RS.window_profile_stats(full_result, wid, profile, RS.WINDOW_CTX[wid])
        for wid in ALL_WINDOW_IDS
        for profile in _PROFILES
    }
    target = w2026_target(stats)

    golden_tick = next(
        t
        for t in full_result["windows"]["w2024_carry_unwind"]["mobile_daily"]["ticks"]
        if t["date"] == "2024-08-05"
    )
    baseline_composite = next(
        t
        for t in RS.GOLDEN_MOBILE["windows"]["w2024_carry_unwind"]["ticks"]
        if t["date"] == "2024-08-05"
    )["composite"]

    w2026_max_composite_baseline = {
        p: window_max_composite(baseline_result, "w2026_structural", p) for p in _PROFILES
    }
    w2026_max_composite_actual = {
        p: window_max_composite(full_result, "w2026_structural", p) for p in _PROFILES
    }
    w2026_max_composite_delta = {
        p: w2026_max_composite_actual[p] - w2026_max_composite_baseline[p] for p in _PROFILES
    }

    return {
        "candidate_pct": extreme_pct,
        "golden_pass": golden_ok,
        "golden_reason": golden_reason,
        "w2026_target": target,
        "w2026_max_composite_baseline": w2026_max_composite_baseline,
        "w2026_max_composite_actual": w2026_max_composite_actual,
        "w2026_max_composite_delta": w2026_max_composite_delta,
        "golden_2024_08_05_composite_baseline": baseline_composite,
        "golden_2024_08_05_composite_actual": golden_tick["composite"],
        "golden_2024_08_05_composite_delta": golden_tick["composite"] - baseline_composite,
        "rank_applies": "B_severity_4_level" in _RANK_APPLIES_TO,  # False — §4.3(5)/AD-7
        "stats": _stats_to_jsonable(stats),
    }


# -----------------------------------------------------------------------------
# variant C — RED sub-level (display-only, no replay change; reuses baseline result)
# -----------------------------------------------------------------------------


def variant_c_timeline(baseline_result: dict[str, Any]) -> list[dict[str, Any]]:
    bp = red_breakpoint()
    out: list[dict[str, Any]] = []
    for wid in ALL_WINDOW_IDS:
        for profile in _PROFILES:
            for t in baseline_result["windows"][wid][profile]["ticks"]:
                if t["phase"] == "RED":
                    out.append(
                        {
                            "window_id": wid,
                            "profile": profile,
                            "date": t["date"],
                            "composite": t["composite"],
                            "sublevel": red_sublevel(t["composite"], bp),
                        }
                    )
    return out


# -----------------------------------------------------------------------------
# main
# -----------------------------------------------------------------------------


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # K-xx cp949 콘솔 함정

    metrics_before = METRICS_JSON_PATH.read_bytes() if METRICS_JSON_PATH.exists() else b""
    hash_before = hashlib.sha256(metrics_before).hexdigest()

    tmp_dir = Path(tempfile.mkdtemp(prefix="bt04_f06_"))

    print("computing baseline (0.3.0-rc, 9 windows incl. holdout, in-process, no writes) ...")
    baseline_result, baseline_stats = compute_baseline()
    print(
        f"  w2023_11_rally mobile amber={baseline_stats[('w2023_11_rally','mobile_daily')]['amber_ticks']} "
        f"(known AD-8 FAIL — not re-tuned here)"
    )

    a_candidates = F06["variants"]["A_threshold_ladder_extension"]["candidate_grid"][
        "kospi_drawdown_extreme_pct"
    ]
    print(f"\n[A] threshold ladder extension (or_any_extreme, ORANGE only) — {a_candidates} ...")
    a_results = [evaluate_variant_a(tmp_dir, v, baseline_stats) for v in a_candidates]
    for r in a_results:
        print(
            f"  {r['candidate_pct']}%: golden_pass={r['golden_pass']} "
            f"gate_pass={r.get('gate_pass')} "
            f"w2026_achieved={_achieved_w2026(r) if r.get('golden_pass') else 'N/A'}"
        )
    a_ranked = rank_variant_a(a_results)
    print(f"  ranked survivors: {[r['candidate_pct'] for r in a_ranked]}")

    b_candidates = F06["variants"]["B_severity_4_level"]["candidate_grid"][
        "kospi_drawdown_extreme_pct"
    ]
    print(f"\n[B] severity 4-tier (measurement only, golden pre-filter NOT applied) — {b_candidates} ...")
    b_results = [
        evaluate_variant_b(tmp_dir, v, baseline_result, baseline_stats) for v in b_candidates
    ]
    for r in b_results:
        print(
            f"  {r['candidate_pct']}%: golden_pass={r['golden_pass']} "
            f"golden_08_05_delta={r['golden_2024_08_05_composite_delta']:.4f} "
            f"w2026_max_composite mobile {r['w2026_max_composite_baseline']['mobile_daily']:.4f}"
            f"->{r['w2026_max_composite_actual']['mobile_daily']:.4f}"
        )

    print("\n[C] RED sub-level (display-only, breakpoint from f06_variants.yaml) ...")
    bp = red_breakpoint()
    c_timeline = variant_c_timeline(baseline_result)
    print(f"  breakpoint={bp}  red_ticks_total={len(c_timeline)}")

    out = {
        "schema": "backtest-f06-variants-result/1",
        "generated_at": datetime.now(UTC).isoformat(),
        "note": "근사-PIT — C1에서 실측 확정 (docs/BACKTEST_PLAN.md §5). "
        "실측 vs 설계 저널 §3 산술 투사의 대조는 BT_REPORT.md BT-04 절 참조(AD-9(e)).",
        "baseline": {
            "windows_evaluated": ALL_WINDOW_IDS,
            "stats": _stats_to_jsonable(baseline_stats),
        },
        "variant_a": {"candidates": a_results, "ranked": a_ranked},
        "variant_b": {"candidates": b_results},
        "variant_c": {"red_breakpoint": bp, "timeline": c_timeline},
    }

    F06_RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    out_path = F06_RESULTS_DIR / "f06_variants_result.json"
    out_path.write_text(json.dumps(out, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    print(f"\nf06 variants result written: {out_path}")

    metrics_after = METRICS_JSON_PATH.read_bytes() if METRICS_JSON_PATH.exists() else b""
    hash_after = hashlib.sha256(metrics_after).hexdigest()
    print(f"\nmetrics.json sha256 before={hash_before}")
    print(f"metrics.json sha256 after ={hash_after}")
    if hash_before != hash_after:
        print("FATAL: backtest/results/metrics.json changed — sandbox invariant violated (AD-7)")
        return 1
    print("metrics.json byte-identical: OK (AD-7 sandbox invariant holds)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
