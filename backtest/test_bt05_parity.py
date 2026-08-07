"""backtest/test_bt05_parity.py — MT1-05j: BT-05 Kotlin<->Python 패리티 판정.

브리프 정본: docs/plans/M1_PLAN_C.md §9-C(계층별 판정 기준 표) + docs/BACKTEST_PLAN.md BT-05
(수용 기준: |Δcomposite| <= 0.05 전 틱·국면 타임라인 완전 일치·golden_mobile 일치).

2단계 절차의 두 번째 단계(첫 단계는 Kotlin JVM 테스트):
    1) uv run python backtest/export_parity.py --window all
    2) cd mobile && ./gradlew :engine:test --tests "*ParityRunnerTest*"   (actual.jsonl 산출)
    3) uv run pytest -q backtest/test_bt05_parity.py                     (본 파일 — 이 판정)

판정 로직만 여기 있다(재구현 없음): expected.jsonl(Python engine_ref 기준)과
actual.jsonl(Kotlin :engine 기준)을 §9-C L0~L6 계층별 허용 오차로 줄 단위 비교한다.
네트워크 없음 — 두 파일 모두 backtest/parity/<window_id>/에 이미 존재하는 픽스처만 읽는다.

as_of 규약(K-06, 브리프 아이템 2): 두 파일의 `evaluated_at`/`visible_at`은 같은 순간을
서로 다른 문자열 형식으로 표현할 수 있다(Python `isoformat()` -> "+00:00" 오프셋, Kotlin
`java.time.Instant.toString()` -> "Z" 접미사) — 반드시 `datetime.fromisoformat`으로 파싱한
값끼리 비교한다(Python 3.11+는 "Z"도 파싱한다). 문자열 그대로 비교하면 항상 거짓불일치가
난다. `kst_date`/`as_of`(달력일)는 시각 성분이 없는 순수 날짜 문자열이라 그대로 비교해도
안전하다(ParityIo.kt 문서화 참조).
"""

from __future__ import annotations

import hashlib
import json
from datetime import datetime
from pathlib import Path
from typing import Any

import pytest
import yaml

from backtest.fixture_schema import load_windows

REPO_ROOT = Path(__file__).resolve().parent.parent
PARITY_DIR = REPO_ROOT / "backtest" / "parity"
GOLDEN_MOBILE_PATH = REPO_ROOT / "backtest" / "golden_mobile.yaml"

# §9-C L4: composite |Δ| <= 0.05 (D-18/BT-05 규정). L2: 상대 1e-9 또는 절대 1e-12 중 큰 쪽.
COMPOSITE_ABS_TOL = 0.05
COVERAGE_ABS_TOL = 1e-9
VALUE_REL_TOL = 1e-9
VALUE_ABS_TOL = 1e-12
GOLDEN_ABS_TOL = 1e-9  # L6: golden_mobile.yaml 자체가 이미 근사-PIT 확정값 — 완전 일치 기대

WINDOW_IDS = tuple(w.window_id for w in load_windows())
GOLDEN_WINDOW_IDS = ("w2024_carry_unwind", "w2024_05_calm")  # D-08 골든 2케이스


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _verify_manifest(window_dir: Path) -> None:
    manifest_path = window_dir / "MANIFEST.sha256"
    assert manifest_path.exists(), f"{window_dir.name}: MANIFEST.sha256 missing"
    for line in manifest_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        digest, name = line.split(None, 1)
        path = window_dir / name
        assert path.exists(), f"{window_dir.name}: MANIFEST references missing file '{name}'"
        actual = _sha256(path)
        assert actual == digest, (
            f"{window_dir.name}: sha256 mismatch for '{name}' "
            f"(manifest={digest} actual={actual}) — K-16 drift guard, re-run export_parity.py"
        )


def _load_jsonl(path: Path) -> list[dict[str, Any]]:
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def _parse_instant(s: str) -> datetime:
    return datetime.fromisoformat(s)


def _num_close(a: float | None, b: float | None, rel: float, abs_: float) -> bool:
    """None==None only if both None (D-25 §3 평가 불능 동결 대칭성)."""
    if a is None and b is None:
        return True
    if a is None or b is None:
        return False
    diff = abs(a - b)
    return diff <= max(rel * max(abs(a), abs(b)), abs_)


def _require_generated(window_id: str) -> tuple[Path, list[dict[str, Any]], list[dict[str, Any]]]:
    window_dir = PARITY_DIR / window_id
    expected_path = window_dir / "expected.jsonl"
    actual_path = window_dir / "actual.jsonl"
    if not expected_path.exists() or not (window_dir / "MANIFEST.sha256").exists():
        pytest.skip(
            f"{window_id}: expected.jsonl/MANIFEST.sha256 missing — run "
            "'uv run python backtest/export_parity.py --window all' first"
        )
    if not actual_path.exists():
        pytest.skip(
            f"{window_id}: actual.jsonl missing — run "
            '\'cd mobile && ./gradlew :engine:test --tests "*ParityRunnerTest*"\' first'
        )
    _verify_manifest(window_dir)
    return window_dir, _load_jsonl(expected_path), _load_jsonl(actual_path)


@pytest.mark.parametrize("window_id", WINDOW_IDS)
def test_bt05_parity_window_l0_to_l5(window_id: str) -> None:
    """§9-C L0(MANIFEST 무결성, 위 fixture) ~ L5(phase 타임라인) — 9창 전체 게이트."""
    _window_dir, expected, actual = _require_generated(window_id)

    assert len(expected) == len(actual), (
        f"{window_id}: tick count mismatch expected={len(expected)} actual={len(actual)}"
    )

    for i, (e, a) in enumerate(zip(expected, actual, strict=True)):
        loc = f"{window_id}[tick {i} kst={e.get('kst_date')}]"

        assert _parse_instant(e["evaluated_at"]) == _parse_instant(a["evaluated_at"]), (
            f"{loc}: evaluated_at mismatch {e['evaluated_at']!r} != {a['evaluated_at']!r}"
        )
        assert e["kst_date"] == a["kst_date"], f"{loc}: kst_date mismatch"

        assert e["indicators"].keys() == a["indicators"].keys(), (
            f"{loc}: indicator id set mismatch "
            f"expected={sorted(e['indicators'])} actual={sorted(a['indicators'])}"
        )
        for ind_id, e_layer in e["indicators"].items():
            a_layer = a["indicators"][ind_id]
            ind_loc = f"{loc} indicator={ind_id}"

            # L1 visible_at — instant-equal (format-agnostic), null-symmetric.
            e_vis, a_vis = e_layer["visible_at"], a_layer["visible_at"]
            if e_vis is None or a_vis is None:
                assert e_vis == a_vis, f"{ind_loc}: L1 visible_at null mismatch ({e_vis!r} vs {a_vis!r})"
            else:
                assert _parse_instant(e_vis) == _parse_instant(a_vis), (
                    f"{ind_loc}: L1 visible_at mismatch {e_vis!r} != {a_vis!r}"
                )
            assert e_layer["as_of"] == a_layer["as_of"], (
                f"{ind_loc}: L1 as_of mismatch {e_layer['as_of']!r} != {a_layer['as_of']!r}"
            )

            # L2 value — rel 1e-9 or abs 1e-12, whichever is larger.
            assert _num_close(e_layer["value"], a_layer["value"], VALUE_REL_TOL, VALUE_ABS_TOL), (
                f"{ind_loc}: L2 value mismatch expected={e_layer['value']} actual={a_layer['value']}"
            )

            # L3 severity/stale — exact.
            assert e_layer["severity"] == a_layer["severity"], (
                f"{ind_loc}: L3 severity mismatch expected={e_layer['severity']} actual={a_layer['severity']}"
            )
            assert e_layer["stale"] == a_layer["stale"], (
                f"{ind_loc}: L3 stale mismatch expected={e_layer['stale']} actual={a_layer['stale']}"
            )

        # L4 composite/coverage/distinct_axes/any_crit/any_extreme/fired_axes.
        assert _num_close(e["composite"], a["composite"], 0.0, COMPOSITE_ABS_TOL), (
            f"{loc}: L4 |Δcomposite| > {COMPOSITE_ABS_TOL} expected={e['composite']} actual={a['composite']}"
        )
        assert _num_close(e["coverage"], a["coverage"], 0.0, COVERAGE_ABS_TOL), (
            f"{loc}: L4 coverage mismatch expected={e['coverage']} actual={a['coverage']}"
        )
        assert e["distinct_axes"] == a["distinct_axes"], f"{loc}: L4 distinct_axes mismatch"
        assert e["any_crit"] == a["any_crit"], f"{loc}: L4 any_crit mismatch"
        assert e["any_extreme"] == a["any_extreme"], f"{loc}: L4 any_extreme mismatch"
        assert e["fired_axes"] == a["fired_axes"], (
            f"{loc}: L4 fired_axes mismatch expected={e['fired_axes']} actual={a['fired_axes']}"
        )

        # L5 phase timeline — exact, every tick.
        assert e["phase"] == a["phase"], f"{loc}: L5 phase mismatch expected={e['phase']} actual={a['phase']}"


@pytest.mark.parametrize("window_id", GOLDEN_WINDOW_IDS)
def test_bt05_parity_golden_l6(window_id: str) -> None:
    """§9-C L6 — Kotlin actual.jsonl을 golden_mobile.yaml과 직접 비교(D-08 x D-16).

    expected.jsonl을 매개하지 않는다: expected==golden은 backtest/test_golden.py(BT-02
    완료 기준)가 이미 별도로 보증하므로, 여기서는 actual(Kotlin)이 골든 기대값 자체와
    직접 일치하는지를 본다 — 두 회귀 게이트가 독립적으로 golden을 지키게 한다.
    """
    window_dir = PARITY_DIR / window_id
    actual_path = window_dir / "actual.jsonl"
    if not actual_path.exists():
        pytest.skip(
            f"{window_id}: actual.jsonl missing — run "
            '\'cd mobile && ./gradlew :engine:test --tests "*ParityRunnerTest*"\' first'
        )

    golden = yaml.safe_load(GOLDEN_MOBILE_PATH.read_text(encoding="utf-8"))
    golden_ticks = golden["windows"][window_id]["ticks"]
    actual = _load_jsonl(actual_path)

    assert len(golden_ticks) == len(actual), (
        f"{window_id}: golden tick count {len(golden_ticks)} != actual {len(actual)}"
    )
    for i, (g, a) in enumerate(zip(golden_ticks, actual, strict=True)):
        loc = f"{window_id}[golden tick {i} date={g['date']}]"
        assert g["date"] == a["kst_date"], f"{loc}: date mismatch"
        assert g["phase"] == a["phase"], f"{loc}: L6 phase mismatch expected={g['phase']} actual={a['phase']}"
        assert _num_close(g["composite"], a["composite"], 0.0, GOLDEN_ABS_TOL), (
            f"{loc}: L6 composite mismatch golden={g['composite']} actual={a['composite']}"
        )
        assert _num_close(g["coverage"], a["coverage"], 0.0, GOLDEN_ABS_TOL), (
            f"{loc}: L6 coverage mismatch golden={g['coverage']} actual={a['coverage']}"
        )
        assert sorted(g["fired_axes"]) == sorted(a["fired_axes"]), (
            f"{loc}: L6 fired_axes mismatch golden={g['fired_axes']} actual={a['fired_axes']}"
        )


def test_bt05_w2026_or_any_extreme_fires() -> None:
    """§9-C 필수 창 근거 사전조건(브리프 아이템 4) — MT0-08에서 확인된 "mobile w2026 첫
    ORANGE가 or_any_extreme 경유"가 이번 재실행에서도 실제로 발화하는지 확인한다.

    발화하지 않으면 D-26/or_any_extreme 이스케이프 경로가 이 9창 패리티 스위트에서 한 번도
    실제로 태워지지 않는다는 뜻이라 이 테스트가 실패로 그 사실을 드러낸다(합성 config 증인을
    추가로 구성하라는 신호) — 조용히 통과시키지 않는다.
    """
    expected_path = PARITY_DIR / "w2026_structural" / "expected.jsonl"
    if not expected_path.exists():
        pytest.skip("w2026_structural/expected.jsonl missing — run export_parity.py first")
    ticks = _load_jsonl(expected_path)
    fired = [t for t in ticks if t["any_extreme"]]
    assert fired, (
        "w2026_structural: any_extreme never fires — D-26/or_any_extreme escape path is not "
        "exercised by this window; a synthetic config witness is required (§9-C)"
    )
