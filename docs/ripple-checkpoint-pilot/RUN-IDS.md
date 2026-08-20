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

## Stage 3 — case `dpaia__feature__service-125` (the pilot's actual case)

The capture builds the whole grid below hangs off. Recovered from TeamCity after the fact — they were
never written down when queued, which is exactly what the note at the top of this file warns against.
Both ran `DpaiaFeatureService125CheckpointCaptureTest` and both were admitted.

| # | method | build id | status | n | agent s | end ctx tok | cost | admitted |
|---|:---|---:|:---|---:|---:|---:|---:|:---|
| 1 | `hookPreflight` | 1035324252 | SUCCESS | — | 398 | — | ≈0 | — |
| 2 | `captureMcpArm` | 1035363501 | SUCCESS | **26** | 3900 | 152414 | $3.83 | **true** |
| 3 | `captureShellArm` | 1035363503 | SUCCESS | **57** | — | 133589 | $3.68 | **true** |

The decisive action of the mcp capture is its CLI tool call 21 = recorder step 19, one
`steroid_execute_code` that wrote the whole integration layer in a single call — see
[RESIDUAL-DIFFICULTY.md](RESIDUAL-DIFFICULTY.md).

### Probe stage — the axis is the FRACTION of the edit phase

The first probe grid measured relative positions of the WHOLE run and could not compare the arms: the
mcp agent read for 15 of its 26 turns and the shell agent for 17 of its 57, so the two spend nearly the
same effort reading and then differ fourfold in the writing itself — 11 edit turns against 40. Dividing
by `n` normalises away exactly that difference, and an absolute turn index compares turn 4 of an
11-turn edit phase against turn 4 of a 40-turn one. Both are meaningless.

So a checkpoint is now `editFraction = k/10`, `k = 0…9`, of the edit phase: step
`firstWriteStep + round(k/10 · (n − firstWriteStep))`. `editFraction = 0` is the first write and
`k = 9` stops short of the end, because the original run's own outcome is judged separately.

The pristine tree is no longer a checkpoint of either arm — it is the same tree in both, and its 9
recorded probes (6 successes) are the shared BASELINE the curves are read against: a bare Haiku solves
this task unaided about two thirds of the time, so the range in which `V` can rise at all is 0.67 → 1.0.

A fraction whose state repeats an earlier one is kept in the metadata as data — it records that the
agent wrote nothing in that window — and is NOT probed: `sameStateAs` names the fraction whose verdict
applies, and paying a second time for the same tree would buy noise, not a point.

Probe builds pin `a301af63a`, the commit that carries the regenerated states.


#### arm = mcp (n = 26, first write at step 15, 11 edit turns)

| k | editFraction | step | patch chars | r1 | r2 | r3 | r4 | r5 |
|---:|---:|---:|---:|:---|:---|:---|:---|:---|
| 0 | 0.0 | 15 | 2436 | 1035498274* | 1035503876* | 1035503878* | 1035503880* | 1035503882* |
| 1 | 0.1 | 16 | 3951 | 1035503884* | 1035674854 | 1035674856 | 1035503891* | 1035674858 |
| 2 | 0.2 | 17 | 4408 | 1035503895* | 1035503897* | 1035674860 | 1035674862 | 1035503903* |
| 3 | 0.3 | 18 | 6639 | 1035674868 | 1035674870 | 1035674872 | 1035674874 | 1035678846 |
| 4 | 0.4 | 19 | 37498 | 1035678910 | 1035678912 | 1035678914 | 1035678916 | 1035678918 |
| 5 | 0.5 | 21 | 37498 | — not probed: same tree as step 19 | | | | |
| 6 | 0.6 | 22 | 37498 | — not probed: same tree as step 19 | | | | |
| 7 | 0.7 | 23 | 37498 | — not probed: same tree as step 19 | | | | |
| 8 | 0.8 | 24 | 37288 | 1035678920 | 1035678922 | 1035678924 | 1035678926 | 1035678928 |
| 9 | 0.9 | 25 | 37288 | — not probed: same tree as step 24 | | | | |

#### arm = none (n = 57, first write at step 17, 40 edit turns)

| k | editFraction | step | patch chars | r1 | r2 | r3 | r4 | r5 |
|---:|---:|---:|---:|:---|:---|:---|:---|:---|
| 0 | 0.0 | 17 | 712 | 1035503924* | 1035503926* | 1035674864 | 1035503931* | 1035674866 |
| 1 | 0.1 | 21 | 712 | — not probed: same tree as step 17 | | | | |
| 2 | 0.2 | 25 | 3511 | 1035678930 | 1035678932 | 1035678934 | 1035678936 | 1035678938 |
| 3 | 0.3 | 29 | 8752 | 1035678940 | 1035678942 | 1035678944 | 1035678946 | 1035679598 |
| 4 | 0.4 | 33 | 12669 | 1035679682 | 1035679684 | 1035679686 | 1035679688 | 1035679690 |
| 5 | 0.5 | 37 | 21078 | 1035679692 | 1035679694 | 1035679696 | 1035679698 | 1035679700 |
| 6 | 0.6 | 41 | 22484 | 1035679702 | 1035679704 | 1035679706 | 1035679708 | 1035679710 |
| 7 | 0.7 | 45 | 25972 | 1035679712 | 1035679714 | 1035679716 | 1035679718 | 1035679720 |
| 8 | 0.8 | 49 | 40196 | 1035679722 | 1035679724 | 1035679726 | 1035679728 | 1035679733 |
| 9 | 0.9 | 53 | 40196 | — not probed: same tree as step 49 | | | | |

`*` marks a cell measured before the axis change and reused: its state is bit-identical, so its verdict
carries over unchanged. 38 verdicts were recorded on the old grid; 13 of them sit on states this grid
also visits, and the remaining 25 measured steps the fraction grid does not land on (mcp 2 and 20, none
4, 22, 31, 43) — they stay in the log as extra points on the arm-internal `position` axis and are not
part of the fraction curve.

### Re-queued cells

Three cells of the fraction grid produced no usable verdict, and each failed for a different reason worth
naming:

| cell | build | what happened | disposition |
|:---|---:|:---|:---|
| mcp/2/3 (step 16) | 1035674856 | agent spent all 1800 s, exit -1, no grade | the probe published `LOST` and this row argued for folding it into `V` as a zero — but the table in [RESULTS.md](RESULTS.md) shows 4 runs at that fraction, i.e. it was excluded in the end, and [RESIDUAL-DIFFICULTY.md](RESIDUAL-DIFFICULTY.md) keeps the exclusion: an UNGRADED cell says nothing about the state, and other budget-exhausted cells were graded normally, several of them `Y=1` |
| none/5/1 (step 33) | 1035679682 | Anthropic closed the connection 26 s in (9 reads, 0 edits) and it was published as `Y=0` | withheld as `LOST reason=api-transport-error`; re-run 1035939472 returned `Y=1` ($0.709 / 778 s) |
| none/3/2 (step 25) | 1035678932 | build produced no `[CHECKPOINT-PROBE]` line at all | withheld; re-run 1035939474 returned `Y=1` ($0.834 / 1067 s) |

### Cells whose grade is VOID because the probe rewrote the oracle

Found by re-reading the logs, not visible in the aggregates: five rollouts edited a FAIL_TO_PASS test
file, so `failToPassTampered` voided their grade and they were published as `Y=0`. Three of them had all
five classes green with no regression. Four of the five sit at `editFraction = 0.3`.

| cell | build | classes green | note |
|:---|---:|:---|:---|
| mcp/4/1 (step 18) | 1035674868 | 5/5, 0 regressions | rewrote `ReleaseControllerTests.java` and `test-data.sql` |
| none/4/3 (step 29) | 1035678944 | 4/5 | rewrote the oracle |
| none/4/4 (step 29) | 1035678946 | 2/5 | rewrote the oracle |
| none/4/5 (step 29) | 1035679598 | 5/5, 0 regressions | rewrote the oracle |
| none/8/2 (step 45) | 1035679714 | 5/5, 0 regressions | rewrote the oracle |

Two more cells were graded while the VERIFIER's own Maven was killed with exit 137 and only one class
reported: `1035674862` (mcp step 17 r4) and `1035679694` (none step 37 r2).

The first re-queue attempt (1035837025 / 1035837027 / 1035837029) never started: TeamCity answered
`Cannot find modification in TeamCity database with revision a301af63a` and the snapshot dependency
failed. The abbreviated SHA is not resolvable for a `--revision` once the branch has moved — the
re-queues above pin the full 40-character `dbac4260750ec72fac330c824fd86267ab110156`.

## Stage 4 — round 2: a second independent capture per arm (case `dpaia__feature__service-125`)

Why a second capture rather than more probes: round 1 has `n = 1` source trajectory per arm, so its
6× residual-work gap cannot be told apart from variance between two Opus runs. The design, its
hypotheses and the decision rule are frozen in [REPLICATION-2.md](REPLICATION-2.md), committed before
the first build below was queued.

Series revision: `0e6f167fa2b90d8ce665584aa77d720ac2807ee9` — `cd646b679` plus the round-1 write-up, the
pre-registration and the capture instrumentation. **Not a rebase**: the prompt, the MCP tool surface,
the grading and the case configuration are the same objects round 1 measured.

The round is encoded in the ARM: `mcp2` / `none2`, states under
`ripple-checkpoints/feature-service-125/mcp2|none2/`. Round 1's `mcp` / `none` directories are never
touched again.

### Capture stage (round 2)

| # | method | build id | status | n | agent s | out tok | cost | admitted | hook records | transcript |
|---|:---|---:|:---|---:|---:|---:|---:|:---|:---|:---|
| 1 | `hookPreflight` | 1037066974 | SUCCESS | 7 | — | — | ≈0 | — | **7/7** | `transcript-0.jsonl` |
| 2 | `captureMcpArm` | 1037073445 | FAILURE (artifacts) | 23 | 786 | 43715 | $3.05 | **true** | **23/23** | published, then LOST |
| 3 | `captureShellArm` | 1037073447 | FAILURE (artifacts) | 70 | 1203 | 55797 | $5.36 | — | **70/70** | published, then LOST |
| 4 | `captureMcpArm` (re-run) | 1037157415 | | | | | | | | |
| 5 | `captureShellArm` (re-run) | 1037157425 | | | | | | | | |

**Attempt 1 of the mcp arm is void, and not because of the agent.** The measurement itself succeeded —
admitted, 23 tool calls, a hook record for every one of them, a patch for every one of them, the
transcript located and copied. TeamCity then failed to zip the run directory:

```
Error while closing archive '…-mcp.zip' with Optional[793] entries:
  Error adding file '…/publish/bundle/checkpoints/transcript-0.jsonl'
  java.nio.file.AccessDeniedException
```

The CLI writes its transcript `0600` as the agent user; `cp` preserved that, the run dir is a bind
mount, and the TeamCity agent reads it back on the host as a different uid. One unreadable file aborts
the WHOLE archive, so 23 patches, 23 hook records and `checkpoints.json` were lost with it and only the
27 MB video survived. Fixed by `chmod 0644` on the copy in `RippleCheckpointRecorder.exportStepRecords`;
the capture must be repeated because nothing of it is recoverable.

What the lost trajectory still tells us, from the build log alone: `n = 23`, first write at step **17**,
and the tree stops changing after step 18 — `step-17` is already 36 179 chars, i.e. this mcp run wrote
essentially the whole solution in ONE call at `editFraction = 0`. Recorded here because it is a
measurement of the arm's batching, and because it means a repeated mcp capture may again collapse `T`
onto `M0`, which the pre-registered rule in [REPLICATION-2.md](REPLICATION-2.md) covers explicitly.

**Attempt 1 of the shell arm is void for the identical reason** — the same `AccessDeniedException` on
`checkpoints/transcript-0.jsonl`, 1195 entries this time. Its instrument was equally complete: `n = 70`,
**70/70** hook records, a patch for every one of the 70 steps, the transcript located and copied. From
its log alone: first write at step **21**, the final tree ≈32 700 chars, 55 797 output tokens in 1203 s
for $5.36. Both arms were re-queued at `8f00b53f2` (rows 4–5), where the copy is `chmod 0644`.

The two void attempts cost **$8.41** and bought no probeable state. They are kept in this table rather
than deleted: an instrument that loses a paid capture to a file mode is exactly what a provenance file
exists to record, and the trajectories they measured (mcp `n = 23`, first write 17; shell `n = 70`,
first write 21) remain evidence about the arms' shapes even though no probe can start from them.

`out tok` is the run's own OUTPUT tokens from the terminal `result` event — not the end-of-run context
size round 1's tables printed under the name `tokens`.

The preflight is the gate for the changed instrument, and it passed on all four counts: the counter
reached 7, every step 0–7 has a snapshot, **every step 1–7 has a hook record**, and the CLI's session
transcript was located through those records and published. Its own artifacts contain no `checkpoints/`
directory — the preflight recorder writes to the container's `/tmp`, while a capture writes into the
bind-mounted run dir — so the transcript's per-message `usage` is first readable in the capture
artifacts below, not here.

Environment recorded per capture, because the agent CLI is installed by `npm install -g` behind a daily
cache-bust and therefore differs from round 1's `claude-code/2.1.197` by construction:

| | capture 1 | capture 2 |
|:---|:---|:---|
| agent CLI | `claude-code/2.1.197` | |
| plugin | `0.101.672-jb-4e6c047` | |
| IDE | `2026.2.1` | |
| capture model | `claude-opus-5` | |

Both round-2 arms must share ONE image build. An arm captured against a different image is a different
experiment and is recorded as such rather than compared.

### Probe stage (round 2)

Four pre-registered states per trajectory — `M0`, `T−1`, `T`, last-distinct-tree — five replicates each,
plus three drift-control cells re-running a capture-1 state to quantify probe-side drift between rounds.

| arm | index | step | editFraction | milestone | r1 | r2 | r3 | r4 | r5 |
|:---|---:|---:|---:|:---|:---|:---|:---|:---|:---|
| mcp2 | 1 | | | `M0` | | | | | |
| mcp2 | 2 | | | `T−1` | | | | | |
| mcp2 | 3 | | | `T` | | | | | |
| mcp2 | 4 | | | last distinct | | | | | |
| none2 | 1 | | | `M0` | | | | | |
| none2 | 2 | | | `T−1` | | | | | |
| none2 | 3 | | | `T` | | | | | |
| none2 | 4 | | | last distinct | | | | | |

| drift control | arm | index | step | build |
|---:|:---|---:|---:|:---|
| 1 | mcp | 5 | 19 | |
| 2 | mcp | 5 | 19 | |
| 3 | mcp | 5 | 19 | |

## Result

The measured curves, the AUC of each arm, the shared baseline and the threats to the result are in
[RESULTS.md](RESULTS.md). Headline: over the fraction of the edit phase the mcp trajectory integrates to
0.847 against the shell arm's 0.713 on the identical range 0.0..0.8, above a shared 0.67 baseline
measured on the pristine tree.

Those two AUC numbers become **0.875 / 0.775** once the void grades above are withdrawn, and the
per-rollout dataset behind every cell — together with the residual-work axis, which moves 5× where the
success rate saturates — is in [RESIDUAL-DIFFICULTY.md](RESIDUAL-DIFFICULTY.md) and `data/`. That pass
also corrects the provenance: the branch ran **97** probe builds and **95** of them printed a verdict
line; "276 verdicts out of 74 builds" was never right.

## Totals

| | value |
|:---|:---|
| capture builds, stage 1 (discarded) | 4 |
| capture builds, stage 2 (abandoned case) | 2 preflights |
| capture builds, stage 3 | 1 preflight + 2 captures, both admitted |
| probe builds queued | 50 on the discarded grid (12 cancelled), 57 on the fraction grid, 2 re-queues |
| probe builds that ran | **97** in total on the branch — 38 printing the old verdict format, 59 the fraction one |
| probe builds graded | 95 printed a verdict; 89 of those are usable (72 solved, 17 failed), 5 are void (oracle rewritten), 3 are instrument failures |
| instrument failures (LOST) | 3 — one transport abort, one build with no verdict line, one with no grade at all; the first two were re-run and both then graded `Y=1` |
| API spend | ≈ $46 — $4.43 stage 1 (discarded), $7.51 captures ($3.83 mcp + $3.68 shell), ≈ $34 for 95 haiku probe cells; a preflight is a scripted agent and costs about nothing |
| TeamCity build time | ≈ 90 build-hours, i.e. ≈ 5 per curve point |
