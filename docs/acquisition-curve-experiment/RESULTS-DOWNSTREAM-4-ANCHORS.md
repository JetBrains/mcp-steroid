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
