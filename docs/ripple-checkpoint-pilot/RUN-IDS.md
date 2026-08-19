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

## Stage 2 — case `dpaia__spring__boot__microshop-18`, positions derived from the measured `n`

Series revision (`--revision`): _fill when the series starts_ — every build of one series must pin the
same commit.

### Capture stage

`n`, `agent s`, `end ctx tok` and `cost` are recorded for EVERY attempt, admitted or not: this case has
no historical sample, so these rows are the sample the next stage's representativeness gate will use.

| # | method | build id | status | n | agent s | end ctx tok | cost | admitted | plan (steps) | corrections |
|---|:---|---:|:---|---:|---:|---:|---:|:---|:---|:---|
| 1 | `hookPreflight` | | | | | | | — | — | |
| 2 | `captureMcpArm` | | | | | | | | | |
| 3 | `captureShellArm` | | | | | | | | | |

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

## Totals

| | value |
|:---|:---|
| capture builds (all stages) | 4 so far (stage 1) |
| probe builds queued | 0 |
| probe builds graded | 0 |
| instrument failures (LOST) | 0 |
| wall-clock span | |
| API spend | $4.43 (stage 1, discarded) |
