#!/usr/bin/env python3
"""Turn one capture artifact into the committed probe input of one arm.

A probe build restores its state from `src/test/resources/ripple-checkpoints/<case>/<arm>/`, addressed
by a 1-based INDEX into that directory's `checkpoints.json`. The capture exports every recorded step;
this script writes out only the states the frozen rule in `rcw_layers.select_checkpoints()` picked, and
re-indexes them 1..N so the probe coordinates are dense.

That is exactly what round 2 committed for `mcp2`/`none2` — four states, re-indexed, alongside a
`checkpoints.json` carrying the capture's own `n` and `firstWriteStep`. The schema is preserved key for
key: the Kotlin side parses it, and an extra field invented here would be a deserialization failure
discovered at the cost of a probe build. The `C1..C5` identity therefore lives in the arm's README and
in `checkpoint-plan-r3.csv`, not in the JSON.

Deliberately NOT automatic: the script prints the plan and refuses to write unless `--write` is passed,
because committing the wrong states is silent — every probe afterwards is well-formed, green, and
measures the wrong trajectory.

Usage:
    commit_checkpoints_r3.py <dataset.json> <case> <arm-token> <capture-checkpoints-dir> [--write]
"""
import json
import os
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rcw_layers  # noqa: E402

# This file sits at docs/ripple-checkpoint-pilot/data/round3/, so the repository root is four levels up.
# Verified rather than assumed: an off-by-one here does not fail, it silently creates
# `docs/test-experiments/src/...` and reports success, and the probes that follow restore a pristine tree
# from a directory the harness never reads.
REPO = os.path.abspath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "..")
)
RESOURCES = os.path.join(
    REPO, "test-experiments", "src", "test", "resources", "ripple-checkpoints"
)
if not os.path.isdir(RESOURCES):
    raise SystemExit(f"{RESOURCES} is not a directory — the repository root was resolved to {REPO}")

README = """# `{case}` / `{arm}` — committed checkpoint states

Round 3 of the residual-completion-work study. Exported from capture build `{build}` and cut down to the
states the pre-registered rule selected; see
[RCW-GENERALIZATION.md](../../../../../../../docs/ripple-checkpoint-pilot/RCW-GENERALIZATION.md).

`index` is the probe coordinate (`-P ripple.checkpoint.index=`), `step` is the position in the original
trajectory, and the checkpoint id is the role the rule assigned:

{table}

The patch of a step is the whole-tree diff against the pristine revision, so a state is restored by
applying one file. Patches and `checkpoints.json` must be committed together: an index whose patch is
missing fails the probe at restore time, and a patch no index names is never read.
"""


def _synthesized(source, steps, step):
    """A metadata entry for a step the recorder exported a patch for but no checkpoint of.

    The recorder writes `checkpoints.json` on a fixed TEN-fraction grid while `exportEveryStepPatch`
    writes a patch for every step, so a rule that picks states by what the agent DID — which is the whole
    point of picking by layer coverage — routinely lands between grid points. Round 3 hit this on four of
    six arms.

    Only `index`, `step`, `editFraction` and `position` are read back by `loadCheckpoint`, and both
    fractions are reproduced from the capture's own `n` and `firstWriteStep` by the recorder's formulas —
    verified against the grid entries of the same file, which they reproduce to the digit.

    `tree` is written as null rather than invented. It is the git tree hash the capture observed, this
    side has no way to recompute it, and a plausible-looking wrong hash is worse than an honest gap: the
    probe does not read the field, but a human auditing which state a cell measured would.
    """
    n, first_write = source["n"], source["firstWriteStep"]
    patch = steps[step]
    return {
        "index": None,
        "step": step,
        "editFraction": round((step - first_write) / (n - first_write), 4) if n > first_write else 0.0,
        "position": round(step / n, 4),
        "tree": None,
        "patchChars": len(patch),
        "sameStateAs": None,
        "synthesized": True,
    }


def main(argv):
    if len(argv) < 5:
        raise SystemExit(__doc__)
    dataset, case, arm, capture_dir = argv[1:5]
    write = "--write" in argv

    by_id = {c["instance_id"]: c for c in json.load(open(dataset))}
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

    source = json.load(open(os.path.join(capture_dir, "checkpoints.json")))
    if source["arm"] != arm:
        raise SystemExit(
            f"the capture in {capture_dir} is arm '{source['arm']}', not '{arm}' — refusing to "
            f"publish one arm's states under another's token"
        )
    if source["case"] != instance_id:
        raise SystemExit(
            f"the capture in {capture_dir} is case '{source['case']}', not '{instance_id}' — this is "
            f"the failure mode that voided round 3's first capture batch"
        )
    by_step = {c["step"]: c for c in source["checkpoints"]}

    # Collisions collapse BY TREE, not by step number — the same fold `gate1_r3.py` applies. Two ids on
    # one step is the obvious case; two ids on different steps carrying byte-identical patches is the
    # one that costs money, and it is common: `jh3-mcp` records steps 7, 8 and 9 with the same tree
    # because the agent wrote the solution once and then ran the build three times. Probing those as
    # three states would buy fifteen cells to measure one.
    #
    # The earliest step carrying a tree represents it, so a later verification re-run never displaces
    # the state's real position in the trajectory.
    ids_of_step, order, seen = {}, [], {}
    for checkpoint_id, step in picked:
        tree = steps[step]
        first = seen.setdefault(tree, step)
        ids_of_step.setdefault(first, []).append(checkpoint_id)
        if first not in order:
            order.append(first)
    order.sort()

    out, lines = [], []
    for index, step in enumerate(order, start=1):
        entry = dict(by_step[step]) if step in by_step else _synthesized(source, steps, step)
        entry["index"] = index
        out.append(entry)
        cov = rcw_layers.coverage(steps[step], case_layers, gold_files)
        lines.append(
            f"| {index} | {step} | {'+'.join(ids_of_step[step])} | {cov['layerCov']:.3f} | "
            f"{len(steps[step])} | {', '.join(cov['layers'])} |"
        )
        print(
            f"  index {index} <- step {step:3d}  {'+'.join(ids_of_step[step]):9s} "
            f"layerCov {cov['layerCov']:.3f}  {len(steps[step]):6d} chars"
        )

    if not write:
        print(f"\n  dry run — pass --write to publish {len(out)} states to {case}/{arm}")
        return

    target = os.path.join(RESOURCES, case, arm)
    os.makedirs(target, exist_ok=True)
    for stale in os.listdir(target):
        if stale.endswith(".patch"):
            os.remove(os.path.join(target, stale))
    for step in order:
        shutil.copyfile(
            os.path.join(capture_dir, f"step-{step}.patch"),
            os.path.join(target, f"step-{step}.patch"),
        )
    published = dict(source)
    published["checkpoints"] = out
    with open(os.path.join(target, "checkpoints.json"), "w") as fh:
        json.dump(published, fh, indent=2)
        fh.write("\n")
    with open(os.path.join(target, "README.md"), "w") as fh:
        fh.write(
            README.format(
                case=case,
                arm=arm,
                build=os.environ.get("RCW_CAPTURE_BUILD", "see RUN-IDS.md"),
                table="| index | step | checkpoint | layerCov | patch chars | layers |\n"
                      "|---:|---:|:---|---:|---:|:---|\n" + "\n".join(lines),
            )
        )
    print(f"\n  wrote {len(out)} states to {target}")


if __name__ == "__main__":
    main(sys.argv)
