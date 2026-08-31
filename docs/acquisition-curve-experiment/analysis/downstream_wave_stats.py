#!/usr/bin/env python3
"""The pre-registered wave reading, run identically on either round.

The battery is fixed by DESIGN-DOWNSTREAM-7.md: rho(U_note, obligations) over the notes with a
permutation p, the three confound checks round 6 ran, and the count of DISTINCT outcome values the
cells actually produced. Nothing here is chosen after seeing a number -- which is why the same file
is run against round 6 first: if it cannot reproduce a published reading it cannot be trusted to
produce a new one.
"""

import argparse
import csv
import itertools
import random
import statistics
import sys


def ranks(values):
    """Average ranks, so Spearman stays defined when notes tie -- and they do, at 0 and at 6."""
    order = sorted(range(len(values)), key=lambda i: values[i])
    out = [0.0] * len(values)
    i = 0
    while i < len(order):
        j = i
        while j + 1 < len(order) and values[order[j + 1]] == values[order[i]]:
            j += 1
        shared = (i + j) / 2 + 1
        for k in range(i, j + 1):
            out[order[k]] = shared
        i = j + 1
    return out


def pearson(x, y):
    n = len(x)
    if n < 3:
        return float("nan")
    mx, my = sum(x) / n, sum(y) / n
    num = sum((a - mx) * (b - my) for a, b in zip(x, y))
    dx = sum((a - mx) ** 2 for a in x) ** 0.5
    dy = sum((b - my) ** 2 for b in y) ** 0.5
    if dx == 0 or dy == 0:
        return float("nan")
    return num / (dx * dy)


def spearman(x, y):
    return pearson(ranks(x), ranks(y))


def permutation_p(x, y, trials=200_000, seed=20260831):
    """Label-shuffling on the NOTE, which is the unit of replication.

    Permuting cells instead would break the replicate pairs apart and quietly test a hypothesis
    nobody registered -- with 36 exchangeable cells the null gets a variance the design never had.
    """
    rx, ry = ranks(x), ranks(y)
    observed = pearson(rx, ry)
    if observed != observed:
        return float("nan"), float("nan"), observed
    rng = random.Random(seed)
    shuffled = list(rx)
    ge_two_sided = 0
    ge_one_sided = 0
    for _ in range(trials):
        rng.shuffle(shuffled)
        r = pearson(shuffled, ry)
        if abs(r) >= abs(observed) - 1e-12:
            ge_two_sided += 1
        if r >= observed - 1e-12:
            ge_one_sided += 1
    return (ge_two_sided + 1) / (trials + 1), (ge_one_sided + 1) / (trials + 1), observed


def mann_whitney_exact(a, b):
    """Exact two-sided p by enumerating every split, ties broken by mid-ranks."""
    n, m = len(a), len(b)
    pooled = a + b
    r = ranks(pooled)
    observed = sum(r[:n]) - n * (n + 1) / 2
    idx = range(n + m)
    hits_low = hits_high = total = 0
    for combo in itertools.combinations(idx, n):
        u = sum(r[i] for i in combo) - n * (n + 1) / 2
        total += 1
        if u <= observed + 1e-9:
            hits_low += 1
        if u >= observed - 1e-9:
            hits_high += 1
    p = 2 * min(hits_low, hits_high) / total
    return observed, min(p, 1.0)


def load(path, outcome_column, impute="none"):
    rows = []
    with open(path) as handle:
        for row in csv.DictReader(handle):
            rows.append(row)
    cells = []
    for row in rows:
        cells.append({
            "trajectory": row["trajectory"],
            "arm": row.get("arm") or row["trajectory"].split("-")[0],
            "checkpoint": int(row["checkpoint"]),
            # A blank U_note is a note the judge refused to grade, which is a hole and not a zero:
            # a zero would rank a real note below every graded one and no reading would ever notice.
            "u_note": float(row["u_note"]) if row.get("u_note") not in (None, "") else None,
            "u_obs": float(row["u_obs"]) if row.get("u_obs") not in (None, "") else None,
            "outcome": float(row[outcome_column]),
            "compiled": int(row["compiled"]),
        })
    graded = [cell["u_note"] for cell in cells if cell["u_note"] is not None]
    if impute != "none":
        if not graded:
            raise SystemExit("--impute needs at least one graded note to take a bound from")
        bound = min(graded) if impute == "min" else max(graded)
        for cell in cells:
            if cell["u_note"] is None:
                cell["u_note"] = bound
    return cells


def fold_to_notes(cells):
    notes = {}
    for cell in cells:
        key = (cell["trajectory"], cell["checkpoint"])
        notes.setdefault(key, []).append(cell)
    folded = []
    for (trajectory, checkpoint), group in sorted(notes.items()):
        folded.append({
            "trajectory": trajectory,
            "arm": group[0]["arm"],
            "checkpoint": checkpoint,
            "u_note": group[0]["u_note"],
            "u_obs": group[0]["u_obs"],
            "outcome": statistics.fmean(c["outcome"] for c in group),
            "both_compiled": all(c["compiled"] for c in group),
            "replicates": len(group),
        })
    return folded


def line(label, xs, ys, trials):
    if len(xs) < 3:
        print(f"  {label:<44} n={len(xs):<3} too few to read")
        return
    two, one, rho = permutation_p(xs, ys, trials=trials)
    print(f"  {label:<44} n={len(xs):<3} rho={rho:+.2f}  p2={two:.4f}  p1={one:.4f}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv", required=True)
    parser.add_argument("--outcome", default="passed")
    parser.add_argument("--label", default="")
    parser.add_argument("--trials", type=int, default=200_000)
    parser.add_argument(
        "--impute",
        choices=("none", "min", "max"),
        default="none",
        help="what to do with notes the judge refused to grade: leave them out, or bound the reading "
             "by giving them the lowest / highest observed U_note. Bounds rather than a point guess -- "
             "the pair answers whether the hole decides the result, which a single value cannot.",
    )
    args = parser.parse_args()

    cells = load(args.csv, args.outcome, args.impute)
    notes = fold_to_notes(cells)
    print(f"\n===== {args.label or args.csv} =====")
    print(f"{len(cells)} cells, {len(notes)} notes, "
          f"{sum(1 for n in notes if n['replicates'] != 2)} notes without exactly 2 replicates")
    ungraded = [n for n in notes if n["u_note"] is None]
    if ungraded:
        print(f"UNGRADED (judge refused, U_note missing not zero), impute={args.impute}: "
              + ", ".join(f"{n['trajectory']}@{n['checkpoint']}" for n in ungraded))
    notes = [n for n in notes if n["u_note"] is not None]
    cells = [c for c in cells if c["u_note"] is not None]

    values = sorted({c["outcome"] for c in cells})
    print(f"DISTINCT outcome values across the {len(cells)} cells: {len(values)} -> {values}")
    print(f"outcome range {min(values):g}..{max(values):g}; "
          f"cells that compiled: {sum(c['compiled'] for c in cells)}/{len(cells)}")

    print("\nPRIMARY -- rho(U_note, obligations), unit = the note")
    line("U_note, all notes", [n["u_note"] for n in notes], [n["outcome"] for n in notes], args.trials)
    both = [n for n in notes if n["both_compiled"]]
    line("U_note, notes whose replicates both built",
         [n["u_note"] for n in both], [n["outcome"] for n in both], args.trials)
    if all(n["u_obs"] is not None for n in notes):
        line("U_obs, all notes", [n["u_obs"] for n in notes], [n["outcome"] for n in notes], args.trials)
        line("U_obs, the same both-built notes",
             [n["u_obs"] for n in both], [n["outcome"] for n in both], args.trials)

    print("\nCONFOUND 1 -- does a better note merely compile more often?")
    print(f"  rho(U_note, compiled) cell-level          "
          f"n={len(cells):<3} rho={spearman([c['u_note'] for c in cells], [float(c['compiled']) for c in cells]):+.2f}")
    print(f"  rho(U_note, both replicates built)        "
          f"n={len(notes):<3} rho={spearman([n['u_note'] for n in notes], [float(n['both_compiled']) for n in notes]):+.2f}")

    print("\nCONFOUND 2 -- is it just 'a later checkpoint scores better'?")
    print(f"  rho(checkpoint, outcome)                  "
          f"n={len(notes):<3} rho={spearman([float(n['checkpoint']) for n in notes], [n['outcome'] for n in notes]):+.2f}")
    print(f"  rho(checkpoint, U_note)                   "
          f"n={len(notes):<3} rho={spearman([float(n['checkpoint']) for n in notes], [n['u_note'] for n in notes]):+.2f}")
    for checkpoint in sorted({n["checkpoint"] for n in notes}):
        sub = [n for n in notes if n["checkpoint"] == checkpoint]
        rho = spearman([n["u_note"] for n in sub], [n["outcome"] for n in sub])
        print(f"    within checkpoint {checkpoint:<3}                     n={len(sub):<3} rho={rho:+.2f}")

    print("\nCONFOUND 3 -- does it survive inside a single arm?")
    for arm in sorted({n["arm"] for n in notes}):
        sub = [n for n in notes if n["arm"] == arm]
        rho = spearman([n["u_note"] for n in sub], [n["outcome"] for n in sub])
        print(f"    within arm {arm:<8}                     n={len(sub):<3} rho={rho:+.2f}")

    print("\nSECONDARY -- the arm difference (not evidence about tools; see the design)")
    by_arm = {}
    for note in notes:
        by_arm.setdefault(note["arm"], []).append(note["outcome"])
    for arm, vals in sorted(by_arm.items()):
        print(f"    mean outcome, {arm:<8} {statistics.fmean(vals):.2f}  (n={len(vals)})")
    if len(by_arm) == 2:
        (a_name, a), (b_name, b) = sorted(by_arm.items())
        u, p = mann_whitney_exact(a, b)
        print(f"    Mann-Whitney exact two-sided p = {p:.3f}  (U={u:g}, {a_name} vs {b_name})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
