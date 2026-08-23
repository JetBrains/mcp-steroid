# Downstream validation of `U` — pre-registration

Written and committed **before** a single downstream cell was queued and before any note was
distilled. The acquisition curves it validates are in [RESULTS.md](RESULTS.md); the instrument that
produced them is pre-registered in [DESIGN.md](DESIGN.md).

## The question

`U(B)` is the share of a fifteen-fact architecture checklist whose evidence appears in the tool
results of a research trajectory's first `B` environment interactions. The pilot showed the semantic
arm reaching a given `U` in fewer interactions than the control arm. That result is only worth
anything if `U` measures something real, and nothing so far rules out the deflationary reading:

> `U` is a score on a checklist we wrote. An agent can maximise it and still hand over a note that
> helps nobody.

This round tests the causal link that would rule that out:

**Higher `U` at a checkpoint ⇒ a note distilled from that checkpoint carries a weak agent further
through the same task.**

The arm is **not** the question here. Every note enters the same analysis regardless of which arm
produced it, and the arm is read afterwards, as a secondary line.

## What is held fixed

| | |
|---|---|
| Case | `acquisition__keycloak__cc-refresh-token`, pinned Keycloak at `60c4d5e` |
| Downstream agent | the weak model (`RippleCheckpointProbeTest.PROBE_MODEL`), identical in every cell |
| Downstream environment | pristine tree, **shell only**, no IDE, no MCP, in every cell including the ones whose note came from the semantic arm |
| Downstream prompt | `buildUnderstandingDownstreamPrompt` — the same string in every cell, differing by the inserted note block and nothing else |
| Oracle | the same eight hidden assertions, applied only **after** the agent finishes |

The downstream agent is never told which arm the note came from, what `B` was, or that a comparison
exists. The distiller's brief forbids the note to mention a tool, a command or a search, so a note
carries no trace of its arm.

## How a note is built

One note per (trajectory, checkpoint). It is a read-out of a knowledge state, produced offline:

1. `AcquisitionRecomputeTest` slices the committed transcript at `B` — the *same* slicing that scored
   `U`, not a second implementation of it — and writes `distill-b<B>.txt`.
2. `analysis/distill_and_judge.py` sends that prompt to the strong model **with no tools and no
   repository**, under a 2 000-character limit.
3. The note is committed to `test-experiments/src/test/resources/acquisition-notes/<caseId>/`.

The distiller sees only the prefix. It is told, in the prompt that already exists in
`buildAcquisitionDistillPrompt`, that early records are thin and that a confident sentence the record
does not support is worse than a gap. **No gold, no oracle, no solution knowledge enters a note.**

## The matrix — 16 cells, fixed here

Four trajectories out of the eight admitted, at three checkpoints each, plus four calibration
anchors. Selection rule, applied before any cell was run: **two trajectories per arm, chosen to
maximise the spread of `U_obs` over the resulting twelve notes, subject to the two arms overlapping in
`U` at two checkpoints** — the overlap is what separates "the note was better" from "the tool was
different".

| # | condition | trajectory | B | `U_obs` |
|---:|---|---|---:|---:|
| 1 | `checkpoint:mcp-b40-l2000-r2@5` | mcp r2 | 5 | .20 |
| 2 | `checkpoint:mcp-b40-l2000-r2@10` | mcp r2 | 10 | .80 |
| 3 | `checkpoint:mcp-b40-l2000-r2@20` | mcp r2 | 20 | .87 |
| 4 | `checkpoint:mcp-b40-l2000-r3@5` | mcp r3 | 5 | .53 |
| 5 | `checkpoint:mcp-b40-l2000-r3@10` | mcp r3 | 10 | .60 |
| 6 | `checkpoint:mcp-b40-l2000-r3@20` | mcp r3 | 20 | .73 |
| 7 | `checkpoint:none-b40-l2000-r1@5` | shell r1 | 5 | .20 |
| 8 | `checkpoint:none-b40-l2000-r1@10` | shell r1 | 10 | .60 |
| 9 | `checkpoint:none-b40-l2000-r1@20` | shell r1 | 20 | .73 |
| 10 | `checkpoint:none-b40-l2000-r3@5` | shell r3 | 5 | .13 |
| 11 | `checkpoint:none-b40-l2000-r3@10` | shell r3 | 10 | .20 |
| 12 | `checkpoint:none-b40-l2000-r3@20` | shell r3 | 20 | .27 |
| 13–14 | `baseline` × 2 | — | — | — |
| 15–16 | `oracle:gold` × 2 | — | — | — |

The twelve `U` values span .13 to .87, and the two arms meet exactly at .60 and .73 (cells 5/8 and
6/9). `mcp-b40-l2000-r4` is excluded everywhere: the harness rejected it for making no semantic call.

**One rollout per note, deliberately.** Several rollouts of one note are not independent observations
about that note's research, and buying them would spend the budget on the wrong axis. The graded
endpoint below is what replaces them.

## Endpoints

**Primary — residual work.** `oracleTestsPassed ∈ 0..8`, counted from surefire's own numbers, against
the case's eight assertions and never against what happened to run: a module that did not compile
scores 0 of 8, not 0 of 0. Chosen over pass/fail because the oracle's eight assertions cover four
different mechanisms, so the count says *how much* of the architecture the note transferred, and
twelve graded cells carry the information twelve coin flips would not.

**Secondary, all recorded per cell:** binary success, downstream tool calls, downstream output tokens,
wall-clock seconds, USD, and the arm.

## Analysis, fixed before the data

The twelve notes come from four trajectories, three each, so they are **not** twelve independent
observations. Every test below respects that.

1. **Primary.** Spearman ρ between `U_obs` and `oracleTestsPassed` over the twelve notes, with a
   **cluster permutation** p: the four outcome-triples are permuted across the four `U`-triples
   (4! = 24 arrangements, one-sided, smallest attainable p = .042). This asks whether the trajectory
   that knew more produced notes that carried the agent further, and cannot be inflated by the
   within-trajectory correlation.
2. **Within-trajectory monotonicity.** For each of the four trajectories, sign of
   `passed(B=20) − passed(B=5)`. A sign test over four trajectories (one-sided, floor p = .0625). This
   is the cleanest manipulation available: same research agent, same arm, more budget.
3. **Anchors.** Mean `oracleTestsPassed` for `baseline` and for `oracle:gold`.
4. **Secondary — arm.** The same twelve cells split by arm, and specifically the two matched pairs at
   equal `U`. Reported descriptively; four cells per arm test nothing.
5. **Secondary — `U_actionable`.** The judged score of the note itself (fifteen blind yes/no questions
   on the note alone), against the same outcome. `U_obs` is what the pilot published, so it is primary;
   `U_note` is the more proximate cause and is reported beside it.

## Verdict rules, fixed before the data

- **`U` is functionally valid** if ρ ≥ 0.5 with cluster-permutation p ≤ .05 **and** the anchors bracket
  the range: `baseline` ≤ 2/8 passed on average and `oracle:gold` ≥ 6/8.
- **`U` is not validated** if ρ ≤ 0.2, or if the anchors fail to bracket. Anchors that fail to bracket
  void the whole reading rather than weaken it: if the weak agent cannot do this task even when told
  exactly how, or can do it with no note at all, then the twelve cells measured the task's difficulty
  and not the notes.
- **Inconclusive** otherwise. An inconclusive result is reported as inconclusive; no further cells are
  bought without a new pre-registration.

Two contingencies, also fixed here:

- A cell whose oracle patch does not apply, or whose stream reports a transport error, is **LOST** and
  is re-run once at the same coordinates. It is never scored as a zero.
- If more than two of the sixteen cells are lost, the wave is reported as incomplete rather than
  analysed.

## Amendment 1 — the floor anchor broke the primary endpoint (2026-08-23, before any note cell reported)

The first calibration anchor came back and the pre-registered floor rule failed:

```
condition=baseline  Y=0  oraclePassed=7/8  residual=1  toolCalls=89  usd=0.9651  agentSeconds=372
```

The weak agent, given **no note at all**, satisfies seven of the eight assertions and fails only
`registerWithoutTheSettingIsAcceptedAndTurnedOff`. The rule above says `baseline ≤ 2/8`, so it is
violated, and the consequence it predicted holds: with the floor at 7 and the ceiling at 8, the
primary endpoint has one bit of resolution across the whole wave and cannot express a `U` gradient.

The cause is structural rather than a mistake in the case. The research agent works under a budget of
environment interactions; the downstream agent does not, and this one spent **89**. Given an unlimited
allowance on a pristine tree it simply performed the research itself. The note is therefore not what
makes this task possible for it — it is what makes it cheap.

So the endpoints are re-ranked, and the substitute is a column this document already required to be
recorded, not a new measurement invented to fit:

- **New primary — downstream effort:** `toolCalls`, with `outputTokens`, `agentSeconds` and `usd` as
  the same quantity in other currencies. Tested exactly as the old primary was: Spearman ρ between
  `U_obs` and `toolCalls` over the twelve notes, cluster-permuted over the four trajectories
  (24 arrangements, one-sided, floor p = .042). The expected sign is **negative** — more understanding
  handed over, less work left to do.
- **Demoted to secondary — `oracleTestsPassed`.** Still reported per cell. It can now only detect the
  one assertion the baseline missed, and that single invariant is worth watching precisely because it
  is the one a naive implementation breaks.
- **Verdict rules, restated:** `U` is functionally valid if ρ(`U_obs`, `toolCalls`) ≤ −0.5 with
  cluster-permutation p ≤ .05; not validated if |ρ| ≤ 0.2; inconclusive otherwise. The ceiling anchor
  keeps its role for the demoted endpoint only.

Two guards on this amendment, because re-ranking endpoints after seeing data is exactly how a null
result gets talked into a positive one:

1. It is written and committed **before any of the twelve note cells reported**, on evidence from a
   calibration anchor — which is the only thing an anchor exists for.
2. `baseline` is replicated (r2) before the wave is read. If the second baseline lands far from 7/8,
   this amendment is withdrawn and the round is reported as uncalibrated rather than re-analysed.

What this costs the round is stated plainly: the wave can no longer show that understanding makes the
task **possible**, only that it makes it **cheaper**. That is a weaker claim than the one the wave was
designed for — and it is the same claim, on the same sign, that the acquisition curves themselves make.

## Cost

24 distilled notes ≈ 0.9 M characters of prompt ≈ 226 k input tokens on the strong model — under
$5, no container. 16 downstream cells at the note round's measured rate (≈ $0.3 and ≈ 4 minutes of
agent time each, plus the container and the Keycloak reactor install) — under $10 of API budget. The
whole validation costs less than one research trajectory of the pilot it validates.

## What this cannot answer

- It cannot compare arms. Four notes per arm is not a comparison, and the round is not designed as
  one.
- It cannot separate "the note was better" from "the note was longer", except by the length column,
  which is reported.
- A positive result licenses exactly one inference: `U` tracks something a downstream agent can use.
  It says nothing about tasks other than this one, and the second case the design already calls for
  remains the way to find out.
