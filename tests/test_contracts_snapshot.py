"""tests/test_contracts_snapshot.py — MT1-02a: contract schema snapshot freeze
tests (Python side, docs/plans/M1_PLAN_B.md §6).

contracts/*.py is NOT modified by this test file — the snapshots freeze contracts'
*current* behavior, they don't shape it (CLAUDE.md §1 SSOT rule). Network-free: every
assertion here reads either `contracts/snapshots/*.json` or the pure builder functions
in `scripts/gen_contract_snapshots.py`.

Covers (§6, brief 완료 기준):
① 스키마 스냅샷 동결 일치 — generator --check against the checked-in files (§6.5).
② 양성 픽스처 왕복 — parse -> model -> re-dump is byte-identical, "schema" alias
   (not "schema_id") fixed per M-16/D-B6.
③ invalid/ 6종 전건이 대응 모델에서 실제로 거부됨.
④ asymmetric/naive_datetime — Python(pydantic) currently ACCEPTS it (§6.2.1 채택
   (a): 부정 케이스가 아니라 "알려진 비대칭"의 고정). Kotlin's `Instant.parse` is
   expected to reject the same input (MT1-02b/c scope) — not exercised here.

커버리지 스코프 (QA 발견 — 이관자 확인용): M1_PLAN_B.md §3.2.1의 결합 명령
(`--cov=backtest.export_parity --cov=scripts.gen_contract_snapshots`)은
`backtest/test_export_parity.py`가 아직 없어(MT1-05e 미착수) 현재 실행 불가하다.
지금은 `scripts.gen_contract_snapshots` 단독 `--cov-fail-under=90`으로 검증한다
(실측 99%). MT1-05e 완료 기준에 결합 명령 복원을 포함시킬 것.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from pydantic import BaseModel, ValidationError

from contracts.evidence import EvidencePack
from contracts.snapshot import ScenarioSnapshot
from scripts import gen_contract_snapshots as gen

POSITIVE_FIXTURES: tuple[tuple[str, type[BaseModel]], ...] = (
    ("scenario_snapshot.min.json", ScenarioSnapshot),
    ("scenario_snapshot.full.json", ScenarioSnapshot),
    ("evidence_pack.min.json", EvidencePack),
    ("evidence_pack.full.json", EvidencePack),
)


def test_generator_check_reports_zero_drift() -> None:
    """① 완료 명령 §6.5: 저장된 스냅샷이 현재 contracts.*로 재생성한 결과와 바이트
    단위로 일치 (drift 0)."""
    assert gen.main(["--check"]) == 0


@pytest.mark.parametrize(("filename", "model"), POSITIVE_FIXTURES)
def test_positive_fixture_round_trips_byte_identical(
    filename: str, model: type[BaseModel]
) -> None:
    """② 파싱 -> 모델 -> 재직렬화가 원본 바이트와 동일 (왕복)."""
    path = gen.SNAPSHOTS_DIR / filename
    original = path.read_text(encoding="utf-8")
    parsed = model.model_validate(json.loads(original))
    assert gen._dump_model(parsed) == original, f"{filename}: round-trip drift"


@pytest.mark.parametrize(("filename", "_model"), POSITIVE_FIXTURES)
def test_positive_fixture_uses_schema_alias_key(
    filename: str, _model: type[BaseModel]
) -> None:
    """M-16/D-B6: 와이어 필드명은 by_alias=True("schema") 고정 — "schema_id" 키는
    직렬화 결과에 나타나지 않는다."""
    payload = json.loads((gen.SNAPSHOTS_DIR / filename).read_text(encoding="utf-8"))
    assert payload["schema"] in ("scenario-snapshot/1", "evidence-pack/1")
    assert "schema_id" not in payload


@pytest.mark.parametrize("name", sorted(gen.INVALID_CASES))
def test_invalid_case_rejected_by_its_model(name: str) -> None:
    """③ invalid/ 6종 전건이 현행 contracts에서 실제로 거부됨(가정이 아니라 실행)."""
    model, expected_payload = gen.INVALID_CASES[name]
    on_disk = json.loads((gen.INVALID_DIR / f"{name}.json").read_text(encoding="utf-8"))
    assert on_disk == expected_payload, (
        f"{name}: on-disk fixture does not match generator"
    )
    with pytest.raises(ValidationError):
        model.model_validate(on_disk)


def test_invalid_cases_cover_all_six_documented_violations() -> None:
    """§6.2 목록의 6종이 전부 존재 - 세지 않고 부분집합만 두는 축소를 차단."""
    assert set(gen.INVALID_CASES) == {
        "composite_out_of_range",
        "subjective_prob_over_one",
        "horizon_days_zero",
        "phase_unknown",
        "scenarios_too_few",
        "leading_indicators_one",
    }


def test_asymmetric_naive_datetime_accepted_by_python() -> None:
    """④ §6.2.1: naive datetime은 현재 pydantic 스키마(AwareDatetime 미적용, 검증기
    0건)에서 거부되지 않는다 — 회귀가 아니라 알려진 비대칭의 고정이다. Kotlin의
    `Instant.parse`는 오프셋 없는 문자열을 거부할 예정이므로(MT1-02b/c), 이 비대칭을
    방치하면 M2에서 LLM이 오프셋 없는 시각을 반환할 때 Python은 통과·Kotlin은
    크래시하는 형태로 터진다 — 그 사실을 지금 눈에 보이게 고정해 둔다."""
    model, payload = gen.ASYMMETRIC_CASES["naive_datetime"]
    on_disk = json.loads(
        (gen.ASYMMETRIC_DIR / "naive_datetime.json").read_text(encoding="utf-8")
    )
    assert on_disk == payload

    parsed = model.model_validate(on_disk)  # must NOT raise (current Python behavior)

    assert parsed.evaluated_at.tzinfo is None  # naive preserved, not silently coerced


def test_shape_digest_file_matches_recomputed_hash() -> None:
    """형상 다이제스트(§6.2 candidate B) 파일이 현재 코드에서 재산출한 해시와 일치."""
    on_disk = gen.SHAPE_SHA256_PATH.read_text(encoding="utf-8").strip()
    assert on_disk == gen.shape_digest_hex()


def test_check_reports_drift_and_missing_targets(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    """--check의 실패 경로: 내용이 다른 파일과 아예 없는 파일을 모두 stderr에
    보고하고 exit 1을 반환한다."""
    drifted = tmp_path / "drifted.json"
    drifted.write_text("stale content\n", encoding="utf-8")
    missing = tmp_path / "missing.json"

    monkeypatch.setattr(
        gen,
        "build_targets",
        lambda: {drifted: "fresh content\n", missing: "anything\n"},
    )

    assert gen.main(["--check"]) == 1
    err = capsys.readouterr().err
    assert f"DRIFT {drifted}" in err
    assert f"MISSING {missing}" in err


def test_main_write_mode_reproduces_checked_in_snapshots(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """generator의 쓰기 경로(--check가 아닌 기본 모드) 자체를 격리된 디렉터리에서
    실행해 build_targets()의 기대값과 실제로 일치하는 파일을 생성하는지 확인한다."""
    monkeypatch.setattr(gen, "SNAPSHOTS_DIR", tmp_path)
    monkeypatch.setattr(gen, "INVALID_DIR", tmp_path / "invalid")
    monkeypatch.setattr(gen, "ASYMMETRIC_DIR", tmp_path / "asymmetric")
    monkeypatch.setattr(gen, "SHAPE_SHA256_PATH", tmp_path / "shape.sha256")

    assert gen.main([]) == 0

    for path, content in gen.build_targets().items():
        assert path.read_text(encoding="utf-8") == content

    assert gen.main(["--check"]) == 0  # freshly written copy must self-verify too
