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

## The matched floor probe at 60 (2026-08-25) — pre-registered before its cells were queued

The unbudgeted probe answered its question and raised the next one. Without a wall the solver spends
**55–96 charged interactions, mean 77** (measured from the transcripts of the ten step-4 cells), so
"no wall" and "the wave's twenty" are not two settings of one dial — they are three-and-a-half times
apart, and neither is the comparison the hypothesis needs.

The comparison it needs is **equal total cost**. A note cell is handed, for free, the research a
stronger agent (`claude-opus-5`) already paid for; a no-note cell at twenty has to do that research
itself out of the same twenty. Comparing them measures the head start, not the note.

**Sixty**, derived and not chosen:

- the research agent was allowed `ACQUISITION_RESEARCH_BUDGET` = **40**, and the shell arm spent all
  of it on `oauth-grant-type` (40, 40, 40) and most of it on `client-auth-method` (40, 25, 29);
- plus `ACQUISITION_DOWNSTREAM_BUDGET` = **20**, the solver's own allowance.

It is also below what the same solver spends when nothing stops it (mean 77), so a no-note cell at
sixty is given less than it takes unaided and more than the entire bill of the most expensive note.
A floor that still falls short at sixty falls short for a reason other than the wall.

The semantic arm's research cost is far lower — about **17** interactions on both cases — so sixty is
an *exact* control for a shell note and a **generous** one for a semantic note. That asymmetry is the
acquisition result, not an accident, and it is left visible rather than averaged into one number.

`ACQUISITION_FLOOR_PROBE_BUDGETS = {60}` is deliberately a separate set from the wave's closed
`{15, 20, 25}`. The wave's allowance stays untouchable, because it is the one parameter that could
manufacture any result the round reports. A probe allowance is not a candidate setting for anything,
and `AcquisitionDownstreamHarnessTest` pins both halves: 60 resolves, an arbitrary generous number
(45, 100) is still refused, and no probe allowance may ever appear in the wave's set.

### On shell-call batching, checked rather than assumed

The allowance charges by TOOL NAME: one `Bash` call is one interaction whatever it contains, so three
chained searches cost one. Measured across the cells bought so far:

| group | `Bash` calls per cell | of which chained | share |
|---|---|---|---|
| unbudgeted | 50.0 | 4.4 | 9 % |
| gold note @20 | 14.2 | 1.2 | 8 % |
| no note @20 | 14.5 | **0.0** | **0 %** |

The control arm never batched, so no reading in this family is inflated by it, and most of what does
get chained is `mvnw compile && grep …` or a `until ! pgrep …` wait on a background build rather than
several independent queries. Batching is also the shell arm's legitimate analogue of what one
`steroid_execute_code` call does for the semantic arm, so it is left charged as one and recorded here
rather than legislated against.

### The prediction

Ten cells, five `baseline` at sixty on each surviving case. Written before they are queued:

| case | predicted | and therefore |
|---|---|---|
| `client-auth-method` | lands near the unbudgeted 5.6/9, since 60 is close to its unaided mean of 66 charged interactions | the gap to the gold note's 9/9 stays under half the scale, and the case stays blocked |
| `oauth-grant-type` | lands somewhat below the unbudgeted 8.0/10, since 60 is well under its unaided mean of 87 | the gap to 10/10 stays under half the scale, and the case stays blocked |

As with amendment 3, the probe is bought because the readings are wrong, not because they are
unfavourable. If either case is admitted after it, the prediction failed and that is a finding.

## Two leaks in the allowance, found by auditing transcripts (2026-08-25)

Both were found by counting tool names across the committed transcripts rather than by reasoning about
the design, and one of them touches the PUBLISHED acquisition result.

### 1. A subagent bought unbounded research for one interaction — and only one arm used it

The gate is a `PreToolUse` hook on the parent agent. An `Agent` call was charged **one** interaction,
and everything the subagent then read or searched was invisible to it. Across the twenty-one committed
research trajectories:

| arm | trajectories | used a subagent |
|---|---|---|
| `mcp` (semantic) | 10 | **0** |
| `none` (shell) | 11 | **7**, one to four calls each |

The leak is one-sided and it favours the CONTROL arm. Its effect on the acquisition curve is **not**
one-directional and must not be asserted either way without measurement:

- the shell arm did more research than its counted budget says, which would make the semantic arm's
  interaction advantage **understated**;
- but `U_obs` is computed from tool results in the parent transcript, and a subagent's internal reads
  are not there, so evidence it found is invisible unless its summary happens to quote the literals —
  which would make the shell arm's `U_obs` **understated** instead, and the advantage overstated.

`U_note` — the blind judge reading the note — has no such blind spot, so the two readings of the same
trajectories are affected differently. What this costs the published curves is a question for a
re-read of the committed transcripts, not for this document.

`UNDERSTANDING_SUBAGENT_TOOLS` now refuses `Agent` and `Task` outright, in both phases. Charging N per
call was rejected: any N is a guess about how much happened inside, the amount varies per call, and a
wrong guess is indistinguishable in the table from a real difference between the arms.

### 2. Polling a background build was taxed

A cell that backgrounded a build paid an interaction per poll — for `ScheduleWakeup`, which reads
nothing, and for reading the CLI's own task-output file under
`/tmp/claude-<uid>/<slug>/<uuid>/tasks/<id>.output`. The allowance counts calls that read or query the
PROJECT, so both were charged against their own definition, and the tax fell on exactly the agents
that ran the long build the brief tells them to run.

`ScheduleWakeup` joins both exempt lists; the task-output path is exempt through a deliberately narrow
glob. A blanket "outside the project is free" was rejected because one `cp -r project /tmp` would then
buy unlimited reads.

### Why these were invisible until now

Every existing test of the gate asserted on the script's TEXT. The script exits 0 on any internal
failure — deliberately, so an instrument that cannot count never becomes one that blocks everything —
which means a mistyped `sed` or a `case` glob matching nothing does not fail, it silently lets every
call through while the table still says twenty. `UnderstandingBudgetGateShellTest` now drives the
script through a real `sh` with the payloads the CLI actually sends, and asserts exit codes and the
counter file: the subagent refusal, the free poll, the charged ordinary `/tmp` read, the wall, and
that nothing reaches stdout.

### Not fixed, and recorded as such

The research brief tells the agent its tree is checked for modification and that a run which changed
anything is discarded. **Nothing checks it.** `GitDriver` appears once in `UnderstandingRun.kt`, in
the downstream path, applying the oracle. The practical exposure to `U` is small — evidence read back
out of the agent's own scratch file was already observed when it was first read, so no new fact can be
manufactured — but a brief that asserts a guarantee the harness does not provide is a defect, and it
is left here rather than quietly repaired at the same time as the two above.

## Amendment 2 revised, and the endpoint decided (2026-08-25)

Decided by the experiment's owner after the floor probes returned, and recorded before the first note
cell of the wave was queued.

### The endpoint

**A tree that did not build satisfied no obligations.** `compiled=0` scores zero, not "unmeasured".

The reason this is now decidable, where in round 2 it was not: the same solver, given 60 interactions
or none at all, compiled **15 of 15** (steps 3 and 4). So a no-note cell that fails to build inside the
wave's twenty did not meet an impossible task — it ran out of room, and running out of room is a
failure of the work, which is what the endpoint measures.

The raw reading stays separable forever. A cell still publishes `oraclePassed=unmeasured/N …
compiled=0`, and the zero lives in one named place, `AcquisitionRolloutEvidence.endpointScore`, that a
reader can find and disagree with — rather than in an averaging convention nobody voted on.

### Amendment 2, restated

It demanded baselines that **demonstrably built**. Its reason was that in rounds 2 and 3 a floor of
zeros hid compile failures and nothing could tell "did not understand" from "did not build". That
reason is now served by a different mechanism — `compiled` is its own recorded column — so the rule
demands the **verdict**, not a successful build.

This is the first amendment in this family that does not move in the stricter direction, and it is
recorded as such. What licenses it is not that it unblocks a case: it is that the defect it was
written against is now prevented by a column that did not exist when it was written. The stricter
version would have refused a floor of four zeros *whose cause is recorded and understood*, which is
the opposite of what the protocol is for.

### Why the research budget was NOT touched

The proposal on the table was to cut the research allowance from 40 to 20 and re-run everything, to
widen the separation between floor and ceiling. Calibrating dynamic range is legitimate and this
protocol licenses it — but the research budget is the wrong lever, and the committed curves say so:

| case | arm | `U` at 20 | calls spent | `U` at 40 | calls spent |
|---|---|---|---|---|---|
| `client-auth-method` | mcp | 0.64 | 16.3 | 0.64 | **16.3** |
| `client-auth-method` | shell | 0.47 | 20 | 0.62 | 31.3 |
| `oauth-grant-type` | mcp | 0.73 | 17.0 | 0.73 | **17.0** |
| `oauth-grant-type` | shell | 0.60 | 20 | **0.76** | 40 |

The semantic arm stops on its own at about seventeen interactions, so cutting the allowance to twenty
does not touch it; the shell arm keeps going and catches up by forty, and on `oauth-grant-type` it
passes. Cutting to twenty would therefore truncate **only the control arm**, raising the measured
advantage from +0.02/−0.03 to +0.17/+0.13 for a mechanical reason.

And it would not achieve the stated goal: the downstream floor is the unaided solver and the ceiling
is a hand-written note, so neither moves with the research budget. What moves is only where the notes
land between them.

The separation the proposal was after already exists, at the solver allowance the design started
with. On `oauth-grant-type` at twenty: floor **0, 0, 0, 0**, ceiling **10, 10, 10**, zero variance in
both groups.

### Where each case stands now

| case | verdict |
|---|---|
| `cc-refresh-token` | retired |
| `client-auth-method` | blocked by 2 — one no-note cell reached 6 of 9, leaving a 3-point gap on a 9-point scale |
| `oauth-grant-type` | **ADMITTED** |

## All three cases admitted, each at its own allowance (2026-08-26)

The calibration rule of `DESIGN-DOWNSTREAM.md` — *a floor that still reaches the ceiling means tighten,
a ceiling that cannot be finished means loosen* — was applied to each case's own readings rather than
to the family as a whole. Applied honestly it gave two different answers, so the allowance is now a
property of the case and is recorded as `AcquisitionCaseAdmission.solverAllowance`.

| case | allowance | why it moved | gold note (3 rollouts) | no note (2 rollouts) |
|---|---|---|---|---|
| `cc-refresh-token` | **15** | at 20 a no-note cell read 5 of 9 — tighten | 9/9, 9/9, 9/9 | 0, 0 |
| `client-auth-method` | **15** | at 20 a no-note cell read 6 of 9 — tighten | 9/9, 9/9, 9/9 | 0, 0 |
| `oauth-grant-type` | **25** | floor was already 0 at 20, but the note WAVE sat on the floor (3 of 24) — loosen | 10/10, 10/10, 10/10 | 0, 0 |

Zero variance in both groups on all three. Every zero is a measured zero: each baseline carries
`compiled=0` and a null obligation count, so the endpoint decision (an unbuilt tree satisfies none)
stays visible rather than baked into the number.

Verified cell by cell before the waves were bought: all twenty anchors are green builds, every one
publishes its `[ACQUISITION-DOWN]` line, none carries a `LOST` verdict or an oracle-patch conflict, and
every one of the nine zeros failed `default-compile` on a file **the solver itself created** — invented
names like `ClientCredentialsGrantRefreshTokenRestrictionExecutor` and
`X509SelfSignedClientAuthenticator_Complete`. Not `testCompile`, not the oracle, not the repository's
own sources.

### The guard that comes with a per-case allowance

`requireAcquisitionAdmission(case, budget)` now refuses a note cell whose allowance differs from the
one its case was calibrated at. Without it a wave queued at another case's number would be graded
against a floor and ceiling nobody measured under it, and would look like an ordinary row in the
table — the exact failure mode this protocol exists to prevent, in a new dress.

The cost is recorded rather than hidden: cells of different cases are no longer measured under the
same constraint, so the `U`-to-outcome relation is read **within** a case. The wave is within-case by
construction, so the primary analysis is unaffected.

### What `cc-refresh-token` shows about the retirement

It was retired on 2026-08-24 for failing to produce a gradable tree in three of three gold rollouts.
Two of those three failed on a unit test the agent wrote itself — the files amendment 3 now discards —
and at an allowance of 15 the case reaches its ceiling **three times of three**. The retirement was a
decision taken on readings a later repair invalidated, and withdrawing it was a correction, not a
relaxation: the stopping rule is intact and simply did not fire on honest readings.

## The repair turn (2026-08-26) — pre-registered before the wave was re-bought

### Why, in the numbers that forced it

The six-trajectory wave (108 cells) was decomposed by variance rather than read for its correlation:

| case | within-note SD | between-note SD | within-note SD where BOTH replicates compiled |
|---|---|---|---|
| `cc-refresh-token` | 2.96 | 2.30 | **1.26** |
| `client-auth-method` | 3.10 | 2.16 | **0.41** |
| `oauth-grant-type` | 2.59 | 2.28 | — |

**The noise inside one note exceeded the whole signal between notes**, and about four notes in ten
returned "0 and high" across two runs of the SAME note. Among the pairs where both replicates
compiled, that noise collapsed to 0.4–1.3 — i.e. once a tree builds, a note's score is nearly
deterministic.

So essentially all of the variance was one coin flip: **did this run get its own code to build inside
the allowance.** It is not a property of the note, it is not a property of the arm, and it drowned the
quantity the round exists to measure.

### Why more data was the wrong answer

Averaging a flip of that size against a signal of 2.2 needs roughly **eighteen replicates per note** —
18 notes × 3 cases × 18 ≈ 970 cells, of order $250, spent entirely on averaging a defect. Splitting
the endpoint in two instead (P(compiles), and obligations-given-compiled) was tried on the existing 108
cells for nothing: it produced one significant number, on the collider-conditioned half, contradicted
by another case. Neither is a fix.

### The rule

After the agent's run, up to `ACQUISITION_REPAIR_ROUNDS` = **3** times: the harness compiles the graded
scope, and if it fails, hands the agent the compiler's own diagnostics together with the current
contents of every file javac named, and asks it to fix **only** those errors.

Why this leaks nothing:

- the **harness** runs the build and reads the files. The agent issues no command whose text could hide
  a question about the repository, and spends no interaction;
- the agent learns only what is wrong with code **it already wrote**. Nothing in a diagnostic says
  where anything lives, what the precedent is, or which second place must be touched — the categories
  the checklist actually measures;
- the prompt is deliberately narrow: fix these errors, add no features, write no tests, write no notes,
  start nothing new. A turn that invited more work would let a cell keep developing after its
  allowance ran out, which is the one thing the allowance exists to prevent.

`repairRounds` is published per cell. Zero means it compiled unaided; a cell that needed all three must
stay distinguishable from one that needed none, or the loop would hide the variance it removes. A cell
that ran before the loop existed publishes no such column at all, rather than a zero that would read as
"compiled first time".

`ArenaVerifier.compileOnly` runs `test-compile` and not `compile`, because the graded build compiles
test sources too and a tree that only passes `compile` still fails grading — repairing the wrong phase
would look like a repair that did not work.

### The prediction

Written before the wave is re-bought:

| quantity | now | predicted after the repair turn |
|---|---|---|
| cells that produce a gradable tree | ~60 % | **> 90 %** |
| within-note SD | 2.6–3.1 | **0.4–1.3** |
| readable at two replicates? | no — noise ≈ signal | yes — noise well under the 2.2 signal |

If the compile rate does NOT rise, the flip was not what the repair addresses and this change bought
nothing; that is a finding about the instrument and the round says so. If it rises and the correlation
stays absent, then the relation is absent at a noise level where it could have been seen — which is the
first time this project would be able to say that.
