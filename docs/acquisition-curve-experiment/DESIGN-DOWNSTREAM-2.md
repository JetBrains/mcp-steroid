# Downstream validation of `U`, round 2 — pre-registration

Written and committed **before** a single cell of this round was queued. Round 1 is
[DESIGN-DOWNSTREAM.md](DESIGN-DOWNSTREAM.md) + [RESULTS-DOWNSTREAM.md](RESULTS-DOWNSTREAM.md); its
verdict was *negative and uninformative*, and this round exists to repair the three reasons why. The
acquisition curves being validated are unchanged, in [RESULTS.md](RESULTS.md).

No new research trajectory is bought. The notes are the **same twelve committed files** under
`test-experiments/src/test/resources/acquisition-notes/`, distilled from transcripts already paid for.

## What round 1 established, and what it could not

The question is unchanged:

> Higher `U` at a checkpoint ⇒ a note distilled from that checkpoint carries a weak agent further
> through the same task.

Round 1 answered ρ(U_obs, oracle assertions passed) = −0.22 (p = .88) and ρ(U_obs, tool calls) = +0.03.
Three defects make those numbers uninterpretable rather than negative, and each has a measurement
behind it:

| # | Defect | Evidence from round 1 |
|---|---|---|
| 1 | **The solving agent had no interaction budget.** It did not read the note, it re-derived it. | The floor anchor reached **7 of 8** assertions with *no note at all*, spending **89** interactions — against a case whose own recorded shell audit reaches four fifths of the checklist in ten good commands. |
| 2 | **The oracle was a cascade.** All eight assertions first discovered the executor through one line of profile JSON, so the scale was `{0} ∪ {5…8}` — one boolean wearing eight names. | Both compiling zeros reported `Tests run: 8, Failures: 8`. |
| 3 | **Per-cell noise equalled the whole scale, and was never measured first.** | Two runs of the *same* `baseline` condition returned 7/8 and 0/8. |

## The three repairs

1. **A hard repository-interaction budget on the solving agent.** The research phase's `PreToolUse`
   gate, reused with a different price list: every shell command, read, search or build costs one of
   `B_down`; **creating and editing files is free and stays available after the wall**
   (`UNDERSTANDING_DOWNSTREAM_BUDGET_EXEMPT_TOOLS`). The agent is told the number and the price list in
   its brief — an allowance that only announces itself by refusing the twenty-first call measures
   surprise, not understanding. The refusal message tells it to *finish the change with what it knows*,
   because an agent that believes it can no longer act abandons a half-written patch, and a cell that
   ends with three files of four measures the wall.

   Charging for the build is deliberate and is the one known cost of this design: `Bash` both greps the
   tree and compiles it, and a rule keyed on the command text is a rule an agent can phrase its way
   around. The brief therefore prices it openly ("build at most once, at the end").

2. **A de-cascaded oracle.** `oracle-v2.patch` replaces the eight cascading assertions with **nine
   independent** ones. It discovers the implementation by scanning the compiled classes of the
   `services` module for a new `ClientPolicyExecutorProvider` — never by class name, and never through
   either registration — so a tree that has the behaviour but neither registration scores clearly above
   a pristine one. The nine map one-to-one onto the independent obligations of the gold change:

   | Assertion | Independent obligation |
   |---|---|
   | A1 `implementationClassExists` | the executor exists at all |
   | A2 `factoryIsRegisteredThroughTheServiceLoader` | mechanism 1: `META-INF/services` |
   | A3 `theShippedStrictProfileListsIt` | mechanism 2: the profile JSON entry, in the right profile |
   | A4 `noOtherShippedProfileGainsIt` | blast radius: no other shipped profile changed |
   | A5 `registerWithTheSettingOnIsRejected` | behaviour on `REGISTER` |
   | A6 `registerWithoutTheSettingTurnsItOff` | the `auto-configure` branch |
   | A7 `updateThatTurnsTheSettingOnIsRejected` | behaviour on `UPDATE` |
   | A8 `partialUpdateOfAClientThatAlreadyHasItOnIsRejected` | **the invariant** a copy of the neighbouring executor misses |
   | A9 `unrelatedEventsAndCleanPartialUpdatesAreIgnored` | no over-rejection |

   A4 passes on a pristine tree by construction — it is a "did not break anything" axis, and its floor
   value of 1 is stated here so that nobody later reads `1/9` as partial progress.

   Verified before use, on five trees, all actually run on JDK 21:

   | tree | passed | failing |
   |---|---|---|
   | pristine (oracle only) | **1**/9, zero errors | everything but A4 |
   | gold's two java files only | **7**/9 | A2, A3 |
   | + the `META-INF` line | **8**/9 | A3 |
   | full gold, naive `UPDATE` branch | **8**/9 | A8 |
   | gold | **9**/9 | — |

   The scale is `1 → 7 → 8 → 8 → 9` where v1's was `{0} ∪ {5…8}`. Those five runs are the de-cascade;
   without them the repair is a claim.

3. **Variance measured before a matrix is designed around it.** Four `baseline` rollouts, not one, and
   the wave is not bought unless they agree.

## Stage 1 — calibration, six cells, fixed here

| Condition | Rollouts | Why |
|---|---|---|
| `baseline` | 4 | the floor under the budget, and the only estimate of per-cell variance |
| `oracle:gold` | 2 | the ceiling: proof the budget leaves the task solvable for a reader who knows everything |

All six at `B_down = 20` (`ACQUISITION_DOWNSTREAM_BUDGET`), the weak agent, pristine tree, shell only,
one at a time. Twenty is not a preference: it is a quarter of what the unbudgeted floor anchor spent
and twice what this case's recorded shell audit needs to reach `U = .80`.

Only three allowances may ever be used — `{15, 20, 25}`, enforced by `acquisitionDownstreamBudgetOf` —
so that the budget cannot be tuned until the notes separate.

## Endpoints, fixed before the data

**Primary (residual work).** `obligations = A1…A9 satisfied`, an integer 0…9, read off surefire's own
counters and counted against the *case's* nine, never against what happened to run: a module that did
not compile scores 0, not "0 of 0". Its floor on a compiling tree is **1**, not 0 (A4).

**Secondary, all recorded for every cell.**

- `successWithinBudget` — every assertion satisfied **and** the wall never reached (`denied == 0`).
  Solving the task after pushing against the wall eleven times is not evidence the note carried the
  agent there.
- `budgetUsed` / `budgetDenied` — how much of the allowance went, and how much more the agent still
  wanted. A wave where every cell ends with dozens of denials had its budget set too small to
  distinguish notes.
- `toolCalls`, output tokens, wall seconds, USD.

Everything lands in one greppable line per cell (`[ACQUISITION-DOWN] … oraclePassed=… budget=used/N
denied=… withinBudget=…`) and in `data/downstream2-cells.csv`.

## Gates — the wave is bought only if all four pass

Read off the six calibration cells, before any note cell is queued.

| Gate | Rule | Action if it fails |
|---|---|---|
| **G1 floor leaves room** | mean `obligations` over the 4 `baseline` cells ≤ 5, and no baseline cell ≥ 8 | budget too generous → re-calibrate at **15** |
| **G2 ceiling reachable** | both `oracle:gold` cells ≥ 7, at least one = 9 | budget too tight, or the case is not solvable by this agent → re-calibrate at **25** |
| **G3 the gap is real** | mean(`oracle:gold`) − mean(`baseline`) ≥ 3 assertions | no room for a note to matter → stop; report the case as ungradable downstream |
| **G4 noise is smaller than the gap** | sd(`baseline`) ≤ 2.5 assertions **and** ≤ half the G3 gap; no cell LOST to the harness — see amendment 2 | report the variance and the `n` it implies; do NOT buy a matrix of single rollouts |

If G1 and G2 fail together at every allowed budget, this case cannot separate a note from
self-research and the round stops there. That outcome is a real answer about the case, and it is
written here so it cannot later be presented as a null about `U`.

### Amendment 2 — calibration wave 1 discarded, and G4's second clause withdrawn

Recorded after the six cells of calibration wave 1 (builds `1039274925`–`1039274935`, `B_down = 20`)
and **before** any cell of wave 2 or any note cell. Wave 1's per-cell numbers are published in
`RUN-IDS.md` and are used for no gate.

**a. An instrument defect — which is what a calibration wave is for.** Both `oracle:gold` cells scored
8/9, losing the same assertion, and not on the merits:

```
registerWithTheSettingOnIsRejected -- ERROR!
java.lang.NullPointerException: Cannot invoke "…$Configuration.isAutoConfigure()"
  because "this.configuration" is null    at …Executor.autoConfigure   <- the AGENT's executor
```

A5, A7, A8 and A9 exercised an executor on which `setupConfiguration` had never been called — a state
the runtime never produces, because the shipped profile entry always carries
`{"auto-configure": true}` and `ClientPoliciesUtil` always configures before dispatch. The in-tree
precedent this case is built around (`ConsentRequiredExecutor`) dereferences its configuration
unconditionally too, so the assertion was punishing an implementation for following the precedent.
The behavioural axes now configure the executor exactly as the profile does. Re-verified on six trees,
the sixth being the new regression: a gold whose null-guard is deleted must still score 9/9.

The wave is re-run rather than patched up — one table, one grader.

**b. G4's "≤ 1 catastrophic baseline cell" clause is withdrawn, because it contradicts G1.** Two of
the four floor cells scored 0/9 by leaving `services` non-compiling: a weak agent given twenty
interactions and no note writes code that does not build. That is the floor being genuinely low, which
is exactly what G1 asks for, so counting it as noise makes the two gates unsatisfiable together. The
clause that carries round 1's actual lesson — "the same condition returned 7/8 and 0/8" — is the
dispersion one, and it is kept and strengthened: sd must also be at most half the anchor gap. A clause
about cells LOST to the harness (a patch conflict, a Docker failure) replaces it, because those are
measurement failures rather than outcomes.

What the amendment costs, plainly: G4 can no longer refuse a wave whose floor is bimodal for reasons
other than dispersion. Every calibration cell's `passed` value is published so a reader can apply the
original clause and see what it would have said.

## Stage 2 — the matrix, unchanged from round 1

Only if all four gates pass: the **same twelve** notes (`ACQUISITION_DOWNSTREAM_MATRIX`, four
trajectories × three checkpoints), one rollout each, at the calibrated budget. Twelve cells, ≈ $10.

The independent unit stays the **trajectory** (four clusters), not the cell. Several rollouts of one
note are not several observations about the research that produced it.

## Analysis, fixed before the data

1. **Primary estimate**: Spearman ρ(`U_obs`, `obligations`) over the twelve note cells, with a
   cluster bootstrap (resampling trajectories, not cells) for the interval. Predicted positive.
2. **Recovery fraction**, the calibrated reading, which is what the anchors are for:
   `(obligations − mean baseline) / (mean oracle:gold − mean baseline)`, per cell. It answers "how
   much of a perfect note's advantage did this knowledge state deliver", on a scale both anchors pin.
3. **Effort**: ρ(`U_obs`, `budgetUsed`) and ρ(`U_obs`, `budgetDenied`), predicted negative.
4. **Cluster permutation p** over the four trajectories is reported, with the standing caveat that its
   floor is .0417 one-sided — four clusters cannot produce a small p, and round 1 attained exactly that
   floor with an effect of +0.03 in the wrong direction. It is not the finding; the estimate is.
5. **Arm (mcp vs shell)**: descriptive only, as in round 1.

## What this round still cannot answer

- Whether `U` predicts anything on a *second* case. One task, one repository.
- Whether the ranking of notes survives a stronger solving agent. The weak agent is fixed on purpose.
- Anything about the acquisition curves themselves. A negative result here weakens the claim that `U`
  is functionally meaningful; it says nothing about the interaction-efficiency result, which is
  measured entirely upstream of any note.

## Cost

Stage 1 six cells + stage 2 twelve cells ≈ 18 agent runs. Round 1's cells averaged ≈ $0.9 unbudgeted;
budgeted cells are shorter, so ≈ $12–16 in total, plus one Docker IDE container per cell (25–35
minutes each, never two at once).
