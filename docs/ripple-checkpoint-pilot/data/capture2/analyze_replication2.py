#!/usr/bin/env python3
"""Round-2 analysis: residual completion work against every upstream denominator.

Reads
  - `/tmp/r2/rollouts-r2.json`   probe verdicts of round 2, produced by ../extract_rollouts.py
  - `upstream-r2.csv`            per-step upstream work of both round-2 captures
  - `../rollouts.json`           round 1, UNTOUCHED, for the cross-round panels

Writes `rollouts-r2.csv|json`, `checkpoints-r2.csv`, `summary-r2.json` and the five figures next to
`REPLICATION-2.md`. Seeded, so every published number is reproducible from the committed datasets.

The analysis follows the pre-registration and does not deviate from it:
  * `C_tokens` (output tokens of a successful rollout) is primary; `C_tools` and `C_edits` corroborate
    and are censoring-immune; `V` is a separate coordinate reported with Wilson intervals.
  * Failures are never converted into completion costs; censored successes are handled by worst-case
    imputation and the conclusion must survive it.
  * Upstream denominators A (tool calls), B (cumulative Opus output tokens), C (seconds) and
    E (editFraction) are all reported. B is exact for round 2 and `NA` for round 1.
"""
import csv
import json
import math
import os
import random
import sys

import numpy as np
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

SEED = 20260820
HERE = os.path.dirname(os.path.abspath(__file__))
PILOT = os.path.normpath(os.path.join(HERE, "..", ".."))
R1 = os.path.join(PILOT, "data", "rollouts.json")
ARM_LABEL = {"mcp2": "mcp2 (semantic IDE)", "none2": "none2 (shell only)",
             "mcp": "mcp (round 1)", "none": "none (round 1)"}
COLOR = {"mcp2": "#1f77b4", "none2": "#d62728", "mcp": "#7fb3d5", "none": "#e59866"}
# Which milestone each committed checkpoint holds, from select_capture2_checkpoints.py's own metadata.
MILESTONE = {}


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
        return (float(stat(xs)) if xs else float("nan")), float("nan"), float("nan")
    rng = rng or np.random.default_rng(SEED)
    a = np.array(xs, dtype=float)
    draws = stat(rng.choice(a, size=(n, len(a)), replace=True), axis=1)
    return float(stat(a)), float(np.percentile(draws, 2.5)), float(np.percentile(draws, 97.5))


def perm_test(a, b, n=100000, rng=None):
    a = [x for x in a if x is not None]
    b = [x for x in b if x is not None]
    if not a or not b:
        return float("nan"), len(a), len(b)
    rng = rng or random.Random(SEED)
    obs = abs(np.mean(a) - np.mean(b))
    pool = a + b
    hits = 0
    for _ in range(n):
        rng.shuffle(pool)
        if abs(np.mean(pool[:len(a)]) - np.mean(pool[len(a):])) >= obs - 1e-12:
            hits += 1
    return (hits + 1) / (n + 1), len(a), len(b)


def outcome(r):
    if r.get("success") is None:
        return "lost"
    if r.get("ftpTampered"):
        return "tampered"
    return "solved" if r["success"] == 1 else "failed"


def tool_calls(r):
    keys = ("readCalls", "editCalls", "writeCalls", "globCalls", "grepCalls", "bashCalls", "execCodeCalls")
    parts = [r.get(k) for k in keys]
    return None if any(p is None for p in parts) else sum(parts)


def load_upstream():
    """Per-step upstream work, keyed `(arm, step)`, plus the per-arm milestone map."""
    rows = list(csv.DictReader(open(os.path.join(HERE, "upstream-r2.csv"))))
    out = {}
    for r in rows:
        num = lambda k: float(r[k]) if r[k] not in ("", None) else None
        out[(r["arm"], int(r["step"]))] = dict(
            tool=r["tool"], patchChars=int(r["patchChars"]), fileCov=num("fileCov"),
            layerCov=num("layerCov"), layers=r["layers"], dLayerCov=num("dLayerCov"),
            cumOutputTokens=num("cumOutputTokens"), cumOutputCharsProxy=num("cumOutputCharsProxy"),
            cumToolCalls=num("cumToolCalls"), cumSeconds=num("cumSeconds"), editFraction=num("editFraction"),
            isM0=r["isM0"] == "1", isT=r["isT"] == "1", isMapi=r["isMapi"] == "1",
        )
    return out, rows


def main():
    rollouts_path = sys.argv[1] if len(sys.argv) > 1 else "/tmp/r2/rollouts-r2.json"
    rows = json.load(open(rollouts_path))
    upstream, upstream_rows = load_upstream()
    for arm in ("mcp2", "none2"):
        meta = json.load(open(os.path.join(
            PILOT, "..", "..", "test-experiments", "src", "test", "resources",
            "ripple-checkpoints", "feature-service-125", arm, "checkpoints.json")))
        for e in meta["checkpoints"]:
            MILESTONE[(arm, e["step"])] = e.get("milestone")

    rng = np.random.default_rng(SEED)
    prng = random.Random(SEED)
    for r in rows:
        r["capture"] = 2
        r["outcome"] = outcome(r)
        r["toolCalls"] = tool_calls(r)
        r["editsAndWrites"] = None if r.get("editCalls") is None else r["editCalls"] + (r.get("writeCalls") or 0)
        u = upstream.get((r["arm"], r["step"]), {})
        r["upstreamTokens"] = u.get("cumOutputTokens")
        r["upstreamToolCalls"] = u.get("cumToolCalls")
        r["upstreamSeconds"] = u.get("cumSeconds")
        r["layerCov"] = u.get("layerCov")
        r["fileCov"] = u.get("fileCov")
        r["milestone"] = MILESTONE.get((r["arm"], r["step"]))

    fields = sorted({k for r in rows for k in r} - {"ftpPerClass"})
    with open(os.path.join(HERE, "rollouts-r2.csv"), "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=fields, extrasaction="ignore")
        w.writeheader()
        w.writerows(sorted(rows, key=lambda r: (r["arm"], r["step"], r["replicate"])))
    json.dump(sorted(rows, key=lambda r: (r["arm"], r["step"], r["replicate"])),
              open(os.path.join(HERE, "rollouts-r2.json"), "w"), indent=1)

    cells = []
    for arm in ("mcp2", "none2"):
        for step in sorted({r["step"] for r in rows if r["arm"] == arm}):
            rs = [r for r in rows if r["arm"] == arm and r["step"] == step]
            graded = [r for r in rs if r["outcome"] in ("solved", "failed")]
            solved = [r for r in graded if r["outcome"] == "solved"]
            v, lo, hi = wilson(len(solved), len(graded))
            u = upstream.get((arm, step), {})
            cell = dict(
                capture=2, arm=arm, step=step, milestone=MILESTONE.get((arm, step)),
                editFraction=u.get("editFraction"), upstreamTokens=u.get("cumOutputTokens"),
                upstreamToolCalls=u.get("cumToolCalls"), upstreamSeconds=u.get("cumSeconds"),
                layerCov=u.get("layerCov"), fileCov=u.get("fileCov"), patchChars=u.get("patchChars"),
                runs=len(rs), graded=len(graded), solved=len(solved),
                lost=sum(1 for r in rs if r["outcome"] == "lost"),
                tampered=sum(1 for r in rs if r["outcome"] == "tampered"),
                V=round(v, 4), V_lo=round(lo, 4), V_hi=round(hi, 4),
                censored=sum(1 for r in solved if r.get("outputTokens") is None),
            )
            for key, field in (("outTok", "outputTokens"), ("tools", "toolCalls"), ("edits", "editsAndWrites"),
                               ("usd", "costUsdArena"), ("sec", "agentSecondsArena")):
                xs = [r[field] for r in solved if r.get(field) is not None]
                m, l, h = boot_ci(xs, rng=rng)
                cell[f"{key}_n"] = len(xs)
                cell[f"{key}_mean"] = None if not xs else round(m, 2)
                cell[f"{key}_median"] = None if not xs else float(np.median(xs))
                cell[f"{key}_lo"] = None if len(xs) < 2 else round(l, 2)
                cell[f"{key}_hi"] = None if len(xs) < 2 else round(h, 2)
                cell[f"{key}_raw"] = "|".join(str(x) for x in sorted(xs))
            cells.append(cell)

    with open(os.path.join(HERE, "checkpoints-r2.csv"), "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=list(cells[0].keys()))
        w.writeheader()
        w.writerows(cells)

    def raw(arm, step, field):
        return [r[field] for r in rows
                if r["arm"] == arm and r["step"] == step and r["outcome"] == "solved" and r.get(field) is not None]

    def milestone_step(arm, name):
        return next((s for (a, s), m in MILESTONE.items() if a == arm and m == name), None)

    tests, brackets = {}, {}
    for arm in ("mcp2", "none2"):
        api = milestone_step(arm, "Mapi")
        before = max([s for (a, s) in MILESTONE if a == arm and s < api], default=None) if api else None
        brackets[arm] = dict(before=before, after=api)
        if api is None or before is None:
            continue
        for field in ("outputTokens", "toolCalls", "editsAndWrites"):
            p, n1, n2 = perm_test(raw(arm, before, field), raw(arm, api, field), rng=prng)
            a1, a2 = raw(arm, before, field), raw(arm, api, field)
            tests[f"{arm} {before}->{api} {field}"] = dict(
                p=round(p, 5), n1=n1, n2=n2,
                mean_before=round(float(np.mean(a1)), 1) if a1 else None,
                mean_after=round(float(np.mean(a2)), 1) if a2 else None,
                ratio=round(float(np.mean(a1)) / float(np.mean(a2)), 3) if a1 and a2 and np.mean(a2) else None,
            )
    # Between arms at their own post-transition state: does mcp's earlier arrival buy a cheaper state?
    for field in ("outputTokens", "toolCalls"):
        p, n1, n2 = perm_test(raw("mcp2", brackets["mcp2"]["after"] or -1, field),
                              raw("none2", brackets["none2"]["after"] or -1, field), rng=prng)
        tests[f"mcp2 vs none2 at Mapi {field}"] = dict(p=round(p, 5), n1=n1, n2=n2)

    worst = max([r["outputTokens"] for r in rows if r.get("outputTokens") is not None], default=None)
    worst_case = {}
    for c in cells:
        solved = [r for r in rows if r["arm"] == c["arm"] and r["step"] == c["step"] and r["outcome"] == "solved"]
        if not solved:
            continue
        xs = [r["outputTokens"] if r.get("outputTokens") is not None else worst for r in solved]
        worst_case[f"{c['arm']} step {c['step']}"] = dict(
            n=len(xs), imputed=sum(1 for r in solved if r.get("outputTokens") is None),
            mean=round(float(np.mean(xs)), 1))
    worst_case["_imputed_value"] = worst

    round1 = json.load(open(R1)) if os.path.exists(R1) else []
    summary = dict(
        seed=SEED, rollouts=len(rows),
        outcomes={o: sum(1 for r in rows if r["outcome"] == o) for o in ("solved", "failed", "tampered", "lost")},
        brackets=brackets,
        upstream_at_milestones={
            arm: {
                m: dict(step=s, editFraction=upstream[(arm, s)]["editFraction"],
                        cumOutputTokens=upstream[(arm, s)]["cumOutputTokens"],
                        cumToolCalls=upstream[(arm, s)]["cumToolCalls"])
                for (a, s), m in MILESTONE.items() if a == arm
            } for arm in ("mcp2", "none2")
        },
        permutation_tests=tests,
        worst_case_censoring=worst_case,
        round1_rollouts=len(round1),
    )
    json.dump(summary, open(os.path.join(HERE, "summary-r2.json"), "w"), indent=1)

    figures(cells, rows, upstream_rows)
    print(json.dumps({k: summary[k] for k in ("rollouts", "outcomes", "brackets")}, indent=1))
    for name, t in tests.items():
        print(f"  {name}: {t}")


def figures(cells, rows, upstream_rows):
    """Five figures, all with individual rollout points where a distribution exists."""
    # A — residual work against every upstream denominator.
    denominators = [("upstreamToolCalls", "upstream tool calls (A)"),
                    ("upstreamTokens", "cumulative Opus output tokens (B)"),
                    ("upstreamSeconds", "upstream agent seconds (C)"),
                    ("editFraction", "edit fraction (E)")]
    fig, axes = plt.subplots(1, 4, figsize=(20, 4.6))
    for ax, (key, label) in zip(axes, denominators):
        for arm in ("mcp2", "none2"):
            cs = sorted([c for c in cells if c["arm"] == arm and c[key] is not None], key=lambda c: c[key])
            if not cs:
                continue
            xs = [c[key] for c in cs]
            ys = [c["outTok_mean"] for c in cs]
            ax.plot(xs, ys, "-o", color=COLOR[arm], label=ARM_LABEL[arm])
            for c in cs:
                pts = [float(v) for v in c["outTok_raw"].split("|") if v]
                ax.scatter([c[key]] * len(pts), pts, s=14, alpha=0.45, color=COLOR[arm])
                if c["outTok_lo"] is not None:
                    ax.plot([c[key]] * 2, [c["outTok_lo"], c["outTok_hi"]], color=COLOR[arm], alpha=0.6)
        ax.set_xlabel(label)
        ax.set_ylabel("residual output tokens | success")
        ax.grid(alpha=0.3)
    axes[0].legend(fontsize=8)
    fig.suptitle("Residual completion work against four upstream denominators (round 2, individual rollouts)")
    fig.tight_layout()
    fig.savefig(os.path.join(PILOT, "fig-r2-a-residual-vs-upstream.png"), dpi=140)
    plt.close(fig)

    # D — the coverage trajectory that the checkpoints were chosen from.
    fig, axes = plt.subplots(1, 2, figsize=(13, 4.4), sharey=True)
    for ax, arm in zip(axes, ("mcp2", "none2")):
        rs = [r for r in upstream_rows if r["arm"] == arm]
        steps = [int(r["step"]) for r in rs]
        ax.plot(steps, [float(r["layerCov"]) for r in rs], "-o", ms=3, color=COLOR[arm], label="layerCov")
        ax.plot(steps, [float(r["fileCov"]) for r in rs], "--s", ms=3, color="#555555", label="fileCov (control)")
        for r in rs:
            if r["isMapi"] == "1":
                ax.axvline(int(r["step"]), color="#2ca02c", ls=":", label="Mapi")
            if r["isM0"] == "1":
                ax.axvline(int(r["step"]), color="#999999", ls=":", label="M0")
        probed = [c["step"] for c in cells if c["arm"] == arm]
        ax.scatter(probed, [1.02] * len(probed), marker="v", color="black", label="probed")
        ax.set_title(ARM_LABEL[arm])
        ax.set_xlabel("source step (tool call)")
        ax.grid(alpha=0.3)
    axes[0].set_ylabel("coverage of the gold solution")
    handles, labels = axes[0].get_legend_handles_labels()
    seen = dict(zip(labels, handles))
    axes[0].legend(seen.values(), seen.keys(), fontsize=8)
    fig.tight_layout()
    fig.savefig(os.path.join(PILOT, "fig-r2-d-coverage-trajectory.png"), dpi=140)
    plt.close(fig)

    # B — both rounds on one axis. Round 1's curve is recomputed from its untouched dataset.
    fig, axes = plt.subplots(1, 2, figsize=(13, 4.6))
    r1 = json.load(open(R1)) if os.path.exists(R1) else []
    for r in r1:
        r["outcome"] = outcome(r)
    for ax, (xkey, xlabel) in zip(axes, [("editFraction", "edit fraction (E)"),
                                         ("upstreamToolCalls", "upstream tool calls (A)")]):
        for arm in ("mcp", "none"):
            pts = {}
            for r in r1:
                if r["arm"] != arm or r["outcome"] != "solved" or r.get("outputTokens") is None:
                    continue
                key = r["editFraction"] if xkey == "editFraction" else r["step"]
                pts.setdefault(key, []).append(r["outputTokens"])
            xs = sorted(k for k in pts if k is not None)
            if xs:
                ax.plot(xs, [np.mean(pts[x]) for x in xs], "--o", ms=4, color=COLOR[arm],
                        label=ARM_LABEL[arm])
        for arm in ("mcp2", "none2"):
            cs = sorted([c for c in cells if c["arm"] == arm and c["outTok_mean"] is not None
                         and c[xkey] is not None], key=lambda c: c[xkey])
            if cs:
                ax.plot([c[xkey] for c in cs], [c["outTok_mean"] for c in cs], "-o",
                        color=COLOR[arm], label=ARM_LABEL[arm])
        ax.set_xlabel(xlabel)
        ax.set_ylabel("residual output tokens | success")
        ax.grid(alpha=0.3)
        ax.legend(fontsize=7)
    fig.suptitle("Capture 1 (dashed) against capture 2 (solid): the collapse replicates, its position does not")
    fig.tight_layout()
    fig.savefig(os.path.join(PILOT, "fig-r2-b-capture1-vs-capture2.png"), dpi=140)
    plt.close(fig)

    # E — exploratory: how much downstream work one unit of upstream work removes.
    fig, axes = plt.subplots(1, 2, figsize=(13, 4.4))
    for arm in ("mcp2", "none2"):
        cs = sorted([c for c in cells if c["outTok_mean"] is not None and c["arm"] == arm],
                    key=lambda c: c["step"])
        labels, deltas, etas = [], [], []
        for a, b in zip(cs, cs[1:]):
            du = (b["upstreamTokens"] or 0) - (a["upstreamTokens"] or 0)
            dc = a["outTok_mean"] - b["outTok_mean"]
            labels.append(f"{arm}\n{a['step']}->{b['step']}")
            deltas.append(dc)
            etas.append(dc / du if du else float("nan"))
        axes[0].bar(labels, deltas, color=COLOR[arm], alpha=0.85)
        axes[1].bar(labels, etas, color=COLOR[arm], alpha=0.85)
    axes[0].set_ylabel("ΔC: downstream output tokens removed")
    axes[1].set_ylabel("η = ΔC / ΔU (downstream removed per upstream token)")
    for ax in axes:
        ax.axhline(0, color="black", lw=0.8)
        ax.grid(alpha=0.3, axis="y")
        ax.tick_params(axis="x", labelsize=7)
    fig.suptitle("Exploratory: residual-work reduction and its leverage (noisy, not a headline metric)")
    fig.tight_layout()
    fig.savefig(os.path.join(PILOT, "fig-r2-e-delta-efficiency.png"), dpi=140)
    plt.close(fig)

    # C — state space: V against residual work, one trajectory per arm.
    fig, ax = plt.subplots(figsize=(7.2, 5.2))
    for arm in ("mcp2", "none2"):
        cs = sorted([c for c in cells if c["outTok_mean"] is not None and c["arm"] == arm],
                    key=lambda c: c["step"])
        ax.plot([c["V"] for c in cs], [c["outTok_mean"] for c in cs], "-o", color=COLOR[arm],
                label=ARM_LABEL[arm])
        for c in cs:
            ax.annotate(f"{c['step']}", (c["V"], c["outTok_mean"]), fontsize=7,
                        textcoords="offset points", xytext=(4, 4))
    ax.set_xlabel("V = P(solve | state)")
    ax.set_ylabel("residual output tokens | success")
    ax.set_title("State space: solvability against residual work (round 2)")
    ax.grid(alpha=0.3)
    ax.legend(fontsize=8)
    fig.tight_layout()
    fig.savefig(os.path.join(PILOT, "fig-r2-c-state-space.png"), dpi=140)
    plt.close(fig)


if __name__ == "__main__":
    main()
