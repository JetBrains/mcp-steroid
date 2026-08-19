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

## Totals

| | value |
|:---|:---|
| capture builds, stage 1 (discarded) | 4 |
| capture builds, stage 2 (abandoned case) | 2 preflights |
| capture builds, stage 3 | 1 preflight + 2 captures, both admitted |
| probe builds queued | 50 on the discarded grid (12 cancelled), 57 on the fraction grid |
| probe builds graded | 0 |
| instrument failures (LOST) | 0 |
| API spend | $11.94 — $4.43 stage 1 (discarded), $7.51 stage 3 ($3.83 mcp + $3.68 shell); a preflight is a scripted agent and costs about nothing |
