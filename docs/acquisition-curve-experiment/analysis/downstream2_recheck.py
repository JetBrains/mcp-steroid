#!/usr/bin/env python3
"""Re-reading of downstream round 2 after compilation became its own measurement.

`downstream2_validation.py` is NOT edited. It is the published analysis of that wave and it computed
what it said it computed; what changed is not the arithmetic but what one of its inputs is now known
to be able to mean.

The fact that forces the re-reading is small and mechanical. The round-2 oracle (`oracle-v2.patch`)
carries a "did not break anything" axis, `noOtherShippedProfileGainsIt`, which an UNTOUCHED tree
satisfies. Its floor is therefore ONE, by construction. A cell that reports ZERO has not scored below
the floor because it understood less than nothing — a tree that compiles cannot do that. It reports
zero because no surefire report existed to read: the tree did not build, or the class never ran.

Round 3 established that this is not hypothetical: twelve of its cells reported zero of ten and not
one of them had failed an assertion — every one failed `javac`. The instrument could not tell the two
apart, and round 2 ran on the same instrument.

So this script does not ask "is rho still +0.668". It asks the question the published analysis could
not: how much of that correlation is a statement about ARCHITECTURAL OBLIGATIONS, and how much is a
statement about whether the tree compiled at all. It reports three readings and refuses to merge them:

  1. the published reading, reproduced exactly, over all cells with zeros taken at face value;
  2. compilation as its own endpoint — rho(U, the tree built), over every note cell;
  3. obligations among cells that DID build, which is the only subset where `passed` means what the
     published claim says it means.

Neither a positive nor a negative verdict is drawn about the hypothesis here. The honest output is
a decomposition plus the note that the design that produced these cells cannot separate the parts
any better than this, which is why the fourth round buys its calibration first.

Usage:
    python3 downstream2_recheck.py --cells data/downstream2-cells.csv
"""

import argparse
import csv
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from downstream_validation import cluster_permutation, spearman  # noqa: E402
from downstream2_validation import by_note  # noqa: E402

#: What an untouched tree scores on `oracle-v2.patch`: axis A4 holds on a tree that changed nothing.
#: Measured, not assumed — the ladder cell `ladder:implementation-only` re-measures it per case.
PRISTINE_FLOOR = 1


def read_cells(path: pathlib.Path) -> list:
    with path.open() as handle:
        return [row for row in csv.DictReader(handle)]


def number(row: dict, column: str):
    value = (row.get(column) or "").strip()
    return float(value) if value else None


def built(row: dict) -> bool:
    """Whether this cell's tree can be shown to have compiled, from its score alone.

    One-directional on purpose. A score at or above the floor could only have been produced by a tree
    that built, so `True` is a fact. `False` is an inference — a tree that built and then broke the
    shipped profiles could also read zero — and it is reported as "below the floor" rather than as
    "did not compile" everywhere the wording matters.
    """
    passed = number(row, "passed")
    return passed is not None and passed >= PRISTINE_FLOOR


def report(title: str, rows: list, x_column: str, y, direction: int) -> None:
    """One correlation, with this family's own cluster permutation over whole trajectories.

    `y` is either a column name or a function of the row, so that "did the tree build" — which is not
    a column of this wave and has to be inferred from the score — travels through the same code path
    as `passed` rather than through a second, subtly different one.
    """
    pairs = []
    for row in rows:
        x = number(row, x_column)
        value = y(row) if callable(y) else number(row, y)
        if x is None or value is None:
            continue
        pairs.append((row["trajectory_id"], x, value))
    if len(pairs) < 3:
        print(f"  {title}: too few cells ({len(pairs)}) to correlate")
        return
    groups: dict = {}
    for trajectory, x, value in pairs:
        groups.setdefault(trajectory, []).append((x, value))
    sizes = sorted(len(values) for values in groups.values())
    # Whole trajectories are the exchangeable unit, exactly as in the published analysis: the
    # checkpoints of one research run share that run and cannot be shuffled against each other. With
    # four trajectories the p-value cannot fall below 1/24 = .0417, which is a property of the design
    # rather than of the effect.
    if len(set(sizes)) == 1 and len(groups) >= 3:
        rho, p, permutations = cluster_permutation(groups, direction)
        print(f"  {title}: rho = {rho:+.3f} over n = {len(pairs)}, "
              f"cluster p = {p:.4f} ({permutations} permutations of {len(groups)} trajectories)")
    else:
        # Unbalanced after dropping unreadable cells: the permutation would swap triples of different
        # lengths, so only the correlation is reported — and the imbalance is said out loud instead of
        # being repaired by inventing values for the cells that were dropped.
        rho = spearman([pair[1] for pair in pairs], [pair[2] for pair in pairs])
        print(f"  {title}: rho = {rho:+.3f} over n = {len(pairs)}, no cluster p "
              f"(trajectories now hold {sizes} cells; the permutation needs equal groups)")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cells", type=pathlib.Path, required=True)
    args = parser.parse_args()

    rows = read_cells(args.cells)
    notes = [row for row in rows if row["condition"] == "checkpoint"]
    anchors = [row for row in rows if row["condition"] != "checkpoint"]

    below = [row for row in rows if not built(row)]
    print("== How many cells cannot be read as obligations ==")
    print(f"  oracle floor on a pristine tree: {PRISTINE_FLOOR} (axis A4 holds on an untouched tree)")
    print(f"  cells below that floor: {len(below)} of {len(rows)}")
    for name, group in (("anchors", anchors), ("note cells", notes)):
        bad = [row for row in group if not built(row)]
        print(f"    {name}: {len(bad)} of {len(group)}")
    print("  every one of these reports 0, which a compiling tree cannot report")
    print()

    # The published estimate collapses the two rollouts of one note to their mean (amendment 3): a
    # note is one knowledge state and two rollouts of it are two measurements of that state. This
    # script reproduces it through the SAME function rather than a second implementation of it.
    collapsed = by_note(notes)
    print("== 1. The published reading, reproduced (zeros taken at face value) ==")
    print(f"  {len(notes)} cells -> {len(collapsed)} notes, exactly as RESULTS-DOWNSTREAM-2.md reports")
    report("rho(U_obs, obligations)", collapsed, "u_obs", "passed", +1)
    report("rho(U_note, obligations)", collapsed, "u_note", "passed", +1)
    report("rho(U_obs, tool calls)", collapsed, "u_obs", "tool_calls", -1)
    report("rho(U_obs, output tokens)", collapsed, "u_obs", "output_tokens", -1)
    print()

    print("== 2. Compilation as its own endpoint ==")
    print("   (1 = scored at or above the pristine floor, i.e. the tree demonstrably built)")
    def buildable(row: dict) -> float:
        return 1.0 if built(row) else 0.0

    report("rho(U_obs, the tree built)", notes, "u_obs", buildable, +1)
    report("rho(U_note, the tree built)", notes, "u_note", buildable, +1)
    lo = [row for row in notes if (number(row, "u_obs") or 0) < 0.4]
    hi = [row for row in notes if (number(row, "u_obs") or 0) >= 0.6]
    for label, group in (("U < 0.4", lo), ("U >= 0.6", hi)):
        if group:
            share = sum(1 for row in group if not built(row)) / len(group)
            print(f"  {label}: {share:.0%} of {len(group)} cells below the floor")
    print()

    print("== 3. Obligations among the cells that demonstrably built ==")
    kept = [row for row in notes if built(row)]
    print(f"  n = {len(kept)} of {len(notes)} note cells survive; "
          f"{len(set(row['trajectory_id'] for row in kept))} trajectories remain")
    report("rho(U_obs, obligations | built)", kept, "u_obs", "passed", +1)
    report("rho(U_note, obligations | built)", kept, "u_note", "passed", +1)
    report("rho(U_obs, tool calls | built)", kept, "u_obs", "tool_calls", -1)
    report("rho(U_obs, output tokens | built)", kept, "u_obs", "output_tokens", -1)
    # And the same subset at the published unit of analysis. Unreadable cells are dropped BEFORE the
    # collapse, never after: averaging a readable rollout with an unreadable one would put a fraction
    # of a compile failure into a number that claims to count architectural obligations.
    collapsed_kept = by_note(kept)
    print(f"  collapsed to {len(collapsed_kept)} of {len(collapsed)} notes")
    report("rho(U_obs, obligations | built, by note)", collapsed_kept, "u_obs", "passed", +1)
    report("rho(U_note, obligations | built, by note)", collapsed_kept, "u_note", "passed", +1)
    print()

    print("== What this does and does not license ==")
    print("  The published claim reads `passed` as architectural obligations. For the cells below the")
    print("  floor that reading is unavailable, and there is no column in this wave that says which of")
    print("  them failed to build. So reading (1) is an upper bound on the functional-validity claim,")
    print("  reading (3) is what survives the ambiguity, and reading (2) is the confound stated as a")
    print("  quantity rather than as a caveat. None of the three retracts the acquisition result:")
    print("  U(B) never depended on the oracle.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
