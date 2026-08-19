# Solution-readiness checkpoint pilot — run ids

This file is the only link between a TeamCity build id and the cell it measured: the pilot's token has no
`TAG_BUILD`/`COMMENT_BUILD` permission, so a build carries no marker of its coordinates beyond the
parameters recorded on it. Fill a row the moment a build is queued, not afterwards.

Launch order and the reasoning behind it: `RUNBOOK.md`. Why the first stage was discarded:
`CAPTURE-2026-08-18-POSTMORTEM.md`.

## Stage 1 — DISCARDED (case `ripple__keycloak__rename-method-wide`, fixed schedule 2/6/11/17/24)

Kept as the record of what was spent and what it proved. Series revision:
`83798f22fb603050b63e3eccbae5693530a377c8`.

| # | method | build id | status | n | agent s | end ctx tok | cost | admitted | note |
|---|:---|---:|:---|---:|---:|---:|---:|:---|:---|
| 1 | `hookPreflight` | 1034576458 | FAILURE | — | — | — | — | — | `mkdir /checkpoints` denied — unprivileged container user; fixed on `83798f22f` |
| 1b | `hookPreflight` | 1034618405 | SUCCESS | 3 | — | — | ≈0 | — | hook fires in `claude-code/2.1.159`, `step-2` patch non-empty — instrument proven |
| 2 | `captureMcpArm` | 1034656372 | SUCCESS | **23** | 990 | 17496 (wrong metric) | $1.12 | **false** | `a_5 = 24` never reached; `step-11`/`step-17` byte-identical |
| 3 | `captureShellArm` | 1034656374 | SUCCESS | **51** | 751 | 25225 (wrong metric) | $3.31 | **false** | deepest checkpoint landed at 47% of the trajectory, not 76% |

No probe build was queued from these captures.

## Stage 2 — case `dpaia__spring__boot__microshop-18` (ABANDONED)

This case was abandoned after the preflights below. Reasoning: its historical success rate is too low
(1/6) to measure a readiness curve (all failures being the same exploration loop), and its
Testcontainers oracle does not work in the arena container (`dockerOracleWorks = false`), allowing
success claims on compile alone.

Series revision (`--revision`): _fill when the capture series starts_ — every build of one series must
pin the same commit. The preflights below pinned `2e62eb6fd` and `2319fbfce`.

### Capture stage (microshop-18)

`n`, `agent s`, `end ctx tok` and `cost` are recorded for EVERY attempt, admitted or not: this case has
no historical sample, so these rows are the sample the next stage's representativeness gate will use.

| # | method | build id | status | n | agent s | end ctx tok | cost | admitted | plan (steps) | corrections |
|---|:---|---:|:---|---:|---:|---:|---:|:---|:---|:---|
| 1 | `hookPreflight` | 1035242055 | SUCCESS | 3 | — | — | ≈0 | — | — | selector proven: the build ran `DpaiaMicroshop18CheckpointCaptureTest`, and `step-2` held exactly the agent's write |
| 2 | `hookPreflight` | 1035267973 | SUCCESS | 7 | — | — | ≈0 | — | 1, 3, 5 | full instrument proven — see below |
| 3 | `captureMcpArm` | | | | | | | | | |
| 4 | `captureShellArm` | | | | | | | | | |

Preflight 1035267973 is the one that exercises the rebuilt instrument end to end on live data:

- the hook counted **7** tool calls and left a snapshot for every one of them (`step-0..step-7`);
- consecutive trees differ exactly where the agent wrote — `step-1` and `step-2` share tree
  `354141df…` (the second call was a directory listing), `step-3` is `de1ddf08…`;
- `plan(7)` selected **1, 3, 5** with both corrections stated: nominal steps 2 and 4 held states already
  probed and moved forward. This is the rule that the discarded stage lacked, running on real snapshots;
- the deepest planned patch is non-empty.

Three checkpoints rather than five is correct for a 7-step recording: a trajectory only carries as many
distinct pre-final states as it actually produced.

Rejected captures stay in this table — a repeat is a new row, and the report shows every attempt.

### Probe stage — 2 arms × up to 5 checkpoints × 5 replicates

The checkpoint STEPS are not shared between arms and are not known before the captures: each arm's
`checkpoints.json` names its own. Copy them here from the committed metadata, so a row can be read
without the artifact. `V_mcp` and `V_shell` are compared at equal normalized positions `a_i/n`, never at
equal tool-call counts.

`Y` is 1 only when FAIL_TO_PASS + PASS_TO_PASS pass and the agent did not tamper with the oracle.
`LOST` means the instrument failed (patch did not apply, container died) and must NOT be read as `Y = 0`.

#### arm = mcp (n = _fill_)

| checkpoint | step | position | r1 | r2 | r3 | r4 | r5 | successes | V |
|---:|---:|---:|:---|:---|:---|:---|:---|---:|---:|
| 1 | | | | | | | | | |
| 2 | | | | | | | | | |
| 3 | | | | | | | | | |
| 4 | | | | | | | | | |
| 5 | | | | | | | | | |

#### arm = none (n = _fill_)

| checkpoint | step | position | r1 | r2 | r3 | r4 | r5 | successes | V |
|---:|---:|---:|:---|:---|:---|:---|:---|---:|---:|
| 1 | | | | | | | | | |
| 2 | | | | | | | | | |
| 3 | | | | | | | | | |
| 4 | | | | | | | | | |
| 5 | | | | | | | | | |

Cell format: `<build id>:<Y|LOST>`. A row a capture's plan does not carry is struck through, not left
blank — a blank reads as "not queued yet".

## Stage 3 — case `dpaia__feature__service-125`, positions derived from the measured `n`

Chosen for its incremental solution path, its working Testcontainers oracle, and a historical success
rate strictly between 0 and 1.

Series revision (`--revision`): `a1fd1ad04` for the preflight, `4e6c04735` for the two captures — every
build of one series must pin the same commit, and these two differ by `git diff --stat a1fd1ad04 4e6c04735`
= this file and `RUNBOOK.md` only. No measurement code, no build file and no case configuration changed
between them, so the captures record what the preflight proved. Any build that pins a commit touching
`test-experiments/` starts a NEW series.

### Capture stage

| # | method | build id | status | n | agent s | end ctx tok | cost | admitted | plan (steps) | corrections |
|---|:---|---:|:---|---:|---:|---:|---:|:---|:---|:---|
| 1 | `hookPreflight` | 1035324252 | SUCCESS | 7 | 27 | n/a | ≈$0 | n/a | 1, 3, 5 | 4 (2 moved, 2 dropped) |
| 2 | `captureMcpArm` | 1035363501 | SUCCESS | 26 | 944 | 152414 | $3.83 | true | 2, 15, 16, 17, 20 | 3 moved |
| 3 | `captureShellArm` | 1035363503 | SUCCESS | 57 | 746 | 133589 | $3.68 | true | 4, 17, 22, 31, 43 | 2 moved |

Both captures were graded SUCCESS by `ArenaVerifier` — the shell arm's agent ended on "107 tests, 0
failures, 0 errors" across all five FAIL_TO_PASS classes — so admission needed only `n > 5`, which both
clear. Representativeness was not judged in either, and both logs print that note: this case has no band
measured on this model and this harness, and these two rows are the first of it.

The two arms differ in a way that is itself a finding rather than noise. The shell arm spent **57** tool
calls and its five states grow smoothly — 0, 711, 1243, 12587, 24299 patch chars at 7.0 %, 29.8 %,
38.6 %, 54.4 %, 75.4 % of its trajectory. The mcp arm needed **26** and wrote nothing until step 15, so
three nominal positions (5, 9, 14) all held the pristine tree and were moved forward onto the first
differing state: its checkpoints land at 7.7 %, 57.7 %, 61.5 %, 65.4 %, 76.9 % with 0, 2435, 3950, 4407,
37497 chars. The mcp curve is therefore measured over a much narrower and later window — a consequence of
reading the project through MCP before writing to it, and the reason the two AUCs cannot be put side by
side without naming the range each one covers.

The committed states are the ones these two builds exported, copied out of `run-*/checkpoints/` inside
the run-dir ZIP artifact — the unpacked artifact directory publishes only `video/` — into
`test-experiments/src/test/resources/ripple-checkpoints/feature-service-125/`. Checkpoint 1 of each arm
is a ZERO-length patch, i.e. the pristine tree: `GitDriver.applyPatch` treats a blank patch as "nothing
to apply", so that cell measures the probe's unaided solve rate on this task and anchors the curve
instead of being a broken export.

Preflight 1035324252 proves the instrument on THIS stage's configuration, and its first line is about
the configuration rather than the hook: the build was started with
`-Pripple.checkpoint.capture.method=hookPreflight` and nothing else, so the case default in
`test-experiments/build.gradle.kts` is what selected
`DpaiaFeatureService125CheckpointCaptureTest.hookPreflight`. A capture started the same way therefore
records the case this stage means to record.

What the hook did (11.5 min wall clock, no Opus): counted **7** tool calls and tagged every one of them;
`step-1` and `step-2` share tree `354141df…` (the second call read, it did not write) while `step-3` is
`de1ddf08…`; `plan(7)` returned steps **1, 3, 5** and stated all four corrections — nominal steps 2 and 3
moved forward onto a differing state, nominal steps 4 and 5 dropped because the work tree stopped
changing after step 5; the deepest planned patch exported non-empty (407 chars).

Three checkpoints out of a 7-step recording is the correct answer, not a shortfall: a trajectory carries
only as many distinct pre-final states as it produced. A paid capture is 20–80 steps, where five distinct
states are expected — and if they do not exist, the plan says so instead of probing one state twice.

Rejected captures stay in this table — a repeat is a new row, and the report shows every attempt.

### Probe stage — 2 arms × up to 5 checkpoints × 5 replicates

Probe builds pin a LATER commit than the captures on purpose: a probe reads its state out of the
committed resources, so it must pin the commit that carries them. The measurement code is identical
between the two — the delta is the patches, this file and the resource READMEs.

#### arm = mcp (n = 26)

| checkpoint | step | position | r1 | r2 | r3 | r4 | r5 | successes | V |
|---:|---:|---:|:---|:---|:---|:---|:---|---:|---:|
| 1 | 2 | 0.0769 | | | | | | | |
| 2 | 15 | 0.5769 | | | | | | | |
| 3 | 16 | 0.6154 | | | | | | | |
| 4 | 17 | 0.6538 | | | | | | | |
| 5 | 20 | 0.7692 | | | | | | | |

#### arm = none (n = 57)

| checkpoint | step | position | r1 | r2 | r3 | r4 | r5 | successes | V |
|---:|---:|---:|:---|:---|:---|:---|:---|---:|---:|
| 1 | 4 | 0.0702 | | | | | | | |
| 2 | 17 | 0.2982 | | | | | | | |
| 3 | 22 | 0.3860 | | | | | | | |
| 4 | 31 | 0.5439 | | | | | | | |
| 5 | 43 | 0.7544 | | | | | | | |

## Totals

| | value |
|:---|:---|
| capture builds, stage 1 (discarded) | 4 |
| capture builds, stage 2 (abandoned case) | 2 preflights |
| capture builds, stage 3 | 1 preflight + 2 captures, both admitted |
| probe builds queued | 0 |
| probe builds graded | 0 |
| instrument failures (LOST) | 0 |
| API spend | $11.94 — $4.43 stage 1 (discarded), $7.51 stage 3 ($3.83 mcp + $3.68 shell); a preflight is a scripted agent and costs about nothing |
