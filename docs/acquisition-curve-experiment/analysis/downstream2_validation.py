#!/usr/bin/env python3
"""The pre-registered analysis of downstream validation round 2.

Round 1's analysis lives beside this one in `downstream_validation.py` and is NOT edited: its numbers
are the published record of a wave that could not answer its question, and a script that quietly
grew new endpoints would erase the evidence for why this round exists.

Two things changed, both fixed in DESIGN-DOWNSTREAM-2.md before any cell was queued:

  * the solving agent runs under a hard repository-interaction budget, so `used`/`denied` are real
    columns rather than always-empty ones;
  * the oracle is de-cascaded into nine independent assertions, so `passed` is a smooth 1..9 scale
    (1, not 0, is the floor on a tree that compiles) instead of round 1's `{0} u {5..8}`.

The statistics are imported from round 1's script rather than re-implemented. Two copies of a
cluster permutation is exactly the kind of drift this project keeps paying for.

Input CSV, one row per cell, anchors included:

    condition,trajectory_id,checkpoint,arm,u_obs,u_note,passed,total,budget,used,denied,
    within,tool_calls,output_tokens,seconds,usd,build_id

`condition` is `baseline`, `oracle-gold`, or `checkpoint`. Anchor rows carry an empty `checkpoint`,
`u_obs` and `u_note`. A cell whose measurement was lost carries an empty `passed` and is reported
separately — never as a zero, which would be a claim about an agent that was never graded.

Usage:
    python3 downstream2_validation.py --cells data/downstream2-cells.csv
"""

import argparse
import csv
import pathlib
import random
import statistics
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from downstream_validation import cluster_permutation, spearman  # noqa: E402

#: The four gates of stage 1, as DESIGN-DOWNSTREAM-2.md fixes them. Kept as data so that the report
#: prints the rule next to its verdict: a gate whose threshold is only in a document is a gate that
#: gets remembered generously.
GATES = [
    ("G1 floor leaves room", "mean(baseline) <= 5 and max(baseline) < 8", "tighten the budget to 15"),
    ("G2 ceiling reachable", "min(oracle-gold) >= 7 and max(oracle-gold) == 9", "loosen the budget to 25"),
    ("G3 the gap is real", "mean(oracle-gold) - mean(baseline) >= 3", "stop: ungradable downstream"),
    ("G4 noise below the gap", "sd(baseline) <= min(2.5, gap/2) and no anchor cell LOST", "do not buy single rollouts"),
]


def read_cells(path: pathlib.Path) -> list:
    with path.open() as handle:
        return [row for row in csv.DictReader(handle)]


def number(row: dict, column: str):
    """A column that may legitimately be empty, as a float or None — never as a zero."""
    value = (row.get(column) or "").strip()
    return float(value) if value else None


def of(rows: list, condition: str) -> list:
    return [row for row in rows if row["condition"] == condition]


def passed_values(rows: list) -> list:
    return [number(row, "passed") for row in rows if number(row, "passed") is not None]


def describe(name: str, rows: list) -> str:
    values = passed_values(rows)
    lost = len(rows) - len(values)
    if not values:
        return f"{name}: no graded cell ({lost} lost)"
    spread = statistics.stdev(values) if len(values) > 1 else 0.0
    return (
        f"{name}: n={len(values)} passed={[int(v) for v in values]} "
        f"mean={statistics.fmean(values):.2f} sd={spread:.2f}"
        + (f" LOST={lost}" if lost else "")
    )


def gates(rows: list) -> bool:
    """Evaluates stage 1. Returns True only when every gate passes."""
    floor = passed_values(of(rows, "baseline"))
    ceiling = passed_values(of(rows, "oracle-gold"))
    print("\n== Stage 1 — calibration ==")
    print(describe("baseline   ", of(rows, "baseline")))
    print(describe("oracle-gold", of(rows, "oracle-gold")))
    for row in of(rows, "baseline") + of(rows, "oracle-gold"):
        print(
            f"   {row['condition']:11s} passed={row['passed'] or 'LOST':>4s}/{row['total']} "
            f"budget={row['used'] or '?'}/{row['budget']} denied={row['denied'] or '?'} "
            f"toolCalls={row['tool_calls'] or '?'} usd={row['usd'] or '?'} build={row['build_id']}"
        )

    if not floor or not ceiling:
        print("\nNOT DECIDABLE: an anchor is missing. Re-run it before reading any gate.")
        return False

    # Amendment 2b: a floor cell that scores zero by leaving the module non-compiling is the floor
    # being genuinely low, which is what G1 asks for — counting it as noise made G1 and G4
    # unsatisfiable together. What is counted instead is a cell LOST to the harness, which is a
    # measurement failure rather than an outcome, and dispersion, which is round 1's actual lesson.
    lost = (len(of(rows, "baseline")) - len(floor)) + (len(of(rows, "oracle-gold")) - len(ceiling))
    gap = statistics.fmean(ceiling) - statistics.fmean(floor)
    spread = statistics.stdev(floor) if len(floor) > 1 else 0.0
    verdicts = [
        statistics.fmean(floor) <= 5 and max(floor) < 8,
        min(ceiling) >= 7 and max(ceiling) == 9,
        gap >= 3,
        spread <= min(2.5, gap / 2) and lost == 0,
    ]
    print()
    for (name, rule, remedy), ok in zip(GATES, verdicts):
        print(f"{'PASS' if ok else 'FAIL'}  {name:24s} [{rule}]" + ("" if ok else f" -> {remedy}"))
    print(
        f"\ngap = {gap:.2f} assertions, baseline sd = {spread:.2f} "
        f"(G4 allows {min(2.5, gap / 2):.2f}), anchor cells lost to the harness = {lost}, "
        f"floor cells that left the module non-compiling = {sum(1 for v in floor if v == 0)} "
        f"(reported, not gated — see amendment 2b)"
    )
    return all(verdicts)


def bootstrap_interval(groups: dict, samples: int = 10_000, seed: int = 20260823) -> tuple:
    """A 90% interval for ρ, resampling TRAJECTORIES rather than cells.

    The unit of replication is the research trajectory: three notes distilled from one transcript
    share everything that made that transcript what it is. Resampling cells would report an interval
    for an experiment nobody ran.
    """
    rng = random.Random(seed)
    keys = sorted(groups)
    estimates = []
    for _ in range(samples):
        drawn = [groups[rng.choice(keys)] for _ in keys]
        xs = [u for group in drawn for u, _ in group]
        ys = [y for group in drawn for _, y in group]
        if len(set(xs)) > 1 and len(set(ys)) > 1:
            estimates.append(spearman(xs, ys))
    estimates.sort()
    if not estimates:
        return (float("nan"), float("nan"))
    return (estimates[int(0.05 * len(estimates))], estimates[int(0.95 * len(estimates)) - 1])


def notes_analysis(rows: list) -> None:
    cells = [row for row in of(rows, "checkpoint") if number(row, "passed") is not None]
    if not cells:
        print("\n== Stage 2 — not run ==")
        return

    floor = passed_values(of(rows, "baseline"))
    ceiling = passed_values(of(rows, "oracle-gold"))
    print("\n== Stage 2 — the twelve notes ==")

    groups: dict = {}
    for row in cells:
        groups.setdefault(row["trajectory_id"], []).append(
            (number(row, "u_obs"), number(row, "passed"))
        )

    us = [number(row, "u_obs") for row in cells]
    ys = [number(row, "passed") for row in cells]
    rho, p_value, permutations = cluster_permutation(groups, direction=1)
    low, high = bootstrap_interval(groups)
    print(f"primary  rho(U_obs, obligations) = {rho:+.3f}  90% CI [{low:+.3f}, {high:+.3f}]  p={p_value:.4f}")
    print(
        f"         (one-sided over {permutations} whole-trajectory permutations, so the smallest "
        f"attainable p is {1 / permutations:.4f}; the estimate is the finding, not the p)"
    )

    if floor and ceiling and statistics.fmean(ceiling) > statistics.fmean(floor):
        base, top = statistics.fmean(floor), statistics.fmean(ceiling)
        recovery = [(y - base) / (top - base) for y in ys]
        print(f"recovery fraction vs the anchors: {[f'{r:+.2f}' for r in recovery]}")
        print(f"         rho(U_obs, recovery) = {spearman(us, recovery):+.3f}")

    for column, label, predicted in (
        ("used", "budget used", "negative"),
        ("denied", "calls refused after the wall", "negative"),
        ("tool_calls", "tool calls", "negative"),
        ("output_tokens", "output tokens", "negative"),
    ):
        pairs = [(number(row, "u_obs"), number(row, column)) for row in cells if number(row, column) is not None]
        if len(pairs) > 2:
            print(
                f"effort   rho(U_obs, {label}) = "
                f"{spearman([u for u, _ in pairs], [v for _, v in pairs]):+.3f}  (predicted {predicted})"
            )

    within = [row["within"] for row in cells if (row.get("within") or "").strip()]
    if within:
        print(f"success within budget: {within.count('1')}/{len(within)} cells")

    print("\nsecondary, descriptive only — the arm is not this round's question:")
    for arm in ("mcp", "shell"):
        values = [number(row, "passed") for row in cells if row["arm"] == arm]
        if values:
            print(f"   {arm:5s} n={len(values)} mean passed={statistics.fmean(values):.2f}/9")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cells", type=pathlib.Path, required=True)
    args = parser.parse_args()

    rows = read_cells(args.cells)
    opened = gates(rows)
    notes_analysis(rows)
    print(
        "\nVERDICT: "
        + ("all gates pass — the twelve-note matrix may be bought" if opened
           else "a gate failed — see its remedy above; no note cell may be queued on this calibration")
    )


if __name__ == "__main__":
    main()
