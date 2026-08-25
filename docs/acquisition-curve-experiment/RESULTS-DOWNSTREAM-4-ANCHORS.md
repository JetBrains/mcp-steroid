# Round 4, step 2: the anchors, and what they say about the instrument

Fifteen agent cells bought 2026-08-24 under
[DESIGN-CASE-ADMISSION.md](DESIGN-CASE-ADMISSION.md) — three gold-note rollouts and two no-note
baselines on each of the three gradable cases, ≈$4.30 in total. They are the first agent cells in
this family that carry a compile verdict, and the first whose failures can be attributed to a cause
rather than to a zero.

No note cell was bought. All three cases remain **blocked**, and the blocks changed.

## The readings

Weak agent (`claude`), 20 repository interactions, grading build with the dependency closure
(`-pl <scope> -am`). `–` means the tree did not build, so the obligation count is *unmeasured*, not
zero.

### `cc-refresh-token` — 9 axes, pristine floor 1

| build | condition | compiled | obligations | phase that failed | file that failed |
|---|---|---|---|---|---|
| 1040174097 | `oracle:gold` r1 | 0 | – | `testCompile` | the agent's own `RejectClientCredentialsRefreshTokenExecutorTest` |
| 1040174099 | `oracle:gold` r2 | 0 | – | `compile` | the agent's `RejectClientCredentialsRefreshTokenExecutor` |
| 1040174101 | `oracle:gold` r3 | 0 | – | `testCompile` | the agent's own `RejectClientCredentialsRefreshTokenExecutorTest` |
| 1040174116 | `baseline` r1 | 0 | – | `compile` | the agent's `NoClientCredentialsRefreshTokenExecutorFactory` |
| 1040174118 | `baseline` r2 | 1 | **5 / 9** | — | — |

### `client-auth-method` — 9 axes, pristine floor 1

| build | condition | compiled | obligations | phase that failed | file that failed |
|---|---|---|---|---|---|
| 1040174120 | `oracle:gold` r1 | 1 | **9 / 9** | — | — |
| 1040174122 | `oracle:gold` r2 | 0 | – | `testCompile` | the agent's own `SelfSignedX509ClientAuthenticatorTest` |
| 1040174124 | `oracle:gold` r3 | 1 | **9 / 9** | — | — |
| 1040174126 | `baseline` r1 | 1 | **6 / 9** | — | — |
| 1040174128 | `baseline` r2 | 0 | – | `compile` | the agent's `X509ClientCertificateExactMatchAuthenticatorFactory` |

### `oauth-grant-type` — 10 axes, pristine floor 1

| build | condition | compiled | obligations | phase that failed | file that failed |
|---|---|---|---|---|---|
| 1040174130 | `oracle:gold` r1 | 0 | – | `testCompile` | the agent's own `OfflineRefreshTokenGrantTypeTest` |
| 1040174132 | `oracle:gold` r2 | 1 | **10 / 10** | — | — |
| 1040174134 | `oracle:gold` r3 | 1 | **10 / 10** | — | — |
| 1040174136 | `baseline` r1 | 0 | – | `compile` | the agent's `CredentialRenewalGrantType` |
| 1040174138 | `baseline` r2 | 0 | – | `compile` | the agent's `RefreshTokenLongLivedGrantType` |

## Three findings, in order of how much they cost the project

### 1. The floor was never zero. It is most of the scale.

Every published floor in this family read `0`. Of the five no-note cells here, **the two that
built read 5 of 9 and 6 of 9** — four and five obligations above the pristine floor of one. The
unaided weak agent, when it gets its own code to compile, satisfies most of the architecture
checklist by itself.

So the `0 → 9` gap that rounds 2 and 3 read as "the note buys the whole checklist" was, where it was
readable at all, a **compile gap**. Where both arms build, the measured gap on `client-auth-method`
is 9 versus 6 — three obligations of nine, which the admission gate rejects on its own terms as less
than half the scale.

This is a direct measurement of the thing
[RESULTS-DOWNSTREAM-2-RECHECK.md](RESULTS-DOWNSTREAM-2-RECHECK.md) could only bound by re-reading.
It does not resurrect the withdrawn verdict in either direction; it removes the last reason to
believe the floor.

### 2. Four of the five gold-note failures are the agent's own scratch test, not its implementation.

The gold patch of every case touches `src/main` and resources **only**; the oracle adds exactly one
`src/test` file, under a name of its own. So every failing `src/test/java/…Test.java` above was
written by the solving agent, on its own initiative, as a companion to the change.

In four of the five gold-note failures the sequence in the log is unambiguous:

```
--- compiler:3.13.0:compile (default-compile) @ keycloak-services ---
Compiling 1580 source files ...            <- WARNINGs only: the agent's implementation is fine
--- compiler:3.13.0:testCompile (default-testCompile) @ keycloak-services ---
COMPILATION ERROR : .../SelfSignedX509ClientAuthenticatorTest.java  <- the agent's own test
```

`default-compile` succeeds; `default-testCompile` kills the cell. The implementation the case exists
to grade compiled cleanly and was never graded.

The bias this introduces is **asymmetric and aimed at the treatment arm**. An agent that was handed
the architecture spends few interactions finding it and has budget left over to write a test; an
agent with no note is still hunting when the wall arrives and never writes one. Split by arm:

| arm | cells that failed to build | because its own **test** did not compile | because its **implementation** did not compile |
|---|---|---|---|
| `oracle:gold` | 5 | **4** | 1 |
| `baseline` | 4 | **0** | 4 |

The no-note arm fails for the reason the endpoint is about. The gold-note arm fails for a reason that
has nothing to do with understanding — and the better the note, the more exposed the cell is.

Round 2's bias ran the opposite way, with compilation failure suppressing the *control* arm. Both
directions have now been observed on this instrument. Neither is the hypothesis.

### 3. Even ignoring that, the gold note does not always land.

On the two cases where it can be measured, `oracle:gold` reaches the ceiling in 2 of 3 rollouts and
does so exactly (9/9, 9/9; 10/10, 10/10). On `cc-refresh-token` it lands in **0 of 3**. The
`client-auth-method` scar — 9 of 9 once, then 0, 0, 0 — was therefore not an accident of that case:
roughly a third of gold-note rollouts do not produce a gradable tree, and three replicates is a
minimum rather than a comfort.

## Where each case stands

Printed by `AcquisitionAdmissionTest`, which is green:

| case | blocked by | the substance |
|---|---|---|
| `cc-refresh-token` | 5 | all three gold rollouts failed to build; the one baseline that built reads 5 of 9 |
| `client-auth-method` | 4 | one gold rollout failed to build; the baseline that built reads 6 of 9, leaving a 3-point gap on a 9-point scale |
| `oauth-grant-type` | 2 | one gold rollout failed to build; **no** baseline built, so the case has no measured floor at all |

`oauth-grant-type` is the closest to admissible and the only one whose two blocks are both about
missing readings rather than about readings that came back wrong.

### Amendment 2 to the admission protocol

`oauth-grant-type` exposed a hole the compile verdict opened without closing: recording
`compiled=false` made the unmeasured cells *visible*, but every threshold in the gate reads
obligation **counts**, and an unmeasured cell's count is null, so all of them skipped it in silence.
Two no-note cells that never built therefore satisfied "at least two baselines" and passed the
"no more than one above the floor" test vacuously — the case read as having a floor it had never
measured.

`baselineProblems` now requires `MIN_BASELINE_ROLLOUTS` baselines that **demonstrably built**. Same
direction as every previous amendment: strictly stronger, and it changes no threshold that could be
tuned into a result.

## Why the agent writes a test it cannot finish

Read before deciding how to repair it, because the mechanism turned out not to be incidental.

The four transcripts have one shape. Reconstructed from cell 1040174097, and the other three differ
only in names:

| # | what the agent did |
|---|---|
| … | reads the executor SPI, the two profile JSONs, the registration file |
| ~13 | **writes the implementation, the factory, the SPI line and the profile entry** — the whole gold change, and `default-compile` later proves it compiles |
| 14 | `ls services/src/test/java/.../executor/` |
| 15 | `find … ConsentRequiredExecutorTest.java` |
| 16 | reads `SecureRedirectUrisEnforcerExecutorTest.java` — an existing sibling test, as a model |
| — | writes its own `RejectClientCredentialsRefreshTokenExecutorTest` (free) |
| 17 | `grep -r beforeUpdate …` to fix the mock it just wrote |
| — | two edits to that test (free) |
| 18 | `./mvnw test -pl services` — the first time it could have seen the errors |
| 19–20 | more of the same, then the wall; two further calls refused |

The change is finished, and everything after it goes into a test nobody asked for:

| cell | first touch of `src/test` | interactions left after it |
|---|---|---|
| 1040174097 | 13th of 20 | 7 |
| 1040174101 | 10th of 20 | 10 |
| 1040174122 | 17th of 20 | 3 |
| 1040174130 | 12th of 20 | 8 |

Two things make this the note's doing rather than the agent's whim.

**The budget rewards it.** The prompt tells the agent that editing files is free and that reads,
searches and build runs each cost one of twenty. An agent that spent few interactions locating the
architecture — which is exactly what a good note buys — arrives at "done" with interactions in hand
and nothing to spend them on except verification. An agent still hunting never gets there. The
allowance therefore converts note quality into scratch-test exposure.

**On `cc-refresh-token` the checklist says so out loud.** Fact `I1`, category `VERIFICATION`, is:

> The right test pattern is a plain JUnit test in the `services` module beside the executors
> (`SecureRedirectUrisEnforcerExecutorTest`), which constructs the executor directly with no server…

`I1` is one of the axes `U` is computed over, so the *better the note, the more certainly it carries
an instruction to write a companion test and the name of the file to imitate* — and the transcript
above is that instruction being followed, file name included. The note causes the behaviour that
makes the cell unreadable, and the causal path runs through the very quantity being measured.

That is a sharper defect than "the grading build compiles too much". It means the endpoint and the
treatment are coupled through the interaction allowance: on this case, part of what `U` measures is
an instruction whose execution costs the agent the budget it needs to finish. The other two cases
have no such fact — their VERIFICATION axes are about *existing* tests breaking — and their agents
wrote tests anyway, so the general mechanism does not depend on `I1`; `I1` only makes
`cc-refresh-token` the extreme.

## What this round has not decided

Whether the gold-note arm's advantage survives once the scratch-test artifact is removed. The
measurement to make is the same fifteen cells, minus the artifact — and how the artifact should be
removed is an instrument decision that has to be pre-registered before those cells are re-bought,
not chosen after seeing which choice helps.

Note what the mechanism above rules out: any repair that only widens or narrows what the grading
build compiles leaves the coupling in place, because the interactions were spent before grading ever
started.

---

# Step 3: the ten anchors re-bought under amendment 3

Queued after amendment 3 and its prediction were committed (revision `c44b70dcc`, pinned by all ten
builds), on the two cases that survived step 2. ≈$2.90.

## The readings

`discarded` is the new `agentTestsDiscarded` column: test files the solver added, removed before
grading.

### `client-auth-method` — 9 axes

| build | condition | compiled | obligations | discarded |
|---|---|---|---|---|
| 1040258857 | `oracle:gold` r1 | 1 | **9 / 9** | 1 |
| 1040259462 | `oracle:gold` r2 | 1 | **9 / 9** | 1 |
| 1040259464 | `oracle:gold` r3 | 1 | **9 / 9** | 1 |
| 1040259466 | `baseline` r1 | 0 | – | 0 |
| 1040259468 | `baseline` r2 | 0 | – | 1 |

### `oauth-grant-type` — 10 axes

| build | condition | compiled | obligations | discarded |
|---|---|---|---|---|
| 1040259470 | `oracle:gold` r1 | 1 | **10 / 10** | 1 |
| 1040259472 | `oracle:gold` r2 | 1 | **10 / 10** | 1 |
| 1040259474 | `oracle:gold` r3 | 1 | **10 / 10** | 1 |
| 1040259476 | `baseline` r1 | 0 | – | 0 |
| 1040259478 | `baseline` r2 | 0 | – | 0 |

## The amendment did what it was pre-registered to do, and the mechanism replicated forward

Six of six gold-note rollouts compile and land exactly on the ceiling, against four of six before.
And **every one of the six discarded exactly one agent-authored test** — including the four whose
step-2 predecessors passed. So the artifact was present in every gold cell of both waves; what
differed between a 9/9 and an `unmeasured` was whether the scratch test happened to compile.

The asymmetry that step 2 could only infer from four transcripts is now a column:

| arm | cells | wrote a test of its own | reached the ceiling |
|---|---|---|---|
| `oracle:gold` | 6 | **6 of 6** | **6 of 6** |
| `baseline` | 4 | **1 of 4** | 0 of 4 |

An agent handed the architecture writes a companion test essentially always; an agent without one
almost never gets far enough to try. That is the prediction of the mechanism, measured prospectively
rather than read back out of the cells it explains.

## Half the prediction was wrong, and the half that was wrong is the finding

Pre-registered, before these cells were queued:

| case | predicted | measured |
|---|---|---|
| `client-auth-method` gold | 3 of 3 compile at 9/9 | **exactly that** |
| `oauth-grant-type` gold | 3 of 3 compile at 10/10 | **exactly that** |
| `client-auth-method` baseline | the 6-of-9 floor reappears, so the case stays blocked on the gap | **wrong**: neither re-bought baseline produced a gradable tree |
| `oauth-grant-type` baseline | both fail again, so the case has no floor | that, and now on four cells rather than two |

So the two cases did not separate the way the prediction said, and they did not separate the way the
protocol wants either. Pooling both waves:

| arm | cells | left a gradable tree |
|---|---|---|
| `oracle:gold` (all four cases-waves) | 12 | **10** |
| `baseline` | 8 | **1** |

The one no-note cell that ever built on these two cases read 6 of 9. Every other no-note cell — seven
of eight — never got its own implementation to compile.

## Where this leaves the two survivors

Both are **blocked**, and for the first time the blocks are about the control arm rather than the
instrument:

| case | blocked by | the substance |
|---|---|---|
| `client-auth-method` | 3 | 1 of 4 baselines built; that one read 6 of 9, so the measured gap is 3 of a 9-point scale |
| `oauth-grant-type` | 1 | 0 of 4 baselines built — no measured floor at all |

Nothing here is an instrument defect. The gold note reaches the ceiling every time it is asked; the
oracle has a measured ladder whose rungs separate by axis name; compilation is its own reading; the
solver is graded on its change. What the protocol now refuses is a wave whose control arm cannot be
read — and that refusal is correct on its own terms.

## The tension this creates, stated and NOT resolved

`oauth-grant-type` presents the strongest raw separation this project has produced: the gold note
reaches 10 of 10 three times of three, and four unaided rollouts produce no gradable tree at all.
Requirement 4 and amendment 2 nevertheless block it, because a floor has to be a *reading*, and "did
not compile" is not one in the oracle's units.

There is an obvious move here and it is deliberately not made: relaxing amendment 2 so that a
replicated "the unaided agent never compiles" counts as a floor. Amendment 2 was written hours before
these cells returned, it now blocks the two remaining cases, and loosening a rule *because* it blocked
the result is precisely the move the whole protocol exists to prevent — amendment 1 states that once
data exists an amendment may only move in the stricter direction. So the rule stands and the cases
stay blocked.

What the tension actually indicates is that the pre-registered **endpoint** may be the wrong one for
these cases, not that the rule is too strict. Three separate readings now point the same way:

- round 2's re-read found that the only relationship it could measure with confidence was
  ρ(`U`, *the tree built*) = +0.660, the same magnitude as its withdrawn headline
  ([RESULTS-DOWNSTREAM-2-RECHECK.md](RESULTS-DOWNSTREAM-2-RECHECK.md));
- step 2 found the obligation counts unreadable in four of nine cells for a reason unrelated to
  understanding;
- step 3 finds a 10-of-12 versus 1-of-8 split on exactly that binary, with the artifact removed.

Changing the endpoint is a larger decision than an amendment: it changes what the project claims, it
needs its own pre-registration, and it must be written down before any note cell is bought — because
a binary endpoint chosen after seeing which endpoint separates is not evidence. It is recorded here as
the open question and nothing more.

## Cost so far, and what it bought

| round | cells | ≈cost | what it could measure |
|---|---|---|---|
| 1–3 | 46 | ≈$24 | nothing; each lost the wave to a different instrument defect |
| 4, step 1 (ladders) | 10 | $0 in model tokens | the oracles have a real scale, rungs separating by axis name |
| 4, step 2 (anchors) | 15 | ≈$4.30 | the floor was never zero; one case retired; the scratch-test artifact and its mechanism |
| 4, step 3 (re-anchors) | 10 | ≈$2.90 | the artifact is gone; 6 of 6 ceilings; the control arm is what blocks the family now |

Thirty-five cells and ≈$7 of the fourth round bought a working instrument and two cases whose only
remaining obstacle is a property of the task rather than of the harness. No note cell has been bought
on it yet, which is the protocol working as designed.

---

# Step 4: the unbudgeted floor probe

Ten cells, five `baseline` on each surviving case, **no allowance at all**, bare `claude-haiku-4-5`,
no note, amendment 3 in force. Revision `341190fcf`. ≈$7.50.

## The readings

| build | case | obligations | compiled | tool calls | usd |
|---|---|---|---|---|---|
| 1040393850 | `client-auth-method` | 6 / 9 | 1 | 60 | 0.47 |
| 1040393852 | `client-auth-method` | 7 / 9 | 1 | 61 | 0.52 |
| 1040393854 | `client-auth-method` | 2 / 9 | 1 | 97 | 0.77 |
| 1040393856 | `client-auth-method` | 6 / 9 | 1 | 72 | 0.65 |
| 1040393858 | `client-auth-method` | 7 / 9 | 1 | 70 | 0.62 |
| 1040393860 | `oauth-grant-type` | 7 / 10 | 1 | 103 | 0.77 |
| 1040393862 | `oauth-grant-type` | 8 / 10 | 1 | 101 | 1.03 |
| 1040393864 | `oauth-grant-type` | 9 / 10 | 1 | 82 | 0.71 |
| 1040393866 | `oauth-grant-type` | 8 / 10 | 1 | 106 | 1.05 |
| 1040393868 | `oauth-grant-type` | 8 / 10 | 1 | 94 | 0.97 |

**Ten of ten compiled.** Against one of eight at an allowance of twenty. The compile failures that
wrecked three rounds were never a property of these tasks: they are what happens when the wall
arrives before the agent has finished. Given room, this agent builds its code every time.

## The pre-registered branch that fires

Branch 1, and not narrowly. The rules that decide it were fixed before these cells were queued:

| pre-registered rule | requires | measured | |
|---|---|---|---|
| a baseline is at most one obligation above the pristine floor of 1 | ≤ 2 | **5.6** mean (`client-auth`), **8.0** mean (`oauth-grant`) | fails |
| gold and baseline separated by half the scale | ≥ 4.5 / ≥ 5 | **2** of 9, **1** of 10 | fails |

| case | unbudgeted no-note | of the ceiling |
|---|---|---|
| `client-auth-method` | 5.6 / 9 mean (6, 7, 2, 6, 7) | **62 %** |
| `oauth-grant-type` | 8.0 / 10 mean (7, 8, 9, 8, 8) | **80 %** |

So the unbudgeted agent does most of the job unaided, and this definition of the floor leaves no room
above it for a note to buy anything. **The floor-by-unlimited-budget is unusable, exactly as
suspected — and now on evidence rather than on a misremembered pair of round-1 numbers.**

Note what it is not: it is not "the agent solves the case". No cell of the ten satisfied every
obligation; the best was 9 of 10, once. The unaided agent gets close and does not arrive.

## What the same ten cells say about the note

The pre-registration says branch 1 means the endpoint is **work, not success**. These readings say
what that endpoint would show, and it is the sharpest contrast this project has produced:

| | obligations | tool calls | cost |
|---|---|---|---|
| no note, no wall — `client-auth-method` | 5.6 / 9 | **72** | $0.61 |
| gold note, 20 interactions — `client-auth-method` | **9 / 9** | **32** | $0.25 |
| no note, no wall — `oauth-grant-type` | 8.0 / 10 | **97** | $0.86 |
| gold note, 20 interactions — `oauth-grant-type` | **10 / 10** | **26** | $0.18 |

The note produces a **complete** result at **2.3×–3.7× less work** than no note produces an
incomplete one. Both halves move, and they move the same way — which is what a "the note reduces the
work" claim needs and what no previous round could show, because in every previous round the control
arm's number was a build failure.

This is also the first reading in the family that is coherent with the acquisition side rather than
merely adjacent to it. Link 1 says semantic access reaches a given level of the checklist in fewer
environment interactions. This says the saving survives being written down and handed to a different,
weaker agent. The chain is one claim about interactions from end to end, and never was a claim about
pass/fail.

## What is decided, and what is not

**Decided by the pre-registration:** the unbudgeted no-note cell is not the floor, and the endpoint of
the downstream half is work rather than success.

**Not decided, and not to be decided by whoever noticed the numbers first:** the design that follows.
Restating the endpoint changes what the project claims and needs its own pre-registration, written
before a note cell is bought. The open questions it has to answer, listed so they are not quietly
settled:

1. **At what allowance does the wave run** — or does it run unbudgeted, with interactions as the
   outcome rather than the constraint? An unbudgeted wave has no compile-failure problem at all, since
   ten of ten built; it costs roughly 3× per cell.
2. **What counts as the residual** when a cell may run as long as it likes. "Obligations at the point
   the agent declares itself done" is the obvious answer and has never been measured.
3. **Whether the admission requirements still apply in their present form.** Requirements 3 and 4 and
   amendment 2 are all phrased in terms of a floor and a ceiling of *obligations*. Under a work
   endpoint the floor is a number of interactions, and the gate has to be rewritten in those units
   rather than reinterpreted in prose.

Nothing in this document licenses skipping that. It reports which branch fired and stops there.
