#!/usr/bin/env python3
"""The five pre-registered states of one round-3 trajectory, and the probe cells that measure them.

Round 2's `data/capture2/select_capture2_checkpoints.py` generalized. What that script decided inline —
which milestone anchors the set, how many states there are, what happens on a collision — is now frozen
in `rcw_layers.select_checkpoints()` and in `RCW-GENERALIZATION.md`, so this file APPLIES the rule and
never restates it. That separation is the point: round 2 had to record a deviation because its anchor
`T` degenerated onto the first write, and the only defence against choosing an anchor once the numbers
are visible is that the rule lives in a file frozen before any build was queued.

Input is `upstream-r3.csv`, i.e. the capture's OWN per-step record. No probe verdict may enter this
rule, and no checkpoint may be moved after any `RCW` value for the case is known.

Three things the trajectory itself decides and this script only reads out of the CSV:

  * `M0` (`C1`) is the `isM0` column — the first step that wrote anything. It is not derived from
    coverage: a step whose only change lies outside a source root leaves `layerCov` at zero while still
    being a real write.
  * `last distinct` (`C5`) is the last step whose `stateId` differs from the final step's. The state id
    is the SHA-1 of the whole-tree patch, so two steps share it exactly when their trees are identical —
    the same identity round 2 used, and the reason a trajectory that idles for its last ten tool calls
    does not spend a checkpoint on ten copies of one state.
  * a COLLISION — two ids on one step — is published, not resolved. It is data about the trajectory's
    shape (round 2's mcp arm reached six of seven layers in its first write), and the cell is probed
    ONCE: five replicates per DISTINCT step, never five per id.

The `jb tc native run start` lines it prints are ready to paste. `-P ripple.checkpoint.index` is the
1-based ORDINAL of the state inside that arm's committed `checkpoints.json` — NOT the step number.
`RippleCheckpointProbeTest.loadCheckpoint` looks the entry up by its `"index"` field and
`probeCoordinates` bounds it by `committedCheckpointCount(arm)`, the number of entries in that file. So
the ordinals are only real once the patches and the metadata are committed: until then this script
prints the order it PLANS to commit them in and says so, and once a `checkpoints.json` exists it is read
and must agree with the plan, or the run is refused rather than addressed at the wrong state.

Usage:
    select_checkpoints_r3.py --upstream upstream-r3.csv --case petclinic-36 --arm pc36-mcp \\
        --out checkpoint-plan-r3.csv
"""
import argparse
import csv
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from rcw_layers import milestones, select_checkpoints, transition_step  # noqa: E402
from extract_rollouts_r3 import REGISTRY_KT, case_of_arm, load_registry  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.normpath(os.path.join(HERE, "..", "..", "..", ".."))
RESOURCES = os.path.join(
    REPO_ROOT, "test-experiments", "src", "test", "resources", "ripple-checkpoints")
BUILD_TYPE = "mcp_steroid_IntegrationTests_RippleCheckpointProbe"
REPLICATES = 5

FIELDS = ["case", "arm", "checkpointId", "step", "layerCov", "collidesWith"]


def trajectory(upstream_path, case, arm):
    """The ordered per-step record of one (case, arm), or a message saying why there is none."""
    if not os.path.exists(upstream_path):
        raise SystemExit(f"--upstream {upstream_path} does not exist; run extract_upstream_r3.py first")
    with open(upstream_path, newline="") as fh:
        rows = [r for r in csv.DictReader(fh) if (r["case"], r["arm"]) == (case, arm)]
    if not rows:
        raise SystemExit(
            f"{upstream_path} holds no row for case={case} arm={arm}. Extract that capture first; "
            f"a plan cannot be written for a trajectory nobody recorded."
        )
    rows.sort(key=lambda r: int(r["step"]))
    return rows


def plan_for(rows, case, arm):
    """`[(id, step)]` with collisions preserved, plus what the operator needs to read the plan."""
    steps = [(int(r["step"]), float(r["layerCov"])) for r in rows]
    first_write = next((int(r["step"]) for r in rows if r["isM0"] == "1"), None)
    if first_write is None:
        raise SystemExit(
            f"{case}/{arm} has no first write in {len(rows)} recorded steps, so it has no edit phase — "
            f"this capture fails Gate 1 and must not be probed"
        )
    final = rows[-1]["stateId"]
    last_distinct = max((int(r["step"]) for r in rows if r["stateId"] != final), default=None)
    if last_distinct is None:
        raise SystemExit(
            f"{case}/{arm} holds only ONE distinct state across {len(rows)} steps, so there is no "
            f"trajectory to place checkpoints along — an atomic solution, as the keycloak case was"
        )

    picked = select_checkpoints(steps, first_write, last_distinct)
    marks = milestones(steps, first_write)
    t, delta = transition_step(steps)
    return picked, dict(
        n=int(rows[-1]["step"]), firstWrite=first_write, lastDistinct=last_distinct,
        T=t, dT=round(delta, 4), milestones=marks,
        distinctStates=len({r["stateId"] for r in rows}),
    )


def committed_ordinals(resources, case, arm_dir):
    """`step -> index` from the arm's committed `checkpoints.json`, or None when nothing is committed.

    Read and not computed, because the committed file is what the probe build addresses: a plan that
    disagrees with it would print a command that starts the agent from a different state than the one
    the plan names, and the verdict would be published under the wrong coordinate.
    """
    path = os.path.join(resources, case, arm_dir, "checkpoints.json")
    if not os.path.exists(path):
        return None
    with open(path) as fh:
        entries = json.load(fh).get("checkpoints")
    if not entries:
        raise SystemExit(f"{path} carries no checkpoints array")
    return {int(e["step"]): int(e["index"]) for e in entries}


def main():
    parser = argparse.ArgumentParser(
        description="Apply the frozen checkpoint rule to one round-3 trajectory and print its probe cells.",
        epilog="`index` is the ordinal inside checkpoints.json, never the step number.",
    )
    parser.add_argument("--upstream", default="upstream-r3.csv")
    parser.add_argument("--case", required=True)
    parser.add_argument("--arm", required=True)
    parser.add_argument("--out", default="checkpoint-plan-r3.csv")
    parser.add_argument("--resources", default=RESOURCES,
                        help="ripple-checkpoints resource root, for the committed ordinals")
    parser.add_argument("--registry", default=REGISTRY_KT)
    parser.add_argument("--build-type", default=BUILD_TYPE)
    parser.add_argument("--branch", default="worktree-semantic-ripple-pilot")
    parser.add_argument("--revision", default="<sha>")
    args = parser.parse_args()

    spec = case_of_arm(load_registry(args.registry), args.arm)
    if spec.resourceDir != args.case:
        raise SystemExit(
            f"the arm '{args.arm}' belongs to case '{spec.resourceDir}', not to '{args.case}'"
        )

    rows = trajectory(args.upstream, args.case, args.arm)
    picked, facts = plan_for(rows, args.case, args.arm)
    cov = {int(r["step"]): float(r["layerCov"]) for r in rows}

    seen = {}
    out_rows = []
    for name, step in picked:
        collides = "|".join(seen.get(step, []))
        seen.setdefault(step, []).append(name)
        out_rows.append(dict(case=args.case, arm=args.arm, checkpointId=name, step=step,
                             layerCov=round(cov[step], 4), collidesWith=collides))

    distinct = sorted(seen)
    print(f"{args.case}/{args.arm}: n={facts['n']} firstWrite={facts['firstWrite']} "
          f"lastDistinct={facts['lastDistinct']} T={facts['T']} ({facts['dT']:+.3f}) "
          f"milestones={facts['milestones']} distinctStates={facts['distinctStates']}")
    for row in out_rows:
        note = f"  collides with {row['collidesWith']}" if row["collidesWith"] else ""
        print(f"  {row['checkpointId']}  step {row['step']:>3}  layerCov {row['layerCov']:.3f}{note}")
    if len(distinct) < 3:
        print(f"WARNING: only {len(distinct)} distinct states — Gate 1 of the pre-registration requires "
              f"at least three, so this capture must not be probed", file=sys.stderr)

    merge(args.out, args.case, args.arm, out_rows)
    print(f"{len(out_rows)} checkpoints ({len(distinct)} distinct) -> {args.out}")

    ordinals = committed_ordinals(args.resources, args.case, spec.arm_dir(args.arm))
    if ordinals is None:
        ordinals = {step: index for index, step in enumerate(distinct, start=1)}
        print(f"\n# PROVISIONAL ordinals: {args.case}/{spec.arm_dir(args.arm)}/checkpoints.json is not "
              f"committed yet.\n# They become real only if the metadata is committed with these steps "
              f"in this order.", file=sys.stderr)
    elif sorted(ordinals) != distinct:
        raise SystemExit(
            f"the committed checkpoints.json of {args.arm} names steps {sorted(ordinals)} but the rule "
            f"selects {distinct}. Refusing to print a command: the ordinals would address states other "
            f"than the ones this plan measures. Re-commit the metadata and patches together."
        )

    print(f"\n# {len(distinct)} distinct states x {REPLICATES} replicates = "
          f"{len(distinct) * REPLICATES} probe cells for {args.arm}")
    for step in distinct:
        ids = "+".join(seen[step])
        for replicate in range(1, REPLICATES + 1):
            print(f'jb tc native run start "{args.build_type}" --branch "{args.branch}" '
                  f'--revision "{args.revision}" --no-push '
                  f'-P ripple.checkpoint.arm={args.arm} '
                  f'-P ripple.checkpoint.index={ordinals[step]} '
                  f'-P ripple.checkpoint.replicate={replicate} --json'
                  f'  # {ids} @ step {step}')


def merge(out_path, case, arm, rows):
    """Replace this (case, arm)'s plan in place, so re-running a case cannot duplicate its rows."""
    kept = []
    if os.path.exists(out_path):
        with open(out_path, newline="") as fh:
            reader = csv.DictReader(fh)
            if reader.fieldnames != FIELDS:
                raise SystemExit(f"{out_path} has columns {reader.fieldnames}, not {FIELDS}")
            kept = [r for r in reader if (r["case"], r["arm"]) != (case, arm)]
    merged = kept + rows
    merged.sort(key=lambda r: (r["case"], r["arm"], r["checkpointId"]))
    with open(out_path, "w", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=FIELDS, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(merged)


if __name__ == "__main__":
    main()
