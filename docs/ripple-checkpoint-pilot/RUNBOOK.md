# Solution-readiness checkpoint pilot — launch runbook

What is measured, and why this file exists: `V(s_i)` is the empirical probability that a fixed weak probe
agent (bare Haiku, no MCP) finishes the original task from the repository state the source Opus run
reached after `a_i` tool calls. The harness is landed and unit-tested; everything below is the part that
spends money and cannot run on a laptop. Design and plan:

- `docs/superpowers/specs/2026-08-18-ripple-checkpoint-readiness-pilot-design.md`
- `docs/superpowers/plans/2026-08-18-ripple-checkpoint-readiness-pilot.md`

## What is already in place

| Piece | Where |
|:---|:---|
| checkpoint positions, `V`, observed-range AUC | `RippleCheckpointMath.kt` |
| blind continuation prompt | `RippleCheckpointProbePrompt.kt` |
| `--settings` seam on the Claude session | `test-helper/.../ClaudePromptArgs.kt`, `DockerClaudeSession.kt` |
| per-step counter + shadow-git snapshots | `RippleCheckpointRecorder.kt` |
| representativeness gate vs the v3 sample | `RippleCaptureAdmission.kt` |
| capture run + hook preflight | `KeycloakRenameMethodWideCheckpointCaptureTest.kt` |
| bare-Haiku probe | `RippleCheckpointProbeTest.kt` |
| verdicts → table, curve, AUC | `RippleCheckpointReport.kt` |
| TeamCity capture/probe configurations | `mcp-steroid-teamcity` commit `67d178d` |

Checkpoint positions are precomputed once and SHARED by both arms, so the hook snapshots five states
instead of every one of them: n̂ = 32 → `2, 6, 11, 17, 24` for `mcp` and for `none` alike. One schedule
because `V_mcp` and `V_shell` are only comparable at equal tool-call counts; 32 because the admission band
of the shorter (mcp) arm accepts captures from 25 steps up, so `a_5` may not exceed 24. Reports normalize
by the capture run's **actual** `n` (the hook counter's final value).

## Prerequisites

1. The pilot branch must be visible to TeamCity: TC's VCS root pulls from `jb`, not `origin`. The pilot
   lives on `jb/worktree-semantic-ripple-pilot`; the branch is intentionally NOT kept on `origin`.
2. The DSL commits `67d178d` + `cb5356a` must reach the TeamCity settings repo — until then the two
   configurations do not exist on the server.
3. Land the Gradle property forwarding **before** the DSL: an unknown `-P`/`-D` is silently ignored, and
   this pilot's 50 probe builds differ ONLY by three coordinates.

## Order of operations (do not reorder)

```
1 hook preflight  →  2 captures (one per arm)  →  admission check  →  commit patches
                  →  1 smoke probe (mcp, checkpoint 5, replicate 1)  →  the remaining 49 probes
                  →  aggregate  →  REPORT.md
```

Why this order: the preflight is the only cheap test of the one unproven dependency (does the
`PostToolUse` hook fire in `claude-code/2.1.159`); the smoke probe is deliberately the *deepest*
checkpoint, because that is where `Y = 1` is most likely — a zero there means the instrument is broken,
not that the state was hopeless.

### Session preamble

```bash
export TEAMCITY_URL=https://buildserver.labs.intellij.net
export TEAMCITY_TOKEN="$(tr -d '\n' < ~/.config/teamcity/token)"
CAP=mcp_steroid_IntegrationTests_RippleCheckpointCapture
PROBE=mcp_steroid_IntegrationTests_RippleCheckpointProbe
BRANCH=worktree-semantic-ripple-pilot              # the pilot branch, as pushed to jb
SHA=$(git -C ~/work/mcp-steroid/.claude/worktrees/semantic-ripple-pilot rev-parse HEAD)  # pins the series
```

The token authenticates only through these environment variables (the keyring path returns 401), and it
has no `TAG_BUILD`/`COMMENT_BUILD` permission — `--revision` is what identifies the series.

### 1. Hook preflight

```bash
jb tc native run start "$CAP" --branch "$BRANCH" --revision "$SHA" --no-push \
  -P ripple.checkpoint.capture.method=hookPreflight --json=id,webUrl,state
```

The capture build selects a test METHOD, not an arm: the class holds `hookPreflight`, `captureMcpArm` and
`captureShellArm`, and the arm IS the method. `hookPreflight` is the default, because it is the only option
that costs nothing.

Expected in the log: `stepCount() >= 3` and a non-empty `step-2` patch. If the counter advances but the
patch is empty, the hook fires without seeing the agent's disk writes — stop and fix that before paying
for a capture.

### 2. Captures

```bash
for method in captureMcpArm captureShellArm; do
  jb tc native run start "$CAP" --branch "$BRANCH" --revision "$SHA" --no-push \
    -P ripple.checkpoint.capture.method="$method" --json=id,webUrl | tee -a /tmp/ripple-capture-runs.jsonl
done
```

Read the `[CHECKPOINT]` block. Admission requires: SUCCESS true; tool-call steps inside the arm's v3
min–max **and** within ±1σ of its mean; agent seconds and end-of-run context tokens within ±1σ; and
`n_actual > a_5`. Reference (claude, `rename-method-wide`, v3): `mcp` steps 22–41, mean 31.6 ± 7.1,
871 ± 411 s, 75070 ± 11820 tok; `none` steps 31–56, mean 39.9 ± 8.1, 749 ± 265 s, 66364 ± 7009 tok.
Rejected → rerun that arm (max 3 attempts) and keep every attempt's numbers for the report.

### 3. Commit the admitted states

Download the capture artifacts and commit them, patches and metadata together:

```
test-experiments/src/test/resources/ripple-checkpoints/rename-method-wide/<arm>/step-<a_i>.patch
test-experiments/src/test/resources/ripple-checkpoints/rename-method-wide/<arm>/checkpoints.json
```

Both directories already carry a `README.md` naming the exact expected files. Until all five patches of an
arm are committed, every probe of that arm fails immediately, before Docker starts — by design, so a
missing state can never be mistaken for a failed probe.

### 4. Smoke probe, then the grid

```bash
jb tc native run start "$PROBE" --branch "$BRANCH" --revision "$SHA" --no-push \
  -P ripple.checkpoint.arm=mcp -P ripple.checkpoint.index=5 -P ripple.checkpoint.replicate=1 \
  --json=id,webUrl,state
```

Before queueing the rest, confirm on that build that the coordinates actually arrived (both in the build's
resulting properties and in the step log) and that the patch applied.

```bash
for arm in mcp none; do for idx in 1 2 3 4 5; do for rep in 1 2 3 4 5; do
  [ "$arm/$idx/$rep" = "mcp/5/1" ] && continue
  jb tc native run start "$PROBE" --branch "$BRANCH" --revision "$SHA" --no-push \
    -P ripple.checkpoint.arm="$arm" -P ripple.checkpoint.index="$idx" \
    -P ripple.checkpoint.replicate="$rep" --json=id,webUrl | tee -a /tmp/ripple-probe-runs.jsonl
  sleep 2
done; done; done
```

Queue in batches of 5–10: each build is a full Docker IDE container with a Keycloak clone and Maven
import, and this token cannot reprioritise the queue.

### 5. Aggregate

```bash
jb tc native run list --job "$PROBE" --revision "$SHA" --limit 0 --json=id,status,state,webUrl
jb tc builds log <runId> -o /tmp/probe-<runId>.log
cat /tmp/probe-*.log | grep '\[CHECKPOINT-PROBE\]' > /tmp/ripple-verdicts.txt
```

Feed that file to `parseProbeVerdicts` / `renderCheckpointReport` (`RippleCheckpointReport.kt`). A
checkpoint with fewer than 5 verdicts renders `INCOMPLETE` instead of a `V`, and `LOST` lines (instrument
failures) are not verdicts — they never pull `V` down.

## Cost expectation

| phase | builds | per build |
|:---|---:|:---|
| preflight | 1 | ~35 min, ≈$0 |
| capture | 2 (≤6 with retries) | ~50 min, ≈$1.8 (Opus) |
| probes | 50 | ~45–60 min, ≈$0.2–0.5 (Haiku) |

Total ≈ $20–35 of API budget and ≈45 agent-hours of TeamCity capacity.

## Record ids here

Fill `RUN-IDS.md` as builds are queued — that file is the only link between a build id and the cell it
measured, and `--tag`/`--comment` are unavailable to this token.
