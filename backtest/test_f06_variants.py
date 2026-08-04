"""backtest/test_f06_variants.py — MT0-06/BT-04 Stage B 증인 테스트(AD-9 완료 기준).

네트워크 없음 — backtest/fixtures/*.parquet(BT-01 산출)만 읽는다. 완료 기준:
`uv run pytest backtest/test_f06_variants.py -q` green.

AD-9(a)(ii): 확장 키가 **존재**하되 골든 창에서는 무발화하는 조건(옵션 A 최소 후보
16.0% — §3-A(b) 안전 하한 15.557%보다 큰 최소값)에서 골든 산출이 여전히 비트 동일함을
증명하는 증인. (i)의 "확장 키 부재" 쪽 증인은 tests/test_engine_ref.py에 있다(엔진
단위 레벨) — 여기는 backtest.run_replay를 통한 실제 리플레이 레벨 증인이다.
"""

from __future__ import annotations

from pathlib import Path

from backtest import run_f06_variants as F
from backtest import run_replay as R
from backtest import run_sweep as RS

# -----------------------------------------------------------------------------
# AD-9(a)(ii) — 골든 창 무발화 조건에서 리플레이 산출 비트 동일
# -----------------------------------------------------------------------------


def test_or_any_extreme_inert_on_golden_windows_is_bit_identical(tmp_path: Path) -> None:
    """옵션 A 최소 후보(16.0%)가 실제로 배선된 상태로 골든 2창(w2024_carry_unwind
    kospi_drawdown 최댓값 15.557% < 16.0%, w2024_05_calm 2.520%)을 리플레이해도, 두
    창 어디서도 is_extreme이 발화하지 않으므로 composite/phase/coverage/fired_axes가
    baseline(확장 비활성)과 정확히 == (approx 아님) 일치해야 한다."""
    baseline = R.run_replay(["server_intraday", "mobile_daily"], list(RS.GOLDEN_IDS))

    ind = F.with_kospi_extreme_threshold(RS.BASE_IND, 16.0)
    sm = F.with_or_any_extreme_orange(RS.BASE_SM)
    candidate = RS.run_candidate(tmp_path, ind, sm, RS.BASE_RP, list(RS.GOLDEN_IDS))

    for wid in RS.GOLDEN_IDS:
        for profile in ("server_intraday", "mobile_daily"):
            b_ticks = baseline["windows"][wid][profile]["ticks"]
            c_ticks = candidate["windows"][wid][profile]["ticks"]
            assert len(b_ticks) == len(c_ticks)
            for b, c in zip(b_ticks, c_ticks, strict=True):
                assert b["date"] == c["date"]
                assert b["phase"] == c["phase"], (wid, profile, b["date"])
                assert b["composite"] == c["composite"], (wid, profile, b["date"])
                assert b["coverage"] == c["coverage"], (wid, profile, b["date"])
                assert b["fired_axes"] == c["fired_axes"], (wid, profile, b["date"])
                # any_extreme은 신규 필드(baseline에도 존재, 항상 False여야 함 — 두 실행
                # 모두에서 kospi_drawdown이 16.0%를 넘는 틱이 없다는 사실 자체의 증인).
                assert c["any_extreme"] is False, (wid, profile, b["date"])

    golden_ok, reason = RS.golden_pass(candidate, sm)
    assert golden_ok, reason


# -----------------------------------------------------------------------------
# or_any_extreme이 실제로 무언가를 바꾼다는 사실의 대조 증인(위 증인이 공허하게 참이
# 되지 않도록) — w2026_structural mobile_daily에서 실제로 발화·타임라인이 달라짐을 확인.
# -----------------------------------------------------------------------------


def test_or_any_extreme_actually_changes_w2026_mobile_timeline(tmp_path: Path) -> None:
    baseline = R.run_replay(["mobile_daily"], ["w2026_structural"])
    ind = F.with_kospi_extreme_threshold(RS.BASE_IND, 16.0)
    sm = F.with_or_any_extreme_orange(RS.BASE_SM)
    candidate = RS.run_candidate(tmp_path, ind, sm, RS.BASE_RP, ["w2026_structural"])

    b_ticks = baseline["windows"]["w2026_structural"]["mobile_daily"]["ticks"]
    c_ticks = candidate["windows"]["w2026_structural"]["mobile_daily"]["ticks"]
    b_phases = [t["phase"] for t in b_ticks]
    c_phases = [t["phase"] for t in c_ticks]
    assert b_phases != c_phases, "or_any_extreme had no effect on w2026 — extension is dead code"
    assert any(t["any_extreme"] for t in c_ticks), "is_extreme never fired for w2026 candidate"
    # composite 계산식 자체는 옵션 A에서 100% 불변이어야 한다(§3-A(a)) — severity/composite
    # 는 그대로, 상태기계 승격 경로만 달라진다.
    assert [t["composite"] for t in b_ticks] == [t["composite"] for t in c_ticks]


# -----------------------------------------------------------------------------
# 옵션 C — red_breakpoint 파싱 + red_sublevel 매핑
# -----------------------------------------------------------------------------


def test_red_breakpoint_parses_from_f06_variants_yaml() -> None:
    assert F.red_breakpoint() == 80.0


def test_red_sublevel_boundary_inclusive() -> None:
    bp = F.red_breakpoint()
    assert F.red_sublevel(80.0, bp) == "RED-2"  # 등호 포함 — O-a 관례
    assert F.red_sublevel(79.99, bp) == "RED-1"
    assert F.red_sublevel(60.0, bp) == "RED-1"
    assert F.red_sublevel(100.0, bp) == "RED-2"


# -----------------------------------------------------------------------------
# 후보 config builder — target key 배선 확인(f06_variants.yaml variants.*.target 그대로)
# -----------------------------------------------------------------------------


def test_with_kospi_extreme_threshold_only_touches_kospi_drawdown() -> None:
    ind = F.with_kospi_extreme_threshold(RS.BASE_IND, 16.0)
    by_id = {item["id"]: item for item in ind["indicators"]}
    assert by_id["kospi_drawdown"]["thresholds"]["extreme"] == 16.0
    assert "max_severity" not in by_id["kospi_drawdown"]  # 옵션 A는 max_severity 미터치
    assert by_id["vix_level_z"]["thresholds"] == {
        "watch": 1.5,
        "warn": 2.0,
        "crit": 3.0,
    }  # 다른 지표는 무변화
    # BASE_IND 자체는 변형되지 않았어야 한다(copy.deepcopy 확인) — MT0-08 채택 후
    # BASE_IND는 이미 프로덕션값 extreme:20.0을 갖는다(부재가 아니다), 그래서 격리는
    # 후보값(16.0)과 값이 다름으로 확인한다.
    base_by_id = {item["id"]: item for item in RS.BASE_IND["indicators"]}
    assert base_by_id["kospi_drawdown"]["thresholds"]["extreme"] == 20.0


def test_with_kospi_max_severity_4_sets_both_extreme_and_max_severity() -> None:
    ind = F.with_kospi_max_severity_4(RS.BASE_IND, 20.0)
    by_id = {item["id"]: item for item in ind["indicators"]}
    assert by_id["kospi_drawdown"]["thresholds"]["extreme"] == 20.0
    assert by_id["kospi_drawdown"]["max_severity"] == 4


def test_with_or_any_extreme_orange_only_touches_orange_not_red() -> None:
    sm = F.with_or_any_extreme_orange(RS.BASE_SM)
    assert sm["upgrade"]["rules"]["ORANGE"]["or_any_extreme"] is True
    assert "or_any_extreme" not in sm["upgrade"]["rules"]["RED"]  # AD-10
    assert "or_any_extreme" not in sm["upgrade"]["rules"]["AMBER"]
    # BASE_SM 자체는 변형되지 않았어야 한다 — MT0-08 채택 후 BASE_SM의 ORANGE도 이미
    # or_any_extreme:true다(값은 후보와 같아져 값 비교로는 격리를 증명할 수 없다), 그래서
    # deepcopy 격리는 객체 동일성으로 확인한다.
    assert sm["upgrade"]["rules"]["ORANGE"] is not RS.BASE_SM["upgrade"]["rules"]["ORANGE"]


# -----------------------------------------------------------------------------
# ranking 규칙(§4.3) — 합성 데이터로 sort_key 순서만 확인
# -----------------------------------------------------------------------------


def _fake_entry(
    *, gate_pass: bool, achieved: bool, damage: int, new_fp: int, burden: str = "moderate"
) -> dict:
    target = {
        p: {"detected": achieved, "before_0728": achieved, "first_orange_or_above_date": "2026-07-08"}
        for p in F._PROFILES
    }
    return {
        "candidate_pct": 0.0,
        "golden_pass": True,
        "gate_pass": gate_pass,
        "w2026_target": target,
        "other_6_positive_windows_damage": damage,
        "holdout_negative_new_false_positive": new_fp,
        "kotlin_parity_burden": burden,
    }


def test_rank_variant_a_excludes_gate_failures() -> None:
    survivor = _fake_entry(gate_pass=True, achieved=True, damage=0, new_fp=0)
    failure = _fake_entry(gate_pass=False, achieved=True, damage=0, new_fp=0)
    ranked = F.rank_variant_a([survivor, failure])
    assert ranked == [survivor]


def test_rank_variant_a_prioritizes_w2026_achievement_then_damage() -> None:
    achieved_more_damage = _fake_entry(gate_pass=True, achieved=True, damage=2, new_fp=0)
    unachieved_no_damage = _fake_entry(gate_pass=True, achieved=False, damage=0, new_fp=0)
    ranked = F.rank_variant_a([unachieved_no_damage, achieved_more_damage])
    assert ranked[0] is achieved_more_damage  # w2026 달성이 손상 여부보다 우선(§4.3 규칙 2>3)


def test_rank_variant_a_empty_when_all_gate_fail() -> None:
    """실측 확인 사실의 회귀 고정: A 3후보 전부 mobile 플래핑 하드 게이트에서 탈락하면
    ranked는 빈 리스트다(임계 사다리가 여기서 탈락하는 결과도 정직하게 보고 — AD-1 iv).
    이 테스트는 rank 함수 자체의 계약(빈 survivor -> 빈 결과)만 고정한다."""
    all_failed = [_fake_entry(gate_pass=False, achieved=True, damage=0, new_fp=0) for _ in range(3)]
    assert F.rank_variant_a(all_failed) == []
