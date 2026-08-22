# Choosing the case: what was already there, and why none of it fit

The instruction was explicit — do not invent a synthetic case before looking at what exists. This file
is the audit of that search: every curated case in this repository, why each plausible candidate was
rejected, and the evidence that the replacement is not just another navigation task wearing a costume.

## What exists

Four families, 28 cases, all inventoried at `60c4d5e9` / the current `main` of this repository.

| family | where | n | what they are |
|---|---|---:|---|
| Semantic Ripple | `RippleCases.kt` | 7 | rename method/type, change signature, move class — wide and narrow |
| DPAIA curated | `DpaiaCuratedCases.kt` | 19 | Spring/JHipster/train-ticket features, migrations and bug fixes |
| Repository understanding | `UnderstandingCase.kt` | 1 | `email-domain-mapper`, the previous round's case |
| IDE power features | `KeycloakRenameTest`, `KeycloakChangeSignatureTest`, `StructuralSearchYoutrackdbTest` | 3 | rename safety, signature change, SSR audit |

## Why each candidate was rejected

Five candidates were close enough to be argued about. They are listed in the order they were
considered, with the criterion that killed each.

**1. `understanding__keycloak__email-domain-mapper`** — the closest thing that exists, same repository,
same harness, a genuine two-mechanism change (a `META-INF/services` line and a model in
`OIDCLoginProtocolFactory.initBuiltIns()`).

Rejected on two counts, and the second is fatal. First, it is precisely the shape the brief excludes:
*"добавить один mapper/provider по очевидному соседнему примеру"* — twenty-two sibling mappers sit in
one directory, and the whole implementation is "copy one of them and change the claim". Second, it is
**saturated**: `FINDINGS.md` records 45/50 downstream successes at note lengths ≥ 2 000 and 20/20 at
5 000, with no arm difference at any clean length. A case whose ceiling is already reached cannot show
one arm reaching it earlier.

**2. `ripple__keycloak__rename-method-wide`** and the six other ripple cases — excluded by name in the
brief (§2: no rename, no find-all-usages, no change-signature). They stay what they were designed to
be: the positive control that semantic navigation exists. Worth keeping in the report precisely
because they are the thing this experiment must NOT reduce to.

**3. `dpaia__spring__petclinic-71`** (JPA → R2DBC) and **`dpaia__spring__boot__microshop-18`**
(`RestTemplate` → `WebClient`, 23 files across 3 modules) — architectural in vocabulary, but both are
migrations: 37 KB and 60 KB of gold patch, hours of mechanical editing after the understanding is in
place. That is hypothesis #3 ("fewer mistakes on long autonomous development"), which §4 explicitly
defers. Also both are sweeps: the understanding needed is "which call sites", i.e. the reference
question again.

**4. `dpaia__feature__service-125`** — a genuine cross-layer feature (entity + JPQL + service +
controller) with an overlay oracle. Rejected on repository size: `feature-service` is a single small
Spring application, and the hypothesis under test is about *a large unfamiliar repository*. There is no
multi-hop architecture to reconstruct when the whole tree fits in one listing. It also scored a ceiling
in an earlier round.

**5. `dpaia__piggymetrics-6`** (flapdoodle → TestContainers) — build/test infrastructure across
modules, small, and the discovery chain is one grep for the base class.

Nothing in the inventory has the required shape: **hard to understand, cheap to implement, in a tree
too large to read**. So a new case was designed.

## The new case, and the four candidates it beat

Six Keycloak mechanisms were evaluated against the same rubric (behavioural statement writable without
naming the targets; ≥ 2 registration mechanisms in different directories; a real invariant; a plain
JUnit oracle with no server, no database, no Mockito; and — the criterion that decided it — a shell
agent must not reach the whole gold set in two or three obvious commands).

| candidate | ≥2 mechanisms | plain-JUnit oracle | 3 shell commands reveal | verdict |
|---|---|---|---|---|
| **client policies + strict security profile** | `META-INF/services` **and** a three-file JSON chain | yes, precedent in-module | ~10 of 17 gold files, **no entry points** | **chosen** |
| declarative user profile validator | services file **and** default-profile JSON | yes | `find -iname '*userprofile*'` + the JSON name → most of it | rejected on shell exposure |
| required action seeded on new realms | services file **and** `DefaultRequiredActions` in another module | weak — needs a session | `find -iname '*RequiredAction*'` → SPI + impls | rejected on oracle |
| new `EventType` + listener | one enum flag, really | weak | two greps reveal everything | rejected on mechanisms |
| client registration policies | services file **and** `DefaultClientRegistrationPolicies` | partial | 8/8 gold files | rejected on shell exposure |
| client description converters | one | trivial | 7/7 gold files | rejected on depth |

### The chosen case

`acquisition__keycloak__cc-refresh-token`. An administrator-level statement: under the `strict`
security profile a confidential OpenID Connect client must never obtain a refresh token from the client
credentials grant; creating or updating such a client must be refused, an update that does not mention
the setting must still be refused when the stored client has it on, and a client created without
mentioning it must end up with it off.

The gold change is **four files, 215 added lines**: an executor, its factory, one line in a
`META-INF/services` file, and six lines in one shipped JSON profile. The research is a seven-hop chain
none of which the statement hints at — and the hops are documented as the fifteen-item checklist in
`AcquisitionCase.kt`.

### The measured evidence that it qualifies

**No leakage.** Every phrase of the statement matches 23–592 Java files. The narrowest conjunction a
reader can form — files mentioning both *"refresh token"* and *"client credentials"* — is 31 files, and
**not one of them is in `clientpolicy/` or `securityprofile/`**. The statement's vocabulary points at
the OIDC token endpoint, which is where the setting is honoured; the change lives where it is governed.

**Research depth.** Ten shell commands were recorded against the pinned checkout — written by someone
who already knew the answer, walking straight at every gold file with no wasted step. Scored by the
pre-registered detectors (`AcquisitionCalibrationTest`, offline, reproducible):

| after | `U_observed` | facts |
|---|---:|---|
| 3 commands | **0.07** | `I1` |
| 10 commands | **0.80** | everything except `A2`, `B1`, `B2` |

Three obvious commands buy one fact out of fifteen. Even the cheating ten never reach the two entry
points or the near-miss precedent — those need "where is this context constructed", which is the
question a listing cannot answer.

**Implementation tractability.** The gold compiles and the hidden oracle's eight tests pass on it
(`Tests run: 8, Failures: 0`, 1.6 s); on the pristine tree all eight fail with no errors. A probe
implementation that copies the obvious neighbour — validating the proposed representation only, never
consulting the stored client — fails **exactly one** test, `partialUpdateOfAClientThatAlreadyHasItOnIsRejected`.
The invariant is load-bearing and it is the only thing separating a copied answer from a correct one.

**Semantic non-triviality.** Five of the fifteen facts (`B2`, `E1`, `E2`, `G1`, `H1`) are not reference
questions at all: when executors run, where shipped configuration enters, which of three JSON files
selects which profile, and which of two data sources wins during a partial update. No `findUsages`
answers any of them, which is the guard against the checklist quietly becoming a benchmark for the very
tool under test.

## One thing the case does that the design did not intend

`keycloak-lax-client-policies.json` binds the same `oauth-2-1-for-confidential-client` profile that the
strict one does, so the new rule ships under **lax as well as strict**. The statement was rewritten to
promise only that a server running *without* a security profile is unaffected, which is true. Recorded
here because it was found by the oracle author, not by the designer, and because a checklist item that
had claimed "and no other profile" would have been false.
