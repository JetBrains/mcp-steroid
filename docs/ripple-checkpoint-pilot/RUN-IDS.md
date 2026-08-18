# Solution-readiness checkpoint pilot — run ids

This file is the only link between a TeamCity build id and the cell it measured: the pilot's token has no
`TAG_BUILD`/`COMMENT_BUILD` permission, so a build carries no marker of its coordinates beyond the
parameters recorded on it. Fill a row the moment a build is queued, not afterwards.

Series revision (`--revision`): `83798f22fb603050b63e3eccbae5693530a377c8` on
`jb/worktree-semantic-ripple-pilot` — every build of one series must pin the same commit.

Launch order and the reasoning behind it: `docs/ripple-checkpoint-pilot/RUNBOOK.md`.

## Capture stage

| # | method | build id | status | n (steps) | agent s | end tok | admitted | note |
|---|:---|---:|:---|---:|---:|---:|:---|:---|
| 1 | `hookPreflight` | 1034576458 | FAILURE | — | — | — | — | instrument bug: `mkdir /checkpoints` denied — the container user is unprivileged; fixed on `83798f22f` |
| 1b | `hookPreflight` | 1034618405 | SUCCESS | 3 | — | — | — | hook fired, counter = 3, `step-2.patch` non-empty (129 chars) — instrument proven |
| 2 | `captureMcpArm` | 1034656372 | | | | | | v3 band: steps 22–41, 31.6 ± 7.1; 871 ± 411 s; 75070 ± 11820 tok |
| 3 | `captureShellArm` | 1034656374 | | | | | | v3 band: steps 31–56, 39.9 ± 8.1; 749 ± 265 s; 66364 ± 7009 tok |

Rejected captures stay in this table — a repeat is a new row, and the report shows every attempt.

## Probe stage — 2 arms × 5 checkpoints × 5 replicates

Checkpoint steps are fixed by ONE schedule shared by both arms: 2, 6, 11, 17, 24 — `V_mcp` and `V_shell`
are only comparable when both were measured after the same number of tool calls.
`Y` is 1 only when the compile gate, the semantic oracle post-condition and FAIL_TO_PASS all pass;
`LOST` means the instrument failed (patch did not apply, container died) and must NOT be read as `Y = 0`.

### arm = mcp

| checkpoint | step | r1 | r2 | r3 | r4 | r5 | successes | V |
|---:|---:|:---|:---|:---|:---|:---|---:|---:|
| 1 | 2 | | | | | | | |
| 2 | 6 | | | | | | | |
| 3 | 11 | | | | | | | |
| 4 | 17 | | | | | | | |
| 5 | 24 | | | | | | | |

### arm = none

| checkpoint | step | r1 | r2 | r3 | r4 | r5 | successes | V |
|---:|---:|:---|:---|:---|:---|:---|---:|---:|
| 1 | 2 | | | | | | | |
| 2 | 6 | | | | | | | |
| 3 | 11 | | | | | | | |
| 4 | 17 | | | | | | | |
| 5 | 24 | | | | | | | |

Cell format: `<build id>:<Y|LOST>`.

## Totals

| | value |
|:---|:---|
| probe builds queued | |
| probe builds graded | |
| instrument failures (LOST) | |
| wall-clock span | |
| API spend | |
