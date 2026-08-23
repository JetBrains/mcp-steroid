#!/usr/bin/env python3
"""The pre-registered analysis of the downstream validation wave.

Reads one CSV of downstream cells and answers the one question the wave was bought to answer: does a
higher `U` at a checkpoint mean a note that carries the weak agent further?

The statistics are the ones fixed in DESIGN-DOWNSTREAM.md before any cell ran, and the reason they
are here rather than typed into a shell is that the awkward part is easy to get wrong quietly: the
twelve notes come from FOUR trajectories, three each, so they are not twelve independent
observations. Every p-value below is a cluster permutation over trajectories.

Input CSV columns (one row per cell, anchors included):

    trajectory_id,checkpoint,arm,u_obs,u_note,passed,total,success,tool_calls,output_tokens,seconds,usd

`trajectory_id` is `baseline` or `oracle-gold` for an anchor, and those rows carry an empty
`checkpoint` and `u_obs`.

Usage:
    python3 downstream_validation.py --cells data/downstream-cells.csv
"""

import argparse
import csv
import itertools
import pathlib
import statistics


def ranks(values: list) -> list:
    """Average ranks, so ties do not silently become an ordering nobody chose."""
    order = sorted(range(len(values)), key=lambda index: values[index])
    result = [0.0] * len(values)
    position = 0
    while position < len(order):
        end = position
        while end + 1 < len(order) and values[order[end + 1]] == values[order[position]]:
            end += 1
        shared = (position + end) / 2 + 1
        for index in order[position:end + 1]:
            result[index] = shared
        position = end + 1
    return result


def spearman(xs: list, ys: list) -> float:
    rx, ry = ranks(xs), ranks(ys)
    mx, my = statistics.fmean(rx), statistics.fmean(ry)
    num = sum((a - mx) * (b - my) for a, b in zip(rx, ry))
    den = (sum((a - mx) ** 2 for a in rx) * sum((b - my) ** 2 for b in ry)) ** 0.5
    return num / den if den else 0.0


def cluster_permutation(groups: dict, direction: int = 1) -> tuple:
    """ρ over all notes, with a p-value that permutes whole trajectories.

    `groups` maps a trajectory id to its list of (u, outcome) pairs, in checkpoint order. The
    permutation reassigns each trajectory's OUTCOME triple to another trajectory's U triple, which is
    the only exchangeability the design supports: within a trajectory the three notes share a
    research run and cannot be shuffled against each other.
    """
    keys = sorted(groups)
    us = [[pair[0] for pair in groups[key]] for key in keys]
    ys = [[pair[1] for pair in groups[key]] for key in keys]
    flat_u = [value for group in us for value in group]

    def rho_for(assignment):
        flat_y = [value for index in assignment for value in ys[index]]
        return spearman(flat_u, flat_y)

    observed = rho_for(range(len(keys)))
    everything = [rho_for(order) for order in itertools.permutations(range(len(keys)))]
    # `direction` is the sign the hypothesis predicts, and it is an ARGUMENT rather than an absolute
    # value on purpose: a two-sided test here would hide the case that matters most, a strong
    # correlation pointing the wrong way. Effort is expected to FALL as understanding rises.
    if direction >= 0:
        extreme = sum(1 for value in everything if value >= observed - 1e-12)
    else:
        extreme = sum(1 for value in everything if value <= observed + 1e-12)
    return observed, extreme / len(everything), len(everything)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cells", required=True)
    args = parser.parse_args()

    rows = list(csv.DictReader(pathlib.Path(args.cells).open()))
    notes = [row for row in rows if row["checkpoint"].strip()]
    anchors = [row for row in rows if not row["checkpoint"].strip()]

    print(f"{len(notes)} note cells, {len(anchors)} anchor cells\n")

    print("## Anchors — do they bracket?\n")
    for label in sorted({row["trajectory_id"] for row in anchors}):
        subset = [row for row in anchors if row["trajectory_id"] == label]
        passed = [int(row["passed"]) for row in subset]
        calls = [int(row["tool_calls"]) for row in subset if row["tool_calls"].strip()]
        print(f"  {label:12s} n={len(passed)}  passed={passed}  mean={statistics.fmean(passed):.2f}/8"
              f"  toolCalls={calls}")

    print("\n## U against residual work\n")
    print("  trajectory                 B   U_obs  U_note  passed  residual  success  toolCalls")
    for row in sorted(notes, key=lambda r: (r["trajectory_id"], int(r["checkpoint"]))):
        total = int(row["total"])
        passed = int(row["passed"])
        print(f"  {row['trajectory_id']:24s} {row['checkpoint']:>3s}  {float(row['u_obs']):.2f}   "
              f"{row['u_note'] or '-':>5s}  {passed:>4d}/{total}  {total - passed:>6d}  "
              f"{row['success']:>7s}  {row['tool_calls'] or '-':>9s}")

    groups = {}
    for row in notes:
        groups.setdefault(row["trajectory_id"], []).append(
            (float(row["u_obs"]), int(row["passed"]))
        )
    for key in groups:
        groups[key].sort()

    # Primary since amendment 1: effort, not the pass count. The floor anchor closed that door —
    # the weak agent reaches 7/8 with no note at all, given 89 unbudgeted interactions — so what a
    # note can still change is how much work is left, and that is what is tested here.
    effort = {}
    for row in notes:
        if row["tool_calls"].strip():
            effort.setdefault(row["trajectory_id"], []).append(
                (float(row["u_obs"]), int(row["tool_calls"]))
            )
    for key in effort:
        effort[key].sort()
    if len(effort) == len(groups):
        rho_effort, p_effort, arrangements = cluster_permutation(effort, direction=-1)
        print(f"\n## Primary — Spearman rho(U_obs, toolCalls) = {rho_effort:+.3f}, "
              f"cluster permutation p = {p_effort:.4f} over {arrangements} arrangements "
              f"(one-sided, negative predicted)")
    else:
        print("\n## Primary — not computable: some cells report no tool count")

    rho, p_value, arrangements = cluster_permutation(groups)
    print(f"\n## Secondary since amendment 1 — Spearman rho(U_obs, passed) = {rho:+.3f}, "
          f"cluster permutation p = {p_value:.4f} over {arrangements} arrangements")

    print("\n## Within-trajectory monotonicity — effort at B=20 against B=5 (falling is the prediction)")
    down = 0
    for key in sorted(effort):
        pairs = sorted(effort[key])
        delta = pairs[-1][1] - pairs[0][1]
        down += 1 if delta < 0 else 0
        print(f"  {key:24s} {pairs[0][1]} -> {pairs[-1][1]} calls   delta={delta:+d}")
    print(f"  fell in {down} of {len(effort)} trajectories")

    print("\n## Within-trajectory monotonicity (passed at B=20 minus passed at B=5)")
    ups = 0
    for key in sorted(groups):
        pairs = sorted(groups[key])
        delta = pairs[-1][1] - pairs[0][1]
        ups += 1 if delta > 0 else 0
        print(f"  {key:24s} {pairs[0][1]} -> {pairs[-1][1]}   delta={delta:+d}")
    print(f"  rose in {ups} of {len(groups)} trajectories")

    print("\n## Secondary — by arm (descriptive only; four cells an arm test nothing)")
    for arm in sorted({row["arm"] for row in notes}):
        subset = [row for row in notes if row["arm"] == arm]
        passed = [int(row["passed"]) for row in subset]
        print(f"  {arm:6s} n={len(passed)}  mean passed={statistics.fmean(passed):.2f}/8  {passed}")

    print("\n## Secondary — matched pairs at equal U_obs")
    by_u = {}
    for row in notes:
        by_u.setdefault(round(float(row["u_obs"]), 2), []).append(row)
    for value, subset in sorted(by_u.items()):
        if len({row["arm"] for row in subset}) > 1:
            detail = ", ".join(f"{row['arm']} {row['trajectory_id']}@{row['checkpoint']}"
                               f"={row['passed']}/{row['total']}" for row in subset)
            print(f"  U={value:.2f}: {detail}")

    judged = [row for row in notes if row["u_note"].strip()]
    if judged:
        rho_note = spearman([float(row["u_note"]) for row in judged],
                            [int(row["passed"]) for row in judged])
        print(f"\n## Secondary — Spearman rho(U_note, passed) = {rho_note:+.3f} over {len(judged)} notes")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
