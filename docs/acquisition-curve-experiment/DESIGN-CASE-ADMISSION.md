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
| 2 | 24 notes + 12 calibration, ≈$8 | looked usable — it produced ρ(U, obligations) = +0.67 after the oracle was rebuilt from a cascade (`{0} ∪ {5..8}`) into nine independent axes — but **twelve of its thirty cells also scored below that oracle's own floor**, so the same collapse was present and merely quieter; the correlation is withdrawn pending re-measurement ([RESULTS-DOWNSTREAM-2-RECHECK.md](RESULTS-DOWNSTREAM-2-RECHECK.md)) |
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

## Where each case stood when this was written

Superseded by the section below; kept because it is what the protocol was written against. Every one
of the three gradable cases was **blocked**, which was the honest reading of the evidence rather than
a new obstacle: all of them had been calibrated under the instrument that has since been repaired.

| case | ladder | gold-note rollouts | baselines | verdict |
|---|---|---|---|---|
| `cc-refresh-token` | 3 rungs declared, 0 measured; the invariant rung is not exported | 2, both compile-status unknown | 4, all read 0 — *below* the pristine floor of 1, so they either broke the profiles or never built, and nobody recorded which | blocked by 12 |
| `client-auth-method` | 2 rungs declared, 0 measured | 3, of which 2 did not compile and 1 is unknown | 2, unknown | blocked by 8 |
| `oauth-grant-type` | 2 rungs declared, 0 measured; the invariant rung is not exported | 2, both unknown | 2, unknown | blocked by 9 |

### Where each case stands after the ladders and the anchors (2026-08-24)

Ten ladder cells and fifteen agent anchors later, with every reading carrying a compile verdict. The
gate prints the full list on every green run of `AcquisitionAdmissionTest`; readings in
[RESULTS-DOWNSTREAM-4-ANCHORS.md](RESULTS-DOWNSTREAM-4-ANCHORS.md).

| case | ladder | gold-note rollouts | baselines | verdict |
|---|---|---|---|---|
| `cc-refresh-token` | measured, rungs separate by axis name | 3, **none compiled** | 2, one built and read 5 of 9 | **retired** — both stopping rules fired |
| `client-auth-method` | measured, rungs separate by axis name | 3, two at 9 of 9, one did not compile | 2, one built and read 6 of 9 | blocked by 4 — the measured gap is 3 of 9 |
| `oauth-grant-type` | measured, rungs separate by axis name | 3, two at 10 of 10, one did not compile | 2, **neither built** | blocked by 2, both for missing readings |

`oauth-grant-type` is the only case whose blocks are both about readings that were never taken rather
than readings that came back wrong.

Note what the `cc-refresh-token` baselines say. The oracle's floor is 1 by construction, and all four
no-note anchors read 0 — a tree that compiled cannot score below an untouched one. Those four readings
were the "floor" of the round that produced ρ = +0.67.

**Followed up, 2026-08-24, before any cell of the fourth round was read.** The same test applied to
every cell of that round finds **twelve of thirty** below the floor, eight of them note cells. Split
that way, the wave says: ρ(`U`, *the tree built*) = **+0.660** (the same magnitude as the published
headline), and among the cells that demonstrably built, ρ(`U`, obligations) = **−0.38**. So the
published estimate is confounded with compilation, and the subset estimate is contaminated by
selection on a collider — neither is the answer. The verdict "`U` is functionally valid" is therefore
**withdrawn pending re-measurement under this protocol**, not replaced by its negation. Details and
the reproduction script: [RESULTS-DOWNSTREAM-2-RECHECK.md](RESULTS-DOWNSTREAM-2-RECHECK.md).

This is also the sharpest argument for the six requirements: the defect was visible in the published
CSV the whole time, and what was missing was not data but a rule that made anyone look.

## Amendment 1 — a rung is identified by WHICH obligations it loses (2026-08-24)

Written after the first ladder cells returned and **before** any note cell of the fourth round; it
makes the rule stricter, which is the only direction an amendment may move once data exists.

Requirement 2 originally said two partial trees must land on **different counts**. The first ladder
of `cc-refresh-token` measured `[7, 8, 8]` and the rule fired — wrongly. Its two one-point rungs lose
*different* obligations: `implementation-and-spi` loses the shipped-profile entry, `naive-partial-update`
loses the partial-update invariant. Two independent axes priced the same is the strongest evidence of
a real scale a ladder can produce, and comparing counts threw it away.

So the comparison moves from the number to the **set of failing test methods**:

- `AcquisitionPartialRung.losesAxes` declares the axes a rung is predicted to fail, before it runs;
- the ladder cell reads the actual ones out of the surefire report (`SurefireClassResult.failedMethods`)
  and prints them as `loses=…`;
- a rung whose prediction and reading name different obligations blocks the wave on its own;
- two rungs losing the **same** set are one rung wearing two names — the original cascade check,
  now in the units it always meant.

Strictly stronger than what it replaces: a cascading oracle can produce two different totals (it did —
`{0} ∪ {5..8}`), but it cannot produce two disjoint failure sets. The cost is that every rung already
measured owes a re-measurement, which is container minutes and **no model tokens**.

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

## Amendment 2 — a floor is two trees that BUILT (2026-08-24)

Written after the fifteen admission anchors returned and before any note cell of the fourth round.
Strictly stronger, like amendment 1.

Requirement 6 made compilation its own reading, which made the unmeasured cells *visible*. It did not
stop them from counting. Every threshold in `problems` reads obligation **counts**, an unmeasured
cell's count is null, and null is skipped — so `oauth-grant-type`, whose two no-note cells both failed
`javac`, satisfied "at least two baselines" and passed "no more than one above the floor" vacuously.
It read as having a floor it had never measured, which is the round-2 defect wearing the round-4
repair.

`baselineProblems` now requires `MIN_BASELINE_ROLLOUTS` baselines that **demonstrably built**. It
changes no tunable threshold.

## `cc-refresh-token` has left the downstream family (2026-08-24)

Both stopping rules fired on the same case, on the same anchor wave:

- the gold note produced **no gradable tree in three of three** rollouts (1040174097/099/101), so the
  case measures implementation difficulty rather than understanding;
- the one no-note tree that built reads **5 of 9** (1040174118) — four obligations above the pristine
  floor — so the unaided solver already holds most of what a note could buy.

This is recorded as `AcquisitionCaseAdmission.retiredFromDownstream` rather than as prose, because a
retirement is not a work item: every other entry of `problems` names a cell somebody can queue, and a
reader working that list must not be able to empty this one.

**The acquisition curve of `cc-refresh-token` is unaffected and stays published.** `U(B)` is a
property of the trajectory and the checklist; it never depended on the oracle. What retires is only
the case's role in the downstream half.

Note which case this was: it produced the ρ = +0.67 headline of round 2, already withdrawn in
[RESULTS-DOWNSTREAM-2-RECHECK.md](RESULTS-DOWNSTREAM-2-RECHECK.md). The anchors now say the wave was
run on a case whose floor and ceiling are both unmeasurable by this instrument.

## The coupling the anchors exposed, which no admission rule yet covers

The four gold-note cells that failed `testCompile` failed on a unit test **the solving agent wrote
itself**, after its implementation had already compiled clean. The mechanism is in
[RESULTS-DOWNSTREAM-4-ANCHORS.md](RESULTS-DOWNSTREAM-4-ANCHORS.md); what matters for the protocol is
that it runs through the treatment:

- the allowance prices reads and builds and leaves edits free, so an agent that located the
  architecture cheaply — the thing a good note buys — arrives at "done" with interactions to spare
  and spends them on verification it cannot finish;
- on `cc-refresh-token` the checklist itself carries axis `I1` (`VERIFICATION`), which names the
  sibling test to imitate. `I1` is one of the axes `U` is computed over, so a higher-`U` note is a
  note that more surely instructs the agent to start the test that costs it the cell.

No requirement here catches that, and none is added by this amendment: a fix chosen now would be
chosen after seeing which direction it helps. It is recorded as an open defect of the instrument, to
be pre-registered on its own before the anchors are re-bought.

## Amendment 3 — the solver is graded on its change, not on the test it wrote itself (2026-08-24)

Written after the fifteen anchors were read and the mechanism was traced
([RESULTS-DOWNSTREAM-4-ANCHORS.md](RESULTS-DOWNSTREAM-4-ANCHORS.md)), and **before** the ten
re-bought anchors it licenses. It carries a falsifiable prediction, below, for the same reason the
ladder carries one: a repair whose outcome was not written down first is indistinguishable from a
repair chosen because of its outcome.

### The rule

Before the oracle patch is applied, every file the solver **added** under a `src/test/` directory is
discarded, and the count is printed. Files the solver *modified* are left alone.

The asymmetry is the whole rule. A shipped test the solver broke is evidence about the solver's
change and must keep failing the cell — that is what the oracles' "did not break anything" axes are
for. A test the solver invented is not part of the change being graded, is graded by nothing, and
under the current instrument can only subtract.

### Why this and not the alternatives

- **Narrowing what the grading build compiles** does not reach it. The build compiles a module, and
  the agent's file is in that module.
- **Telling the agent not to write tests** changes the treatment rather than the measurement, cannot
  be applied to trajectories already bought, and is advice an agent may decline — after which the
  artifact returns with no symptom.
- **Accepting it** is defensible, but then the endpoint is "did the change land AND did the agent's
  own test compile", and the note is being credited or debited for the second half. Since the second
  half is what the allowance makes expensive, that endpoint measures the allowance.

### What it does NOT repair

The coupling described in the anchors document runs through the interaction budget, and the budget is
spent before grading starts. This rule recovers the *reading*; it does not stop an agent from
spending five to ten of its twenty interactions on a test. It is enough here only because in all four
observed cells the implementation was already complete when the test began — `default-compile`
succeeded in every one of them.

The other half of the coupling — checklist axis `I1` of `cc-refresh-token`, a `VERIFICATION` fact
that instructs the agent to write the test and names the file to imitate, while being one of the axes
`U` is computed over — is **not** repaired here either. It leaves with its case: `cc-refresh-token` is
retired, and it is the only case carrying such an axis. Should a future case want one, the checklist
and the endpoint have to be reconciled before it is written, not after.

### The prediction

Ten anchors re-bought on the two surviving cases, same conditions, same allowance. Written before
they are queued:

| case | predicted | and therefore |
|---|---|---|
| `client-auth-method` | 3 of 3 gold rollouts compile and read 9/9 — the single failure was a scratch test | the case stays **blocked**: the baseline that builds reads 6 of 9, so the measured gap stays 3 of a 9-point scale, under half |
| `oauth-grant-type` | 3 of 3 gold rollouts compile and read 10/10 — its single failure was a scratch test too | but both baselines failed on their **own implementation**, not on a test, so this rule does not touch them; if they fail again the case has no floor and **retires** |

Note what this predicts: the amendment rescues neither case. It is bought because the readings are
wrong, not because they are unfavourable — and if either case is admitted after it, that is the
prediction failing, which is a finding of its own.

## The unbudgeted floor probe (2026-08-24) — pre-registered before its cells were queued

After step 3 both surviving cases have a ceiling their gold note reaches every time and **no measured
floor**: seven of eight no-note cells never produced a gradable tree. Amendment 2 blocks them on that,
correctly, and is not relaxed.

This probe tests a different definition of the floor, proposed rather than assumed: **the unaided
agent with no wall at all.** If the interaction allowance is what stops the no-note arm, removing it
says so; and an agent that may iterate until its code builds cannot fail for the reason that has
wrecked every previous reading.

`understanding.budget=none` runs a cell with no allowance and no gate. It is NOT a fourth member of
the pre-registered set {15, 20, 25} — those are candidate settings for the wave, this is a probe — and
a cell run this way prints no `budget=` column, so it can never be read as a wave cell.

Ten cells: five `baseline`, unbudgeted, on each of `client-auth-method` and `oauth-grant-type`.
No note, bare `claude-haiku-4-5`, amendment 3 in force.

### What the prior evidence is worth

Round 1 ran its whole downstream unbudgeted and produced the only two readings that exist: **7 of 8
and 0 of 8**. All three of the following are true of them, and each alone disqualifies the pair:

- they are on `cc-refresh-token`, retired in step 2;
- the oracle was the cascade later found to be one assertion wearing eight names, so "7 of 8" does not
  name seven independent things;
- there was no compile verdict, so "0 of 8" may be a build failure rather than a reading.

So the question has **no usable prior answer**, and the recollection that an unbudgeted agent "solves
these cases most of the time" is not supported by anything on record.

### The two branches, decided in advance

| if the unbudgeted no-note agent | then |
|---|---|
| **reaches at or near the ceiling** | this floor definition is unusable — there is no room above it for a note to buy anything. The consequence is NOT that the hypothesis fails: it is that the endpoint is **work, not success**. The note would then buy interactions rather than capability, which is exactly what the acquisition side already measures, and the downstream claim has to be restated in those units before any note cell is bought. |
| **lands well below the ceiling** | it is the better floor, and it retires the whole compile-failure problem: an agent that may iterate until its code builds either builds it or demonstrably cannot. The wave then runs against this floor. |

Either way the numbers of this probe are reported, including if they are inconvenient for both
branches. What is NOT licensed by this pre-registration is picking the branch after seeing the
readings — the mapping above is fixed.
