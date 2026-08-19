# Solution-readiness checkpoint pilot — launch runbook

What is measured: `V(s_i)` is the empirical probability that a fixed weak probe agent (bare Haiku, no
MCP) finishes the original task from the repository state the source Opus run reached after `a_i` tool
calls. The result is the curve `V(a_i/n)` and its area over the OBSERVED range of positions.

Design and plan:

- `docs/superpowers/specs/2026-08-18-ripple-checkpoint-readiness-pilot-design.md`
- `docs/superpowers/plans/2026-08-18-ripple-checkpoint-readiness-pilot.md`
- **Read `CAPTURE-2026-08-18-POSTMORTEM.md` before touching the instrument.** The first stage of this
  pilot was discarded; that file says exactly why, and every rule below marked "learned" comes from it.

## The case

`dpaia__feature__service-125` — incremental delivery of a release status transition validator,
5 new query endpoints, and DB migration; 25 FAIL_TO_PASS tests for the validator, graded by
`ArenaVerifier.verify`.

Chosen for three properties: **its solution is assembled incrementally** (independent deliverables
an agent can land in any order); its **oracle really executes** (`dockerOracleWorks = true`) in the
arena container, so no Docker-surrender clause weakens the grade; and its **historical success rate**
is strictly between 0 and 1 (5 recorded runs: one timeout, four passes), so its readiness curve can be
informative.

## What is in place

| Piece | Where |
|:---|:---|
| positions from the MEASURED `n`, distinct-state selection, `V`, observed-range AUC | `RippleCheckpointMath.kt` |
| blind continuation prompt | `RippleCheckpointProbePrompt.kt` |
| `--settings` seam on the Claude session | `test-helper/.../ClaudePromptArgs.kt`, `DockerClaudeSession.kt` |
| per-step counter + a shadow-git snapshot of EVERY step | `RippleCheckpointRecorder.kt` |
| representativeness gate (`reference = null` when a case has no sample) | `RippleCaptureAdmission.kt` |
| end-of-run context, measured as v3 measured it | `extractEndContextTokens` in `AgentOutputMetrics.kt` |
| capture run + hook preflight | `DpaiaFeatureService125CheckpointCaptureTest.kt` |
| bare-Haiku probe | `RippleCheckpointProbeTest.kt` |
| verdicts → table, curve, AUC | `RippleCheckpointReport.kt` |
| TeamCity capture/probe configurations | `mcp-steroid-teamcity` commit `67d178d` |

**No checkpoint position exists before a capture run finishes.** The hook snapshots every tool call;
`RippleCheckpointRecorder.plan(n)` then derives the five positions from the length that actually
happened and writes them into the capture's `checkpoints.json`. That file is the ONLY source of
positions — the probe reads the step and the normalized position of its checkpoint ordinal from it.
Never reintroduce a constant schedule: the guess of `n̂ = 32` met runs of 23 and 51 tool calls, and the
23-step run had no fifth state to probe at all.

Two checkpoints may not hold the same state. The selection compares git tree ids; a checkpoint whose
nominal step repeats the previous checkpoint's state moves forward to the first differing step, and when
no differing state exists before the final one it is **dropped**, with the reason recorded in
`corrections` and rendered next to the shortened curve.

## Prerequisites

1. The pilot branch must be visible to TeamCity: TC's VCS root pulls from `jb`, not `origin`. The pilot
   lives on `jb/worktree-semantic-ripple-pilot`; the branch is intentionally NOT kept on `origin`.
2. The DSL commits `67d178d` + `cb5356a` must reach the TeamCity settings repo — until then the two
   configurations do not exist on the server.
3. Land the Gradle property forwarding **before** the DSL: an unknown `-D` is silently ignored, and the
   probe builds differ ONLY by three coordinates.

The capture selector is the one parameter that is NOT silent. `-Pripple.checkpoint.capture.method` is
mapped to a test filter in `test-experiments/build.gradle.kts`, and an unknown value fails the build's
configuration phase instead of running whatever else the task would select. `-Pripple.checkpoint.capture.case`
picks which capture class it selects and defaults to `feature-service-125`; pass `rename-method-wide` to capture
the second, already-measured case. `RippleCheckpointCaptureFilterTest` loads every class and method that
mapping names by reflection, so a rename on either side turns into a red unit test rather than a green
build that measured the wrong case.

## Order of operations (do not reorder)

```
1 hook preflight  →  2 captures (one per arm)  →  admission check  →  commit patches + checkpoints.json
                  →  1 smoke probe (deepest checkpoint, replicate 1)  →  the remaining probes
                  →  aggregate  →  REPORT.md
```

Why: the preflight is the only cheap test of the one dependency nothing else proves (does the
`PostToolUse` hook fire, and does its snapshot see the agent's writes); the smoke probe is deliberately
the *deepest* checkpoint of the mcp arm, because that is where `Y = 1` is most likely — a zero there
means the instrument is broken, not that the state was hopeless.

### Session preamble

```bash
export TEAMCITY_URL=https://buildserver.labs.intellij.net
export TEAMCITY_TOKEN="$(tr -d '\n' < ~/.config/teamcity/token)"
CAP=mcp_steroid_IntegrationTests_RippleCheckpointCapture
PROBE=mcp_steroid_IntegrationTests_RippleCheckpointProbe
BRANCH=worktree-semantic-ripple-pilot              # the pilot branch, as pushed to jb
SHA=$(git -C ~/work/mcp-steroid/.claude/worktrees/semantic-ripple-pilot rev-parse HEAD)
```

The token authenticates only through these environment variables (the keyring path returns 401), and it
has no `TAG_BUILD`/`COMMENT_BUILD` permission — `--revision` is what identifies a series, and
`RUN-IDS.md` is what identifies a cell.

### 1. Hook preflight

```bash
jb tc native run start "$CAP" --branch "$BRANCH" --revision "$SHA" --no-push \
  -P ripple.checkpoint.capture.method=hookPreflight --json=id,webUrl,state
```

Expected in the log: the counter advances on every tool call, and the exported patch of a step AFTER the
agent's write is non-empty. A counter that advances while the patch stays empty means the hook fires
without seeing the agent's disk writes — stop there, before paying for a capture.

### 2. Captures

```bash
for method in captureMcpArm captureShellArm; do
  jb tc native run start "$CAP" --branch "$BRANCH" --revision "$SHA" --no-push \
    -P ripple.checkpoint.capture.method="$method" --json=id,webUrl | tee -a /tmp/ripple-capture-runs.jsonl
done
```

Read the `[CHECKPOINT]` block. It reports `n`, the end-of-run context, the admission verdict with its
reasons AND notes, the planned positions with their nominal counterparts, and every correction.

`dpaia__feature__service-125` has an arena history (one timeout failure, four passes at 638s, 444s,
570s, 403s) but **no band this gate can use**: those runs are another model on an older harness and
report tool-call totals instead of `extractEndContextTokens`. So the capture is admitted with
`reference = null` — representativeness is not judged, and the verdict says so in its notes; admission
then requires only SUCCESS and `n > 5`. Record `n`, seconds, context tokens and cost in `RUN-IDS.md`
regardless — those rows ARE the sample the next stage will judge against.

Watch the corrections. A capture whose plan carries fewer than five checkpoints has told you that its
work tree stopped changing; that is a fact about the trajectory, and it is the operator's call whether
to publish a shorter curve or spend another Opus run.

### 3. Commit the admitted states

Download the capture artifacts and commit the patches and the metadata **together**:

```
test-experiments/src/test/resources/ripple-checkpoints/feature-service-125/<arm>/step-<a_i>.patch
test-experiments/src/test/resources/ripple-checkpoints/feature-service-125/<arm>/checkpoints.json
```

The probe refuses to run when the committed patches and the metadata disagree — a mismatch means they
come from two different capture runs, and no probe verdict from such a pair means anything.

### 4. Smoke probe, then the grid

```bash
jb tc native run start "$PROBE" --branch "$BRANCH" --revision "$SHA" --no-push \
  -P ripple.checkpoint.arm=mcp -P ripple.checkpoint.index=5 -P ripple.checkpoint.replicate=1 \
  --json=id,webUrl,state
```

Confirm on that build that the coordinates arrived (in the build's resulting properties AND in the step
log) and that the patch applied, before queueing the rest:

```bash
for arm in mcp none; do for idx in 1 2 3 4 5; do for rep in 1 2 3 4 5; do
  [ "$arm/$idx/$rep" = "mcp/5/1" ] && continue
  jb tc native run start "$PROBE" --branch "$BRANCH" --revision "$SHA" --no-push \
    -P ripple.checkpoint.arm="$arm" -P ripple.checkpoint.index="$idx" \
    -P ripple.checkpoint.replicate="$rep" --json=id,webUrl | tee -a /tmp/ripple-probe-runs.jsonl
  sleep 2
done; done; done
```

Queue in batches of 5–10: each build is a full Docker IDE container with a clone and a build-system
import, and this token cannot reprioritise the queue. Skip the indices a capture's plan does not carry.

### 5. Aggregate

```bash
jb tc native run list --job "$PROBE" --revision "$SHA" --limit 0 --json=id,status,state,webUrl
jb tc builds log <runId> -o /tmp/probe-<runId>.log
cat /tmp/probe-*.log | grep '\[CHECKPOINT-PROBE\]' > /tmp/ripple-verdicts.txt
```

Feed that file to `parseProbeVerdicts` / `renderCheckpointReport` (`RippleCheckpointReport.kt`). A
checkpoint with fewer than 5 verdicts renders `INCOMPLETE` instead of a `V`, and `LOST` lines
(instrument failures) are not verdicts — they never pull `V` down.

The AUC is integrated between the FIRST and LAST measured position only, and the report prints that
range next to the number. Nothing is extrapolated to 0 or to the end of the trajectory: readiness there
was not measured.

## Cost expectation

| phase | builds | per build |
|:---|---:|:---|
| preflight | 1 | ~35 min, ≈$0 |
| capture | 2 (≤6 with retries) | ~50 min, ≈$1–3.5 (Opus) |
| probes | up to 50 | ~45–60 min, ≈$0.2–0.5 (Haiku) |

The discarded first stage cost $4.43 and ~2.5 h of agent time; budget for the possibility of repeating
a capture rather than assuming the first one is usable.

## Record ids here

Fill `RUN-IDS.md` as builds are queued — that file is the only link between a build id and the cell it
measured, and `--tag`/`--comment` are unavailable to this token.
