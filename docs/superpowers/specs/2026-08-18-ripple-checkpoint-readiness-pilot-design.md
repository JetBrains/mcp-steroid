# Solution-readiness along a trajectory — checkpoint/probe pilot (design)

Status: approved, running. Branch: `worktree-semantic-ripple-pilot`, published to `jb` (TeamCity's VCS
root pulls from the JetBrains mirror, not from `origin`).

## 1. Question this pilot answers

Not "is MCP better". The pilot validates a **measurement method**: take intermediate states of one
completed agent trajectory and measure, empirically, how easily a fixed **weaker** probe agent
finishes the original task from each state.

`V(s_i)` = fraction of probe continuations from checkpoint `i` that pass the same objective verifier
the original task uses. The pilot's deliverable is the curve `V(a_i/n)` plus its AUC, and a verdict
on whether the instrument works at all (states restore, probe can continue, `V` moves, repeats are
stable, cost is acceptable).

Non-goals: no cross-arm significance claim, no new cases, no prompt tuning, no change to grading.

## 2. Source trajectories

Case: `ripple__keycloak__rename-method-wide` (`RippleCases.renameMethodWide`), agent `claude`
(`claude-opus-5`), **both arms** — `mcp` and `none`. Two source trajectories, each yielding five
checkpoints.

Existing v3 traces (`docs/ripple-trajectory-spike/features-v3-claude+codex.csv`) give the reference
distribution but **not** the intermediate states, so each source trajectory is re-run once with
snapshotting enabled (section 4).

### 2.1 Representativeness gate (before any probe is launched)

A capture run is usable only if it looks like a *typical* run of this case/arm. Reference numbers
from v3 (claude, `rename-method-wide`):

| arm  | runs | SUCCESS | steps med (min–max) | steps IQR | agent s med | s IQR   | end-context tok med |
|:-----|-----:|--------:|--------------------:|:----------|------------:|:--------|--------------------:|
| mcp  |    9 |     9/9 |          30 (22–41) | 25–38     |         685 | 629–882 |               73019 |
| none |   10 |   10/10 |        37.5 (31–56) | 33–47     |         617 | 554–861 |               65728 |

Admission criteria for a capture run, all required:

1. `SUCCESS: true` (both arms are historically 9/9 and 10/10 — a failed capture is not typical).
2. tool-call steps inside the arm's v3 **min–max** range and within ±1σ of the v3 mean.
3. agent wall time and end-of-run context tokens each within ±1σ of the v3 mean for that arm.
4. all five snapshots were taken, i.e. `n_actual > a_5` — a run that stopped early has no last
   checkpoint and cannot carry the curve.

A capture run that misses any criterion is discarded and repeated (max 3 attempts per arm); every
attempt's numbers are recorded in the report next to the reference table. This gate is the reason the
capture phase is separate from the probe phase: probes are only launched against an admitted capture.

## 3. Step definition and checkpoint positions

**One step = one agent tool call** (the `PostToolUse` event), the same unit the trajectory spike's
`steps` column counts.

```
a_i = round(n̂ * (i/6)^1.5),  i = 1..5      → ≈ 7%, 19%, 35%, 54%, 76% of n̂
```

The positions are computed **before the capture run** from ONE assumed step count `n̂ = 32`, shared by
both arms, which yields `2, 6, 11, 17, 24` for `mcp` and for `none` alike.

Why one schedule for both arms: `V_mcp` and `V_shell` are only comparable when they are measured after
the same amount of agent work. A per-arm schedule (`mcp: 2, 6, 11, 17, 24` vs `none: 3, 8, 14, 22, 30`,
from each arm's own v3 mean) would compare readiness after 24 mcp tool calls against readiness after 30
shell tool calls and attribute the difference to the arm.

Why `n̂ = 32` and not the pooled mean 36: the fifth position must be reachable by an admissible capture
of EITHER arm, and `mcp` is the shorter one — the admission band (31.6 ± 7.1 steps, section 2.1) accepts
mcp captures from 25 tool calls up, so `a_5` may not exceed 24. `n̂ = 33` already gives `a_5 = 25`. So 32
is the deepest shared schedule the gate allows; a unit test pins exactly that (one step more must fail).

Why not the capture run's own `n`: a snapshot is a full `git add -A` over the whole Keycloak tree, so
snapshotting *every* step would add tens of tree scans inside the measured agent loop and could distort
the very trajectory it records. Precomputed positions mean the hook counts tool calls and snapshots at
exactly five of them. The representativeness gate (section 2.1) is what keeps `n̂` honest.

After rounding, positions must be unique and strictly increasing; a collision is resolved by pushing
the later position up by the minimum amount that keeps the sequence strictly increasing and `< n̂`.
The final state is never a checkpoint.

The run's **actual** `n` is still recorded (the hook counter's final value), and the report normalizes
by it: `x_i = a_i / n_actual`, never by `n̂` and never by the nominal 7/19/35/54/76%. A capture whose
`n_actual ≤ a_5` never reached the last checkpoint and is rejected by the gate.

Implemented as pure Kotlin (`rippleCheckpointSteps(n)`) with unit tests covering the worked examples,
the collision rule on short trajectories, and monotonicity for `n = 6..100`.

## 4. Capture phase (2 TeamCity builds)

Runs the existing arm unchanged — same container, same prompt, same gold capture, same grading — plus
snapshotting:

1. Before the agent starts, a **shadow git dir** outside the project (`/checkpoints/.git`, work-tree =
   the guest project dir) is initialised and commits the pristine post-`testPatch` tree as `step-0`.
   A shadow git dir keeps the project's own `.git` untouched, so an agent that runs `git status`
   cannot see the instrument.
2. Claude runs with a settings file (`--settings`) carrying a `PostToolUse` hook that invokes a
   staged shell script. The script increments a counter file on every tool call — that is the cheap
   part, and its final value is the run's `n` — and commits the work tree into the shadow git dir
   **only when the counter equals one of the five precomputed `a_i`**, tagging it `step-<a_i>`.
   IDE-side edits reach disk before the hook because `steroid_execute_code` commits and saves all
   documents (`McpScriptContextImpl`), so the `mcp` arm's snapshots are as faithful as the shell arm's.
3. After the agent returns, the harness exports one patch per captured tag (`step-0..step-<a_i>`) plus a
   `checkpoints.json` (case, arm, model, build id, `n̂`, `n_actual`, `a_i`, `a_i/n_actual`, capture run
   metrics) into the run dir, which TeamCity publishes.
4. Admitted patches are committed to the branch under
   `test-experiments/src/test/resources/ripple-checkpoints/<case>/<arm>/step-<a_i>.patch`, so a probe
   build needs no artifact plumbing and the pilot is reproducible from the revision alone.

Preflight (cheap, before the real capture): a single container run asserting the hook fires and the
shadow commits appear — the hook contract is the only unproven external dependency.

## 5. Probe phase (50 TeamCity builds)

One build = one `(arm, checkpoint, replicate)`. Order inside the container:

1. Container, clone, `testPatch`, Maven import, gate environment — unchanged.
2. **Gold capture on the pristine tree** (unchanged). This must happen before the checkpoint patch is
   applied: gold is the pre-agent resolved reference set of the *task*, and a partially transformed
   tree would silently shrink it.
3. `git apply` the checkpoint patch in the guest project dir, then refresh the IDE's VFS. Fail the
   build loudly if the patch does not apply, or if it touches a FAIL_TO_PASS file (that would mean the
   capture run tampered, and the whole checkpoint family is void).
4. `normalizeFormattingBeforeSnapshot` + `snapshotTestFiles` + `snapshotOracleContents` — taken *after*
   the patch, so tamper detection measures the probe only.
5. Probe agent: `claude` with `claude.model = <haiku>`, **`withMcp = false` in every probe, including
   probes of the `mcp` capture** — the probe is a bare agent by design. Same agent timeout (90 min)
   and identical settings in all 50 runs.
6. Grading unchanged: semantic oracle post-condition + scoped compile gate + FAIL_TO_PASS
   verification; `Y = gate.passed && verification.objectiveSuccess && grade.allPassed`.

### 5.1 Blindness of the probe prompt

The probe prompt is exactly `buildRipplePrompt(case, projectDir, withMcp = false)` — the shell-variant
text, identical for both arms' probes — prefixed with one fixed paragraph:

> You are given an intermediate state of an ongoing attempt to solve this task. Some investigation
> and/or modifications may already have been performed. Continue from the current repository state and
> complete the original task.

Nothing else is added. A contract test asserts the probe prompt (a) contains that paragraph exactly
once, (b) is otherwise byte-identical to the shell-arm prompt, and (c) contains no checkpoint index,
no step count, no percentage, no arm name, and no summary of prior actions.

## 6. Metric and report

`V(s_i) = (1/5) Σ_j Y_{i,j}`, so `V ∈ {0, .2, .4, .6, .8, 1}`.

Report table per arm: checkpoint, `a_i`, `a_i/n`, successes, runs, `V(s_i)`; plus the curve
`(a_i/n, V)` and `AUC_V` by the trapezoidal rule over the **observed** range `[a_1/n, a_5/n]` only —
using real normalized positions, never the nominal 7/19/35/54/76%, and with no extrapolation to 0 or
beyond the last checkpoint. The integration range is printed with the AUC. Because the two arms have
different `n`, their AUCs are also reported normalized by their own integration width.

The `a_i` column is IDENTICAL in both arms' tables (section 3), so `V_mcp(a_i)` and `V_shell(a_i)` can be
compared row by row at equal agent effort; the normalized `a_i/n` differs between the arms only because
their measured trajectory lengths do.

Also reported: the original capture runs' final outcome (separately from the curve), instrument
failures vs graded zeros, per-run cost/time, and the total pilot spend.

`V`, the trapezoidal AUC and the range handling are pure Kotlin functions with unit tests; the
aggregator reads the per-run summary JSONs (or `[RIPPLE] SUCCESS:` lines from build logs, the same
source the v3 analysis used).

## 7. Cost estimate

| phase | builds | per build | note |
|:---|---:|:---|:---|
| hook preflight | 1 | ~35 min, ~$0 | no agent turn beyond a smoke prompt |
| capture (2 arms, ≤3 attempts each) | 2–6 | ~50 min, ~$1.8 | Opus, v3 mean cost for this case |
| probes | 50 | ~45–60 min, ~$0.2–0.5 | Haiku, 90-min cap |
| **total** | **53–57** | | **≈ $20–35 API, ≈ 45 agent-hours of TC capacity** |

TeamCity runs them; local execution would serialize into ~2 days of machine time.

## 8. Risks

| risk | mitigation |
|:---|:---|
| `PostToolUse` hook unsupported/mis-shaped in `claude-code/2.1.159` | preflight build before spending capture runs; hook is the only unproven dependency |
| snapshotting perturbs the trajectory it measures | only five snapshots per run; every other tool call costs one counter increment |
| capture run's `n_actual` differs from the assumed `n̂` | positions stay where they were snapshotted; the report normalizes by `n_actual`, and the gate rejects `n_actual ≤ a_5` |
| checkpoint patch does not apply / conflicts | patches are generated from the same base commit + `testPatch` the probe container recreates; mismatch fails the build loudly |
| gold captured on a transformed tree | order fixed in section 5 and asserted by a harness test |
| probe inherits a broken (non-compiling) intermediate state | that is the measurement, not a defect: early checkpoints are expected to score 0 |
| capture run is an outlier | representativeness gate (section 2.1) |
| MCP-arm state partly unsaved in the IDE | `steroid_execute_code` saves all documents; hook commits disk state after the call returns |

## 9. Testing strategy

- Unit: `rippleCheckpointSteps(n)` (worked examples, collisions, monotonicity), `V`, trapezoidal AUC
  with explicit range, checkpoint metadata (de)serialization.
- Contract: probe prompt blindness (section 5.1); checkpoint patch must not touch FAIL_TO_PASS files.
- Integration: hook preflight build; then one full probe build end-to-end (one checkpoint, one
  replicate) before the remaining 49 are queued.

## 10. Out of scope / next stage

If the instrument works: 10 trajectories × 5 checkpoints × 5 probes × 2 arms = 500 probe runs, testing
`V_MCP(x) > V_shell(x)` and `AUC_MCP > AUC_shell`.
