# Round 2, re-read after compilation became its own measurement

Written 2026-08-24, after the case-admission protocol
([DESIGN-CASE-ADMISSION.md](DESIGN-CASE-ADMISSION.md)) separated "the tree did not build" from "the
obligations were not met". It re-reads the **already published** wave of
[RESULTS-DOWNSTREAM-2.md](RESULTS-DOWNSTREAM-2.md) — no cell was bought, no note changed, no
trajectory was re-run. `analysis/downstream2_validation.py` is untouched; the re-reading lives beside
it in `analysis/downstream2_recheck.py` and reproduces its published estimates before decomposing
them.

## The status this changes

> **Was:** `U` is a functionally valid measure of actionable repository understanding, on this case,
> for this weak solving agent, under a bounded interaction budget.

> **Is:** the round-2 result is **potentially confounded by compilation failure**. The functional
> validity of `U` is **not established and not refuted** — it requires re-measurement under the
> protocol that records compilation as its own reading.

The acquisition result is **not** affected: `U(B)` and the interaction-efficiency finding never
depended on the oracle. See [RESULTS.md](RESULTS.md) and
[RESULTS-GENERALIZATION.md](RESULTS-GENERALIZATION.md).

## Why the re-read was forced

The round-2 oracle (`oracle-v2.patch`) carries axis A4, `noOtherShippedProfileGainsIt`, which an
**untouched** tree satisfies. Its floor is therefore **1**, by construction and by measurement (the
five-tree scale 1 → 7 → 8 → 8 → 9). A tree that compiles cannot score below an untouched tree.

Twelve of the wave's thirty cells report **0**:

| group | cells below the floor |
|---|---|
| anchors (`baseline` ×4, `oracle:gold` ×2) | 4 of 6 |
| note cells | 8 of 24 |
| all | **12 of 30** |

At the time there was no column that could say why. Round 3 then established that this is not a
hypothetical failure mode: twelve of its cells scored 0 of 10 and **not one had failed an assertion**
— every one failed `javac`, eight of them on a constant in `core` the grading build never recompiled.
Round 2 ran on the same instrument.

## The decomposition

All three readings come from `downstream2_recheck.py` over the unchanged
[data/downstream2-cells.csv](data/downstream2-cells.csv). Reading 1 collapses the two rollouts of a
note to their mean through round 2's own `by_note`, which is why it reproduces the published numbers
exactly.

### 1. The published reading, reproduced (zeros read as obligations)

| quantity | ρ | n | cluster p |
|---|---|---|---|
| ρ(`U_obs`, obligations) | **+0.668** | 12 notes | .0417 |
| ρ(`U_note`, obligations) | **+0.825** | 12 notes | .0417 |
| ρ(`U_obs`, tool calls) | −0.543 | 12 notes | .1667 |
| ρ(`U_obs`, output tokens) | −0.459 | 12 notes | .1250 |

### 2. Compilation as its own endpoint

Here the outcome is the binary "did this cell's tree demonstrably build", i.e. did it score at or
above the pristine floor.

| quantity | ρ | n | cluster p |
|---|---|---|---|
| ρ(`U_obs`, the tree built) | **+0.660** | 24 cells | .0417 |
| ρ(`U_note`, the tree built) | **+0.719** | 24 cells | .0417 |

| notes at | cells below the floor |
|---|---|
| `U_obs` < 0.4 | **70 %** of 10 |
| `U_obs` ≥ 0.6 | **8 %** of 12 |

The published correlation and this one are the same size. That is the whole finding: what the wave
measured with confidence is that a higher-`U` note makes the weak agent *more likely to leave a
compiling tree behind*, which is a real and interesting outcome — but it is not the claim that was
published, and it collapses N independent architectural axes into one bit.

### 3. Obligations among the cells that demonstrably built

| quantity | ρ | n |
|---|---|---|
| ρ(`U_obs`, obligations) | **−0.361** | 16 cells |
| ρ(`U_note`, obligations) | −0.121 | 16 cells |
| ρ(`U_obs`, obligations), by note | **−0.381** | 10 notes |
| ρ(`U_note`, obligations), by note | −0.095 | 10 notes |
| ρ(`U_obs`, tool calls) | +0.037 | 16 cells |
| ρ(`U_obs`, output tokens) | +0.018 | 16 cells |

No cluster p is reported for this subset: after dropping the unreadable cells the trajectories hold
2, 3, 5 and 6 cells, and the whole-trajectory permutation the design licenses needs equal groups.
Inventing values for the dropped cells to restore the balance would be the repair that produces the
result.

## What this does NOT establish

Reading 3 is **not** the corrected estimate, and this document does not claim the effect is negative.
Conditioning on "the tree built" conditions on a **collider**: compilation is plausibly downstream of
both the note's quality and the agent's luck, so selecting on it can induce a negative association
between them out of nothing. Reading 1 is an upper bound on the functional-validity claim; reading 3
is a lower bound contaminated by selection. The wave has no column that separates them, and no
re-analysis of it can manufacture one.

Two further readings that would have been decisive are also unavailable retroactively: whether a
zero-scoring cell failed `javac` or broke the shipped profiles, and how far from compiling it was.
Both are recorded from now on — `ArenaVerificationResult.compiled` is derived from the compiler's own
diagnostics, and `oracleAssertionsPassed` returns `null` rather than `0` for a tree that never built.

## What it takes to settle it

The fourth round, under the admission protocol, and in this order:

1. **The ladder**, per case: at least two deliberately partial trees landing on *different* counts
   strictly between the floor and the ceiling. Queued as `ladder:<rung>` — no agent, no model tokens.
2. **Anchors that carry a compile verdict**: ≥ 3 gold-note rollouts, all compiled, all ≥ 80 % of the
   scale, and ≥ 2 baselines no more than one obligation above the floor.
3. **Only then** the note cells, which now report `oraclePassed=unmeasured/N … compiled=0` instead of
   a zero nobody can interpret — so `U → compiles` and `U → obligations` are two columns, not one.

Until step 3 has been run on at least one case, the honest statement of this project's chain is:

> semantic access reaches a given level of the architecture checklist in fewer environment
> interactions (established, three cases, replicated) **→ [link under re-measurement]** → a downstream
> agent finishes more of the change with less work.
