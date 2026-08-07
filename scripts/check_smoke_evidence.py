"""MT1-08c machine judge for the M1 on-device smoke evidence
(docs/plans/M1_PLAN_D.md §11.3, docs/runbooks/M1_SMOKE.md).

Reads the diagnostic JSON snapshots exported by the app's MT1-08d "진단
내보내기" feature and judges the S-1~S-4 pass conditions mechanically --
replacing "a human eyeballs the JSON and calls it fine" (the MT0-06
procedural incident this exists to prevent).

Scope note (brief-documented deviation from the §11.3 prose): the shipped
diagnostic JSON is `app` + `counts` (tick_input/run_log/observation) +
`current_phase` + `last_tick` + `last_run` + `last_success_run` -- not the
full `phase_commit[]`/`indicators[]`/`preview[]` row dumps that §11.3
sketches. `phase_commit` never existed as a production table (brief aaa
item 1); its role in the S-2/S-3 judgment below is played by
`counts.tick_input` + `current_phase` instead, per that same aaa item.
`last_success_run` exists separately from `last_run` for the reason in
check_s2's docstring (aaa C-1): `last_run` is whatever ran most recently,
which a cold-start catchup can turn into a "noop" after a genuine success.

Snapshot roles are identified by filename prefix (case-insensitive), not by
position in a directory listing -- the smoke procedure produces one export
per step and the runbook instructs saving each under its role name:
  diag-s1*.json       after warmup, before the first confirmed tick
  diag-s2*.json       after the confirmed tick fires
  diag-s3a/b/c*.json  the three preview taps at distinct clock conditions
  diag-s4*.json       after the same-day manual confirmed-tick re-run

S-5 (advisory, outside the gate per §11) and S-6 (a non-JSON instrumented
test log) are not judged here -- see the runbook for those.

Usage:
    uv run python scripts/check_smoke_evidence.py docs/gates/evidence/GM1/
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

REQUIRED_ROLES = ("s1", "s2", "s3a", "s3b", "s3c", "s4")
WARMUP_INSUFFICIENT = "WARMUP_INSUFFICIENT"

REQUIRED_TOP_KEYS = (
    "app",
    "exported_at_epoch_millis",
    "counts",
    "current_phase",
    "last_tick",
    "last_run",
    "last_success_run",
)
REQUIRED_APP_KEYS = ("version_name", "registry_version", "assets_manifest_sha256")
REQUIRED_COUNT_KEYS = ("tick_input", "run_log", "observation")


@dataclass(frozen=True)
class CheckResult:
    name: str
    passed: bool
    detail: str


def find_role_files(evidence_dir: Path) -> dict[str, list[Path]]:
    """Map role name -> matching file(s) under `diag-<role>[-label].json`
    (case-insensitive). More than one match for a role is a caller-reported
    failure (ambiguous evidence), not silently resolved by picking one."""
    roles: dict[str, list[Path]] = {role: [] for role in REQUIRED_ROLES}
    for path in sorted(evidence_dir.glob("*.json")):
        stem = path.stem.lower()
        if not stem.startswith("diag-"):
            continue
        rest = stem[len("diag-") :]
        for role in REQUIRED_ROLES:
            if rest == role or rest.startswith((role + "-", role + "_")):
                roles[role].append(path)
                break
    return roles


def load_snapshot(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def check_schema(role: str, snapshot: dict[str, Any]) -> CheckResult:
    name = f"schema[{role}]"
    missing_top = [k for k in REQUIRED_TOP_KEYS if k not in snapshot]
    if missing_top:
        return CheckResult(name, False, f"missing top-level keys: {missing_top}")
    missing_app = [k for k in REQUIRED_APP_KEYS if k not in snapshot["app"]]
    if missing_app:
        return CheckResult(name, False, f"missing app keys: {missing_app}")
    missing_counts = [k for k in REQUIRED_COUNT_KEYS if k not in snapshot["counts"]]
    if missing_counts:
        return CheckResult(name, False, f"missing counts keys: {missing_counts}")
    return CheckResult(name, True, "ok")


def check_registry_consistency(snapshots: dict[str, dict[str, Any]]) -> CheckResult:
    """K-16 -- one smoke session is one install, so registry_version and the
    packaged assets manifest hash must not drift between snapshots."""
    versions = {role: s["app"]["registry_version"] for role, s in snapshots.items()}
    manifests = {
        role: s["app"]["assets_manifest_sha256"] for role, s in snapshots.items()
    }
    if len(set(versions.values())) > 1:
        return CheckResult(
            "registry_consistency", False, f"registry_version drifted: {versions}"
        )
    if len(set(manifests.values())) > 1:
        return CheckResult(
            "registry_consistency",
            False,
            f"assets_manifest_sha256 drifted: {manifests}",
        )
    return CheckResult("registry_consistency", True, "ok")


def check_s1(s1: dict[str, Any]) -> CheckResult:
    """§11.2 S-1: warmup complete + bootstrap gate -- tick_input must still
    be 0 (no confirmed tick has committed yet) and, if a confirm-tick run
    already happened, it must not have been rejected for insufficient
    warmup (that would mean warmup isn't actually done)."""
    tick_count = s1["counts"]["tick_input"]
    if tick_count != 0:
        return CheckResult(
            "S-1",
            False,
            f"expected tick_input count 0 before the first confirmed tick, got {tick_count}",
        )
    last_run = s1.get("last_run")
    if last_run is not None and last_run.get("status") == WARMUP_INSUFFICIENT:
        return CheckResult(
            "S-1",
            False,
            "bootstrap gate still reports WARMUP_INSUFFICIENT -- warmup not actually complete",
        )
    return CheckResult("S-1", True, "ok")


def check_s2(s1: dict[str, Any], s2: dict[str, Any]) -> CheckResult:
    """§11.2 S-2, MT1-06 semantics (brief aaa item 2): the confirmed tick
    always advances tick_input by exactly +1 (bootstrap day 1 or any later
    day alike), commits with gap 0, and a derivable phase.

    aaa C-1: success is judged from `last_success_run` (the latest run_log
    row with status "success"), not `last_run` (the latest row by ran_at).
    BranchConsoleApplication.onCreate calls triggerCatchupNow on every cold
    start; if the user reopens the app to export S-2's diagnostic JSON,
    that catchup re-run finds nothing left to do and logs a *later* "noop"
    row -- making `last_run.status` a non-deterministic proxy for "did the
    tick actually succeed" even on a genuinely healthy run. Tying the check
    to `last_tick.trading_date` (the day just committed) rather than
    whatever ran last sidesteps that race entirely.
    """
    before, after = s1["counts"]["tick_input"], s2["counts"]["tick_input"]
    if after != before + 1:
        return CheckResult(
            "S-2",
            False,
            f"tick_input delta must be exactly +1, got {before} -> {after}",
        )
    last_tick = s2.get("last_tick")
    if last_tick is None or last_tick.get("gap_reason") is not None:
        return CheckResult(
            "S-2", False, f"last_tick.gap_reason must be null (gap 0), got {last_tick}"
        )
    last_success_run = s2.get("last_success_run")
    if last_success_run is None or last_success_run.get(
        "trading_date"
    ) != last_tick.get("trading_date"):
        return CheckResult(
            "S-2",
            False,
            f"no success run_log row found for the committed date {last_tick.get('trading_date')!r} "
            f"(last_success_run={last_success_run})",
        )
    if s2.get("current_phase") is None:
        return CheckResult(
            "S-2", False, "current_phase must be derivable once a tick_input row exists"
        )
    return CheckResult("S-2", True, "ok")


def check_s3(label: str, s2: dict[str, Any], s3: dict[str, Any]) -> CheckResult:
    """§11.2 S-3 phase invariance, substituting `phase_commit` row-count
    (brief aaa item 1, that table never existed in production) with
    `tick_input` count + `current_phase` -- a preview tap must commit
    nothing and must not move the derived phase."""
    if s3["counts"]["tick_input"] != s2["counts"]["tick_input"]:
        return CheckResult(
            label,
            False,
            "tick_input count changed during a preview tap -- preview must not commit a confirmed tick",
        )
    if s3.get("current_phase") != s2.get("current_phase"):
        return CheckResult(
            label,
            False,
            f"phase must be invariant across preview taps: {s2.get('current_phase')} -> {s3.get('current_phase')}",
        )
    return CheckResult(label, True, "ok")


def check_s4(s2: dict[str, Any], s4: dict[str, Any]) -> CheckResult:
    """§11.2 S-4 idempotency: manually re-running the same trading day must
    not add another tick_input row or change which day was last committed."""
    if s4["counts"]["tick_input"] != s2["counts"]["tick_input"]:
        return CheckResult(
            "S-4",
            False,
            "tick_input count changed on same-day manual re-run -- not idempotent",
        )
    s2_tick, s4_tick = s2.get("last_tick") or {}, s4.get("last_tick") or {}
    if s2_tick.get("trading_date") != s4_tick.get("trading_date"):
        return CheckResult(
            "S-4", False, "last_tick.trading_date changed on re-run -- not idempotent"
        )
    return CheckResult("S-4", True, "ok")


def run_checks(evidence_dir: Path) -> list[CheckResult]:
    role_files = find_role_files(evidence_dir)
    results: list[CheckResult] = []

    missing_roles = [role for role, paths in role_files.items() if not paths]
    if missing_roles:
        results.append(
            CheckResult(
                "evidence_files",
                False,
                f"missing required diag-<role>.json for: {missing_roles}",
            )
        )
        return results
    ambiguous_roles = [role for role, paths in role_files.items() if len(paths) > 1]
    if ambiguous_roles:
        results.append(
            CheckResult(
                "evidence_files",
                False,
                f"more than one file matched roles: {ambiguous_roles}",
            )
        )
        return results

    snapshots = {role: load_snapshot(paths[0]) for role, paths in role_files.items()}
    for role, snapshot in snapshots.items():
        results.append(check_schema(role, snapshot))
    if not all(r.passed for r in results):
        return results

    results.append(check_registry_consistency(snapshots))
    results.append(check_s1(snapshots["s1"]))
    results.append(check_s2(snapshots["s1"], snapshots["s2"]))
    for role in ("s3a", "s3b", "s3c"):
        results.append(check_s3(f"S-3({role})", snapshots["s2"], snapshots[role]))
    results.append(check_s4(snapshots["s2"], snapshots["s4"]))
    return results


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        "evidence_dir",
        type=Path,
        help="docs/gates/evidence/GM1/ (or a fixture directory)",
    )
    args = parser.parse_args(argv)

    if not args.evidence_dir.is_dir():
        print(
            f"FAIL evidence_dir: {args.evidence_dir} is not a directory",
            file=sys.stderr,
        )
        return 1

    results = run_checks(args.evidence_dir)
    for result in results:
        print(f"{'PASS' if result.passed else 'FAIL'} {result.name}: {result.detail}")

    failed = [r for r in results if not r.passed]
    if failed:
        print(f"\n{len(failed)}/{len(results)} checks failed", file=sys.stderr)
        return 1
    print(f"\nall {len(results)} checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
