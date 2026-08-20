#!/usr/bin/env python3
"""Residual-difficulty analysis of the ripple checkpoint pilot, from the recovered per-rollout dataset.

Usage:
    RIPPLE_ROLLOUTS=rollouts-raw.json RIPPLE_DOCS=. python3 analyze_residual_difficulty.py

Reads what extract_rollouts.py produced and writes rollouts.csv/json, checkpoints.csv, summary.json and
the four figures of RESIDUAL-DIFFICULTY.md. Needs numpy and matplotlib.
"""
import csv
import json
import math
import os
import random

import numpy as np
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

SEED = 20260820
OUT = os.environ.get("RIPPLE_DOCS", os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DATA = os.path.join(OUT, "data")
os.makedirs(DATA, exist_ok=True)

# The three cells whose grade is an instrument failure rather than a verdict. Two are recorded as LOST
# by the probe itself; `1035679682` predates `extractApiTransportError` and was published as `Y=0`
# after Anthropic closed the connection 26 s in — RUN-IDS.md withdraws it and its re-run is 1035939472.
LOST = {
    "1035674856": "no-grade",
    "1035678932": "no-verdict-line",
    "1035679682": "api-transport-error",
}
GRID = {"mcp": [15, 16, 17, 18, 19, 24], "none": [17, 25, 29, 33, 37, 41, 45, 49]}
# `editFraction` as `checkpoints.json` defines it. Needed as a lookup because the cells reused from the
# first grid were measured before the axis existed and their verdict line carries `position` only.
EDIT_FRACTION = {
    ("mcp", 15): 0.0, ("mcp", 16): 0.1, ("mcp", 17): 0.2, ("mcp", 18): 0.3, ("mcp", 19): 0.4, ("mcp", 24): 0.8,
    ("none", 17): 0.0, ("none", 25): 0.2, ("none", 29): 0.3, ("none", 33): 0.4, ("none", 37): 0.5,
    ("none", 41): 0.6, ("none", 45): 0.7, ("none", 49): 0.8,
}
BASELINE_STEPS = {("mcp", 2), ("none", 4)}
ARM_LABEL = {"mcp": "mcp (semantic IDE)", "none": "shell only"}
COLOR = {"mcp": "#1f77b4", "none": "#d62728"}


def outcome(r):
    if r["buildId"] in LOST or r["success"] is None:
        return "lost"
    if r["ftpTampered"]:
        return "tampered"
    return "solved" if r["success"] == 1 else "failed"


def tool_calls(r):
    parts = [r[k] for k in ("readCalls", "editCalls", "writeCalls", "globCalls", "grepCalls", "bashCalls", "execCodeCalls")]
    return None if any(p is None for p in parts) else sum(parts)


def wilson(k, n, z=1.959963985):
    if n == 0:
        return (float("nan"),) * 3
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return p, max(0.0, c - h), min(1.0, c + h)


def boot_ci(xs, stat=np.mean, n=10000, alpha=0.05, rng=None):
    xs = [x for x in xs if x is not None]
    if len(xs) < 2:
        return (stat(xs) if xs else float("nan")), float("nan"), float("nan")
    rng = rng or np.random.default_rng(SEED)
    a = np.array(xs, dtype=float)
    draws = stat(rng.choice(a, size=(n, len(a)), replace=True), axis=1)
    return float(stat(a)), float(np.percentile(draws, 100 * alpha / 2)), float(np.percentile(draws, 100 * (1 - alpha / 2)))


def perm_test(a, b, n=100000, rng=None):
    """Two-sided permutation test on the difference of means."""
    a = [x for x in a if x is not None]
    b = [x for x in b if x is not None]
    if not a or not b:
        return float("nan"), 0, 0
    rng = rng or random.Random(SEED)
    obs = abs(np.mean(a) - np.mean(b))
    pool = a + b
    hits = 0
    for _ in range(n):
        rng.shuffle(pool)
        if abs(np.mean(pool[: len(a)]) - np.mean(pool[len(a):])) >= obs - 1e-12:
            hits += 1
    return (hits + 1) / (n + 1), len(a), len(b)


def main():
    rows = json.load(open(os.environ.get("RIPPLE_ROLLOUTS", "/tmp/rd/rollouts.json")))
    for r in rows:
        r["outcome"] = outcome(r)
        r["toolCalls"] = tool_calls(r)
        r["editsAndWrites"] = None if r["editCalls"] is None else r["editCalls"] + (r["writeCalls"] or 0)
        r["inGrid"] = r["step"] in GRID[r["arm"]]
        r["isBaselineTree"] = (r["arm"], r["step"]) in BASELINE_STEPS
        if r["editFraction"] is None:
            r["editFraction"] = EDIT_FRACTION.get((r["arm"], r["step"]))
        r["ftpClassScore"] = None if r["ftpClassesPassed"] is None else r["ftpClassesPassed"] / r["ftpClassesTotal"]
        # Finer partial credit than the class count: the verifier's own Maven summary over the five
        # FAIL_TO_PASS classes, recoverable from the printed tail in 84 of the 97 builds.
        r["ftpTestScore"] = (
            None if not r["ftpTestsRun"] else (r["ftpTestsRun"] - (r["ftpTestsFailed"] or 0)) / r["ftpTestsRun"]
        )

    fields = [
        "buildId", "arm", "step", "editFraction", "position", "replicate", "grid", "inGrid", "isBaselineTree",
        "verdict", "outcome", "success", "lostReason", "ftpTampered", "testPatchFilesEdited",
        "patchChars", "ftpClassesPassed", "ftpClassesTotal", "ftpClassScore", "ftpTestsRun", "ftpTestsFailed",
        "ftpTestScore", "verifierObjective", "regressions", "claimedFix", "usedMcp", "exitCode", "budgetExhausted",
        "apiTransportError", "usageEventMissing", "agentSecondsArena", "prewarmSeconds", "numTurns", "apiSeconds",
        "endContextTokens", "inputTokens", "outputTokens", "cacheCreationTokens", "cacheReadTokens",
        "costUsdArena", "usdVerdict", "toolCalls", "readCalls", "editCalls", "writeCalls", "globCalls",
        "grepCalls", "bashCalls", "execCodeCalls", "editsAndWrites", "agentTestsRun", "agentTestsFail",
        "agentBuildSuccess", "baselinePassing", "baselineFailing", "verifyMavenExit", "agentModel",
    ]
    with open(os.path.join(DATA, "rollouts.csv"), "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=fields, extrasaction="ignore")
        w.writeheader()
        for r in sorted(rows, key=lambda r: (r["arm"], r["step"], r["replicate"], r["buildId"])):
            w.writerow(r)
    json.dump(
        sorted(rows, key=lambda r: (r["arm"], r["step"], r["replicate"], r["buildId"])),
        open(os.path.join(DATA, "rollouts.json"), "w"),
        indent=1,
    )

    rng = np.random.default_rng(SEED)
    cells = []
    for arm in ("mcp", "none"):
        for step in GRID[arm]:
            rs = [r for r in rows if r["arm"] == arm and r["step"] == step]
            graded = [r for r in rs if r["outcome"] in ("solved", "failed")]
            solved = [r for r in graded if r["outcome"] == "solved"]
            tampered = [r for r in rs if r["outcome"] == "tampered"]
            lost = [r for r in rs if r["outcome"] == "lost"]
            v, lo, hi = wilson(len(solved), len(graded))
            cell = dict(
                arm=arm, step=step,
                editFraction=EDIT_FRACTION[(arm, step)],
                patchChars=next((r["patchChars"] for r in rs if r["patchChars"]), None),
                runs=len(rs), graded=len(graded), solved=len(solved), tampered=len(tampered), lost=len(lost),
                V=round(v, 4), V_lo=round(lo, 4), V_hi=round(hi, 4),
            )
            # tamper sensitivity: counted as a failure (the pilot's published choice) vs excluded (here)
            v2, lo2, hi2 = wilson(len(solved), len(graded) + len(tampered))
            cell.update(V_tamper_as_fail=round(v2, 4), V_tamper_as_fail_lo=round(lo2, 4), V_tamper_as_fail_hi=round(hi2, 4))
            for key, field in (
                ("outTok", "outputTokens"), ("endCtx", "endContextTokens"), ("usd", "costUsdArena"),
                ("sec", "agentSecondsArena"), ("turns", "numTurns"), ("tools", "toolCalls"),
                ("edits", "editsAndWrites"),
            ):
                xs = [r[field] for r in solved if r[field] is not None]
                m, l, h = boot_ci(xs, rng=rng)
                cell[f"{key}_n"] = len(xs)
                cell[f"{key}_mean"] = None if not xs else round(m, 4)
                cell[f"{key}_lo"] = None if len(xs) < 2 else round(l, 4)
                cell[f"{key}_hi"] = None if len(xs) < 2 else round(h, 4)
                cell[f"{key}_median"] = None if not xs else float(np.median(xs))
            partial = [r["ftpClassScore"] for r in graded + tampered if r["ftpClassScore"] is not None]
            cell["partial_mean"] = round(float(np.mean(partial)), 4) if partial else None
            cell["partial_n"] = len(partial)
            pf = [r["ftpClassScore"] for r in graded if r["outcome"] == "failed" and r["ftpClassScore"] is not None]
            cell["partial_failed_mean"] = round(float(np.mean(pf)), 4) if pf else None
            pt = [r["ftpTestScore"] for r in graded + tampered if r["ftpTestScore"] is not None]
            cell["partialTests_mean"] = round(float(np.mean(pt)), 4) if pt else None
            cell["partialTests_n"] = len(pt)
            ptf = [r["ftpTestScore"] for r in graded if r["outcome"] == "failed" and r["ftpTestScore"] is not None]
            cell["partialTests_failed_mean"] = round(float(np.mean(ptf)), 4) if ptf else None
            cells.append(cell)

    with open(os.path.join(DATA, "checkpoints.csv"), "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=list(cells[0].keys()))
        w.writeheader()
        w.writerows(cells)

    base = [r for r in rows if r["isBaselineTree"]]
    b_solved = sum(1 for r in base if r["outcome"] == "solved")
    b_v, b_lo, b_hi = wilson(b_solved, len(base))
    base_out = [r["outputTokens"] for r in base if r["outcome"] == "solved" and r["outputTokens"] is not None]

    def cell(arm, step):
        return next(c for c in cells if c["arm"] == arm and c["step"] == step)

    def vals(arm, step, field, only_solved=True):
        return [
            r[field] for r in rows
            if r["arm"] == arm and r["step"] == step and r[field] is not None
            and (r["outcome"] == "solved" if only_solved else r["outcome"] in ("solved", "failed"))
        ]

    prng = random.Random(SEED)
    tests = {}
    for name, (a1, s1), (a2, s2), field in (
        ("mcp 0.3 vs 0.4 outputTokens", ("mcp", 18), ("mcp", 19), "outputTokens"),
        ("mcp 0.3 vs 0.4 endContextTokens", ("mcp", 18), ("mcp", 19), "endContextTokens"),
        ("mcp 0.3 vs 0.4 toolCalls", ("mcp", 18), ("mcp", 19), "toolCalls"),
        ("mcp 0.3 vs 0.4 editsAndWrites", ("mcp", 18), ("mcp", 19), "editsAndWrites"),
        ("mcp<=0.3 vs mcp>=0.4 outputTokens", ("mcp", "early"), ("mcp", "late"), "outputTokens"),
        ("shell<=0.7 vs shell 0.8 outputTokens", ("none", "early"), ("none", "late"), "outputTokens"),
        ("mcp 0.4 vs shell 0.4 outputTokens", ("mcp", 19), ("none", 33), "outputTokens"),
        ("mcp 0.4 vs shell 0.4 toolCalls", ("mcp", 19), ("none", 33), "toolCalls"),
        ("mcp 0.4 vs shell 0.8 outputTokens", ("mcp", 19), ("none", 49), "outputTokens"),
        ("mcp 0.3 vs pristine tree outputTokens", ("mcp", 18), ("base", 0), "outputTokens"),
        ("mcp 0.4 vs pristine tree outputTokens", ("mcp", 19), ("base", 0), "outputTokens"),
        ("shell 0.6 vs pristine tree outputTokens", ("none", 41), ("base", 0), "outputTokens"),
        ("shell 0.8 vs pristine tree outputTokens", ("none", 49), ("base", 0), "outputTokens"),
    ):
        def collect(arm, step):
            if arm == "base":
                return [r[field] for r in base if r["outcome"] == "solved" and r[field] is not None]
            if step == "early":
                steps = [s for s in GRID[arm] if s <= (18 if arm == "mcp" else 45)]
            elif step == "late":
                steps = [s for s in GRID[arm] if s >= (19 if arm == "mcp" else 49)]
            else:
                steps = [step]
            return [x for s in steps for x in vals(arm, s, field)]

        p, na, nb = perm_test(collect(a1, s1), collect(a2, s2), rng=prng)
        tests[name] = dict(p=round(p, 5), n1=na, n2=nb,
                           mean1=round(float(np.mean(collect(a1, s1))), 1) if na else None,
                           mean2=round(float(np.mean(collect(a2, s2))), 1) if nb else None)

    # Worst case for the censored successes: give every one of them the LARGEST output-token count seen
    # anywhere in the pilot. If the drop survives that, it is not an artefact of the missing usage events.
    worst = max(r["outputTokens"] for r in rows if r["outputTokens"] is not None)
    worst_case = {}
    for arm in ("mcp", "none"):
        for step in GRID[arm]:
            solved = [r for r in rows if r["arm"] == arm and r["step"] == step and r["outcome"] == "solved"]
            if not solved:
                continue
            xs = [r["outputTokens"] if r["outputTokens"] is not None else worst for r in solved]
            worst_case[f"{arm} {EDIT_FRACTION[(arm, step)]:.1f}"] = dict(
                n=len(xs), imputed=sum(1 for r in solved if r["outputTokens"] is None),
                mean=round(float(np.mean(xs)), 1),
            )
    worst_case["_imputed_value"] = worst

    # Is residual work a smoother progress signal than the binary V? Spearman against editFraction.
    def spearman(xs, ys):
        def rank(v):
            order = sorted(range(len(v)), key=lambda i: v[i])
            rk = [0.0] * len(v)
            i = 0
            while i < len(order):
                j = i
                while j + 1 < len(order) and v[order[j + 1]] == v[order[i]]:
                    j += 1
                avg = (i + j) / 2 + 1
                for k in range(i, j + 1):
                    rk[order[k]] = avg
                i = j + 1
            return rk
        rx, ry = rank(xs), rank(ys)
        mx, my = np.mean(rx), np.mean(ry)
        num = sum((a - mx) * (b - my) for a, b in zip(rx, ry))
        den = math.sqrt(sum((a - mx) ** 2 for a in rx) * sum((b - my) ** 2 for b in ry))
        return round(num / den, 4) if den else float("nan")

    spearman_tbl = {}
    for arm in ("mcp", "none"):
        cs = sorted([c for c in cells if c["arm"] == arm], key=lambda c: c["editFraction"])
        spearman_tbl[arm] = dict(
            V=spearman([c["editFraction"] for c in cs], [c["V"] for c in cs]),
            outputTokens=spearman([c["editFraction"] for c in cs if c["outTok_mean"] is not None],
                                  [c["outTok_mean"] for c in cs if c["outTok_mean"] is not None]),
            toolCalls=spearman([c["editFraction"] for c in cs if c["tools_mean"] is not None],
                               [c["tools_mean"] for c in cs if c["tools_mean"] is not None]),
            partialVerifier=spearman([c["editFraction"] for c in cs if c["partial_mean"] is not None],
                                     [c["partial_mean"] for c in cs if c["partial_mean"] is not None]),
            partialVerifierTests=spearman([c["editFraction"] for c in cs if c["partialTests_mean"] is not None],
                                          [c["partialTests_mean"] for c in cs if c["partialTests_mean"] is not None]),
        )

    def auc(arm, key="V"):
        pts = sorted(((c["editFraction"], c[key]) for c in cells if c["arm"] == arm), key=lambda t: t[0])
        area = sum((pts[i + 1][0] - pts[i][0]) * (pts[i][1] + pts[i + 1][1]) / 2 for i in range(len(pts) - 1))
        return area, area / (pts[-1][0] - pts[0][0])

    summary = dict(
        seed=SEED,
        rollouts=len(rows),
        inGrid=sum(1 for r in rows if r["inGrid"]),
        outcomes={o: sum(1 for r in rows if r["outcome"] == o) for o in ("solved", "failed", "tampered", "lost")},
        baseline=dict(n=len(base), solved=b_solved, V=round(b_v, 4), lo=round(b_lo, 4), hi=round(b_hi, 4),
                      outputTokens_mean=round(float(np.mean(base_out)), 1) if base_out else None,
                      outputTokens_n=len(base_out)),
        auc={arm: dict(zip(("auc", "normalised"), map(lambda x: round(x, 4), auc(arm)))) for arm in ("mcp", "none")},
        auc_tamper_as_fail={arm: dict(zip(("auc", "normalised"),
                                          map(lambda x: round(x, 4), auc(arm, "V_tamper_as_fail"))))
                            for arm in ("mcp", "none")},
        permutation_tests=tests,
        censoring=dict(
            solved_total=sum(1 for r in rows if r["outcome"] == "solved"),
            solved_without_usage=sum(1 for r in rows if r["outcome"] == "solved" and r["outputTokens"] is None),
            # `Agent budget: EXHAUSTED` only exists in the later builds, so the run fact is read off the
            # runner instead: the case's limit is 1800 s and the CLI is killed, hence exit=-1 and no usage.
            solved_at_the_1800s_limit=sum(
                1 for r in rows if r["outcome"] == "solved" and (r["agentSecondsArena"] or 0) >= 1795
            ),
            note="a killed CLI emits no terminal result event, so usd/input/output/cache are NA exactly for "
                 "the slowest runs; toolCalls and editsAndWrites come from the decoded transcript and survive",
        ),
        worst_case_imputation=worst_case,
        monotonicity_spearman=spearman_tbl,
    )
    json.dump(summary, open(os.path.join(DATA, "summary.json"), "w"), indent=1)
    print(json.dumps(summary, indent=1))
    plots(cells, rows, summary, worst_case)


def plots(cells, rows, summary, worst_case):
    def arm_cells(arm):
        return sorted([c for c in cells if c["arm"] == arm], key=lambda c: c["editFraction"])

    # A — success probability
    fig, ax = plt.subplots(figsize=(7.2, 4.4))
    for arm in ("mcp", "none"):
        cs = arm_cells(arm)
        x = [c["editFraction"] for c in cs]
        y = [c["V"] for c in cs]
        err = [[c["V"] - c["V_lo"] for c in cs], [c["V_hi"] - c["V"] for c in cs]]
        ax.errorbar(x, y, yerr=err, marker="o", capsize=3, color=COLOR[arm], label=ARM_LABEL[arm], lw=1.8)
    b = summary["baseline"]
    ax.axhline(b["V"], ls="--", color="grey", lw=1)
    ax.axhspan(b["lo"], b["hi"], color="grey", alpha=0.12)
    ax.text(0.02, b["V"] + 0.015, f"pristine-tree baseline {b['V']:.2f} (n={b['n']})", color="grey", fontsize=8)
    ax.set_xlabel("editFraction  (0 = first write of the source run, 1 = its end)")
    ax.set_ylabel("V = P(probe finishes the task)")
    ax.set_title("A. Solvability from an inherited state (Wilson 95 %)")
    ax.set_ylim(-0.05, 1.12)
    ax.legend(loc="lower right", fontsize=8)
    ax.grid(alpha=0.25)
    fig.tight_layout()
    fig.savefig(os.path.join(OUT, "fig-a-success-vs-turn.png"), dpi=160)

    # B — residual cost, two panels
    fig, axes = plt.subplots(1, 2, figsize=(11.5, 4.4), sharex=True)
    for ax, key, title, unit in (
        (axes[0], "endCtx", "B1. End-of-run context of a successful probe", "tokens"),
        (axes[1], "outTok", "B2. Cumulative OUTPUT tokens of a successful probe", "tokens"),
    ):
        for arm in ("mcp", "none"):
            cs = [c for c in arm_cells(arm) if c[f"{key}_mean"] is not None]
            x = [c["editFraction"] for c in cs]
            y = [c[f"{key}_mean"] for c in cs]
            lo = [c[f"{key}_mean"] - (c[f"{key}_lo"] if c[f"{key}_lo"] is not None else c[f"{key}_mean"]) for c in cs]
            hi = [(c[f"{key}_hi"] if c[f"{key}_hi"] is not None else c[f"{key}_mean"]) - c[f"{key}_mean"] for c in cs]
            ax.errorbar(x, y, yerr=[lo, hi], marker="o", capsize=3, color=COLOR[arm], label=ARM_LABEL[arm], lw=1.8)
            for c in cs:
                ax.annotate(f"n={c[f'{key}_n']}", (c["editFraction"], c[f"{key}_mean"]),
                            textcoords="offset points", xytext=(4, 5), fontsize=6.5, color=COLOR[arm])
            if key == "outTok":
                # Worst case for the runs killed at the 1800 s limit, which report no usage at all: each
                # is given the largest output-token count in the pilot. The drop has to survive this.
                wc = [(c["editFraction"], worst_case[f"{arm} {c['editFraction']:.1f}"]["mean"]) for c in arm_cells(arm)]
                ax.plot([p[0] for p in wc], [p[1] for p in wc], ":", color=COLOR[arm], alpha=0.65, lw=1.3,
                        label=f"{ARM_LABEL[arm]} — censored runs imputed at max")
        if key == "outTok" and summary["baseline"]["outputTokens_mean"]:
            ax.axhline(summary["baseline"]["outputTokens_mean"], ls="--", color="grey", lw=1)
            ax.text(0.02, summary["baseline"]["outputTokens_mean"] + 400,
                    f"pristine tree {summary['baseline']['outputTokens_mean']:.0f} (n=4)", color="grey", fontsize=8)
        ax.set_xlabel("editFraction")
        ax.set_ylabel(unit)
        ax.set_title(title, fontsize=10)
        ax.grid(alpha=0.25)
        ax.legend(fontsize=7, loc="upper right")
    fig.suptitle("Residual work to finish, conditional on finishing (mean, bootstrap 95 %)", fontsize=11)
    fig.tight_layout()
    fig.savefig(os.path.join(OUT, "fig-b-residual-tokens.png"), dpi=160)

    # C — state space
    fig, ax = plt.subplots(figsize=(7.2, 4.8))
    for arm in ("mcp", "none"):
        cs = [c for c in arm_cells(arm) if c["outTok_mean"] is not None]
        x = [c["V"] for c in cs]
        y = [c["outTok_mean"] for c in cs]
        ax.plot(x, y, "-o", color=COLOR[arm], label=ARM_LABEL[arm], lw=1.6, ms=5)
        for c in cs:
            ax.annotate(f"{c['editFraction']:.1f}", (c["V"], c["outTok_mean"]),
                        textcoords="offset points", xytext=(6, -3), fontsize=8, color=COLOR[arm])
        for i in range(len(cs) - 1):
            ax.annotate("", xy=(x[i + 1], y[i + 1]), xytext=(x[i], y[i]),
                        arrowprops=dict(arrowstyle="->", color=COLOR[arm], alpha=0.55))
    if summary["baseline"]["outputTokens_mean"]:
        ax.plot([summary["baseline"]["V"]], [summary["baseline"]["outputTokens_mean"]], marker="*", ms=14,
                color="grey", ls="none", label="pristine tree")
    ax.set_xlabel("V = P(solve | state)   →  better")
    ax.set_ylabel("mean OUTPUT tokens | solved   ↓ better")
    ax.set_title("C. State space: solvability against residual work")
    ax.grid(alpha=0.25)
    ax.legend(fontsize=8)
    fig.tight_layout()
    fig.savefig(os.path.join(OUT, "fig-c-state-space.png"), dpi=160)

    # D — continuous verifier score, at two granularities
    fig, axes = plt.subplots(1, 2, figsize=(11.5, 4.2), sharey=True)
    for ax, mean_key, failed_key, ylabel in (
        (axes[0], "partial_mean", "partial_failed_mean", "mean FAIL_TO_PASS classes green / 5"),
        (axes[1], "partialTests_mean", "partialTests_failed_mean", "mean FAIL_TO_PASS tests green / 77"),
    ):
        for arm in ("mcp", "none"):
            cs = [c for c in arm_cells(arm) if c[mean_key] is not None]
            ax.plot([c["editFraction"] for c in cs], [c[mean_key] for c in cs], "-o",
                    color=COLOR[arm], label=f"{ARM_LABEL[arm]} — all graded", lw=1.8)
            pf = [c for c in arm_cells(arm) if c[failed_key] is not None]
            ax.plot([c["editFraction"] for c in pf], [c[failed_key] for c in pf], "--s",
                    color=COLOR[arm], alpha=0.6, ms=4, label=f"{ARM_LABEL[arm]} — unsuccessful only")
        ax.set_xlabel("editFraction")
        ax.set_ylabel(ylabel)
        ax.set_ylim(0, 1.05)
        ax.grid(alpha=0.25)
        ax.legend(fontsize=7)
    fig.suptitle("D. Continuous verifier score (partial credit) — saturated at both granularities", fontsize=11)
    fig.tight_layout()
    fig.savefig(os.path.join(OUT, "fig-d-partial-verifier-score.png"), dpi=160)
    print("figures written to", OUT)


if __name__ == "__main__":
    main()
