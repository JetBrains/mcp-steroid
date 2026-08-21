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

    # Collisions collapse: two ids on one step are probed once. The ids are kept for the README, so the
    # collapse stays visible instead of looking like a trajectory that had fewer milestones.
    ids_of_step, order = {}, []
    for checkpoint_id, step in picked:
        ids_of_step.setdefault(step, []).append(checkpoint_id)
        if step not in order:
            order.append(step)
    order.sort()

    out, lines = [], []
    for index, step in enumerate(order, start=1):
        template = by_step.get(step)
        if template is None:
            raise SystemExit(
                f"step {step} was selected but the capture committed no checkpoint for it; the "
                f"recorder's grid holds {sorted(by_step)}"
            )
        entry = dict(template)
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
