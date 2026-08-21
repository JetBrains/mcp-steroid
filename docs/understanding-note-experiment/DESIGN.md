# Fixed-budget repository understanding → downstream task success

**Status:** harness implemented, task selected, **not yet run**. The expensive matrix is not started
until the calibration below has an answer and the operator has authorised it.

## The hypothesis

> With a fixed exploration budget, semantic repository access enables a strong coding agent to acquire
> and distil a more useful actionable model of a large unfamiliar codebase, as measured by the
> downstream performance of a weaker agent receiving only that distilled note.

Formally: a strong agent turns `Repository + Task` into a note `K` under a hard budget of `B`
environment interactions and a hard limit of `L` characters; a weak agent then turns
`Repository + Task + K` into a solution. The outcome is `P(success | K)`, graded by a hidden test.

Two denominators, never mixed, and both published per cell:

| axis | denominator | what a difference on it means |
|:---|:---|:---|
| interaction efficiency | research tool calls | semantic access buys understanding in fewer queries |
| model-compute efficiency | research Opus **output tokens** | semantic access buys understanding per token of thinking |

Round 2 of the checkpoint pilot already found these two can disagree: the mcp arm reached the same
structural milestone in 15 calls against 41, and spent 40 175 output tokens against 25 176 doing it
(`docs/ripple-checkpoint-pilot/REPLICATION-2.md`). A win on the first axis with parity on the second is
a clean, publishable result — *semantic IDE improves repository-understanding acquisition per
environment interaction, not necessarily per model token* — and the report must be able to say exactly
that. Mixing the axes is the one analysis error this design refuses to allow.

## Why this experiment and not another RCW round

Rounds 1–3 measured *residual completion work* from the **repository state** `R_t`. The exploratory
re-analysis of those traces (see the session notes and `docs/ripple-checkpoint-pilot/RCW-GENERALIZATION.md`)
showed that a third to a half of an agent's work happens before a single byte changes on disk, so
`RCW(R_t)` is blind to it by construction — symmetrically for both arms, which is why it could neither
confirm nor refute an MCP advantage. This experiment makes that invisible half the *only* thing
measured: the research phase is required to leave the tree byte-identical, so **the note is the entire
delta between conditions**.

## The three arms

| arm | what the downstream agent gets |
|:---|:---|
| `baseline` | pristine tree + task |
| `shell-note` | pristine tree + task + note written by Opus with shell tools only |
| `mcp-note` | pristine tree + task + note written by Opus with the IDE's resolved-program tools |

Plus one calibration-only condition, `oracle:<name>` — a hand-written, gold-derived note. It answers
"is the weak agent capable of this task at all when the understanding is handed to it" and **never
appears in an mcp-versus-shell comparison**.

The downstream agent is the same model (`claude-haiku-4-5`), with the same tools (shell only, no IDE),
the same time budget, and a brief that differs between arms by exactly one inserted block — pinned by
`UnderstandingHarnessTest`. It is not told the arm, the budget, that a note came from a model, or that
a comparison exists.

## The task

`understanding__keycloak__email-domain-mapper` — add an OpenID Connect token mapper that contributes an
`email_domain` claim, and make it available **out of the box** in a fresh realm.

Why this one, against the four criteria the design pre-registered:

- **A — no leaked localization.** The statement is behavioural. The measured `grep -ril` hit counts of
  every greppable word live in the registry (`UnderstandingCase.statementLeakageTokens`) and are pinned
  by `UnderstandingCaseRegistryTest`; the two words that would localize the answer, `email_domain` and
  `oidc-email-domain-mapper`, occur **zero** times in the tree.
- **B — a discoverable precedent.** `HardcodedClaim` and `UserAttributeMapper` are the same feature,
  already implemented the same architectural way.
- **C — several conceptually different roles.** Behaviour + SPI implementation + config properties +
  `META-INF/services` registration + **a second, unrelated registration mechanism**: the built-in mapper
  map in `OIDCLoginProtocolFactory.initBuiltIns()`. That second mechanism is the point of the whole
  case. It is in a different file, in a different package, reached by no grep of the statement, and an
  agent that copies a sibling mapper and stops has produced exactly the plausible partial solution the
  oracle rejects. It is also precisely what a good note can carry in one sentence.
- **D — the right difficulty band.** Three cheaper candidates (`notPalindrome` password rule,
  `starts-with-letter` validator, `andotp` OTP app) were **rejected after a simulated shell audit**: a
  shell agent holds both gold files and the registration file after three commands, so the baseline
  would solve them "usually" and the note could add nothing. The audit is summarised in
  `CANDIDATES.md`.

### Why the oracle test is not in the tree

Every earlier family deploys the FAIL_TO_PASS test with the project. Here that would defeat the
experiment: the test file's package and imports name the classes to write, which is the localization
being measured. So the oracle is applied **after** the downstream agent finishes and before the graded
`mvn clean test -pl :keycloak-services`. Two consequences, both deliberate:

- the test may reference **only pre-existing API** — it resolves the new mapper through
  `ServiceLoader` and through the built-in mapper map, never by class name, so it compiles against the
  pristine tree and against any correct solution;
- tamper protection becomes structural: the file the agent would have had to rewrite did not exist
  while the agent was running. A patch that fails to apply is a **LOST** cell, never a zero.

## What the harness enforces

| rule | mechanism | file |
|:---|:---|:---|
| exactly `B` environment interactions | `PreToolUse` hook, exit code 2, counter incremented only for allowed calls | `UnderstandingResearchBudget.kt` |
| `ToolSearch` / `TodoWrite` do not consume budget | tool-name case in the hook | same |
| the note is at most `L` characters | hard truncation at extraction, `truncated` recorded | `UnderstandingNote.kt` |
| the note is prose, not a tool dump | `verbatimLinePercent` measured and published (never enforced) | same |
| the research run changed nothing | `git status --porcelain` of the deployed clone; a dirty tree invalidates the run | `UnderstandingPristine.kt` |
| research tool calls, denials, output tokens, cost, wall clock | hook counters + the CLI's own usage event | `UnderstandingRun.kt` |
| the downstream cell runs on a haiku, the research cell does not | model assertions before the agent starts | same |

`ToolSearch` is exempt because the mcp arm spends its first one to three calls discovering the tools
before it can address the IDE at all — measured on all five round-3 mcp captures. At `B = 5`, charging
for that would hand the shell arm a 60 % larger effective budget and the experiment would be measuring
the CLI's plumbing.

## The matrix and what it costs

One **research cell** = one Opus run (no reactor install, no grading): ≈ 45–60 min of machine time,
≈ $1.0–1.5 of API.
One **downstream cell** = one Haiku run plus the Keycloak reactor install and the scoped graded build:
≈ 1.5 h of machine time, ≈ $0.5 of API.
`:test-integration` / `:test-experiments` cells **must not run concurrently** — each is a full IntelliJ
container.

### Phase 0 — calibration (must happen first)

| condition | n | machine | API |
|:---|--:|--:|--:|
| `baseline` | 3 | 4.5 h | $1.5 |
| `oracle:gold` | 3 | 4.5 h | $1.5 |

Decision rule, fixed in advance:

- baseline 3/3 → **the task is too easy**, pick another (do not run the matrix);
- oracle 0/3 → **the weak agent cannot do it even with perfect understanding**, pick another;
- baseline ≤ 1/3 and oracle ≥ 2/3 → the band is right, proceed.

The three baseline runs are reusable as three of the five baseline replicates of phase 1.

### Phase 1 — the minimal informative matrix

Research: 2 arms × budgets {5, 10} × limits {1 000, 5 000} × 1 replicate = **8 notes**.
Downstream: 8 note conditions + baseline, n = 5 → **45 cells** (of which 3 baselines already exist).

| block | cells | machine | API |
|:---|--:|--:|--:|
| research | 8 | ≈ 7 h | ≈ $10 |
| downstream | 42 | ≈ 63 h | ≈ $21 |
| **total (incl. phase 0)** | **59** | **≈ 79 h** | **≈ $34** |

### Phase 2 — only if phase 1 separates the arms

Add budget 20 (2 research + 10 downstream cells, ≈ 17 h) and a second research replicate per cell to
measure note-to-note variance within an arm (8 research + 40 downstream, ≈ 67 h).

The cheaper opening, if 79 h is too much: drop the 1 000-character condition (4 notes, 20 downstream
cells → ≈ 39 h total). It costs the "can it distil?" question and keeps the "does it know more?" one.

## Pre-registered analysis

- Primary: `P(success)` per condition, with Wilson intervals; the curve `research budget → downstream
  success`, one line per arm, with the baseline as a horizontal reference.
- Secondary: the same successes against **research output tokens** instead of calls. If the arms
  separate on calls and not on tokens, that is the headline, not a failure.
- Tertiary, and explicitly not primary: a blind judge scores the notes (factual correctness,
  completeness, actionable specificity, architecture understanding, hallucinations) without seeing the
  arm.

### What counts as a refutation

- `P(success | K_mcp) ≈ P(success | K_shell)` at every budget → the better-understanding hypothesis is
  not supported for this task, and the track closes for it.
- Neither note beats the baseline → the task or the note format does not measure the mechanism.
- The oracle note does not beat the baseline → the downstream agent is too weak; the result says
  nothing about the arms.

### What invalidates a cell rather than scoring it

A dirty tree after a research run; a research run that spent zero budgeted interactions; an oracle patch
that does not apply; an API transport abort; an ungraded verification. Each is printed as `LOST` with
its reason and excluded from the denominator — never folded in as a zero.

## Runbook

```bash
# one research cell (Opus, MCP arm, budget 10, 5 000-character note)
./gradlew :test-experiments:test --tests '*UnderstandingResearchTest*' \
  -Dunderstanding.case=understanding__keycloak__email-domain-mapper \
  -Dunderstanding.arm=mcp -Dunderstanding.budget=10 \
  -Dunderstanding.noteLimit=5000 -Dunderstanding.replicate=1

# commit the note the run published, so downstream cells can be queued against it
cp <runDir>/mcp-b10-l5000-r1.md \
  test-experiments/src/test/resources/understanding-notes/understanding__keycloak__email-domain-mapper/

# one downstream cell (Haiku; condition is baseline | oracle:<name> | <noteId>)
./gradlew :test-experiments:test --tests '*UnderstandingDownstreamTest*' \
  -Dunderstanding.case=understanding__keycloak__email-domain-mapper \
  -Dunderstanding.condition=mcp-b10-l5000-r1 -Dunderstanding.replicate=1
```

Every property also has an environment-variable spelling (`UNDERSTANDING_ARM`, …) because TeamCity's
Gradle runner does not put `system.*` parameters on the command line for these builds — the same trap
that made an earlier wave measure the default model.

The calibration note is already written: `oracle-gold.md`, 4.1 kB, under the case's note directory. It
is gold-derived on purpose — it names both registrations, including the one in the protocol factory —
so `oracle:gold` measures the ceiling of what any note could buy on this task.

`UnderstandingResearchTest` and `UnderstandingDownstreamTest` fail with a readable message when their
coordinates are absent, exactly as `RippleCheckpointProbeTest.probe()` does. That is intended: a cell
build must never quietly run a default cell. Neither is reached by an ordinary build — `:test-experiments:test`
is guarded by its `onlyIf` — so the cheap, always-runnable part of this work is

```bash
./gradlew :test-experiments:test --tests '*UnderstandingHarnessTest*' --tests '*UnderstandingCaseRegistryTest*'
```

which is 28 tests and takes seconds.

Nothing here is queued on TeamCity without an explicit go-ahead.
