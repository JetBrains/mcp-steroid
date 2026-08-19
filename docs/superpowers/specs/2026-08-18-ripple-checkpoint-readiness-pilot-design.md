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

Case: **`dpaia__feature__service-125`** (incremental delivery of a release status transition validator,
5 new query endpoints, and DB migration; 25 FAIL_TO_PASS tests for the validator, run by
`DpaiaFeatureService125Test` on `DpaiaScenarioBaseTest`), agent `claude` (`claude-opus-5`),
**both arms** — `mcp` and `none`. Two source trajectories, each yielding **ten** checkpoints.

### 2.0 Why this case was chosen (three-case history)

The pilot evaluated three cases before settling on one that satisfies all measurement requirements.

1. `ripple__keycloak__rename-method-wide` was dropped because its solution is **ATOMIC**. In the measured
   capture the work tree went from untouched to all 111 files renamed inside a single tool call, so
   every checkpoint before it holds the pristine tree and every checkpoint after it holds the finished
   solution. `V(x)` on such a case is a step function whose position measures *when the agent happened
   to run the rename*, not how readiness grows.

2. `dpaia__spring__boot__microshop-18` was dropped because it is **not solvable often enough** to
   measure. Its recorded history is one success in six runs, every failure being the same exploration
   loop, and it carries `dockerOracleWorks = false`, so its Testcontainers oracle never runs in the
   arena container and the prompt lets an agent claim success on a compile alone. Probes over that case
   would return zero everywhere, and a flat zero cannot be told apart from "readiness does not grow".

3. `dpaia__feature__service-125` is what remains, and it is chosen for how **UNDETERMINED** its solution
   path is. The reference solution is a set of independent deliverables (validator, query endpoints,
   filtering, DB migration) which an agent can land in any order, so two states at the same trajectory
   position are genuinely different amounts of the solution rather than the same edit seen twice. Its
   history is non-degenerate: five recorded runs, one timeout and four passes (638s, 444s, 570s, 403s),
   a success rate strictly between 0 and 1. And it is the one curated case with
   `dockerOracleWorks = true`: the 25-test `ReleaseStatusTransitionValidatorTest` really executes in the
   container, so the grade cannot be earned by compiling alone.

Existing v3 traces (`docs/ripple-trajectory-spike/features-v3-claude+codex.csv`) describe the
`rename-method-wide` case only. `feature-service-125` has a recorded arena history, but not a usable
BAND: those runs were taken on another model and a much older harness, and they report wall time and
tool-call totals rather than the `extractEndContextTokens` quantity the gate compares. So the
representativeness gate runs in its "not judged" mode (section 2.1) and this pilot's own captures become
the first rows of the sample.

### 2.1 Representativeness gate (before any probe is launched)

A capture run is usable only if it looks like a *typical* run of this case/arm. What is on record for
this case (`claude` + MCP, DPAIA arena of 2026-04, `docs/dpaia-arena-results.md` and
`docs/dpaia-arena-comparison-table.md`) is context, not a band:

| series   | runs | SUCCESS | agent s          | tool calls | cost  |
|:---------|-----:|--------:|:-----------------|-----------:|:------|
| original |    2 |     1/2 | 900 (timeout), 638 |         72 | $2.72 |
| pass 1–3 |    3 |     3/3 | 444, 570, 403    |         54 | $1.84 |

Admission criteria for a capture run, all required:

1. `SUCCESS: true` (a failed capture is not typical for a successful DPAIA pass).
2. the run is longer than the checkpoint count, so ten distinct pre-final positions exist in it.
3. tool-call steps inside the historical **min–max** range and within ±1σ of the mean.
4. agent wall time and end-of-run context tokens each within ±1σ of the mean for that arm.

Criteria 3 and 4 apply **only to a case with a measured sample of the same model and harness**. The
pilot's case has none, so `admitCapture` is called with `reference = null`: representativeness is not
judged at all, and the missing band is reported as a NOTE. Inventing one from the table above would be
worse than admitting the gap — it would look objective while comparing a `claude-opus-5` run against
numbers measured on another model, with tool-call totals in place of `extractEndContextTokens`. The
capture's own `n`, seconds, context tokens and cost are recorded in `RUN-IDS.md` and become the first
rows of the sample this gate will one day use.

"End-of-run context tokens" means `input + cache_read + cache_creation + output` of the **last assistant
message** (`extractEndContextTokens`) — the definition the v3 band of the keycloak case was built with,
and the one a future band for this case has to be built with too. The terminal
`result` event reports cumulative traffic instead (its cache-read counter reached 969851 on a run whose
context was ~75k), and `TokenUsage.totalTokens` is `input + output`; comparing either against this band
rejects every run, which is exactly what happened on 2026-08-18.

The criterion that used to read "all five snapshots were taken, i.e. `n_actual > a_5`" is gone: every
step is snapshotted now and the positions are derived from `n_actual`, so a position beyond the run
cannot exist.

A capture run that misses any criterion is discarded and repeated (max 3 attempts per arm); every
attempt's numbers are recorded in the report next to the reference table. This gate is the reason the
capture phase is separate from the probe phase: probes are only launched against an admitted capture.

## 3. The axis: Fraction of the edit phase

The turns before a capture's first tree change are the agent reading the material, and both arms spend
nearly the same effort there: 15 of the mcp arm's 26 steps and 17 of the shell arm's 57. Normalizing by
the WHOLE run count (`n`) would count reading time as trajectory, and an absolute turn index would
compare "edit turn 4" of an 11-turn edit phase against "edit turn 4" of a 40-turn one.

The axis is therefore the **FRACTION of the edit phase**:

```
tau_k = k/10,  k = 0..9
step_k = firstWriteStep + round(tau_k * (n - firstWriteStep))
```

`tau = 0` is the first write; the grid deliberately stops before the end because the original run's
own outcome is judged separately. This fractional axis is what makes the arms comparable: "mcp at 30%
of its edits" and "shell at 30% of its edits" now name the same relative progress towards their
respective final states.

A fraction whose nominal step repeats an earlier one's state is recorded as DATA but is not probed.
The aggregator folds the verdict of the original state into the curve for every fraction that shares
it.

## 4. Capture phase (2 TeamCity builds)

Runs the existing arm unchanged — same container, same prompt, same gold capture, same grading — plus
snapshotting:

1. Before the agent starts, a **shadow git dir** outside the project (`/checkpoints/.git`, work-tree =
   the guest project dir) is initialised and commits the pristine post-`testPatch` tree as `step-0`.
   A shadow git dir keeps the project's own `.git` untouched, so an agent that runs `git status`
   cannot see the instrument.
2. Claude runs with a settings file (`--settings`) carrying a `PostToolUse` hook that invokes a
   staged shell script. The script increments a counter file on every tool call — its final value is the
   run's `n` — and commits the work tree into the shadow git dir on **every** call, tagging it
   `step-<n>`. IDE-side edits reach disk before the hook because `steroid_execute_code` commits and
   saves all documents (`McpScriptContextImpl`), so the `mcp` arm's snapshots are as faithful as the
   shell arm's. Snapshotting every step is what makes positions derivable from the measured `n`; its
   cost is one `add -A` per tool call over the case's tree, which is why the pilot's case is a
   Spring-sized repository and not the Keycloak monorepo.
3. After the agent returns, the harness reads `n`, its first write step, and derives ten checkpoints
   at even fractions of the edit phase (section 3). It exports the patches and a `checkpoints.json`
   (case, arm, model, `n`, `firstWriteStep`, `fractions`, and the list of `checkpoints` — each with
   `index`, `step`, `editFraction`, `position`, `tree`, `patchChars`, and `sameStateAs`).
   That file is the only source of truth for every downstream consumer.
4. Admitted patches are committed to the branch under
   `test-experiments/src/test/resources/ripple-checkpoints/<case>/<arm>/step-<n>.patch`.

Preflight (cheap, before the real capture): a single container run asserting the hook fires and the
shadow commits appear — the hook contract is the only unproven external dependency.

## 5. Probe phase (up to 100 TeamCity builds)

One build = one `(arm, checkpoint, replicate)`. Coordinates: 10 fractions × 5 replicates × 2 arms.
Fractions whose `sameStateAs` is non-null are **not probed** — they reuse the verdict of their original
state.

1. Container, clone, `testPatch`, Maven import, gate environment — unchanged.
2. **Pre-agent baseline on the pristine tree** (unchanged, `baselineSnapshotAtBaseCommit`). Before the
   checkpoint patch: the baseline records which test classes were ALREADY red at the base commit, and a
   partially migrated tree would mis-attribute those to the probe.
3. `git apply` the checkpoint patch in the guest project dir, then refresh the IDE's VFS. Fail the
   build loudly if the patch does not apply, or if it touches a FAIL_TO_PASS file (that would mean the
   capture run tampered, and the whole checkpoint family is void).
4. `normalizeFormattingBeforeSnapshot` + `snapshotTestFiles` — taken *after* the patch, so tamper
   detection measures the probe only.
5. Probe agent: `claude` with `claude.model = <haiku>`, **`withMcp = false` in every probe, including
   probes of the `mcp` capture** — the probe is a bare agent by design. Same agent timeout (90 min)
   and identical settings in all probe runs.
6. Grading is the case's own objective verifier, `ArenaVerifier.verify`.
   `Y = verification.objectiveSuccess && !verification.failToPassTampered`.
   Additionally, a probe that passes records the **price of finishing**: `usd`, `agentSeconds`,
   and `tokens` spent by the Haiku continuation.

A probe that fails because the INSTRUMENT failed (the checkpoint patch did not apply, the container
died) is reported as `LOST`, never as `Y = 0`: a zero is a statement about the state's readiness, and
attributing an infrastructure failure to the state would bias every `V` downward.

### 5.1 Blindness of the probe prompt

The probe prompt is exactly the case's own task prompt in its **shell variant** (`withMcp = false`),
identical for both arms' probes, prefixed with one fixed paragraph:

> You are given an intermediate state of an ongoing attempt to solve this task. Some investigation
> and/or modifications may already have been performed. Continue from the current repository state and
> complete the original task.

Nothing else is added. A contract test asserts the probe prompt (a) contains that paragraph exactly
once, (b) is otherwise byte-identical to the shell-arm prompt, and (c) contains no checkpoint index,
no step count, no percentage, no arm name, and no summary of prior actions.

## 6. Metric and report

`V(tau_k) = (1/5) Σ_j Y_{k,j}`, so `V ∈ {0, .2, .4, .6, .8, 1}`.

Because a bare Haiku solves this task unaided 67% of the time (`V_baseline = 0.67`), `V` saturates at
1.0 mid-trajectory and stops discriminating. The pilot's second signal is the **COST OF FINISHING**:
median `usd`, `agentSeconds` and `tokens` over the successful replicates of a checkpoint. The price
continues to move after `V` has hit its ceiling.

Report table per arm: `editFraction` (tau_k), `step`, `position`, successes, `V`, and median cost.
The curve `(tau_k, V)` and `AUC_V` are integrated by the trapezoidal rule over the **measured**
`editFraction` range only (typically 0.0 to 0.9), with no extrapolation to 1.0.

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
| hook preflight | 1 | ~35 min, ~$0 | |
| capture (2 arms) | 2–6 | ~50 min, ~$3 | Opus |
| probes | up to 100 | ~4–60 min, ~$0.15–1.40 | Haiku |
| **total** | **≈ 60–100** | | **≈ $15–60 API** |

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
