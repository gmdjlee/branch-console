"""backtest/plot_sweep.py — BT-03 스윕 결과 시각화 (MT0-05④).

    uv run python backtest/plot_sweep.py

입력: backtest/results/sweep/sweep_result.json(S1 baseline 후보의 mobile n_transitions)
     + backtest/results/metrics.json(0.3.0-rc 재실행 산출). 네트워크 없음.
산출: backtest/reports/mobile_flapping_before_after.png — mobile_daily 창별 국면
전이 횟수(§6 플래핑 지표)의 0.2.0(기준선) vs 0.3.0-rc(BT-03 선정) 비교.
근사-PIT — C1에서 실측 확정(docs/BACKTEST_PLAN.md §5).
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

matplotlib.rcParams["font.family"] = "Malgun Gothic"  # 한글 렌더링 (Windows 기본 폰트)
matplotlib.rcParams["axes.unicode_minus"] = False

REPO_ROOT = Path(__file__).resolve().parent.parent
if __package__ in (None, ""):
    sys.path.insert(0, str(REPO_ROOT))
from backtest.run_sweep import BASE_USDKRW, FLAP_NEG_MAX, FLAP_POS_MAX

SWEEP_RESULT_PATH = REPO_ROOT / "backtest" / "results" / "sweep" / "sweep_result.json"
METRICS_PATH = REPO_ROOT / "backtest" / "results" / "metrics.json"
REPORTS_DIR = REPO_ROOT / "backtest" / "reports"


def _find_s1_baseline_entry(sweep_result: dict) -> dict:
    """S1 entries 중 usdkrw가 BASE_USDKRW(기준선 0.2.0값)와 동일한 것 — ③④도 S1 내내
    baseline이므로 이 엔트리는 순수 0.2.0 전체 기준선과 같다."""
    for e in sweep_result["stages"]["S1"]["entries"]:
        if e["usdkrw"] == BASE_USDKRW and e["eval"]["golden_pass"]:
            return e
    raise ValueError("baseline usdkrw entry not found in S1 — sweep_result.json 재생성 필요")


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sweep_result = json.loads(SWEEP_RESULT_PATH.read_text(encoding="utf-8"))
    metrics = json.loads(METRICS_PATH.read_text(encoding="utf-8"))

    baseline_entry = _find_s1_baseline_entry(sweep_result)
    sweep_ids = sweep_result["windows"]["sweep_ids"]
    positive_ids = set(sweep_result["windows"]["positive_ids"])

    before = []
    after = []
    for wid in sweep_ids:
        key = str((wid, "mobile_daily"))
        before.append(baseline_entry["eval"]["stats"][key]["n_transitions"])
        after.append(metrics["windows"][wid]["mobile_daily"]["summary"]["n_transitions"])

    x = range(len(sweep_ids))
    width = 0.35
    fig, ax = plt.subplots(figsize=(10, 5))
    ax.bar([i - width / 2 for i in x], before, width, label="0.2.0 (기준선)", color="#888")
    ax.bar([i + width / 2 for i in x], after, width, label="0.3.0-rc (BT-03 선정)", color="#2a6")
    ax.axhline(
        FLAP_POS_MAX, color="crimson", linestyle="--", linewidth=1,
        label=f"§6 양성 상한 ({FLAP_POS_MAX})",
    )
    ax.axhline(
        FLAP_NEG_MAX, color="orange", linestyle="--", linewidth=1,
        label=f"§6 음성 상한 ({FLAP_NEG_MAX})",
    )
    ax.set_xticks(list(x))
    labels = [
        f"{wid}\n({'양성' if wid in positive_ids else '음성'})" for wid in sweep_ids
    ]
    ax.set_xticklabels(labels, rotation=30, ha="right", fontsize=8)
    ax.set_ylabel("mobile_daily n_transitions (창당 국면 전이 횟수)")
    ax.set_title(
        "BT-03 스윕: mobile_daily 플래핑 — 0.2.0 기준선 vs 0.3.0-rc 선정\n"
        "근사-PIT — C1에서 실측 확정 (홀드아웃 2창 제외, 7창)"
    )
    ax.legend(fontsize=8)
    fig.tight_layout()

    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    out_path = REPORTS_DIR / "mobile_flapping_before_after.png"
    fig.savefig(out_path, dpi=150)
    print(f"chart written: {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
