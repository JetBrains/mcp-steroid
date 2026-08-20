#!/usr/bin/env python3
"""Turn one capture-2 trajectory into the FOUR states round 2 probes, by the pre-registered rule.

The rule is frozen in `REPLICATION-2.md` and implemented in `gold_layers.py`; this script only applies
it and writes the artifacts the probe harness reads:

    C1 = M0            the first write
    C2 = T - 1         the recorded step immediately before the transition
    C3 = T             the transition: the largest single-step increase in layerCov (ties -> earliest)
    C4                 the last step whose tree differs from the final one

Identical for both arms, and computed from the capture's own artifacts — never from a probe verdict,
which is why this script runs BEFORE any round-2 cell is queued.

Output is `checkpoints.json` in exactly the shape `RippleCheckpointRecorder.metadataJson` emits, plus one
`step-<n>.patch` per selected state. `RippleCheckpointProbeTest` then validates the pair: the committed
patch set must be precisely the set of steps the metadata names, so a partial copy cannot be probed.

Usage:
    select_capture2_checkpoints.py --arm mcp2 --artifact /tmp/r2/mcp2.zip \
        --out ../../../../test-experiments/src/test/resources/ripple-checkpoints/feature-service-125/mcp2
"""
import argparse
import hashlib
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from extract_capture_trajectory import Artifact, STEP_PATCH  # noqa: E402
from gold_layers import coverage, transition_step  # noqa: E402

CASE = "dpaia__feature__service-125"
MODEL = "claude-opus-5"
# Round 1's ten-fraction grid, kept in the metadata because `fractions` documents what the CAPTURE
# planned, and round 2's captures still plan it. The four probed states are a separate selection.
FRACTIONS = [round(k / 10, 1) for k in range(10)]


def select(steps, trees, first_write, layer_cov):
    """The four pre-registered states, as `(id, step)` pairs, deduplicated in trajectory order."""
    t, _ = transition_step([(s, layer_cov[s]) for s in steps])
    before = max([s for s in steps if s < t], default=None)
    final_tree = trees.get(steps[-1])
    last_distinct = max([s for s in steps if trees.get(s) != final_tree], default=None)
    chosen = [("M0", first_write), ("T-1", before), ("T", t), ("last-distinct", last_distinct)]

    picked, seen = [], set()
    for name, step in chosen:
        if step is None:
            print(f"warning: {name} does not exist in this trajectory and is dropped", file=sys.stderr)
            continue
        if step in seen:
            print(f"warning: {name} collides with an earlier checkpoint at step {step}", file=sys.stderr)
            continue
        seen.add(step)
        picked.append((name, step))
    return sorted(picked, key=lambda pair: pair[1]), t


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--arm", required=True, choices=["mcp2", "none2"])
    parser.add_argument("--artifact", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--trees", help="path to a 'step-<n> <tree>' listing from the shadow repository; "
                                        "without it the state id is the SHA-1 of the state's own patch")
    args = parser.parse_args()

    artifact = Artifact(args.artifact)
    patches = {int(m.group(1)): m.group(0) for m in map(STEP_PATCH.match, artifact.names) if m}
    steps = sorted(s for s in patches if s > 0)
    if not steps:
        raise SystemExit("the artifact holds no step patch, so no state can be selected")

    text = {s: artifact.read(patches[s]) for s in steps}
    # The published `tree` is the IDENTITY of a state, and identity is what decides which states repeat.
    # The shadow repository's tree ids are the authority when the listing is at hand; without it the
    # SHA-1 of the state's own whole-tree patch is an exact equivalent — two steps hold the same tree
    # exactly when their diffs against the pristine tree are byte-identical — and it is a real digest of
    # measured bytes rather than an empty field standing in for one.
    trees = {s: "sha1:" + hashlib.sha1(text[s].encode()).hexdigest() for s in steps}
    if args.trees:
        with open(args.trees) as fh:
            listed = dict(
                (int(line.split()[0].replace("step-", "")), line.split()[1])
                for line in fh if len(line.split()) == 2
            )
        trees = {s: listed.get(s, trees[s]) for s in steps}

    layer_cov = {s: coverage(text[s])["layerCov"] for s in steps}
    first_write = next((s for s in steps if text[s].strip()), None)
    if first_write is None:
        raise SystemExit("no step of this capture wrote anything — there is no edit phase to probe")

    n = max(steps)
    picked, t = select(steps, trees, first_write, layer_cov)
    print(f"{args.arm}: n={n} firstWrite={first_write} T={t} -> {[(k, s) for k, s in picked]}")

    os.makedirs(args.out, exist_ok=True)
    entries = []
    for index, (name, step) in enumerate(picked, start=1):
        with open(os.path.join(args.out, f"step-{step}.patch"), "w") as fh:
            fh.write(text[step])
        entries.append({
            "index": index,
            "step": step,
            "editFraction": round((step - first_write) / (n - first_write), 4) if n > first_write else 0.0,
            "position": round(step / n, 4),
            "tree": trees[step],
            "patchChars": len(text[step]),
            "sameStateAs": None,
            "milestone": name,
        })

    metadata = {
        "case": CASE,
        "arm": args.arm,
        "model": MODEL,
        "n": n,
        "firstWriteStep": first_write,
        "fractions": FRACTIONS,
        "checkpoints": entries,
    }
    with open(os.path.join(args.out, "checkpoints.json"), "w") as fh:
        json.dump(metadata, fh, indent=2)
        fh.write("\n")
    print(f"{len(entries)} checkpoints -> {args.out}")


if __name__ == "__main__":
    main()
