"""backtest/test_run_sweep.py — BT-03 스윕 하니스 갭 해소 + 선정 로직 증인 테스트
(MT0-05 §11.2·§11.3). 네트워크 없음 — backtest/fixtures/*.parquet(BT-01 산출)만 읽는다.

완료 기준(MT0-05 브리프): `uv run pytest backtest/test_run_sweep.py -q` green.
"""

from __future__ import annotations

import copy
from pathlib import Path

import yaml

from backtest import run_replay as R
from backtest import run_sweep as S


def _write_yaml(obj: dict, path: Path) -> None:
    path.write_text(yaml.safe_dump(obj, allow_unicode=True, sort_keys=False), "utf-8")


# -----------------------------------------------------------------------------
# §11.2-1/2 증인: statemachine_path/replay_path 오버라이드 배선 (F-1 split-brain 재발 방지)
# -----------------------------------------------------------------------------


def test_run_replay_statemachine_path_override_changes_result(tmp_path: Path) -> None:
    """path만 변형한 mutant가 실제로 결과를 바꾼다. mobile_daily promote_sustain_ticks
    1->2로 바꾸면 D-08 골든 양성 창의 2024-08-05 phase가 달라져야 한다(설계 §8:
    promote_sustain=2 -> 첫 ORANGE가 08-05 -> 08-06으로 밀림, 08-05는 AMBER->GREEN)."""
    baseline = R.run_replay(["mobile_daily"], ["w2024_carry_unwind"])
    baseline_tick = next(
        t
        for t in baseline["windows"]["w2024_carry_unwind"]["mobile_daily"]["ticks"]
        if t["date"] == "2024-08-05"
    )

    mutant_sm = yaml.safe_load(R.STATEMACHINE_YAML_PATH.read_text(encoding="utf-8"))
    mutant_sm["profiles"]["mobile_daily"]["promote_sustain_ticks"] = 2
    mutant_path = tmp_path / "mutant_statemachine.yaml"
    _write_yaml(mutant_sm, mutant_path)

    mutant = R.run_replay(
        ["mobile_daily"], ["w2024_carry_unwind"], statemachine_path=mutant_path
    )
    mutant_tick = next(
        t
        for t in mutant["windows"]["w2024_carry_unwind"]["mobile_daily"]["ticks"]
        if t["date"] == "2024-08-05"
    )

    assert baseline_tick["phase"] != mutant_tick["phase"], (
        "statemachine_path override had no effect on the result -- split-brain "
        "regression (F-1 pattern, MT0-04)"
    )

    # 원본 configs/statemachine.yaml 자체는 건드리지 않았어야 한다(명시 path만 영향).
    unchanged = R.run_replay(["mobile_daily"], ["w2024_carry_unwind"])
    unchanged_tick = next(
        t
        for t in unchanged["windows"]["w2024_carry_unwind"]["mobile_daily"]["ticks"]
        if t["date"] == "2024-08-05"
    )
    assert unchanged_tick["phase"] == baseline_tick["phase"]


def test_run_replay_replay_path_override_is_wired(tmp_path: Path) -> None:
    """confirm_time_kst 오버라이드(replay_path)가 실제로 mobile 틱 시각에 반영된다."""
    mutant_rp = yaml.safe_load(R.REPLAY_YAML_PATH.read_text(encoding="utf-8"))
    mutant_rp["profiles"]["mobile_daily"]["confirm_time_kst"] = "17:00"
    mutant_path = tmp_path / "mutant_replay.yaml"
    _write_yaml(mutant_rp, mutant_path)

    result = R.run_replay(
        ["mobile_daily"], ["w2024_carry_unwind"], replay_path=mutant_path
    )
    ticks = result["windows"]["w2024_carry_unwind"]["mobile_daily"]["ticks"]
    assert ticks and all(t["kst_time"] == "17:00" for t in ticks)


def test_registry_load_statemachine_explicit_path_not_cached(tmp_path: Path) -> None:
    """F-2 교훈 회귀 방지: 같은 경로에 다른 내용을 반복 덮어써도(스윕 패턴) 매번 새로
    읽어야 한다 — 캐시 고착 시 두 번째 호출도 첫 값을 반환한다."""
    from engine_ref import registry

    base = yaml.safe_load(R.STATEMACHINE_YAML_PATH.read_text(encoding="utf-8"))
    path = tmp_path / "sm.yaml"

    variant_a = copy.deepcopy(base)
    variant_a["profiles"]["mobile_daily"]["promote_sustain_ticks"] = 1
    _write_yaml(variant_a, path)
    cfg_a = registry.load_statemachine(path=path)
    assert cfg_a.profiles["mobile_daily"].promote_sustain_ticks == 1

    variant_b = copy.deepcopy(base)
    variant_b["profiles"]["mobile_daily"]["promote_sustain_ticks"] = 2
    _write_yaml(variant_b, path)  # 같은 경로, 내용만 덮어씀(F-2 재현 패턴)
    cfg_b = registry.load_statemachine(path=path)
    assert cfg_b.profiles["mobile_daily"].promote_sustain_ticks == 2


# -----------------------------------------------------------------------------
# 골든 사전필터 작동 증인 (MT0-03 신설 규율 ①, §11.3)
# -----------------------------------------------------------------------------


def test_golden_prefilter_rejects_known_violating_usdkrw_threshold(tmp_path: Path) -> None:
    """usdkrw watch=2.25(가용 영역 1.194<watch<=2.204 밖)는 mobile w2024_carry_unwind
    2024-08-01 composite 이탈로 골든 사전필터에서 탈락해야 한다 — 7창 게이트 계산
    자체를 생략한다(stage: pre_filter, sweep.yaml golden_constraint)."""
    ind = S.with_usdkrw(S.BASE_IND, {"watch": 2.25, "warn": 2.5, "crit": 3.5})
    ev = S.evaluate_candidate(tmp_path, ind, S.BASE_SM, S.BASE_RP)
    assert ev["golden_pass"] is False
    assert "composite" in ev["golden_reason"]
    assert "gates" not in ev  # 성능 계산 자체가 생략됐다


def test_golden_prefilter_rejects_known_violating_mobile_promote_sustain(
    tmp_path: Path,
) -> None:
    """promote_sustain_ticks=2는 56조합 전건 탈락(설계 §8)의 대표값 재확인."""
    sm = S.with_mobile_profile(
        S.BASE_SM,
        {
            "promote_sustain_ticks": 2,
            "demote_below_ticks": 3,
            "min_dwell_ticks": 2,
            "reentry_cooldown_ticks": 0,
        },
    )
    ev = S.evaluate_candidate(tmp_path, S.BASE_IND, sm, S.BASE_RP)
    assert ev["golden_pass"] is False
    assert "GREEN" in ev["golden_reason"]


def test_golden_prefilter_passes_current_baseline(tmp_path: Path) -> None:
    """회귀 방지 belt-and-suspenders: 현행 0.2.0 registry 자체는 당연히 골든을 통과해야
    한다(backtest/test_golden.py green이 이미 보장하지만, run_sweep의 golden_pass()가
    같은 결론을 내는지 별도 확인 — 재구현 결과가 실제 test_golden.py와 어긋나지 않음을
    증명)."""
    ind = S.with_usdkrw(S.BASE_IND, S.BASE_USDKRW)
    ev = S.evaluate_candidate(tmp_path, ind, S.BASE_SM, S.BASE_RP)
    assert ev["golden_pass"] is True


# -----------------------------------------------------------------------------
# 선정 규칙 결정론 + rank 축퇴 처리 증인 (§11.3)
# -----------------------------------------------------------------------------


def test_selection_is_deterministic_across_repeated_runs(tmp_path: Path) -> None:
    """동일 입력 2회 실행이 동일 승자를 낸다. S1의 작은 부분집합(2조합)으로 대체 실행해
    결정론만 확인한다(전체 154평가 재실행은 이 단위테스트 목적에 비해 낭비)."""
    candidates = [
        {"watch": 2.0, "warn": 2.5, "crit": 3.5},
        {"watch": 1.5, "warn": 2.0, "crit": 3.0},
    ]

    def evaluate_once() -> dict | None:
        entries = []
        for t in candidates:
            ind = S.with_usdkrw(S.BASE_IND, t)
            ev = S.evaluate_candidate(tmp_path, ind, S.BASE_SM, S.BASE_RP)
            spec = S.diff_spec(usdkrw=t)
            entries.append(
                {"usdkrw": t, "eval": ev, "simplicity": S.simplicity_key(spec)}
            )
        golden_ok = [e for e in entries if e["eval"]["golden_pass"]]
        ranked, _ = S._rank_golden_pool(golden_ok)
        return ranked[0]["usdkrw"] if ranked else None

    assert evaluate_once() == evaluate_once()


def test_rank_uses_lead_time_when_false_positive_degenerate() -> None:
    """rank 1순위(오탐 최소)가 축퇴(전 후보 (0,0))한 상태에서 rank 2(리드타임 최대)가
    실제로 승자를 가르는 경로의 단위 증인(§6.1 F-8) — sort key 튜플만 검증한다."""

    def _entry(lead_median: float, changed: int) -> dict:
        return {
            "eval": {
                "gates": {
                    "per_profile": {
                        p: {
                            "fp_orange_sum": 0,
                            "fp_amber_sum": 0,
                            "lead_median": lead_median,
                            "lead_sum": lead_median * 6,
                        }
                        for p in S.PROFILES
                    }
                }
            },
            "simplicity": (changed, 0, "z"),
        }

    low_lead_simple = _entry(lead_median=5, changed=0)
    high_lead_complex = _entry(lead_median=10, changed=3)
    ranked = sorted([low_lead_simple, high_lead_complex], key=S.rank_sort_key)
    # fp 항이 둘 다 (0,0)으로 동률(축퇴)이므로 lead가 더 큰 쪽이 앞에 온다 — simplicity가
    # 더 나빠도(changed=3) 2순위(리드타임)가 3순위(단순성)보다 우선한다.
    assert ranked[0] is high_lead_complex
