#!/usr/bin/env python3
"""Read the generalization round's curves and answer the two pre-registered questions.

Input: one CSV row per (case, arm, trajectory, checkpoint) — the `[ACQUISITION-CURVE]` rows the cells
print, concatenated:

    case,arm,trajectory_id,checkpoint,actual_calls,complete,u_obs,output_tokens

Two questions, in the order `DESIGN-GENERALIZATION.md` fixes them:

1. REPLICATION, per case and pooled: is U higher in the semantic arm at a given number of environment
   interactions? Tested by permuting arm labels WITHIN each case (the trajectory is the unit, so a
   case with 3 v 3 has an exact floor of 1/20 = .05 and cannot carry the round alone) and summing the
   case-level differences.
2. ORDERING: is the effect largest on the navigational control, smallest on the shallow one, and
   between on the two architecture cases? Tested as the rank correlation between the predicted order
   and the observed one — a stronger claim than replication, because a harness that produced the same
   difference everywhere would fail it while passing (1).

The token axis is reported beside the call axis and never merged into it: the two have disagreed in
sign in every round so far, and that disagreement IS the result.
"""
import argparse
import csv
import itertools
import statistics as st
from collections import defaultdict

# The pre-registered order, best-to-worst expected arm difference. Written before the round ran.
PREDICTED_ORDER = [
    "acquisition__keycloak__rename-method-wide",
    "acquisition__keycloak__client-auth-method",
    "acquisition__keycloak__oauth-grant-type",
    "acquisition__keycloak__email-domain-mapper",
]
CHECKPOINTS = (5, 10, 20, 40)


def load(path):
    """(case, checkpoint, arm) -> {trajectory_id: u_obs}, plus the token table."""
    curves = defaultdict(dict)
    tokens = defaultdict(dict)
    with open(path) as handle:
        for row in csv.DictReader(handle):
            key = (row["case"], int(row["checkpoint"]), row["arm"])
            curves[key][row["trajectory_id"]] = float(row["u_obs"])
            tokens[key][row["trajectory_id"]] = int(row["output_tokens"])
    return curves, tokens


def exact_permutation_p(mcp, shell):
    """Two-sided exact p over every relabelling of one case's trajectories.

    Exhaustive rather than sampled: with three per arm there are only twenty distinct splits, and a
    sampled p at that size reports its own noise.
    """
    values = list(mcp) + list(shell)
    n_mcp = len(mcp)
    observed = st.mean(mcp) - st.mean(shell)
    splits = list(itertools.combinations(range(len(values)), n_mcp))
    extreme = 0
    for split in splits:
        left = [values[i] for i in split]
        right = [values[i] for i in range(len(values)) if i not in split]
        if abs(st.mean(left) - st.mean(right)) >= abs(observed) - 1e-12:
            extreme += 1
    return observed, extreme / len(splits), 1.0 / len(splits)


def stratified_p(per_case):
    """Permute within each case, sum the case-level differences, count the extremes.

    Exhaustive over the product of the per-case splits, which is 20^k for k cases with 3 v 3 — 160 000
    for four cases, cheap enough to enumerate and honest enough to quote.
    """
    observed = sum(st.mean(m) - st.mean(s) for m, s in per_case)
    space = []
    for mcp, shell in per_case:
        values = list(mcp) + list(shell)
        splits = []
        for split in itertools.combinations(range(len(values)), len(mcp)):
            left = [values[i] for i in split]
            right = [values[i] for i in range(len(values)) if i not in split]
            splits.append(st.mean(left) - st.mean(right))
        space.append(splits)
    total = extreme = 0
    for combo in itertools.product(*space):
        total += 1
        if abs(sum(combo)) >= abs(observed) - 1e-12:
            extreme += 1
    return observed, extreme / total, 1.0 / total


def spearman(a, b):
    def ranks(xs):
        order = sorted(range(len(xs)), key=lambda i: xs[i])
        out = [0.0] * len(xs)
        for rank, index in enumerate(order):
            out[index] = float(rank)
        return out

    ra, rb = ranks(a), ranks(b)
    mean_a, mean_b = st.mean(ra), st.mean(rb)
    num = sum((x - mean_a) * (y - mean_b) for x, y in zip(ra, rb))
    den = (sum((x - mean_a) ** 2 for x in ra) * sum((y - mean_b) ** 2 for y in rb)) ** 0.5
    return num / den if den else float("nan")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--curves", required=True)
    parser.add_argument("--checkpoint", type=int, default=10,
                        help="the checkpoint the ordering contrast is read at (pre-registered: 10)")
    args = parser.parse_args()
    curves, tokens = load(args.curves)

    cases = [c for c in PREDICTED_ORDER if any(k[0] == c for k in curves)]
    unknown = sorted({k[0] for k in curves} - set(PREDICTED_ORDER))
    if unknown:
        raise SystemExit(f"case(s) not in the pre-registered order: {unknown}")

    print("=== U by environment interactions ===")
    print(f"{'case':46} {'B':>3} {'mcp':>6} {'shell':>6} {'delta':>7} {'p':>7} {'floor':>7}")
    deltas_at_checkpoint = {}
    for case in cases:
        for budget in CHECKPOINTS:
            mcp = list(curves.get((case, budget, "mcp"), {}).values())
            shell = list(curves.get((case, budget, "shell"), {}).values())
            if not mcp or not shell:
                continue
            delta, p, floor = exact_permutation_p(mcp, shell)
            print(f"{case.replace('acquisition__keycloak__', ''):46} {budget:3d} "
                  f"{st.mean(mcp):6.2f} {st.mean(shell):6.2f} {delta:+7.2f} {p:7.4f} {floor:7.4f}")
            if budget == args.checkpoint:
                deltas_at_checkpoint[case] = delta

    print("\n=== stratified across cases (the primary test) ===")
    for budget in CHECKPOINTS:
        per_case = []
        for case in cases:
            mcp = list(curves.get((case, budget, "mcp"), {}).values())
            shell = list(curves.get((case, budget, "shell"), {}).values())
            if mcp and shell:
                per_case.append((mcp, shell))
        if len(per_case) < 2:
            continue
        total, p, floor = stratified_p(per_case)
        print(f"B={budget:3d}  sum of case deltas={total:+.2f}  p={p:.4f}  (floor {floor:.5f}, "
              f"{len(per_case)} cases)")

    print("\n=== ordering contrast (the round's own claim) ===")
    ordered = [c for c in PREDICTED_ORDER if c in deltas_at_checkpoint]
    if len(ordered) >= 3:
        predicted = list(range(len(ordered), 0, -1))
        observed = [deltas_at_checkpoint[c] for c in ordered]
        rho = spearman(predicted, observed)
        for case, delta in zip(ordered, observed):
            print(f"  {case.replace('acquisition__keycloak__', ''):46} delta={delta:+.2f}")
        print(f"  rho(predicted, observed) = {rho:+.3f} at B={args.checkpoint}")
    else:
        print("  fewer than three cases present; the contrast is not readable yet")

    print("\n=== U by output tokens (never merged with the axis above) ===")
    for case in cases:
        for arm in ("mcp", "shell"):
            rows = curves.get((case, 40, arm), {})
            tok = tokens.get((case, 40, arm), {})
            if not rows:
                continue
            per_fact = [tok[t] / rows[t] for t in rows if rows[t] > 0 and t in tok]
            if per_fact:
                print(f"  {case.replace('acquisition__keycloak__', ''):46} {arm:5} "
                      f"tokens per unit of U at the plateau: {st.mean(per_fact):8.0f}")


if __name__ == "__main__":
    main()
