# Round 3 — the RCW analysis pipeline

Four scripts around one frozen module. They turn twelve capture builds and up to 300 probe builds into
the three datasets `RCW-GENERALIZATION.md` promises to publish — `upstream-r3.csv`,
`rollouts-r3.csv`, `checkpoints-r3.csv` — plus `checkpoint-plan-r3.csv` and `summary-r3.json`.

Python 3, **standard library only**. No numpy, no matplotlib as a hard dependency: the numbers are the
result, and a pipeline that cannot be re-run on a bare machine is a pipeline whose numbers cannot be
checked. Every script runs with `--help` and refuses to write an empty dataset.

| file | what it does |
|:---|:---|
| [`rcw_layers.py`](rcw_layers.py) | **frozen, do not edit.** Layer taxonomy, `coverage()`, `milestones()`, `select_checkpoints()` |
| [`extract_upstream_r3.py`](extract_upstream_r3.py) | one capture artifact → per-step rows in `upstream-r3.csv` (idempotent per case+arm) |
| [`select_checkpoints_r3.py`](select_checkpoints_r3.py) | `upstream-r3.csv` → `checkpoint-plan-r3.csv` + the `jb tc native run start` lines |
| [`extract_rollouts_r3.py`](extract_rollouts_r3.py) | probe build logs → `rollouts-r3.csv` (case resolved from the arm token) |
| [`analyze_rcw_r3.py`](analyze_rcw_r3.py) | → `checkpoints-r3.csv`, `summary-r3.json`, `plots/` |

## Running it

```bash
# once, from rcw_layers.DATASET_URL — nothing downloads by itself
curl -sSL -o /tmp/java-spring-ee-dataset.json \
  https://raw.githubusercontent.com/dpaia/ee-dataset/main/datasets/java-spring-ee-dataset.json

# per capture arm (see RUNBOOK.md for fetching the artifact)
python3 extract_upstream_r3.py --case petclinic-36 --arm pc36-mcp --build <id> \
    --artifact /tmp/r3/pc36-mcp.zip --dataset /tmp/java-spring-ee-dataset.json --out upstream-r3.csv

# per capture arm, BEFORE its probes are queued
python3 select_checkpoints_r3.py --upstream upstream-r3.csv --case petclinic-36 --arm pc36-mcp \
    --revision <sha>

# after the probes
python3 extract_rollouts_r3.py --logs /tmp/r3/logs --out rollouts-r3.csv
python3 analyze_rcw_r3.py --rollouts rollouts-r3.csv --upstream upstream-r3.csv --out-dir .
```

## `index` is an ORDINAL, not a step number

`-P ripple.checkpoint.index=<n>` addresses the **n-th entry of that arm's committed
`checkpoints.json`**, counted from 1. `RippleCheckpointProbeTest.loadCheckpoint` selects
`entries.firstOrNull { it["index"] == coordinates.checkpoint }` and reads the STEP out of that entry;
`probeCoordinates` bounds `index` by `committedCheckpointCount(arm)`, which is `entries.size`. Passing a
step number would either be rejected (step 41 > 4 committed entries) or, worse, silently address a
different state.

`select_checkpoints_r3.py` therefore reads the committed metadata when it exists and refuses to print a
command when the plan and the metadata disagree; when nothing is committed yet it prints provisional
ordinals and says so on stderr.

## Verification against round 2

There is no round-3 data yet, so the pipeline was run over **round 2's own raw inputs** — the two
capture artifacts and all 40 probe build logs — and its output compared with `REPLICATION-2.md`.

| quantity | `REPLICATION-2.md` | this pipeline |
|:---|:---|:---|
| upstream steps | 73 | 73 |
| `Mlast` mcp2 / none2 | 15 / 41 | 15 / 41 |
| probe rollouts parsed | 40 | 40 (0 unparsed, 0 LOST) |
| mcp2 14 → 15 `RCW_tokens` | 17 175 → 5 685, **3.02×** | 17 175.25 → 5 685.00, **3.021×** |
| mcp2 14 → 15 permutation `p` | 0.008 | 0.00806 |
| none2 40 → 41 `RCW_tokens` | 14 254 → 4 964, **2.87×** | 14 254.40 → 4 964.40, **2.871×** |
| none2 40 → 41 permutation `p` | 0.008 | 0.00791 |
| `RCW_tools` mcp2 / none2 | 1.99× (p 0.016) / 1.96× (p 0.016) | 1.990× (p 0.01549) / 1.963× (p 0.01581) |
| `RCW_edits` mcp2 / none2 | 19.4× (p 0.008) / 17.7× (p 0.008) | 19.375× (p 0.00770) / 17.667× (p 0.00804) |
| `V` from the first write on | 1.00 everywhere | 1.00 everywhere (mcp2 13 = 0.40, none2 16 = 0.80) |
| bootstrap intervals at both collapses | disjoint | disjoint |
| censored successes | 3 (`checkpoints-r2.csv`) | 3 |
| worst-case imputation value | 32 876 | 32 876 |
| upstream at `Mlast`, mcp2 | 40 175 tok / 15 calls / 451 s | identical |
| upstream at `Mlast`, none2 | 25 176 tok / 41 calls / 877 s | identical |
| Q3 sign | shell earlier on output tokens, mcp on calls and clock | identical |

Every cell of `checkpoints-r2.csv` reproduces exactly — `V`, means, medians, tool and edit means,
censored counts — and all 40 rollouts match row for row on `outputTokens`, `toolCalls`, `editActions`,
`usd`, `agentSeconds` and `Y`. **One number differs**: the bootstrap interval of none2 step 40, published
as 13 032–15 544 and computed here as 13 087–15 655. Round 2 drew its resamples from numpy's PCG64 and
this pipeline draws them from the standard library's Mersenne Twister, so bootstrap endpoints move in
their last digits by construction. Means, medians, ratios, Wilson intervals and permutation `p` do not
depend on the random source and are identical.

matplotlib was **not** importable on the verification machine, so the five per-case panels were written
as CSV under `plots/`. The renderer is present and takes over automatically when matplotlib is available.

## Where the pre-registration is ambiguous, and what was implemented

Recorded rather than resolved by invention. Each of these is a literal reading of the document.

1. **The `T` column of the "rule validated against rounds 1 and 2" table does not reproduce.** The table
   gives `T` = 40 (+0.714) for `none2`. Computed from that capture's own artifact under
   `rcw_layers.transition_step`, `T` = **16** (+0.143): every `none2` step raises `layerCov` by exactly
   one layer of seven, so "largest increase, ties → earliest" lands on the first write. This agrees with
   `REPLICATION-2.md`, which states in as many words that `T` "returns the FIRST WRITE on both round-2
   trajectories". `Mlast` — which is what the checkpoint rule actually uses — reproduces exactly (15 and
   41), and `T` anchors nothing unless `layerCov` never reaches 1.0, so no selected state is affected.
2. **"Applied retrospectively to round 2 the rule selects `{13,14,15,23}` / `{16,40,41,44}`" is true for
   four of five states.** `select_checkpoints()` returns `{13,14,15,18,23}` for `mcp2` and
   `{16,30,40,41,44}` for `none2`: every state round 2 probed, plus the new positional `C2` anchor that
   round 3 adds and round 2 did not have (round 2 probed four states, round 3 probes five). The rule
   recovers round 2's set; it does not equal it.
3. **The "five pre-registered plots" are never enumerated.** Implemented as round 2's five figures
   minus the cross-round panel: (a) `RCW` against every upstream denominator, (b) the coverage
   trajectory with the probed states marked, (c) the `V`/`RCW` state space, (d) `ΔRCW` and its leverage
   `η`, (e) the censoring-immune proxies.
4. **Criterion 1 says "a trajectory's states"; the case-level verdict is not defined.** Evaluated per
   arm and folded with `any` for criteria 1, 2, 5, 6, with `all` for criterion 3 (which the document
   words as "on both arms"), and case-wide for criterion 4. The both-arms case is reported separately as
   `especiallyStrong`, the document's own term. The aggregation is published in
   `summary-r3.json.perCase.<case>.criteriaAggregation` so the choice is auditable.
5. **Criterion 6 says "the effect survives" without naming which effect.** The metrics section says
   "every headline drop is recomputed", so `holds` is decided on the headline (`Mlast`) transition
   recomputed under the imputation. The largest-ratio pair's recomputation and the largest ratio
   re-derived over imputed means are published next to it, because a claim that survives only one of the
   three readings has not survived.
6. **Criterion 2 is only half mechanical.** Whether the largest `|ΔRCW|` lands on a milestone step is
   computed; whether the source trace independently *shows* a structural change is not, and is flagged
   `requiresTraceReview: true`. A script that answered it would be inventing the evidence.
7. **Censoring rule 1 is written for successes; the flag is set for every run.** `censored=1` marks any
   rollout with no terminal `result` event. `censoredSuccesses` — the number the document asks each cell
   to report — counts only the successful ones.
8. **The verdict regex had to widen.** Round 2's Python used `arm=(\w+)`, which cannot match a hyphen
   and would have found no verdict line in any round-3 log. `\S+` is what the pre-registration itself
   names, and it still matches `mcp2` / `none2`.
