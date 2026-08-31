# Round 7 — case selection for the second `U`-to-outcome case

Pre-registered in [DESIGN-DOWNSTREAM-7.md](DESIGN-DOWNSTREAM-7.md) before any number below existed.
Step 0a is complete and cost nothing: no agent ran, no cell was queued. Step 0b's slot is empty.

## Step 0a — how much of each candidate survives compilation

Measured on a checkout of `keycloak/keycloak` at the pinned base commit
`60c4d5e9321ff5462a772ceb896f8cb2e639e04b`, the same commit the ripple family runs against.

**The method validates against the family's own pins before it is used to judge anything.** Counting
same-name type declarations reproduces both pinned decoy numbers exactly: five `ResourceType`
declarations, i.e. the target plus the four `RippleCases.moveClassWide` pins, and four
`ValidationContext` declarations, i.e. the target plus the three `renameTypeWide` pins. Counting
`getId()` declarations reads 1074 against a pinned 1018 — the same family at the same scale, my regex
being the looser of the two.

| | `move-class-wide` (`ResourceType`) | `rename-type-wide` (`ValidationContext`) | `change-signature-wide` (`Resource#getId`) |
|---|---|---|---|
| FQN in any non-`.java` file | **0** | **0** | **0** |
| named by `Class.forName` / `getMethod` in production code | 0 | 0 | 0 |
| same-name declarations a text tool would also hit | 4 | 3 | ~1018 methods |
| compiler-**invisible** obligation families | **2** | **2** | **≥ 4** |

### What the invisible families actually are

For both parity candidates the answer is the same and it is short. After a move or a rename, every
remaining textual use of the old name **fails to compile**, so every reference site is compiler-visible
and `javac` enumerates the ripple for the agent. What survives compilation is only:

1. **the old name surviving as a copy or forwarder** — graded by the hidden consumer's
   `Class.forName(oldFqn())`, which is a runtime assertion;
2. **over-reach onto a same-name declaration**, which compiles when done consistently.

Two families. The pre-registered threshold is three, and the reason is round 6's own noise figure: the
within-note SD is 0.61, and a two-point scale cannot separate notes at that noise.

For `change-signature-wide` the invisible surface is structural rather than lexical:

1. **an overload instead of a signature change.** Keeping `getId()` beside `getId(boolean)` compiles
   perfectly and is wrong; the overlay already asserts it — *"an overload is not a signature change"*;
2. **the argument passed at each of the 104 call sites.** Any boolean expression compiles; only the
   gold's literal is correct;
3. **over-reach onto the ~1018 foreign `getId()` declarations**, invisible whenever it is consistent;
4. **receiver correctness** (`P7_RECEIVER`), already a predicate of the family.

### Verdicts under the pre-registered rule

- `ripple__keycloak__move-class-wide` — **refused**, two invisible families.
- `ripple__keycloak__rename-type-wide` — **refused**, two invisible families.
- `ripple__keycloak__change-signature-wide` — **admitted to step 0b**, at least four.

The mechanism the design predicted is therefore measured rather than argued: on this family a case is
at parity exactly when the compiler can enumerate its ripple, and a case whose ripple the compiler
enumerates has no room for a note. **The parity preference cannot be satisfied on the ripple family**,
and the round proceeds on the declared fallback.

### One correction to a pinned case record, found on the way

`RippleCases.moveClassWideTarget.behaviourPreservationEvidence` states that the simple name
`ResourceType` is "load-bearing in 39 theme-message and realm-JSON files". Thirty-nine such files do
match the substring, but every hit is an admin-console message key — `applyToResourceTypeHelp`,
`chooseAResourceType`, `workflowResourceTypeNotSupported` — inside a longer identifier, not a reference
to the type. No non-`.java` file references the target at all, by FQN or as a standalone name.

The conclusion the field draws is unaffected: a move does not change the simple name, so those files
stay untouched either way. What is wrong is the premise, and it made the case look as though it had a
resource-side obligation surface. It has none, which is precisely why it is refused above.

## Step 0a, applied to the feature cases — and the round changes course

The same criterion costs nothing to apply to the cases that already have eighteen notes, and
`oauth-grant-type` passes it with room to spare: the `META-INF/services` line (omitting it compiles,
the feature is simply never found), the published `grant_types_supported` metadata, the global
short-code uniqueness the token-context encoder checks at start-up, and the credential-kind check —
four compiler-invisible families out of ten axes. Its refusal in round 6 was by the **floor**, and the
floor is high because the other six axes grade implementation, which the unaided solver already knows.

That is a re-weighting of the endpoint, not a new case — and the notes for it are already bought.
This is a **deviation from the candidate order pre-registered in `DESIGN-DOWNSTREAM-7.md`**, which
named `change-signature-wide` as the fallback. It is recorded as a deviation rather than folded into
the design retroactively; the fallback remains available and un-refused.

## The per-axis reading, recovered from cells already paid for

The graded oracle's surefire XML is embedded in each cell's build log, so **every axis of every anchor
of round 6 is recoverable at zero cost**. The extraction reproduces each cell's published total exactly
— gold 10/10 three times and baseline 6/10 twice on `oauth-grant-type`; 6/9, 7/9, 9/9 and 5/9 on
`client-auth-method` — which is what licenses reading it per axis.

### `oauth-grant-type`: the whole signal lives in four axes of ten

| axis | gold ×3 | baseline ×2 | kind |
|---|---|---|---|
| `theGrantIsRegisteredSoTheTokenEndpointCanDispatchToIt` | PASS | **FAIL** | wiring, compiler-invisible |
| `theGrantAppearsInThePublishedGrantTypesSupported` | PASS | **FAIL** | discovery, compiler-invisible |
| `anOrdinaryInteractiveCredentialIsRefusedBeforeAnyTokenIsMinted` | PASS | **FAIL** | invariant — the naive grant mints |
| `anUnreadableCredentialIsAProtocolErrorNotAServerError` | PASS | **FAIL** | error contract |
| `aNewGrantImplementationIsCompiledIntoTheModule` | PASS | PASS | implementation |
| `theGrantTypeUriAClientWouldSendIsExposed` | PASS | PASS | implementation |
| `aRequestWithoutACredentialIsRefusedAsAProtocolError` | PASS | PASS | implementation |
| `aLongLivedCredentialIsAcceptedAndHandedToTheShippedRenewalPath` | PASS | PASS | implementation |
| `theTokenContextShortCodeIsGloballyUnique` | PASS | PASS | trap; also true of a pristine tree |
| `theShippedGrantsAreUnchanged` | PASS | PASS | trap; also true of a pristine tree |

The floor of 6 of 10 is made entirely of axes the unaided solver satisfies **in every replicate**. Four
axes carry the whole measured difference between having the gold note and having none, and both traps
are satisfied by a tree that changed nothing — which is what a floor is supposed to mean.

### `client-auth-method`: the unstable ceiling is two axes, and it is not the note

| axis | gold r61 | gold r62 | gold r63 | baseline |
|---|---|---|---|---|
| `itAdvertisesTheSelfSignedMutualTlsTokenEndpointAuthMethod` | PASS | PASS | PASS | **FAIL** |
| `theAuthenticatorIsRegisteredThroughTheProviderSpi` | PASS | PASS | PASS | **FAIL** |
| `aClientPresentingItsRegisteredCertificateIsAuthenticated` | **FAIL** | **FAIL** | PASS | **FAIL** |
| `theShippedCertificateAwareProfilesAllowTheNewAuthenticator` | **FAIL** | **FAIL** | PASS | **FAIL** |
| `nothingThatAlreadyWorkedWasTakenAway` | **FAIL** | PASS | PASS | PASS |
| the four remaining axes | PASS | PASS | PASS | PASS |

Its ceiling reads 9, 7, 6 because the weak solver, holding the gold note, fails to finish the
certificate-matching implementation and the profile allow-list in two rollouts of three. The two wiring
axes it never misses. So the case is blocked by what the endpoint asks the solver to *write*, not by
what the note manages to say — the same finding as round 4 step 6, now visible per axis.

## The surgery is not a universal solvent, and `client-auth-method` shows where it stops

The same re-weighting applied to `client-auth-method` — keep the four axes on which the gold note and
the no-note solver differ, keep the conservation trap, drop the four the unaided solver always passes —
recomputes to:

| condition | under the 9-axis oracle | under the same surgery |
|---|---|---|
| gold note r61 | 6 of 9 | **2 of 5** |
| gold note r62 | 7 of 9 | **3 of 5** |
| gold note r63 | 9 of 9 | **5 of 5** |
| no note r61 | 5 of 9 | **1 of 5** |

Its **floor problem disappears**: 5 of 9 was made of implementation axes, and without them the unaided
solver scores the conservation trap and nothing else — exactly the pristine floor. Its **ceiling problem
does not**: the weak solver holding the gold note reaches all five only once in three, missing the same
two obligations both other times, and one rollout additionally broke a shipped path it was told nothing
about. So the case stays blocked, and it now stays blocked for a single, localized reason — the solver
cannot finish the certificate matching and the profile allow-list inside its allowance — rather than for
a floor that was never about the note.

That is worth stating because it constrains what this round may claim. Re-weighting an endpoint onto the
obligations a compiling implementation misses fixes a floor; it cannot manufacture a ceiling the solver
cannot reach.

It also changes what the allowance lever means, and this is a hypothesis rather than a finding: round 5
withdrew a raised allowance because the extra interactions were handed to the no-note arm and closed the
gap. Under an endpoint that no longer grades implementation, extra interactions buy mostly what is no
longer scored, so the lever may be usable again on a case blocked by its ceiling alone. Nothing in this
round tests that, and it must not be tried on the same cells that would then report the result.

## The re-weighted endpoint, measured: nine cells, nine predictions, no misses

Every number below was predicted in `DESIGN-DOWNSTREAM-7.md` before the cell that produced it was
queued. Ladder rungs run no agent; the anchors run the weak solver (`claude-haiku-4-5`) at the case's
own allowance of 25.

| condition | predicted | measured | build | note |
|---|---|---|---|---|
| pristine | 1 of 6 | — | — | from the axis table: the conservation trap alone |
| `ladder:implementation-only` | 4 | **4** | 1046505073 | loses exactly A2 and A5 |
| `ladder:naive-shortcut` | 5 | **5** | 1046529291 | loses exactly A4 |
| `ladder:gold` | 6 | **6** | 1046476916 | the ceiling — and `oracle-v2`'s first compilation |
| `oracle:gold` r61 | 6 | **6** | 1046554383 | compiled, 0 repair turns |
| `oracle:gold` r62 | 6 | **6** | 1046595043 | compiled after 2 repair turns |
| `oracle:gold` r63 | 6 | **6** | 1046626868 | compiled, 0 repair turns |
| `baseline` r61 | ≤ 2 | **2** | 1046693489 | both traps only; compiled after 1 repair turn |
| `baseline` r62 | ≤ 2 | **2** | 1046826970 | the same two traps; compiled after 2 repair turns |
| `baseline` r63 | ≤ 2 | **1** | 1046832714 | not even a factory; compiled after 1 repair turn |

**The case is admitted.** `AcquisitionDownstreamAdmission.problems()` returns an empty list for
`oauth-grant-type` for the first time in the family's history — the second case ever to reach that
state, and the first to reach it by having its endpoint re-weighted rather than its allowance tuned.

Three readings deserve to be stated separately, because they are what the round was bought for:

- **The ceiling is reachable and reproducible**: 6 of 6, three times of three. On the ten-axis contract
  this case also read 10 of 10 three times — the difference is that four of those ten were passed with
  no note at all, and now none are.
- **The floor is at the floor**: 2, 2, 1, against a pristine floor of 1 and a `BASELINE_SLACK` of 1.
  Every one of the three trees was carried to a build, so no reading is a `javac` failure wearing a
  zero — the failure mode that made three earlier rounds uninterpretable. What the unaided solver never
  does is register the grant, publish it, refuse an interactive credential, or return a protocol error
  instead of a server error.
- **The gap is four obligations of six**, where the same solver on the same trees read 6 of 10 before.
  Nothing about the agent changed; the endpoint stopped paying for the part the agent already knew.

And the ladder is a scale rather than a verdict: three deliberately broken trees returned three
DIFFERENT subsets of unmet obligations — the registration pair, the uniqueness invariant, and the
ceiling — which is the check that a six-point scale is not one boolean wearing six names.

## Step 0b — the floor probe

*(not bought: superseded. The per-axis reading above answers the floor question for the case this round
now runs on, from cells already paid for, and no ripple probe was queued.)*

## The wave: 36 cells on the second case, and both cases put on one instrument

**The instrument is recorded before any reading.** Every note in this section — on both cases — was
graded by `anthropic/claude-opus-5`, pinned, in one run seeded with `--seed` from the 54 notes already
committed under `test-experiments/src/test/resources/acquisition-notes`. Nothing was re-distilled, so
the graded texts are byte-for-byte the ones the solvers received. That matters twice over: it is what
makes the two cases comparable at all, and it is why the cc numbers below can be set beside round 6's
without the judge being a free variable.

Three of the 18 `oauth-grant-type` notes were **refused** by that judge (`stop_reason=refusal`, zero
output tokens): `none-b40-l2000-r1@5`, `none-b40-l2000-r2@5`, `none-b40-l2000-r2@10`. Their notes are
on disk and perfectly usable; what is missing is a grade. A refusal is a hole, never a zero — a zero
would rank a real note below every graded one and no reading would notice. The pre-registered response
is to read the wave three times: leaving the holes out, and bounding them at the lowest and the highest
observed `U_note`. If the three readings disagree, the disagreement *is* the result.

One cell of 36 was lost outright: `none-b40-l2000-r3@5` replicate 61, build `1046929403`, killed after
29m36s by the repair prompt overflowing Linux's 128 KiB single-argv cap on the `docker exec` command
line. It contributes no row, so its note folds from one replicate instead of two. That loss is
outcome-correlated by construction — it strikes exactly the cells whose repair prompts grew longest —
which is why it is reported here rather than absorbed. The transport is fixed at the source; the fix is
not retrofitted into these numbers.

### `oauth-grant-type` — the new case

| holes treated as | n | ρ(`U_note`, obligations) | p (two-sided) |
|---|---|---|---|
| left out | 15 | **+0.57** | 0.028 |
| bounded low | 18 | **+0.48** | 0.047 |
| bounded high | 18 | **+0.39** | 0.112 |

By the letter of the pre-registered rule — publish as a failure to replicate if |ρ| < 0.3 — the case
replicates: all three treatments clear the threshold. The significance verdict does not survive the
bounds, though, crossing 0.05 depending on how three refusals are handled, so the honest headline is a
positive relation of uncertain size, not a confirmed p.

The mechanism check is where this case parts company with the first one. Restricted to notes whose
**both** replicates built, ρ falls to +0.28 / +0.32 / **+0.05** — nothing, under any treatment. And the
confound the design was built to catch reads +0.48 / +0.30 / +0.45 for ρ(`U_note`, compiled) at cell
level. Read together: on this case the note's association with the endpoint runs almost entirely
through *whether the tree built at all*, and there is essentially no relation left among the trees that
did build.

Two further readings, both negative, belong in the record. Within the `mcp` arm ρ is **−0.29** under all
three treatments — the relation does not survive inside that arm. And the endpoint itself came out
nearly constant: 4 distinct values across 35 cells, with **28 of 35 sitting at exactly 4 of 6**. The
round-7 axis surgery bought a scale that discriminated beautifully on the ladder and the anchors, and
then met a wave that parked on one rung of it. That near-constancy — not the judge, not the holes — is
the largest single limit on what these 36 cells could ever have shown about *differences between
notes*.

It is not, as this section first concluded, evidence that the case could not measure. That conclusion
was drawn while the wave was scored `x/6` on `oracle-v2` and the anchors were scored `x/10` on the
ten-axis oracle, so the two were never on one scale. They can be: the six axes `oracle-v2` retains are
byte-identical to the same six in the ten-axis oracle, shared assertion helper included, so the anchor
build logs already carry the six-axis reading. Extracting it re-runs nothing — same surefire XML,
smaller axis set — and it costs nothing, which is why leaving it unextracted was the round's real
omission and not a limit of the case.

| `oauth-grant-type`, allowance 25, `claude-haiku-4-5` | obligations of 6 | built |
|---|---|---|
| no note at all (`baseline`) | 2, 2 | 2 of 3 |
| a distilled note (wave, `mcp` arm) | mean 4.06 | 15 of 18 |
| the gold patch (`oracle-gold`) | 6, 6, 6 | 3 of 3 |

So the scale has three working levels and the note's two obligations are real. What is flat is the
spread *inside* the note arm, and the per-axis table says exactly where it went: the note buys both
registration axes and nothing else, two further axes pass in every condition including the floor, and
the pair sharing `assertRefusedBeforeIssuing` — one obligation counted twice — fails 32 of 32 for every
note-carrying cell and passes only for gold. One obligation is the whole of the remaining headroom, and
no note reached it. `DESIGN-DOWNSTREAM-8.md` registers the probe of that headroom and the scale rule
that stops the two constant axes from being paid for twice.

### `cc-refresh-token` — the first case, re-read on the same instrument

Round 6's outcomes are untouched; only `U_note` is replaced by the opus-5 grade. Four of the 18 notes moved: `mcp-b40-l2000-r1@10` 0.53→0.47, `mcp-b40-l2000-r1@20` 0.67→0.80, `mcp-b40-l2000-r2@10` 0.67→0.73, `none-b40-l2000-r3@20` 0.40→0.33.

| reading | round 6 as published | opus-5 instrument |
|---|---|---|
| ρ(`U_note`, obligations), all notes | +0.62 (p 0.008) | **+0.56** (p 0.016) |
| … notes whose replicates both built | +0.84 (p 0.0004) | **+0.81** (p 0.0011) |
| ρ(`U_note`, compiled), cell level | — | **+0.03** |

The first case is robust to the judge: swapping the instrument moves ρ by 0.06 and changes no verdict.
And its mechanism is the mirror image of the new case's — the relation is not a compile confound at all
(+0.03), and it *strengthens* to +0.81 among the trees that built, exactly where `oauth-grant-type`
collapses to +0.05.

### What the second case actually established

The relation replicated on the number the design pre-registered, and failed to replicate on the thing
that number was supposed to stand for. `cc-refresh-token` says a better note yields more discharged
obligations among trees that compile. `oauth-grant-type` says a better note yields a tree that
compiles, and once it compiles the note stops predicting anything. Those are different claims, and one
case each is not enough to say which one generalizes.

Neither case licenses a claim about tools. The arm difference on the new case (`mcp` 4.06 vs `none`
2.33–2.78, Mann-Whitney p ≈ 0.02; cc: 5.44 vs 4.00, p = 0.114) is secondary by pre-registration and
confounded by the arm's own trajectories, as the design states.

Data: `data/downstream7-wave-oauth.csv` (35 cells), `data/downstream7-axes-oauth.csv` (per-axis),
`data/downstream7-wave-cc-opus5.csv` (round-6 outcomes, opus-5 notes). Marker line and surefire XML were
parsed independently and agree on every one of the 35 measured cells.
