#!/usr/bin/env python3
"""Gate 1 of round 3 — is a captured trajectory measurable at all?

`RCW-GENERALIZATION.md` requires, before a case's probes are bought, that its capture "yield at least
three DISTINCT states under the checkpoint rule". This script decides that, and it is separate from
`select_checkpoints_r3.py` on purpose: the gate must be answerable, and answered, before a single probe
build is queued.

DISTINCT MEANS DISTINCT TREE, NOT DISTINCT STEP NUMBER. That distinction is the whole point. Round 3's
`jh3-mcp` capture records steps 7, 8 and 9 with byte-identical patches — the agent wrote the entire
solution at step 7 and then ran the build three times. Counting step numbers calls that four states and
buys twenty probe cells; counting trees calls it two, which is what it is. The pre-registration already
says collisions collapse through `sameStateAs`, so this is that rule applied one stage earlier, where it
still saves money instead of merely annotating a table.

Usage:
    gate1_r3.py <dataset.json> <case> <arm> <capture-checkpoints-dir> [<case> <arm> <dir> ...]
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rcw_layers  # noqa: E402

MIN_DISTINCT_STATES = 3


def assess(dataset_path, case, arm, capture_dir):
    """One arm's Gate-1 verdict, plus the evidence a reader needs to disagree with it."""
    by_id = rcw_layers._load(dataset_path)
    instance_id = {**rcw_layers.PILOT_CASE, **rcw_layers.ROUND3_CASES}[case]
    case_layers, gold_files = rcw_layers.gold_layers(by_id[instance_id])

    steps = rcw_layers.read_steps(capture_dir)
    rows = [
        (step, rcw_layers.coverage(steps[step], case_layers, gold_files)["layerCov"])
        for step in sorted(steps)
    ]
    first_write = rcw_layers.first_write_step(steps)
    last_distinct = rcw_layers.last_distinct_step(steps)
    picked = rcw_layers.select_checkpoints(rows, first_write, last_distinct)

    # Fold by tree. The first step carrying a given patch text is the state's representative, so the
    # earliest position in the trajectory wins and a later re-run of the build never displaces it.
    representative, chosen = {}, []
    for _, step in picked:
        key = steps[step]
        if key not in representative:
            representative[key] = step
            chosen.append(step)
    chosen.sort()

    # How much of the trajectory could have been measured, independent of the five picked states. A
    # capture with many distinct trees that the rule collapses is a rule problem; one with two distinct
    # trees is a trajectory problem, and only the second is a reason to skip the arm.
    available = len({patch for patch in steps.values()})

    return dict(
        case=case,
        arm=arm,
        picked=[(name, step) for name, step in picked],
        distinct=chosen,
        distinctCount=len(chosen),
        availableTrees=available,
        steps=len(steps),
        firstWrite=first_write,
        lastDistinct=last_distinct,
        layerCov={step: round(cov, 3) for step, cov in rows if step in chosen},
        passes=len(chosen) >= MIN_DISTINCT_STATES,
    )


def main(argv):
    if len(argv) < 5 or (len(argv) - 2) % 3 != 0:
        raise SystemExit(__doc__)
    dataset = argv[1]
    verdicts = [
        assess(dataset, argv[i], argv[i + 1], argv[i + 2]) for i in range(2, len(argv), 3)
    ]
    print(f"{'arm':12s} {'steps':>5s} {'trees':>5s} {'M0':>3s} {'last':>4s} "
          f"{'distinct states':24s} {'n':>2s}  gate1")
    for v in verdicts:
        print(
            f"{v['arm']:12s} {v['steps']:5d} {v['availableTrees']:5d} {v['firstWrite']:3d} "
            f"{v['lastDistinct']:4d} {str(v['distinct']):24s} {v['distinctCount']:2d}  "
            f"{'PASS' if v['passes'] else 'FAIL — too few distinct trees to measure'}"
        )
    failed = [v['arm'] for v in verdicts if not v['passes']]
    if failed:
        print(f"\n  {len(failed)} arm(s) fail Gate 1: {', '.join(failed)}")
    return verdicts


if __name__ == "__main__":
    main(sys.argv)
