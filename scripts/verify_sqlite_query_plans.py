"""MT1-00f: EXPLAIN QUERY PLAN check for the adopted M1 observation query shapes.

Scope (per M1_PLAN_D.md MT1-00f row + M1_PLAN_FINAL.md M-44): SQLite window
functions are NOT used regardless of version (already decided) — this script
only checks that the *adopted* correlated-subquery query shape hits the
proposed indices (ux_obs_cell_rev / ix_obs_scan) instead of full-scanning, on
a synthetic ~100k-row observation table + a small tick_input table.

Query shapes tested (schema + SQL literal are the D §2.1/§2.2.2 form — the
one M1_PLAN_FINAL.md's mapping table names as the actual implementation
target `:app lake/AsOfQuery.kt`; B §5.4.1 describes the same
as_of-BETWEEN + correlated-subquery-latest-revision *shape* but keyed on
`origin` instead of `lane` — M-43 adopted `lane`, so D's literal SQL is what
this script runs):
  1. confirmed-path range read (lane=0 forced, as_of BETWEEN window_start..cutoff)
  2. preview-path range read (lane IN (0,1), lane=0 wins ties)
  3. tick_input latest committed row

No network, no SSOT files touched. Run: uv run python scripts/verify_sqlite_query_plans.py
"""

from __future__ import annotations

import random
import sqlite3
import time
from datetime import UTC, datetime, timedelta
from pathlib import Path

import yaml

DAY_MS = 86_400_000
N_SERIES = 20
N_FIELDS = 2
N_DAYS = 2500  # regular history days
BASE_AS_OF = int(datetime(2018, 1, 1, tzinfo=UTC).timestamp() * 1000)

_INDICATORS_YAML = (
    Path(__file__).resolve().parent.parent / "configs" / "indicators.yaml"
)


def _warmup_padding_days() -> int:
    """SSOT value (configs/indicators.yaml) — not a literal (CLAUDE.md §1)."""
    cfg = yaml.safe_load(_INDICATORS_YAML.read_text(encoding="utf-8"))
    return int(cfg["engine"]["warmup_padding_days"])


SCHEMA = """
CREATE TABLE observation (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  series_id   TEXT    NOT NULL,
  field       TEXT    NOT NULL,
  as_of       INTEGER NOT NULL,
  value       REAL    NOT NULL,
  observed_at INTEGER NOT NULL,
  revision    INTEGER NOT NULL,
  lane        INTEGER NOT NULL,
  source      TEXT    NOT NULL,
  UNIQUE(series_id, field, as_of, lane, revision)
);
CREATE UNIQUE INDEX ux_obs_cell_rev ON observation(series_id, field, as_of, lane, revision);
CREATE INDEX        ix_obs_scan     ON observation(series_id, field, lane, as_of);

CREATE TABLE tick_input (
  target_date  TEXT PRIMARY KEY,
  evaluated_at INTEGER NOT NULL,
  composite    REAL
);
"""

# D §2.2.2 literal (lane form). :include_preview bound 0 (confirm) or 1 (preview).
RANGE_QUERY = """
SELECT o.as_of, o.value, o.lane, o.revision
  FROM observation o
 WHERE o.series_id = :sid
   AND o.field     = :field
   AND o.as_of    <= :cutoff
   AND o.as_of    >= :window_start
   AND (:include_preview = 1 OR o.lane = 0)
   AND o.id = (SELECT o2.id FROM observation o2
                WHERE o2.series_id = o.series_id
                  AND o2.field     = o.field
                  AND o2.as_of     = o.as_of
                  AND (:include_preview = 1 OR o2.lane = 0)
                ORDER BY o2.lane ASC, o2.revision DESC, o2.id DESC
                LIMIT 1)
 ORDER BY o.as_of ASC
"""

TICK_INPUT_QUERY = """
SELECT target_date, composite
  FROM tick_input
 WHERE composite IS NOT NULL
 ORDER BY target_date DESC
 LIMIT 1
"""


def build_db(seed: int = 20260807) -> sqlite3.Connection:
    con = sqlite3.connect(":memory:")
    con.executescript(SCHEMA)
    rng = random.Random(seed)

    rows: list[tuple] = []
    bumped_cells: dict[
        tuple[str, str, int], float
    ] = {}  # (sid,field,as_of) -> revision-1 value
    for s in range(N_SERIES):
        sid = f"SERIES_{s:02d}"
        for f in range(N_FIELDS):
            field = f"field_{f}"
            for d in range(N_DAYS):
                as_of = BASE_AS_OF + d * DAY_MS
                observed_at = as_of + 17 * 3_600_000  # 17:00 same-day confirm
                rows.append(
                    (
                        sid,
                        field,
                        as_of,
                        rng.uniform(-3, 3),
                        observed_at,
                        0,
                        0,
                        "fixture",
                    )
                )
                if rng.random() < 0.05:  # ~5% of cells get a same-day late revision
                    bumped_value = rng.uniform(-3, 3)
                    rows.append(
                        (
                            sid,
                            field,
                            as_of,
                            bumped_value,
                            observed_at + 3_600_000,
                            1,
                            0,
                            "fixture",
                        )
                    )
                    bumped_cells[(sid, field, as_of)] = bumped_value
                if d >= N_DAYS - 30:  # last 30 days: also carry an intraday preview row
                    rows.append(
                        (
                            sid,
                            field,
                            as_of,
                            rng.uniform(-3, 3),
                            as_of + 5 * 3_600_000,
                            0,
                            1,
                            "fixture",
                        )
                    )

    # "today": preview fired, confirm collection has not landed yet (no lane=0 row at all)
    today_as_of = BASE_AS_OF + N_DAYS * DAY_MS
    for s in range(N_SERIES):
        sid = f"SERIES_{s:02d}"
        for f in range(N_FIELDS):
            field = f"field_{f}"
            rows.append(
                (
                    sid,
                    field,
                    today_as_of,
                    rng.uniform(-3, 3),
                    today_as_of + 5 * 3_600_000,
                    0,
                    1,
                    "fixture",
                )
            )

    con.executemany(
        "INSERT INTO observation(series_id, field, as_of, value, observed_at, revision, lane, source) "
        "VALUES (?,?,?,?,?,?,?,?)",
        rows,
    )

    tick_rows = []
    for d in range(N_DAYS):
        target_date = (datetime(2018, 1, 1, tzinfo=UTC) + timedelta(days=d)).strftime(
            "%Y-%m-%d"
        )
        composite = (
            None if d % 97 == 0 else rng.uniform(0, 4)
        )  # sprinkle frozen/gap rows (M-34)
        tick_rows.append((target_date, BASE_AS_OF + d * DAY_MS, composite))
    con.executemany("INSERT INTO tick_input VALUES (?,?,?)", tick_rows)

    con.commit()
    return con, today_as_of, bumped_cells


def explain(con: sqlite3.Connection, sql: str, params: dict) -> list[str]:
    plan = con.execute("EXPLAIN QUERY PLAN " + sql, params).fetchall()
    return [row[-1] for row in plan]  # last column = detail text


def full_scan_detected(plan_lines: list[str]) -> bool:
    return any(
        "SCAN observation" in line and "USING INDEX" not in line for line in plan_lines
    )


def time_calls(con: sqlite3.Connection, sql: str, param_list: list[dict]) -> float:
    t0 = time.perf_counter()
    for params in param_list:
        con.execute(sql, params).fetchall()
    return (time.perf_counter() - t0) / len(param_list) * 1000  # ms/call


def main() -> None:
    warmup_padding_days = _warmup_padding_days()
    con, today_as_of, bumped_cells = build_db()

    n_obs = con.execute("SELECT COUNT(*) FROM observation").fetchone()[0]
    n_tick = con.execute("SELECT COUNT(*) FROM tick_input").fetchone()[0]
    print(f"sqlite3.sqlite_version = {sqlite3.sqlite_version}")
    print(f"observation rows = {n_obs:,}  tick_input rows = {n_tick:,}")
    print(f"warmup_padding_days (SSOT configs/indicators.yaml) = {warmup_padding_days}")

    cutoff = today_as_of
    window_start = cutoff - warmup_padding_days * DAY_MS
    sample_pairs = [
        (f"SERIES_{s:02d}", f"field_{f}")
        for s in range(N_SERIES)
        for f in range(N_FIELDS)
    ]

    print("\n--- (1) confirmed-path range query (lane=0 forced) - BEFORE ANALYZE ---")
    p1 = {
        "sid": sample_pairs[0][0],
        "field": sample_pairs[0][1],
        "cutoff": cutoff,
        "window_start": window_start,
        "include_preview": 0,
    }
    plan1 = explain(con, RANGE_QUERY, p1)
    for line in plan1:
        print(" ", line)
    print("  full scan? ", full_scan_detected(plan1))

    print("\n--- (2) preview-path range query (lane IN (0,1)) - BEFORE ANALYZE ---")
    p2 = dict(p1, include_preview=1)
    plan2 = explain(con, RANGE_QUERY, p2)
    for line in plan2:
        print(" ", line)
    print("  full scan? ", full_scan_detected(plan2))

    print("\n--- (3) tick_input latest committed row ---")
    plan3 = explain(con, TICK_INPUT_QUERY, {})
    for line in plan3:
        print(" ", line)
    print(
        "  full scan? ",
        any("SCAN tick_input" in line and "USING INDEX" not in line for line in plan3),
    )

    con.execute("ANALYZE")
    print("\n--- (1) same query AFTER ANALYZE (does the plan change?) ---")
    plan1b = explain(con, RANGE_QUERY, p1)
    for line in plan1b:
        print(" ", line)
    print("  plan changed vs pre-ANALYZE?", plan1b != plan1)

    confirm_params = [dict(p1, sid=sid, field=field) for sid, field in sample_pairs]
    preview_params = [dict(p2, sid=sid, field=field) for sid, field in sample_pairs]
    t_confirm = time_calls(con, RANGE_QUERY, confirm_params)
    t_preview = time_calls(con, RANGE_QUERY, preview_params)
    t_tick = time_calls(con, TICK_INPUT_QUERY, [{}] * 50)
    print(
        f"\navg ms/call  confirm-range={t_confirm:.3f}  preview-range={t_preview:.3f}  tick_input={t_tick:.4f}"
    )
    print(
        f"rough per-tick estimate (~25 series x field pairs, confirm path): {t_confirm * 25:.2f} ms"
    )

    _self_check(con, today_as_of, bumped_cells, cutoff, window_start, sample_pairs[0])
    print("\nself-check: PASS")


def _self_check(
    con, today_as_of, bumped_cells, cutoff, window_start, first_pair
) -> None:
    """Runnable correctness witnesses for the adopted query shape (not just plan shape)."""
    sid, field = first_pair

    # W-a: confirm path excludes the not-yet-confirmed "today" cell (lane=1 only, no lane=0).
    confirm_rows = con.execute(
        RANGE_QUERY,
        {
            "sid": sid,
            "field": field,
            "cutoff": cutoff,
            "window_start": window_start,
            "include_preview": 0,
        },
    ).fetchall()
    assert all(r[0] != today_as_of for r in confirm_rows), (
        "confirm path leaked a preview-only cell"
    )

    # W-b: preview path DOES include that same "today" cell.
    preview_rows = con.execute(
        RANGE_QUERY,
        {
            "sid": sid,
            "field": field,
            "cutoff": cutoff,
            "window_start": window_start,
            "include_preview": 1,
        },
    ).fetchall()
    assert any(r[0] == today_as_of for r in preview_rows), (
        "preview path missed the not-yet-confirmed cell"
    )

    # W-c: for a cell that got a same-day revision bump, confirm path returns the LATEST
    # revision's value, not the original revision=0 value.
    bumped_as_of, bumped_value = next(
        (
            (ao, v)
            for (s, fl, ao), v in bumped_cells.items()
            if s == sid and fl == field and window_start <= ao <= cutoff
        ),
        (None, None),
    )
    if bumped_as_of is not None:
        got = next(r[1] for r in confirm_rows if r[0] == bumped_as_of)
        assert abs(got - bumped_value) < 1e-9, (
            "confirm path did not pick the latest revision"
        )

    # W-d: tick_input query never returns a frozen (composite IS NULL) row.
    tick_row = con.execute(TICK_INPUT_QUERY, {}).fetchone()
    assert tick_row is not None and tick_row[1] is not None


if __name__ == "__main__":
    main()
