#!/usr/bin/env python3
"""Round-3 analysis: Residual Completion Work per state, per arm, per case, and the Q1 decision rule.

Reads `rollouts-r3.csv` (one row per probe rollout) and `upstream-r3.csv` (one row per capture step) and
writes `checkpoints-r3.csv`, `summary-r3.json` and five per-case plots — or, when matplotlib cannot be
imported, the DATA of those five plots as CSV. Seeded with `20260821`, the seed
`RCW-GENERALIZATION.md` fixes, so every published number is reproducible from the committed datasets.

The statistics are round 2's, deliberately unchanged: Wilson 95 % for `V`, bootstrap over 10 000
resamples for every `RCW` mean, two-sided permutation tests on the difference of means over 100 000
relabelings. They are re-implemented here on the standard library ALONE — round 2's
`analyze_replication2.py` used numpy, which is not installed on every machine that has to re-run this,
and a pipeline that cannot be re-run is a pipeline whose numbers cannot be checked. The estimators are
identical (`numpy.percentile`'s default linear interpolation included); only the random source differs,
so bootstrap endpoints move in their last digits while means, medians, ratios and permutation `p` do not.

Measurement decisions inherited from rounds 1 and 2, each of them load-bearing:

  * `RCW_tokens` is the rollout's own OUTPUT tokens, read off the CLI's terminal `result` event. It is
    explicitly NOT `endContextTokens`: that is the size of the conversation at the end of the run, is
    dominated by the cached prompt prefix, and on the pilot it moved 1.5× where the real work moved 6×.
  * It is conditioned on SUCCESS. A failure is not a completion cost, and converting one into a large
    number would let a cell that never finishes look like a cell that finished expensively.
  * A censored success — a CLI killed at the case's budget, hence no terminal `result` event — is NOT
    imputed in the primary figure. The missingness is informative and biased against the metric: it hits
    exactly the SLOWEST successes, so filling it with the cell mean would flatter every slow state.
    Instead the imputation is done ONCE, at the worst case (the largest output-token count observed
    anywhere in the round), as criterion 6 of the Q1 rule, and the claim has to survive it.
  * `RCW_tools` and `RCW_edits` are decoded from the transcript and exist even for a killed run. That is
    their entire role: they are the censoring-immune corroboration, not a second headline.
  * `V` and `RCW` are never combined into one number. They are two coordinates of a state, which is what
    makes Q2 — does `RCW` say anything `V` does not — a question that can be answered at all.
  * `LOST` rollouts are dropped from every statistic, including from `V`'s denominator, and their count
    is published per cell. A cell that lost a run has fewer observations, not a zero.

What this script does NOT decide: criterion 2 of the Q1 rule asks whether the largest `|ΔRCW|` coincides
with a structural change **the source trace independently shows**. Only the mechanical half of that —
does the transition land on a milestone step, or on the one next to it — can be computed, and it is
reported as `coincidesWithMilestone` with `requiresTraceReview: true` next to it. The trace itself has to
be read by a human, and a script that quietly answered "yes" would be inventing the evidence.

Usage:
    analyze_rcw_r3.py --rollouts rollouts-r3.csv --upstream upstream-r3.csv --out-dir .
"""
import argparse
import csv
import json
import math
import os
import random
import statistics
import sys
from datetime import datetime, timezone

SEED = 20260821
BOOTSTRAP = 10000
PERMUTATIONS = 100000
RATIO_THRESHOLD = 2.0
MAJORITY_OF_SIX = 4


# ── statistics, stdlib only ──────────────────────────────────────────────────────────────────────

def wilson(k, n, z=1.959963985):
    """`V` with its 95 % Wilson interval. Round 2's function, unchanged."""
    if n == 0:
        return (float("nan"),) * 3
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return p, max(0.0, c - h), min(1.0, c + h)


def percentile(values, q):
    """Linear-interpolation percentile — the same estimator `numpy.percentile` uses by default."""
    ordered = sorted(values)
    if not ordered:
        return float("nan")
    if len(ordered) == 1:
        return float(ordered[0])
    position = (len(ordered) - 1) * q / 100.0
    low = int(math.floor(position))
    high = min(low + 1, len(ordered) - 1)
    return float(ordered[low] + (ordered[high] - ordered[low]) * (position - low))


def boot_ci(xs, rng, n=BOOTSTRAP):
    """Mean and its 95 % bootstrap interval over `n` resamples with replacement."""
    xs = [x for x in xs if x is not None]
    if not xs:
        return float("nan"), float("nan"), float("nan")
    if len(xs) < 2:
        return float(xs[0]), float("nan"), float("nan")
    size = len(xs)
    draws = [sum(rng.choice(xs) for _ in range(size)) / size for _ in range(n)]
    return statistics.fmean(xs), percentile(draws, 2.5), percentile(draws, 97.5)


def perm_test(a, b, rng, n=PERMUTATIONS):
    """Two-sided permutation test on the difference of means. Round 2's procedure, unchanged.

    `(hits + 1) / (n + 1)` and not `hits / n`: with five observations per cell the exact test has 252
    distinct relabelings, so an estimate of zero is an artefact of sampling and never a real `p`.
    """
    a = [x for x in a if x is not None]
    b = [x for x in b if x is not None]
    if not a or not b:
        return float("nan"), len(a), len(b)
    observed = abs(statistics.fmean(a) - statistics.fmean(b))
    pool = a + b
    split = len(a)
    total = len(pool)
    hits = 0
    for _ in range(n):
        rng.shuffle(pool)
        left = sum(pool[:split]) / split
        right = sum(pool[split:]) / (total - split)
        if abs(left - right) >= observed - 1e-12:
            hits += 1
    return (hits + 1) / (n + 1), len(a), len(b)


def spearman(xs, ys):
    """Spearman rank correlation with average ranks for ties, or NaN when a series is constant."""
    pairs = [(x, y) for x, y in zip(xs, ys) if x is not None and y is not None]
    if len(pairs) < 3:
        return float("nan")
    rx = _ranks([p[0] for p in pairs])
    ry = _ranks([p[1] for p in pairs])
    mx, my = statistics.fmean(rx), statistics.fmean(ry)
    num = sum((a - mx) * (b - my) for a, b in zip(rx, ry))
    den = math.sqrt(sum((a - mx) ** 2 for a in rx) * sum((b - my) ** 2 for b in ry))
    return float("nan") if den == 0 else num / den


def _ranks(values):
    order = sorted(range(len(values)), key=lambda i: values[i])
    ranks = [0.0] * len(values)
    i = 0
    while i < len(order):
        j = i
        while j + 1 < len(order) and values[order[j + 1]] == values[order[i]]:
            j += 1
        shared = (i + j) / 2.0 + 1
        for k in range(i, j + 1):
            ranks[order[k]] = shared
        i = j + 1
    return ranks


def disjoint(a, b):
    """Do two intervals fail to overlap? NaN endpoints mean the question cannot be answered."""
    if any(x is None or (isinstance(x, float) and math.isnan(x)) for x in (*a, *b)):
        return None
    return a[1] < b[0] or b[1] < a[0]


# ── input ────────────────────────────────────────────────────────────────────────────────────────

def num(value, cast=float):
    return None if value in ("", None) else cast(value)


def read_csv(path, what):
    if not os.path.exists(path):
        raise SystemExit(f"{path} does not exist; {what}")
    with open(path, newline="") as fh:
        rows = list(csv.DictReader(fh))
    if not rows:
        raise SystemExit(f"{path} holds no data row; refusing to analyse an empty dataset")
    return rows


def load(rollouts_path, upstream_path):
    rollouts = read_csv(rollouts_path, "run extract_rollouts_r3.py first")
    upstream = read_csv(upstream_path, "run extract_upstream_r3.py first")
    for row in rollouts:
        row["step"] = int(row["step"])
        row["replicate"] = int(row["replicate"])
        row["Y"] = num(row["Y"], int)
        row["outputTokens"] = num(row["outputTokens"], int)
        row["toolCalls"] = num(row["toolCalls"], int)
        row["editActions"] = num(row["editActions"], int)
        row["usd"] = num(row["usd"])
        row["agentSeconds"] = num(row["agentSeconds"], int)
        row["censored"] = row["censored"] == "1"
        row["lost"] = row["lost"] == "1"
        row["tampered"] = row["tampered"] == "1"
    by_step = {}
    for row in upstream:
        row["step"] = int(row["step"])
        by_step[(row["case"], row["arm"], row["step"])] = row
    return rollouts, upstream, by_step


# ── cells ────────────────────────────────────────────────────────────────────────────────────────

CELL_FIELDS = [
    "case", "arm", "checkpointId", "step", "layerCov", "fileCov", "editFraction",
    "upstreamTokens", "upstreamToolCalls", "upstreamSeconds",
    "n", "graded", "successes", "lost", "tampered", "censoredSuccesses",
    "V", "V_lo", "V_hi",
    "RCW_tokens_n", "RCW_tokens_mean", "RCW_tokens_median", "RCW_tokens_lo", "RCW_tokens_hi",
    "RCW_tools_n", "RCW_tools_mean", "RCW_edits_n", "RCW_edits_mean",
    "RCW_usd_mean", "RCW_sec_mean", "RCW_tokens_raw",
]


def cells_for(rollouts, by_step, rng):
    """One row per probed state. `V` counts graded rollouts; `RCW` counts successful ones."""
    cells = []
    keys = sorted({(r["case"], r["arm"], r["step"]) for r in rollouts})
    for case, arm, step in keys:
        rows = [r for r in rollouts if (r["case"], r["arm"], r["step"]) == (case, arm, step)]
        # LOST is withheld from V and from RCW alike: the cell's readiness is UNKNOWN, not zero.
        # A tampered run is withheld from V too — it graded an oracle the agent had edited.
        graded = [r for r in rows if not r["lost"] and not r["tampered"]]
        solved = [r for r in graded if r["Y"] == 1]
        v, lo, hi = wilson(len(solved), len(graded))
        upstream = by_step.get((case, arm, step), {})

        tokens = [r["outputTokens"] for r in solved if r["outputTokens"] is not None]
        mean, blo, bhi = boot_ci(tokens, rng)
        tools = [r["toolCalls"] for r in solved if r["toolCalls"] is not None]
        edits = [r["editActions"] for r in solved if r["editActions"] is not None]
        usd = [r["usd"] for r in solved if r["usd"] is not None]
        secs = [r["agentSeconds"] for r in solved if r["agentSeconds"] is not None]

        cells.append(dict(
            case=case, arm=arm, checkpointId=rows[0]["checkpointId"], step=step,
            layerCov=num(upstream.get("layerCov")), fileCov=num(upstream.get("fileCov")),
            editFraction=num(upstream.get("editFraction")),
            upstreamTokens=num(upstream.get("cumulativeOutputTokens")),
            upstreamToolCalls=num(upstream.get("cumulativeToolCalls")),
            upstreamSeconds=num(upstream.get("wallClockSec")),
            n=len(rows), graded=len(graded), successes=len(solved),
            lost=sum(1 for r in rows if r["lost"]), tampered=sum(1 for r in rows if r["tampered"]),
            censoredSuccesses=sum(1 for r in solved if r["censored"]),
            V=round(v, 4), V_lo=round(lo, 4), V_hi=round(hi, 4),
            RCW_tokens_n=len(tokens),
            RCW_tokens_mean=None if not tokens else round(mean, 2),
            RCW_tokens_median=None if not tokens else statistics.median(tokens),
            RCW_tokens_lo=None if len(tokens) < 2 else round(blo, 2),
            RCW_tokens_hi=None if len(tokens) < 2 else round(bhi, 2),
            RCW_tools_n=len(tools),
            RCW_tools_mean=None if not tools else round(statistics.fmean(tools), 2),
            RCW_edits_n=len(edits),
            RCW_edits_mean=None if not edits else round(statistics.fmean(edits), 2),
            RCW_usd_mean=None if not usd else round(statistics.fmean(usd), 4),
            RCW_sec_mean=None if not secs else round(statistics.fmean(secs), 1),
            RCW_tokens_raw="|".join(str(x) for x in sorted(tokens)),
        ))
    return cells


# ── the pre-registered Q1 criteria, evaluated mechanically ───────────────────────────────────────

def largest_ratio(cells):
    """Criterion 1: the largest ratio between any two states of one trajectory, and its disjointness."""
    measured = [c for c in cells if c["RCW_tokens_mean"]]
    if len(measured) < 2:
        return None
    best = None
    for i, high in enumerate(measured):
        for low in measured[i + 1:]:
            a, b = (high, low) if high["RCW_tokens_mean"] >= low["RCW_tokens_mean"] else (low, high)
            ratio = a["RCW_tokens_mean"] / b["RCW_tokens_mean"]
            if best is None or ratio > best["ratio"]:
                best = dict(
                    ratio=round(ratio, 3), highStep=a["step"], lowStep=b["step"],
                    highMean=a["RCW_tokens_mean"], lowMean=b["RCW_tokens_mean"],
                    intervalsDisjoint=disjoint(
                        (a["RCW_tokens_lo"], a["RCW_tokens_hi"]), (b["RCW_tokens_lo"], b["RCW_tokens_hi"])),
                )
    best["meetsThreshold"] = best["ratio"] >= RATIO_THRESHOLD
    best["holds"] = bool(best["meetsThreshold"] and best["intervalsDisjoint"])
    return best


def transitions_for(cells, rollouts, rng, upstream_rows):
    """Every adjacent-measured-state transition of one trajectory, with its permutation tests.

    `ΔRCW_i = RCW(s_i) − RCW(s_{i+1})` between ADJACENT MEASURED states, which is what the analysis plan
    of `RCW-GENERALIZATION.md` asks for. Monotonicity is not required and a negative `Δ` is a real
    observation, so the sign is reported rather than an absolute value.
    """
    ordered = sorted(cells, key=lambda c: c["step"])
    marks = {int(r["step"]): r for r in upstream_rows}
    out = []
    for before, after in zip(ordered, ordered[1:]):
        entry = dict(
            fromStep=before["step"], toStep=after["step"],
            fromCheckpoint=before["checkpointId"], toCheckpoint=after["checkpointId"],
        )
        for name, field in (("tokens", "outputTokens"), ("tools", "toolCalls"), ("edits", "editActions")):
            a = _raw(rollouts, before, field)
            b = _raw(rollouts, after, field)
            p, n1, n2 = perm_test(a, b, rng)
            mean_a = round(statistics.fmean(a), 2) if a else None
            mean_b = round(statistics.fmean(b), 2) if b else None
            entry[name] = dict(
                meanBefore=mean_a, meanAfter=mean_b, n1=n1, n2=n2, p=round(p, 5),
                delta=None if mean_a is None or mean_b is None else round(mean_a - mean_b, 2),
                ratio=None if not mean_a or not mean_b else round(mean_a / mean_b, 3),
            )
        entry["intervalsDisjoint"] = disjoint(
            (before["RCW_tokens_lo"], before["RCW_tokens_hi"]),
            (after["RCW_tokens_lo"], after["RCW_tokens_hi"]))
        landing = marks.get(after["step"], {})
        entry["landsOn"] = "|".join(
            name for name, key in (("M0", "isM0"), ("Mmid", "isMmid"), ("Mlast", "isMlast"), ("T", "isT"))
            if landing.get(key) == "1")
        entry["upstreamTokensSpent"] = (
            None if before["upstreamTokens"] is None or after["upstreamTokens"] is None
            else after["upstreamTokens"] - before["upstreamTokens"])
        out.append(entry)
    return out


def _raw(rollouts, cell, field):
    return [r[field] for r in rollouts
            if (r["case"], r["arm"], r["step"]) == (cell["case"], cell["arm"], cell["step"])
            and not r["lost"] and not r["tampered"] and r["Y"] == 1 and r[field] is not None]


def v_tied_pairs(cells):
    """Criterion 4 / Q2: pairs binary success calls equal whose `RCW` intervals nevertheless separate."""
    measured = [c for c in cells if c["RCW_tokens_lo"] is not None]
    tied, separated, examples = 0, 0, []
    for i, a in enumerate(measured):
        for b in measured[i + 1:]:
            if not (a["V_lo"] <= b["V_hi"] and b["V_lo"] <= a["V_hi"]):
                continue
            tied += 1
            if disjoint((a["RCW_tokens_lo"], a["RCW_tokens_hi"]),
                        (b["RCW_tokens_lo"], b["RCW_tokens_hi"])):
                separated += 1
                examples.append(dict(
                    stepA=a["step"], stepB=b["step"], V_A=a["V"], V_B=b["V"],
                    RCW_A=a["RCW_tokens_mean"], RCW_B=b["RCW_tokens_mean"]))
    return dict(vTiedPairs=tied, separatedByRCW=separated, holds=separated >= 1, examples=examples)


def worst_case(cells, rollouts, worst_value):
    """Criterion 6: every censored success imputed at the largest output count seen in the ROUND.

    The imputation is deliberately the WORST case and not a plausible one. Censoring hits the slowest
    successes, so any central-tendency fill would flatter exactly the states the metric is supposed to
    call expensive; substituting the largest value observed anywhere makes the check adversarial to the
    claim, which is the only kind of robustness check worth publishing.
    """
    out = {}
    for cell in cells:
        solved = [r for r in rollouts
                  if (r["case"], r["arm"], r["step"]) == (cell["case"], cell["arm"], cell["step"])
                  and not r["lost"] and not r["tampered"] and r["Y"] == 1]
        if not solved:
            continue
        xs = [r["outputTokens"] if r["outputTokens"] is not None else worst_value for r in solved]
        out[cell["step"]] = dict(
            n=len(xs), imputed=sum(1 for r in solved if r["outputTokens"] is None),
            mean=round(statistics.fmean(xs), 1))
    return out


def evaluate_case(case, cells, rollouts, upstream_rows, rng, worst_value):
    """The six numbered Q1 criteria for one case, arm by arm, exactly as the document numbers them."""
    arms = sorted({c["arm"] for c in cells})
    per_arm = {}
    for arm in arms:
        arm_cells = sorted([c for c in cells if c["arm"] == arm], key=lambda c: c["step"])
        arm_upstream = [r for r in upstream_rows if r["arm"] == arm]
        moves = transitions_for(arm_cells, rollouts, rng, arm_upstream)
        ratio = largest_ratio(arm_cells)

        with_delta = [m for m in moves if m["tokens"]["delta"] is not None]
        biggest = max(with_delta, key=lambda m: abs(m["tokens"]["delta"]), default=None)
        mlast_move = next((m for m in moves if "Mlast" in (m["landsOn"] or "")), None)

        spearman_rho = spearman(
            [c["layerCov"] for c in arm_cells if c["RCW_tokens_mean"]],
            [c["RCW_tokens_mean"] for c in arm_cells if c["RCW_tokens_mean"]])

        headline = mlast_move or biggest
        proxies = {}
        if headline:
            sign = _sign(headline["tokens"]["delta"])
            for name in ("tools", "edits"):
                proxies[name] = dict(
                    delta=headline[name]["delta"], p=headline[name]["p"],
                    sameSign=None if headline[name]["delta"] is None else _sign(headline[name]["delta"]) == sign)

        imputed = worst_case(arm_cells, rollouts, worst_value)
        imputed_means = {step: facts["mean"] for step, facts in imputed.items() if facts["mean"]}
        # Three readings, because "the effect survives" can mean three things. All three are published
        # so the choice is auditable, but only ONE of them can decide the criterion.
        headline_after = None
        if headline and headline["toStep"] in imputed_means and headline["fromStep"] in imputed_means:
            headline_after = round(
                imputed_means[headline["fromStep"]] / imputed_means[headline["toStep"]], 3)
        pair_after = None
        if ratio and ratio["highStep"] in imputed_means and ratio["lowStep"] in imputed_means:
            pair_after = round(imputed_means[ratio["highStep"]] / imputed_means[ratio["lowStep"]], 3)
        largest_after = (round(max(imputed_means.values()) / min(imputed_means.values()), 3)
                         if len(imputed_means) > 1 else None)

        # Criterion 6 asks whether the effect SURVIVES imputation, so it must recompute the quantity
        # criterion 1 established — the same pair of states, under substituted means — and nothing else.
        #
        # The first implementation applied criterion 1's 2x threshold to the HEADLINE (`Mlast`)
        # transition instead. That is a different claim, and it produced a nonsense reading: three arms
        # with ZERO censored successes were marked as not surviving censoring, `sb31-none` among them,
        # whose ratio after imputation is arithmetically identical to before it (4.065) because there is
        # nothing to impute. A criterion that fails on data it does not apply to is measuring the
        # threshold, not the censoring.
        #
        # So: not applicable when criterion 1 did not hold (there is no effect to survive, and reporting
        # a second failure for one cause double-counts it); trivially true when nothing was imputed;
        # otherwise the recomputed pair must still clear the threshold.
        imputed_count = sum(facts["imputed"] for facts in imputed.values())
        if not (ratio or {}).get("holds"):
            survives = None
        elif imputed_count == 0:
            survives = True
        else:
            survives = pair_after is not None and pair_after >= RATIO_THRESHOLD

        per_arm[arm] = dict(
            cells=len(arm_cells),
            criterion1_largestRatio=ratio,
            criterion2_largestDeltaTransition=biggest and dict(
                fromStep=biggest["fromStep"], toStep=biggest["toStep"],
                delta=biggest["tokens"]["delta"], p=biggest["tokens"]["p"],
                coincidesWithMilestone=bool(biggest["landsOn"]), landsOn=biggest["landsOn"],
                requiresTraceReview=True),
            criterion3_spearmanRcwVsLayerCov=None if math.isnan(spearman_rho) else round(spearman_rho, 4),
            criterion3_holds=bool(spearman_rho < 0) if not math.isnan(spearman_rho) else None,
            criterion5_proxies=proxies,
            criterion5_holds=any(p.get("sameSign") for p in proxies.values()) if proxies else None,
            criterion6_worstCase=dict(
                imputedValue=worst_value, perCell=imputed,
                headlineRatioAfterImputation=headline_after,
                originalPairRatioAfterImputation=pair_after,
                largestRatioAfterImputation=largest_after,
                imputedSuccesses=imputed_count,
                holds=survives),
            headlineTransition=headline and dict(
                fromStep=headline["fromStep"], toStep=headline["toStep"],
                landsOn=headline["landsOn"],
                tokens=headline["tokens"], tools=headline["tools"], edits=headline["edits"],
                intervalsDisjoint=headline["intervalsDisjoint"]),
            transitions=moves,
        )

    q2 = v_tied_pairs(cells)
    criteria = {
        "1_largeRatioWithDisjointIntervals": any(
            (per_arm[a]["criterion1_largestRatio"] or {}).get("holds") for a in arms),
        "2_largestDeltaAtStructuralChange": any(
            (per_arm[a]["criterion2_largestDeltaTransition"] or {}).get("coincidesWithMilestone")
            for a in arms),
        "3_spearmanNegativeOnBothArms": all(per_arm[a]["criterion3_holds"] for a in arms) if arms else None,
        "4_rcwSeparatesVTiedStates": q2["holds"],
        "5_censoringImmuneProxyAgrees": any(per_arm[a]["criterion5_holds"] for a in arms),
        "6_survivesWorstCaseCensoring": any(
            (per_arm[a]["criterion6_worstCase"] or {}).get("holds") for a in arms),
    }
    # How the per-arm readings fold into one per-case verdict. The document writes criterion 3 as "on
    # both arms of the case" and leaves the other five at the trajectory level, so those are satisfied
    # when EITHER trajectory shows them and the both-arms case is reported separately as
    # `especiallyStrong`, which is the document's own name for it.
    aggregation = {
        "1_largeRatioWithDisjointIntervals": "any arm",
        "2_largestDeltaAtStructuralChange": "any arm (mechanical half only)",
        "3_spearmanNegativeOnBothArms": "all arms, as the document words it",
        "4_rcwSeparatesVTiedStates": "case-wide, over every pair of probed states",
        "5_censoringImmuneProxyAgrees": "any arm",
        "6_survivesWorstCaseCensoring": "any arm",
    }
    return dict(
        case=case, arms=arms, perArm=per_arm, q2_constructValidity=q2, criteria=criteria,
        criteriaAggregation=aggregation,
        allSix=all(bool(v) for v in criteria.values()),
        especiallyStrong=all(
            bool((per_arm[a]["criterion1_largestRatio"] or {}).get("holds")) and
            bool(per_arm[a]["criterion3_holds"]) and bool(per_arm[a]["criterion5_holds"])
            for a in arms) if len(arms) > 1 else False,
        criterion2Note="the trace half of criterion 2 is not mechanical: open the transcript around "
                       "the named steps and describe what it actually shows, or record it as unexplained",
    )


def _sign(value):
    return 0 if value is None else (1 if value > 0 else (-1 if value < 0 else 0))


def finite(value):
    """`NaN`/`Infinity` to `null`, recursively — `summary-r3.json` must be STRICT JSON.

    Python writes bare `NaN`, which every other JSON reader rejects. A statistic that could not be
    computed is `null`, and `null` is honest: it says the number does not exist rather than printing a
    token that parses as a number in exactly one language.
    """
    if isinstance(value, float) and (math.isnan(value) or math.isinf(value)):
        return None
    if isinstance(value, dict):
        return {k: finite(v) for k, v in value.items()}
    if isinstance(value, list):
        return [finite(v) for v in value]
    return value


def q3_table(upstream_rows, cases):
    """Q3, reported and never optimized for: what each arm spent to reach its own `Mlast`, and in total."""
    table = {}
    for case in cases:
        arms = {}
        for arm in sorted({r["arm"] for r in upstream_rows if r["case"] == case}):
            rows = sorted([r for r in upstream_rows if r["case"] == case and r["arm"] == arm],
                          key=lambda r: int(r["step"]))
            mlast = next((r for r in rows if r["isMlast"] == "1"), None)
            last = rows[-1]
            arms[arm] = dict(
                steps=int(last["step"]),
                MlastStep=None if mlast is None else int(mlast["step"]),
                atMlast=None if mlast is None else dict(
                    outputTokens=num(mlast["cumulativeOutputTokens"]),
                    toolCalls=num(mlast["cumulativeToolCalls"]),
                    wallClockSec=num(mlast["wallClockSec"]),
                    editFraction=num(mlast["editFraction"]),
                ),
                atEnd=dict(
                    outputTokens=num(last["cumulativeOutputTokens"]),
                    toolCalls=num(last["cumulativeToolCalls"]),
                    wallClockSec=num(last["wallClockSec"]),
                    layerCov=num(last["layerCov"]), fileCov=num(last["fileCov"]),
                ),
            )
        table[case] = dict(arms=arms, earlierAtMlast=_who_is_earlier(arms))
    return table


def _who_is_earlier(arms):
    """Which arm reached `Mlast` first on each denominator — the sign, with no threshold attached."""
    out = {}
    for key in ("outputTokens", "toolCalls", "wallClockSec"):
        readings = {
            arm: facts["atMlast"][key]
            for arm, facts in arms.items() if facts["atMlast"] and facts["atMlast"][key] is not None
        }
        out[key] = min(readings, key=readings.get) if len(readings) > 1 else None
    return out


# ── plots ────────────────────────────────────────────────────────────────────────────────────────

PLOTS = [
    ("a-rcw-vs-upstream", "residual work against every upstream denominator"),
    ("b-coverage-trajectory", "layerCov and fileCov per source step, with the probed states marked"),
    ("c-state-space", "V against residual work, one point per probed state"),
    ("d-delta-rcw", "ΔRCW between adjacent measured states and its leverage per upstream token"),
    ("e-censoring-immune-proxies", "RCW_tools and RCW_edits per state"),
]


def plot_data(case, cells, upstream_rows, per_arm):
    """The five pre-registered panels as plain tables — the same rows a renderer would consume."""
    probed = {(c["arm"], c["step"]) for c in cells}
    panels = {
        "a-rcw-vs-upstream": [
            dict(arm=c["arm"], step=c["step"], checkpointId=c["checkpointId"],
                 upstreamToolCalls=c["upstreamToolCalls"], upstreamTokens=c["upstreamTokens"],
                 upstreamSeconds=c["upstreamSeconds"], layerCov=c["layerCov"],
                 editFraction=c["editFraction"], RCW_tokens_mean=c["RCW_tokens_mean"],
                 RCW_tokens_lo=c["RCW_tokens_lo"], RCW_tokens_hi=c["RCW_tokens_hi"],
                 RCW_tokens_raw=c["RCW_tokens_raw"])
            for c in cells],
        "b-coverage-trajectory": [
            dict(arm=r["arm"], step=int(r["step"]), layerCov=r["layerCov"], fileCov=r["fileCov"],
                 isM0=r["isM0"], isMmid=r["isMmid"], isMlast=r["isMlast"], isT=r["isT"],
                 probed=int((r["arm"], int(r["step"])) in probed))
            for r in upstream_rows],
        "c-state-space": [
            dict(arm=c["arm"], step=c["step"], V=c["V"], V_lo=c["V_lo"], V_hi=c["V_hi"],
                 RCW_tokens_mean=c["RCW_tokens_mean"])
            for c in cells],
        "d-delta-rcw": [
            dict(arm=arm, fromStep=m["fromStep"], toStep=m["toStep"], landsOn=m["landsOn"],
                 deltaRCW=m["tokens"]["delta"], p=m["tokens"]["p"],
                 upstreamTokensSpent=m["upstreamTokensSpent"],
                 eta=None if not m["upstreamTokensSpent"] or m["tokens"]["delta"] is None
                 else round(m["tokens"]["delta"] / m["upstreamTokensSpent"], 4))
            for arm, facts in per_arm.items() for m in facts["transitions"]],
        "e-censoring-immune-proxies": [
            dict(arm=c["arm"], step=c["step"], layerCov=c["layerCov"],
                 RCW_tools_mean=c["RCW_tools_mean"], RCW_edits_mean=c["RCW_edits_mean"],
                 censoredSuccesses=c["censoredSuccesses"])
            for c in cells],
    }
    return panels


def write_plots(out_dir, case, panels, renderer):
    """Render the five panels if matplotlib is importable, else publish their data as CSV.

    matplotlib is not a hard dependency of this pipeline: the numbers are the result and a machine that
    cannot draw them must still be able to produce and check them. Which branch was taken is printed.
    """
    directory = os.path.join(out_dir, "plots")
    os.makedirs(directory, exist_ok=True)
    written = []
    for name, _ in PLOTS:
        rows = panels[name]
        if not rows:
            print(f"  {case} {name}: no rows, panel skipped", file=sys.stderr)
            continue
        path = os.path.join(directory, f"{case}-{name}.csv")
        with open(path, "w", newline="") as fh:
            writer = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
            writer.writeheader()
            writer.writerows(rows)
        written.append(os.path.basename(path))
        if renderer is not None:
            written.append(os.path.basename(renderer(directory, case, name, rows)))
    return written


def make_renderer():
    """A renderer, or None when matplotlib is not installed. Never a silent no-op."""
    try:
        import matplotlib
    except ImportError as e:
        print(f"matplotlib is not importable ({e}); writing the plot DATA as CSV instead of figures",
              file=sys.stderr)
        return None
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    def render(directory, case, name, rows):
        keys = [k for k in rows[0]
                if k not in ("arm", "landsOn", "checkpointId", "RCW_tokens_raw")
                and any(isinstance(r[k], (int, float)) for r in rows)]
        x = keys[0]
        ys = [k for k in keys[1:]]
        fig, ax = plt.subplots(figsize=(9, 5))
        for arm in sorted({r.get("arm") for r in rows}):
            series = sorted([r for r in rows if r.get("arm") == arm and r[x] is not None],
                            key=lambda r: r[x])
            for y in ys:
                points = [(r[x], r[y]) for r in series if isinstance(r[y], (int, float))]
                if points:
                    ax.plot([p[0] for p in points], [p[1] for p in points], "-o", ms=4,
                            label=f"{arm} {y}")
        ax.set_xlabel(x)
        ax.set_title(f"{case} — {name}")
        ax.grid(alpha=0.3)
        ax.legend(fontsize=7)
        path = os.path.join(directory, f"{case}-{name}.png")
        fig.tight_layout()
        fig.savefig(path, dpi=140)
        plt.close(fig)
        return path

    print("matplotlib is available; writing figures next to the plot data", file=sys.stderr)
    return render


# ── main ─────────────────────────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="Residual Completion Work per state and the pre-registered Q1 decision rule.",
        epilog=f"seed {SEED}; bootstrap {BOOTSTRAP}; permutations {PERMUTATIONS}",
    )
    parser.add_argument("--rollouts", default="rollouts-r3.csv")
    parser.add_argument("--upstream", default="upstream-r3.csv")
    parser.add_argument("--out-dir", default=".")
    args = parser.parse_args()

    rollouts, upstream, by_step = load(args.rollouts, args.upstream)
    os.makedirs(args.out_dir, exist_ok=True)
    rng = random.Random(SEED)

    cells = cells_for(rollouts, by_step, rng)
    with open(os.path.join(args.out_dir, "checkpoints-r3.csv"), "w", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=CELL_FIELDS, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(cells)

    worst_value = max((r["outputTokens"] for r in rollouts if r["outputTokens"] is not None), default=None)
    cases = sorted({c["case"] for c in cells})
    renderer = make_renderer()

    per_case, plots_written = {}, {}
    for case in cases:
        case_cells = [c for c in cells if c["case"] == case]
        case_rollouts = [r for r in rollouts if r["case"] == case]
        case_upstream = [r for r in upstream if r["case"] == case]
        verdict = evaluate_case(case, case_cells, case_rollouts, case_upstream, rng, worst_value)
        per_case[case] = verdict
        plots_written[case] = write_plots(
            args.out_dir, case, plot_data(case, case_cells, case_upstream, verdict["perArm"]), renderer)

    supported = [c for c in cases if per_case[c]["allSix"]]
    summary = dict(
        generated=datetime.now(timezone.utc).isoformat(timespec="seconds"),
        seed=SEED, bootstrapResamples=BOOTSTRAP, permutations=PERMUTATIONS,
        rollouts=len(rollouts), cells=len(cells), cases=cases,
        outcomes={
            reason: sum(1 for r in rollouts if r["exitReason"] == reason)
            for reason in sorted({r["exitReason"] for r in rollouts})},
        censoring=dict(
            censoredRollouts=sum(1 for r in rollouts if r["censored"]),
            lostRollouts=sum(1 for r in rollouts if r["lost"]),
            worstCaseImputationValue=worst_value,
            cellsWithCensoredSuccess=sum(1 for c in cells if c["censoredSuccesses"]),
        ),
        perCase=per_case,
        crossCase=dict(
            casesEvaluated=len(cases),
            casesMeetingAllSix=len(supported), which=supported,
            majorityThreshold=MAJORITY_OF_SIX,
            q1Supported=len(supported) >= MAJORITY_OF_SIX,
            especiallyStrong=[c for c in cases if per_case[c]["especiallyStrong"]],
            note="Q1 needs >= 4 of the 6 round-3 cases; a run over fewer cases is reported as partial "
                 "and decides nothing.",
            partial=len(cases) < 6,
        ),
        q3=q3_table(upstream, cases),
        plots=dict(rendered=renderer is not None, files=plots_written),
    )
    with open(os.path.join(args.out_dir, "summary-r3.json"), "w") as fh:
        json.dump(finite(summary), fh, indent=1)
        fh.write("\n")

    print(f"{len(rollouts)} rollouts, {len(cells)} cells, {len(cases)} case(s) -> "
          f"checkpoints-r3.csv, summary-r3.json, plots/")
    for case in cases:
        criteria = per_case[case]["criteria"]
        print(f"  {case}: " + " ".join(f"{k.split('_')[0]}={'Y' if v else 'n'}"
                                       for k, v in criteria.items()))
        for arm, facts in per_case[case]["perArm"].items():
            head = facts["headlineTransition"]
            if head:
                print(f"    {arm} {head['fromStep']}->{head['toStep']} ({head['landsOn'] or '-'}): "
                      f"{head['tokens']['meanBefore']} -> {head['tokens']['meanAfter']} "
                      f"({head['tokens']['ratio']}x, p={head['tokens']['p']}), "
                      f"tools {head['tools']['ratio']}x p={head['tools']['p']}, "
                      f"edits {head['edits']['ratio']}x p={head['edits']['p']}, "
                      f"CIs disjoint={head['intervalsDisjoint']}")


if __name__ == "__main__":
    main()
