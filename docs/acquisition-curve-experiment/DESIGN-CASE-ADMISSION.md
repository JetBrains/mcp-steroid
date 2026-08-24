# Case admission: what a case must show before a downstream wave is bought

Pre-registration, written 2026-08-24, after the third downstream round and **before** any cell of a
fourth is queued. It changes no acquisition-side design: `U(B)`, the checklist, the budget rules, the
degeneracy guard and the unit of replication (the trajectory) are untouched. What it changes is the
order of purchase — calibration first, notes second — and it makes that order machine-enforced instead
of remembered.

## Why

Three downstream rounds bought note cells and calibrated afterwards. All three lost the **wave**, and
none of them tested the hypothesis:

| round | what was bought | why it could not measure |
|---|---|---|
| 1 | 12 notes + 3 anchors, ≈$10 | the solver had no interaction budget: the no-note anchor reached 7 of 8 assertions in 89 interactions, so there was nothing left for a note to buy |
| 2 | 24 notes + 12 calibration, ≈$8 | usable — this is the round that produced ρ(U, obligations) = +0.67 — but only after the oracle was rebuilt from a cascade (`{0} ∪ {5..8}`) into nine independent axes |
| 3 | 12 notes + 10 anchors, ≈$6 | every one of the twelve scored 0 of 10 and **not one failed an assertion**: all twelve failed `javac`, eight of them on a constant in `core` that the grading build never recompiled |

Each failure was a property of the *instrument* that was knowable before the wave and was not known,
because nothing forced anyone to look. Round 3's is the sharpest: below the level of the oracle's ten
independent axes there was still one boolean, and it collapsed all ten exactly the way the cascading
oracle collapsed eight — with no symptom at all, because "no surefire report" and "the assertion ran
and failed" are the same zero in a build log.

## The six requirements

Enforced by `AcquisitionCaseAdmission.problems(case)`; a note cell calls `requireAcquisitionAdmission`
before it starts a container. The calibration conditions — `baseline`, `oracle:gold`, `ladder:<rung>` —
are deliberately **never** gated, because they are how the list gets shorter.

1. **A measured ceiling.** The gold patch, replayed through the *same* grading build the solver is
   graded by, must satisfy every obligation. Round 3's gold reached its ceiling only because it
   sidestepped a module boundary with a local literal, which is precisely what no imitation of the
   repository's own precedent could do.
2. **A ladder.** At least two deliberately partial trees, each measured, each landing on a *different*
   count strictly between the pristine floor and the ceiling. Two rungs separating is the only evidence
   that the oracle has a scale rather than a verdict wearing N names. A rung is either a subset of the
   gold patch — cut out of it by `filterPatchToPaths`, so it cannot drift from the reference
   implementation — or a hand-written patch, which is what the invariant traps have to be ("the whole
   change, but the neighbour's shortcut reused" is not a smaller gold, it is a different one).
3. **At least three weak-agent rollouts with the gold note**, all compiled, all scoring ≥ 80 % of the
   scale. Three and not two, and the number is a scar: `client-auth-method` read 9 of 9 on its first
   ceiling run and 0, 0, 0 on the next three.
4. **At least two no-note baselines**, none of them more than one obligation above the pristine floor,
   and separated from the gold-note rollouts by at least half the scale. Without a floor, a flat wave
   and an easy task are the same picture.
5. **A grading build that rebuilds the dependency closure** (`-pl <scope> -am`), so a solver that
   follows the repository's own idiom into an upstream module is graded on its solution rather than on
   the scope of the evaluation build.
6. **Compilation as its own reading.** `ArenaVerificationResult.compiled` is derived from the compiler's
   own diagnostics, and `oracleAssertionsPassed` returns **null** — unmeasured — for a tree that did not
   build. A cell publishes `oraclePassed=unmeasured/N ... compiled=0`, and every consumer has to decide
   what to do with it explicitly instead of having an average decide.

### What deliberately did NOT change

- The **floor is not assumed to be zero**. Both Keycloak oracles carry a "did not break anything" axis
  that a pristine tree satisfies, so their floor is one, and it is recorded per case.
- A rung that lands somewhere other than predicted does **not** fail its cell. It is a finding about the
  oracle, and a cell that threw would destroy the reading on the way out; the disagreement blocks the
  wave at the admission gate instead, where it can be reasoned about.
- The two thresholds that could be tuned into any result — the solver's interaction allowance and the
  set of allowances it may run under — are unchanged (20, from {15, 20, 25}).

## Where each case stands today

Every one of the three gradable cases is **blocked**, which is the honest reading of the evidence rather
than a new obstacle: all of them were calibrated under the instrument that has since been repaired. The
gate prints the full list on every green run of `AcquisitionAdmissionTest`; in summary:

| case | ladder | gold-note rollouts | baselines | verdict |
|---|---|---|---|---|
| `cc-refresh-token` | 3 rungs declared, 0 measured; the invariant rung is not exported | 2, both compile-status unknown | 4, all read 0 — *below* the pristine floor of 1, so they either broke the profiles or never built, and nobody recorded which | blocked by 12 |
| `client-auth-method` | 2 rungs declared, 0 measured | 3, of which 2 did not compile and 1 is unknown | 2, unknown | blocked by 8 |
| `oauth-grant-type` | 2 rungs declared, 0 measured; the invariant rung is not exported | 2, both unknown | 2, unknown | blocked by 9 |

Note what the `cc-refresh-token` baselines say. The oracle's floor is 1 by construction, and all four
no-note anchors read 0 — a tree that compiled cannot score below an untouched one. Those four readings
were the "floor" of the round that produced ρ = +0.67. They are not being retracted here; they are
being marked as a reading that this protocol would no longer accept without knowing whether `javac` ran.

## Cost of lifting the block

- **Ladder cells: 10** (4 + 3 + 3, one per rung including the ceiling). No agent, no model tokens — a
  container start, a reactor install, a patch and a graded build, ~25–35 min each. This is the cheapest
  evidence in the whole family and the only evidence that the scale exists.
- **Agent cells: 15** (3 gold-note + 2 baseline per case), ≈$1 each, ≈$15 total.
- Two partial patches have to be **written and exported** first: the naive-invariant tree of
  `cc-refresh-token` and the collided-shortcut tree of `oauth-grant-type`. Both were built once, by the
  oracle authors, and measured (8 and 9); neither was exported, which is why they must be re-created
  rather than re-run.

Roughly **$15 and a day of queue** to make one case measurable, against ≈$24 already spent on three
waves that could not measure anything.

## How a ladder cell is queued

The existing downstream build configuration, with a condition of a different shape — no DSL change:

```
-Dunderstanding.case=acquisition__keycloak__cc-refresh-token
-Dunderstanding.condition=ladder:implementation-and-spi
-Dunderstanding.replicate=1
```

It prints one line to copy back into `ACQUISITION_CASE_ADMISSIONS`:

```
[ACQUISITION-LADDER] case=... rung=implementation-and-spi replicate=1 measured=8/9 expected=8/9 compiled=1
```

## Stopping rules

- If a case's ladder rungs do **not** separate, the oracle is a verdict and the case leaves the
  downstream family. Its acquisition curve stays valid — `U(B)` never depended on the oracle.
- If a gold-note rollout cannot compile the change in the allowance, the case is testing implementation
  difficulty rather than understanding, and it leaves the downstream family too. Both new cases were
  designed for a hard *research* phase and were never checked against this; that is a design defect
  recorded as such, not a property of the hypothesis.
- If a baseline reaches the gold-note score, the allowance is too large — and only then may it move,
  within the pre-registered set, in the direction the earlier design fixed.
