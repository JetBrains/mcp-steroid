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

## Step 0b — the floor probe

*(not bought: superseded. The per-axis reading above answers the floor question for the case this round
now runs on, from cells already paid for, and no ripple probe was queued.)*
