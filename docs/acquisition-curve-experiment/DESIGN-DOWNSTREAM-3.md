# Downstream replication on the two generalization cases — pre-registration

Written before a single cell of this wave was queued, and before either new hidden oracle existed in a
runnable form. It reuses `DESIGN-DOWNSTREAM-2.md` wholesale: same solving agent, same hard interaction
budget, same "independent obligations" outcome, same clustered analysis. What is new is only WHERE the
question is asked.

## Why this wave exists

The evidence package currently has two halves that do not touch:

- **Acquisition.** On four cases, semantic access reaches a given level of the pre-registered
  architecture checklist in fewer environment interactions — 1.89× at B=5, closing to 1.02× at B=40
  (`RESULTS-GENERALIZATION.md`).
- **Function.** On ONE case, a higher `U` leaves the weak solver measurably less work:
  ρ(U_obs, obligations met) = +0.668, ρ(U_note, obligations met) = +0.825, and effort falls on all three
  denominators (`RESULTS-DOWNSTREAM-2.md`).

The join is asserted, not measured: the case where `U` was shown to be functionally valid is not one of
the cases where the acquisition advantage was replicated. This wave measures the join on both new cases,
`client-auth-method` (middling advantage, +0.18 at B=10) and `oauth-grant-type` (the round's largest,
+0.38 at B=10).

**This wave does not ask which arm is better.** Arm is recorded and read as a tertiary line only. The
question is whether the metric means what the acquisition round assumes it means.

## What is bought, and what is not

Nothing about research is re-bought. The twelve research trajectories of these two cases are already
paid for; their transcripts are committed under
`data/trajectories/<caseId>/<trajectoryId>/transcript.ndjson.gz` and every note of this wave is distilled
offline from a PREFIX of one of them, by the same slicing that scored `U`. A note therefore contains
only what the research agent had actually seen by interaction B — no gold knowledge, no oracle, no
hindsight.

## The matrix, fixed in code

`ACQUISITION_DOWNSTREAM_MATRICES` in `AcquisitionDownstream.kt`. Per case: two trajectories per arm,
three checkpoints each (B = 5, 10, 20), one rollout per note.

| case | trajectories | `U` at 5 / 10 / 20 |
|---|---|---|
| `client-auth-method` | mcp r1 | .47 / .60 / .80 |
| | mcp r2 | .53 / .53 / .53 |
| | shell r1 | .00 / .27 / .40 |
| | shell r3 | .27 / .47 / .47 |
| `oauth-grant-type` | mcp r1 | .53 / .73 / .73 |
| | mcp r2 | .47 / .67 / .73 |
| | shell r1 | .20 / .40 / .60 |
| | shell r2 | .00 / .27 / .53 |

The selection rule is `DESIGN-DOWNSTREAM.md`'s: the arms must OVERLAP in `U`, or "the note was better"
and "the note came from the other arm" are one column. They meet at .47/.53 on the first case and at
.53/.60 on the second.

`client-auth-method` mcp r2 is in the matrix because its curve is FLAT — three prefixes of different
lengths carrying the same `U`. If the solver's outcome tracked the research budget rather than the
understanding, those three cells would separate, and no other cell in the design would show it.

Plus four anchors per case: `baseline` ×2 (no note) and `oracle:gold` ×2 (a hand-written note derived
from the gold change, committed at
`test-experiments/src/test/resources/understanding-notes/<caseId>/oracle-gold.md`).

**32 cells total** — 24 notes + 8 anchors — at ≈ $0.3 each under the interaction budget.

## The instrument, unchanged from round 2

- **Solver**: `claude-haiku-4-5`, shell only, pristine tree, no IDE in any condition.
- **Budget**: 20 repository interactions. Reads and builds are charged, file edits are free and keep
  working after the wall, and the price is stated in the brief. Without it, round 1's floor anchor
  reached 7 of 8 with no note at all in 89 interactions, and the notes had nothing to be measured
  against.
- **Outcome**: how many of the case's INDEPENDENT oracle assertions pass, counted against the case's
  declared total rather than against what the run happened to execute (a module that did not compile is
  0 of N, never "0 of 0").
- **Effort**: interactions used, output tokens over all models of the run, wall time, dollars.

## Gates, decided before the note cells are bought

The eight anchor cells run FIRST and are read against three gates. This is the same order round 2 used,
and it is what caught a defect in the oracle itself before twelve note cells were spent on it.

- **G1 — the floor is a floor.** Mean `baseline` ≤ 2 of N obligations. A no-note agent that already
  passes most of the oracle leaves nothing for a note to buy.
- **G2 — the ceiling is reachable.** Mean `oracle:gold` ≥ N − 2, AND at least 4 obligations above the
  baseline mean. A task the solver cannot do even when handed the architecture measures implementation
  difficulty, not understanding.
- **G3 — every anchor produced a reading.** No cell may end without a graded module.

Failure rules, written now so they cannot be chosen later:

- G1 fails → re-run the anchors at budget 15 (the pre-registered neighbour). One retry only.
- G2 fails → re-run the anchors at budget 25. One retry only.
- Both fail, or a retry fails → the case is dropped from this wave and reported as not gradable at any
  pre-registered budget. Its acquisition curve stands; its functional link stays unmeasured.

## Analysis

The unit is the NOTE (trajectory × checkpoint), never the rollout. Pooled over both cases, with ranks
computed within case so that a case-level difficulty difference cannot manufacture a correlation:

- **Primary**: Spearman ρ(`U_obs`, obligations met). Pre-registered prediction ρ > 0, with the round-2
  point estimate (+0.67) as the prior. Reported with a 90% interval from a cluster bootstrap over
  trajectories (three notes of one trajectory are not three independent observations).
- **Secondary**: ρ(`U_obs`, interactions used), ρ(`U_obs`, output tokens) — both predicted negative;
  ρ(`U_note`, obligations met), where `U_note` is the blind judge's score of the note alone, predicted
  to be the stronger of the two (it was in round 2: +0.825 against +0.668).
- **Tertiary**: the same table split by arm, reported for completeness and not tested.

**Falsifiers.** Pooled ρ ≤ 0 on the primary; or a ρ that is positive only because the anchors are in the
table (the primary excludes anchors by construction); or a per-case ρ that changes sign between the two
cases, which would say the metric is case-specific rather than a property of understanding.

## What a positive result would license, and what it would not

It would let the two halves be stated as one chain, on three cases: semantic access reaches a given
architectural model in fewer interactions → that model, written down, leaves a weak solver measurably
less work. It would still NOT license "the semantic arm produces better notes" — that claim was tested
in the note-bottleneck round and failed (p = 0.4), and nothing in this design revives it. The arm buys
the model EARLIER; the model is what pays downstream.
