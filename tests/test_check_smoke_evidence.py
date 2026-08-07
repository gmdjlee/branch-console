"""MT1-08c self-check for scripts/check_smoke_evidence.py -- pure logic, no
network, no Android tooling. Fixtures are synthesized JSON snapshots written
to `tmp_path` per test (this script's whole job is reading directories of
JSON files, so that *is* the fixture format -- no separate fixtures/ dir).

Both directions are required (docs/plans/M1_PLAN_D.md §11.3): a judge that
always exits 0 is not a judge."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from scripts.check_smoke_evidence import main, run_checks

REGISTRY_VERSION = "0.3.1-rc"
MANIFEST_SHA = "a" * 64


def _snapshot(
    tick_input: int,
    run_log: int = 1,
    observation: int = 10,
    current_phase: str | None = "NORMAL",
    last_tick: dict[str, Any] | None = None,
    last_run: dict[str, Any] | None = None,
    registry_version: str = REGISTRY_VERSION,
    manifest_sha: str = MANIFEST_SHA,
) -> dict[str, Any]:
    return {
        "app": {
            "version_name": "0.1.0",
            "registry_version": registry_version,
            "assets_manifest_sha256": manifest_sha,
        },
        "exported_at_epoch_millis": 0,
        "counts": {
            "tick_input": tick_input,
            "run_log": run_log,
            "observation": observation,
        },
        "current_phase": current_phase,
        "last_tick": last_tick,
        "last_run": last_run,
    }


def _write(evidence_dir: Path, role: str, snapshot: dict[str, Any]) -> None:
    (evidence_dir / f"diag-{role}.json").write_text(
        json.dumps(snapshot), encoding="utf-8"
    )


def _write_full_pass_scenario(evidence_dir: Path) -> None:
    """A clean single-day smoke run: S-1 pre-tick, S-2 confirms, S-3a/b/c
    preview taps leave everything unchanged, S-4 re-run is idempotent."""
    committed_tick = {
        "trading_date": "2026-08-10",
        "coverage": 1.0,
        "is_catchup": False,
        "gap_reason": None,
    }
    success_run = {
        "trading_date": "2026-08-10",
        "status": "success",
        "detail": "committed=1",
    }

    _write(
        evidence_dir,
        "s1",
        _snapshot(tick_input=0, run_log=0, observation=200, current_phase=None),
    )
    _write(
        evidence_dir,
        "s2",
        _snapshot(
            tick_input=1,
            run_log=1,
            observation=210,
            last_tick=committed_tick,
            last_run=success_run,
        ),
    )
    for role in ("s3a", "s3b", "s3c"):
        _write(
            evidence_dir,
            role,
            _snapshot(
                tick_input=1,
                run_log=1,
                observation=220,
                last_tick=committed_tick,
                last_run=success_run,
            ),
        )
    _write(
        evidence_dir,
        "s4",
        _snapshot(
            tick_input=1,
            run_log=3,
            observation=220,
            last_tick=committed_tick,
            last_run=success_run,
        ),
    )


def test_full_pass_scenario_exits_zero(tmp_path: Path) -> None:
    _write_full_pass_scenario(tmp_path)

    results = run_checks(tmp_path)

    assert results, (
        "a passing directory must still produce checks (never an empty vacuous pass)"
    )
    assert all(r.passed for r in results), [r for r in results if not r.passed]
    assert main([str(tmp_path)]) == 0


def test_missing_role_file_fails(tmp_path: Path) -> None:
    _write_full_pass_scenario(tmp_path)
    (tmp_path / "diag-s4.json").unlink()

    results = run_checks(tmp_path)

    assert not all(r.passed for r in results)
    assert main([str(tmp_path)]) == 1


def test_ambiguous_role_files_fails(tmp_path: Path) -> None:
    _write_full_pass_scenario(tmp_path)
    _write(tmp_path, "s2-retry", _snapshot(tick_input=1))

    results = run_checks(tmp_path)

    assert any(r.name == "evidence_files" and not r.passed for r in results)


def test_schema_missing_key_fails(tmp_path: Path) -> None:
    _write_full_pass_scenario(tmp_path)
    broken = json.loads((tmp_path / "diag-s1.json").read_text(encoding="utf-8"))
    del broken["current_phase"]
    (tmp_path / "diag-s1.json").write_text(json.dumps(broken), encoding="utf-8")

    results = run_checks(tmp_path)

    assert any(r.name == "schema[s1]" and not r.passed for r in results)


def test_registry_version_drift_fails(tmp_path: Path) -> None:
    _write_full_pass_scenario(tmp_path)
    drifted = json.loads((tmp_path / "diag-s4.json").read_text(encoding="utf-8"))
    drifted["app"]["registry_version"] = "0.3.0-rc"
    (tmp_path / "diag-s4.json").write_text(json.dumps(drifted), encoding="utf-8")

    results = run_checks(tmp_path)

    assert any(r.name == "registry_consistency" and not r.passed for r in results)


def test_s1_fails_when_tick_input_already_nonzero(tmp_path: Path) -> None:
    _write_full_pass_scenario(tmp_path)
    (tmp_path / "diag-s1.json").write_text(
        json.dumps(_snapshot(tick_input=1)), encoding="utf-8"
    )

    results = run_checks(tmp_path)

    assert any(r.name == "S-1" and not r.passed for r in results)


def test_s1_fails_when_gate_reports_warmup_insufficient(tmp_path: Path) -> None:
    _write_full_pass_scenario(tmp_path)
    blocked = _snapshot(
        tick_input=0,
        last_run={
            "trading_date": None,
            "status": "WARMUP_INSUFFICIENT",
            "detail": "{}",
        },
    )
    (tmp_path / "diag-s1.json").write_text(json.dumps(blocked), encoding="utf-8")

    results = run_checks(tmp_path)

    assert any(r.name == "S-1" and not r.passed for r in results)


def test_s2_fails_when_tick_input_delta_is_not_exactly_one(tmp_path: Path) -> None:
    _write_full_pass_scenario(tmp_path)
    jumped = json.loads((tmp_path / "diag-s2.json").read_text(encoding="utf-8"))
    jumped["counts"]["tick_input"] = 3
    (tmp_path / "diag-s2.json").write_text(json.dumps(jumped), encoding="utf-8")

    results = run_checks(tmp_path)

    assert any(r.name == "S-2" and not r.passed for r in results)


def test_s2_fails_when_last_run_status_is_not_success(tmp_path: Path) -> None:
    _write_full_pass_scenario(tmp_path)
    failed_run = json.loads((tmp_path / "diag-s2.json").read_text(encoding="utf-8"))
    failed_run["last_run"]["status"] = "failed"
    (tmp_path / "diag-s2.json").write_text(json.dumps(failed_run), encoding="utf-8")

    results = run_checks(tmp_path)

    assert any(r.name == "S-2" and not r.passed for r in results)


def test_s2_fails_when_gap_reason_is_present(tmp_path: Path) -> None:
    _write_full_pass_scenario(tmp_path)
    gapped = json.loads((tmp_path / "diag-s2.json").read_text(encoding="utf-8"))
    gapped["last_tick"]["gap_reason"] = "CATCHUP_GAP_TRUNCATED"
    (tmp_path / "diag-s2.json").write_text(json.dumps(gapped), encoding="utf-8")

    results = run_checks(tmp_path)

    assert any(r.name == "S-2" and not r.passed for r in results)


def test_s3_fails_when_a_preview_tap_commits_a_tick(tmp_path: Path) -> None:
    _write_full_pass_scenario(tmp_path)
    committed_during_preview = json.loads(
        (tmp_path / "diag-s3b.json").read_text(encoding="utf-8")
    )
    committed_during_preview["counts"]["tick_input"] = 2
    (tmp_path / "diag-s3b.json").write_text(
        json.dumps(committed_during_preview), encoding="utf-8"
    )

    results = run_checks(tmp_path)

    assert any(r.name == "S-3(s3b)" and not r.passed for r in results)


def test_s3_fails_when_phase_moves_during_preview(tmp_path: Path) -> None:
    _write_full_pass_scenario(tmp_path)
    moved_phase = json.loads((tmp_path / "diag-s3c.json").read_text(encoding="utf-8"))
    moved_phase["current_phase"] = "RED"
    (tmp_path / "diag-s3c.json").write_text(json.dumps(moved_phase), encoding="utf-8")

    results = run_checks(tmp_path)

    assert any(r.name == "S-3(s3c)" and not r.passed for r in results)


def test_s4_fails_when_rerun_adds_another_tick_input_row(tmp_path: Path) -> None:
    _write_full_pass_scenario(tmp_path)
    duplicated = json.loads((tmp_path / "diag-s4.json").read_text(encoding="utf-8"))
    duplicated["counts"]["tick_input"] = 2
    (tmp_path / "diag-s4.json").write_text(json.dumps(duplicated), encoding="utf-8")

    results = run_checks(tmp_path)

    assert any(r.name == "S-4" and not r.passed for r in results)


def test_s4_fails_when_trading_date_advances_on_rerun(tmp_path: Path) -> None:
    _write_full_pass_scenario(tmp_path)
    advanced = json.loads((tmp_path / "diag-s4.json").read_text(encoding="utf-8"))
    advanced["last_tick"]["trading_date"] = "2026-08-11"
    (tmp_path / "diag-s4.json").write_text(json.dumps(advanced), encoding="utf-8")

    results = run_checks(tmp_path)

    assert any(r.name == "S-4" and not r.passed for r in results)


def test_role_matching_does_not_confuse_s1_with_a_hypothetical_s10(
    tmp_path: Path,
) -> None:
    _write_full_pass_scenario(tmp_path)
    (tmp_path / "diag-s10-unrelated.json").write_text(
        json.dumps(_snapshot(tick_input=99)), encoding="utf-8"
    )

    results = run_checks(tmp_path)

    assert all(r.passed for r in results), [r for r in results if not r.passed]
