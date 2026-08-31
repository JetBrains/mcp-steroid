# Oracle for `acquisition__keycloak__oauth-grant-type`

- Repository: `keycloak`, pinned commit `60c4d5e9321ff5462a772ceb896f8cb2e639e04b`
- Worktree used: `/Users/matvei.ludzskii/Work/keycloak-c3` (restored to pristine; `git status --porcelain` empty)
- Build: JDK 21 (`/Users/matvei.ludzskii/Library/Java/JavaVirtualMachines/jdk-21.0.11+10/Contents/Home`),
  `./mvnw -o -pl services clean test -Dtest=<...> -DfailIfNoTests=false`. `mvn install` / `-am` were never run.
- Framework: **JUnit 4** (`org.junit.Test` / `org.junit.Assert`). The `services` module (`keycloak-services`)
  has **no Mockito** dependency — verified by reading `services/pom.xml` (its only test deps are `junit`,
  `junit-jupiter-api`, `junit-jupiter-params`, `junit-platform-engine`, `hamcrest`, `greenmail`). All test
  doubles are `java.lang.reflect.Proxy` instances and small private static classes.
- No running server, no database, no network.

## Files written

| file | size |
|---|---|
| `oracle.patch` | 1195 lines (one new file, `@@ -0,0 +1,1189 @@`) |
| `ORACLE-EVIDENCE.md` | this file |

`gold.patch` was not modified. Nothing else in the case directory was touched.

### Test file

- Path: `services/src/test/java/org/keycloak/protocol/oidc/grants/OAuth2GrantTypeResidualContractTest.java`
- **1189 lines**, `10` `@Test` methods, `36` `Assert.*` call sites (24 written directly inside the
  `@Test` bodies, 12 in the two shared helpers `assertRefusedBeforeIssuing(...)` and the discovery routines).

## Discovery — how the implementation is found without naming it

The oracle never names the gold implementation, its factory, its grant-type URI, or its short code. The
implementation is located by the **union of two independent routes**, each measured against a baseline
captured on the pristine tree by a throw-away probe and then hardcoded:

1. **Compiled-class scan.** The module's classes directory is located through
   `RefreshTokenGrantType.class.getProtectionDomain().getCodeSource().getLocation()` (never a relative path;
   the code also handles a jar code source). Everything under `org/keycloak/protocol/oidc/grants/` is walked
   and every concrete, non-synthetic, non-anonymous class assignable to `OAuth2GrantType` (or, separately, to
   `OAuth2GrantTypeFactory`) that is not in the pinned baseline is a candidate. Each class is loaded inside
   its own `catch (Throwable)` so that a single unloadable class cannot abort the scan.
   Measured baseline: **10 grant classes** and **10 factory classes**.
2. **Provider SPI.** Grant ids resolvable through the provider registry, minus a pinned baseline of
   **9** ids. Nine, not ten: `PreAuthorizedCodeGrantTypeFactory` (`pc`) is gated behind a disabled
   `Profile.Feature` and is therefore compiled but not registered. Measuring this rather than assuming it
   is what stopped the registration axis from being wrong by one from the start.

When neither route finds anything, every behavioural axis **FAILS** with an explanatory message. Zero ERRORs
in every scenario below.

## Axis table

`P` = passes, `F` = fails, on a **pristine** tree (oracle only, gold absent).

| id | `@Test` name | obligation graded | pristine |
|---|---|---|---|
| A1 | `aNewGrantImplementationIsCompiledIntoTheModule` | the renewal operation exists as a compiled grant implementation (route 1 finds exactly one new one) | F |
| A2 | `theGrantIsRegisteredSoTheTokenEndpointCanDispatchToIt` | the `META-INF/services` hop: exactly one new id is live through the provider SPI, and the exact lookup `TokenEndpoint.checkGrantType()` performs (`session.getProvider(OAuth2GrantType.class, grantType)`) returns a grant — route 2 only, never requires A1 | F |
| A3 | `theGrantTypeUriAClientWouldSendIsExposed` | it exposes the `grant_type` value a client sends: non-blank, whitespace-free, distinct from all 10 shipped grant ids, an absolute URI (RFC 6749 §4.5 for extension grants), and its factory really creates a grant — union of both routes, so gradable before registration exists | F |
| A4 | `theTokenContextShortCodeIsGloballyUnique` | the short code stamped into issued tokens is globally unique — graded by driving the **real** `DefaultTokenContextEncoderProviderFactory` through its **real** `init`/`postInit` with a hand-written `KeycloakSessionFactory` stub serving every compiled grant factory (including the feature-gated one) plus the new one, plus an explicit "the shipped grant X already uses this code" check | F |
| A5 | `theGrantAppearsInThePublishedGrantTypesSupported` | the operation is discoverable: the list `grant_types_supported` is built from gains exactly one entry, and that entry equals the id the new factory itself declares (catching a declared-id/registered-id mismatch) | F |
| A6 | `aRequestWithoutACredentialIsRefusedAsAProtocolError` | a request carrying no credential is refused as a 4xx OAuth error before anything is minted; also grades the base-class contract (reading form params before handing the context to the base class NPEs here instead of producing a 4xx) | F |
| A7 | `anOrdinaryInteractiveCredentialIsRefusedBeforeAnyTokenIsMinted` | **the point of the case**: an ordinary (online) refresh token is REFUSED with `invalid_grant`, before issuance | F |
| A8 | `aLongLivedCredentialIsAcceptedAndHandedToTheShippedRenewalPath` | the accepting path delegates instead of reimplementing issuance | F |
| A9 | `anUnreadableCredentialIsAProtocolErrorNotAServerError` | an unparseable credential is a 4xx protocol error, not a leaked parse exception / 500 | F |
| A10 | `theShippedGrantsAreUnchanged` | "did not break anything": all 10 shipped grant classes still compiled, all 10 shipped factories still declaring the same id **and** short code, all 9 shipped ids still live through the SPI | **P** |

**A10 passes on a pristine tree BY CONSTRUCTION.** It is a floor, not partial progress — a score of 1/10
means nothing has been implemented. This is stated in the Javadoc on the method itself so that nobody reads
the floor as progress.

### Why `grant_types_supported` is derived, not read from a live document

`OIDCWellKnownProvider.getGrantTypesSupported()` is `private` and `getConfig()` needs a session bound to a
realm with resolved URIs — not reachable without infrastructure the module cannot give. A5 therefore
reproduces that method's derivation verbatim (the `OAuth2GrantType` factory stream mapped through
`ProviderFactory::getId`, concatenated with `OAuth2Constants.IMPLICIT`, sorted) over the same factory set.
This is a re-derivation, not a live read; it is flagged here rather than silently substituted.

### The behavioural harness — one shared, runtime-faithful set-up

`TokenEndpoint.processGrantRequest()` authenticates the client, builds the CORS context, handles DPoP,
**stamps the event type** (`event.event(grant.getEventType())`) and only then constructs
`OAuth2GrantType.Context` and calls `grant.process(context)`. The whole set-up lives in exactly one private
method, `invokeWith(String refreshToken)`, so no axis added later can forget it. It supplies:

- a **fresh grant instance per request**, built by its own factory wherever one is discoverable (which is
  how `session.getProvider(OAuth2GrantType.class, ...)` builds it);
- an `EventBuilder` whose **event type is already stamped** — `EventBuilder.error(String)` throws
  `IllegalStateException` when it is not, i.e. an unstamped event is a state the server never creates and
  grading it would punish an implementation for the harness's omission;
- a **real** `TokenManager` and a **real** `OIDCAdvancedConfigWrapper.fromClientModel(client)` in the
  context, because `OAuth2GrantTypeBase.setContext` casts both unconditionally;
- a live CORS context (a `Cors` proxy that turns a response builder into a response exactly as the shipped
  one does, so the refusal's HTTP status and `OAuth2ErrorRepresentation` error code are readable);
- an authenticated client and a realm in the session context;
- `grant_type` in the form parameters set to the identifier the grant itself declares.

Refusal vs. delegation is observed with a **delegation marker**: the stub session's `clientPolicy()` throws
a private marker exception. The shipped renewal grant triggers the client-policy chain as its first step
after it has read the credential, so reaching the marker proves the request was handed on to the shipped
issuance path, and *not* reaching it proves nothing was minted. This was validated against the shipped
`RefreshTokenGrantType` before the oracle was written: it reaches the marker for an **online**, an
**offline** *and* a **garbage** token alike — i.e. the shipped precedent does not discriminate, which is
exactly why A7 and A9 are a real delta and not a restatement of existing behaviour.

## Verdicts (verbatim, every run preceded by `clean`)

Command in every case:
`./mvnw -o -pl services clean test -Dtest=OAuth2GrantTypeResidualContractTest -DfailIfNoTests=false`

### V1 — gold + oracle

```
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.989 s -- in org.keycloak.protocol.oidc.grants.OAuth2GrantTypeResidualContractTest
```

Failing tests: none. **10/10.**

### V2 — oracle only, pristine sources

```
[ERROR] Tests run: 10, Failures: 9, Errors: 0, Skipped: 0, Time elapsed: 0.909 s <<< FAILURE! -- in org.keycloak.protocol.oidc.grants.OAuth2GrantTypeResidualContractTest
```

Failing (all FAILURE, **0 ERRORS**): `theGrantIsRegisteredSoTheTokenEndpointCanDispatchToIt`,
`anUnreadableCredentialIsAProtocolErrorNotAServerError`, `theGrantAppearsInThePublishedGrantTypesSupported`,
`theGrantTypeUriAClientWouldSendIsExposed`, `aLongLivedCredentialIsAcceptedAndHandedToTheShippedRenewalPath`,
`anOrdinaryInteractiveCredentialIsRefusedBeforeAnyTokenIsMinted`,
`aRequestWithoutACredentialIsRefusedAsAProtocolError`, `aNewGrantImplementationIsCompiledIntoTheModule`,
`theTokenContextShortCodeIsGloballyUnique`.

Passing: `theShippedGrantsAreUnchanged` (A10) only — as designed. **1/10.**

### V3 — partial: the two new implementation sources from gold, **without** the ServiceLoader line

```
[ERROR] Tests run: 10, Failures: 2, Errors: 0, Skipped: 0, Time elapsed: 0.981 s <<< FAILURE! -- in org.keycloak.protocol.oidc.grants.OAuth2GrantTypeResidualContractTest
```

Failing: `theGrantIsRegisteredSoTheTokenEndpointCanDispatchToIt` (A2),
`theGrantAppearsInThePublishedGrantTypesSupported` (A5).

Passing: A1, A3, A4, A6, A7, A8, A9, A10. **8/10** — clearly intermediate: everything that can be judged
from the code itself is satisfied; the two consequences of the missing registration are not.

### V4 — V3 + the ServiceLoader registration line

```
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.987 s -- in org.keycloak.protocol.oidc.grants.OAuth2GrantTypeResidualContractTest
```

Failing: none. **10/10.**

**Honest deviation from the brief:** the brief expected V4 to make *one* more axis pass; it makes **two**
(A2 and A5). That is not a cascade artefact, it is the shape of the case: `CASE-EVIDENCE.md` §4 Mechanism A
states that without the line the endpoint answers `unsupported_grant_type` **and** "the grant is *also*
silently missing from `grant_types_supported`, because discovery enumerates the same factory stream". One
edit, two distinct runtime consequences, graded separately on purpose — a wrong `getId()` (Mechanism C)
fails A5 while A2 still passes. Collapsing them into one axis would have hidden that. The alternative —
deriving A5 from the union of both routes so it passes at V3 — was rejected because it would claim the
metadata advertises a grant that at runtime it does not.

### V5 — naive: full gold, but the factory reuses the nearest neighbour's short code

Variant: `public static final String GRANT_SHORTCUT = RefreshTokenGrantTypeFactory.GRANT_SHORTCUT;` — the
exact trap `CASE-EVIDENCE.md` §5 names ("reuse the neighbour's constant, since my grant *is* a refresh").

```
[ERROR] Tests run: 10, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.958 s <<< FAILURE! -- in org.keycloak.protocol.oidc.grants.OAuth2GrantTypeResidualContractTest
```

Failing: `theTokenContextShortCodeIsGloballyUnique` (A4) only, with

```
java.lang.AssertionError: The new grant declares the short code 'rt', which the shipped grant 'refresh_token'
already uses. The server writes this code into the tokens it issues, so two grants sharing it make a token's
origin ambiguous - and the server refuses to start rather than allow that
```

**9/10.**

> **A real defect this scenario caught, and how it was fixed.** On the first run V5 produced
> `Tests run: 10, Failures: 1, Errors: 8`. A duplicate short code makes
> `ResteasyKeycloakSessionFactory.init()` itself throw (the encoder's `postInit` runs during bootstrap), so
> every axis that touched the provider registry ERRORed — the duplicate would have cost **eight** points
> instead of one, precisely the cascade the brief forbids. Fixed by (a) making the registry bootstrap
> failure-tolerant (caught once, reported on stderr), (b) falling back to walking `META-INF/services`
> directly and applying the same enablement gate the provider manager applies
> (`scope.getBoolean("enabled", true)` plus `EnvironmentDependentProviderFactory.isSupported(scope)`), and
> (c) serving a fully synthetic session (with a non-recording `TracingProvider` span) to the behavioural
> harness when the registry is down. All other verdicts were re-measured after the fix and were unchanged.

### V6 — robustness: full gold with a defensive guard removed

Variant: the `JWSInputException` branch of the credential-kind check collapses to `return false` (an
unreadable token is simply "not the right kind"), and the two `event.detail(Details.REASON, ...)` audit
annotations are dropped — i.e. the implementation is exactly as defensive as the shipped precedent and no
more.

```
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.974 s -- in org.keycloak.protocol.oidc.grants.OAuth2GrantTypeResidualContractTest
```

Failing: none. **10/10, zero errors** — the oracle does not punish an implementation for following the
shipped precedent. This is why A6 and A9 grade "a 4xx OAuth protocol error, nothing minted" without pinning
the error code, and why no axis requires an event detail to be stamped.

### V7 (extra, not requested) — naive: full gold, but `process` only calls `super.process(context)`

Included to prove the behavioural axes do not ride along with the structural ones.

```
[ERROR] Tests run: 10, Failures: 2, Errors: 0, Skipped: 0, Time elapsed: 0.919 s <<< FAILURE! -- in org.keycloak.protocol.oidc.grants.OAuth2GrantTypeResidualContractTest
```

Failing: `anOrdinaryInteractiveCredentialIsRefusedBeforeAnyTokenIsMinted` (A7),
`anUnreadableCredentialIsAProtocolErrorNotAServerError` (A9). All eight structural/other axes pass.
**8/10** — a grant that is wired perfectly but forgets to refuse loses exactly the two points that measure
refusal.

## Score scale

| tree | score |
|---|---|
| pristine (nothing implemented) | **1** |
| implementation sources only, no registration (V3) | **8** |
| + ServiceLoader registration = gold (V1 / V4) | **10** |

As a list: **1 → 8 → 10**.

Off-ladder variants, showing the failures are isolated:

| tree | score | axis lost |
|---|---|---|
| gold with the neighbour's short code reused (V5) | **9** | A4 only |
| gold that delegates without checking the credential kind (V7) | **8** | A7, A9 only |
| gold with the defensive guard removed (V6) | **10** | none |

No scenario collapses to "everything fails" or "everything passes": the scale takes 4 distinct values
(1, 8, 9, 10) across six trees, and three different single-obligation defects each cost a different,
identifiable subset of points.

## Regression sweep

With **gold + oracle** applied, `./mvnw -o -pl services clean test`:

| scope | result |
|---|---|
| `-Dtest='org.keycloak.protocol.oidc.**'` (the neighbouring shipped tests of this subsystem: `TokenManagerTest`, `DefaultTokenContextEncoderProviderTest`, `PreAuthorizedCodeGrantTypeTest`, `AuthzEndpointRequestParserSanitizationTest`, `OIDCAttributeMapperHelperTest`, `ResourceIndicatorValidationTest`, `ClientHostUtilsTest`, `RedirectUtilsTest`, + the oracle) | `Tests run: 91, Failures: 0, Errors: 0, Skipped: 0` |
| whole `services` module | `Tests run: 599, Failures: 0, Errors: 0, Skipped: 2` |
| whole `services` module, **control**: pristine, oracle absent | `Tests run: 589, Failures: 0, Errors: 0, Skipped: 2` |

599 − 589 = 10 — exactly the oracle's own tests. The 2 skips are pre-existing. The oracle builds a provider
registry as static state; the full-module run confirms it does not disturb any other test.

## Registration

- `failToPass` string (fully-qualified test class name):
  **`org.keycloak.protocol.oidc.grants.OAuth2GrantTypeResidualContractTest`**
- Assertion count: **36** `Assert.*` call sites across **10** `@Test` methods (24 written directly in the
  test bodies + 12 in shared helpers).

Per-axis assertion count as executed (helper assertions attributed to the axis that reaches them):

| axis | assertions |
|---|---|
| A1 | 1 |
| A2 | 3 |
| A3 | 6 |
| A4 | 4 |
| A5 | 2 |
| A6 | 4 (all via `assertRefusedBeforeIssuing`) |
| A7 | 5 (4 via `assertRefusedBeforeIssuing` + 1) |
| A8 | 2 |
| A9 | 4 (all via `assertRefusedBeforeIssuing`) |
| A10 | 5 statements, executed over 10 classes / 10 factories / 9 ids |

## Patch hygiene

`oracle.patch` is `git diff` against the pinned commit and contains **only** the new test file.

`git apply --check` on a pristine checkout, all five modes verified:

| # | invocation | result |
|---|---|---|
| A | `git apply --check oracle.patch` | OK |
| B | `git apply --check oracle.patch gold.patch` | OK |
| C | `git apply --check gold.patch oracle.patch` | OK |
| D | apply `gold.patch`, then `git apply --check oracle.patch` | OK |
| E | apply `oracle.patch`, then `git apply --check gold.patch` | OK |

Textual audit of `oracle.patch` (`grep -c -F`):

| token | occurrences |
|---|---|
| `Mockito` | 0 |
| `mock(` | 0 |
| `OfflineRefreshTokenGrantType` (gold's implementation and factory class names) | 0 |
| `offline-refresh` (gold's grant-type URI) | 0 |
| `OFFLINE_REFRESH` (gold's grant-id constant) | 0 |
| `GRANT_SHORTCUT` (gold's short-code constant) | 0 |
| `isUnitTestMode` | 0 |

**One deliberate, documented exception.** The word "offline" appears in three assertion *messages* and the
two token kinds are built from `TokenUtil.TOKEN_TYPE_OFFLINE` / `TokenUtil.TOKEN_TYPE_REFRESH`. These are
**shipped platform constants** in `core` (`"Offline"` / `"Refresh"`, the `typ` claim the server has always
written into refresh tokens), not gold's naming: they are the wire representation of "long-lived credential"
versus "interactive-session credential" that predates the change by years. Referencing them is how the
oracle expresses the *behavioural* distinction without knowing what the solving agent will call its class,
its grant type, or its short code.

## Assertions weakened or re-scoped, and why

1. **A6 and A9 do not pin the OAuth error code**, only "a 4xx protocol error, nothing minted". Gold answers
   `invalid_request` for a missing token and `invalid_grant` for an unreadable one, but an implementation
   that is exactly as defensive as the shipped precedent legitimately reports a different (still 4xx) code.
   Pinning would have failed V6. A7 *does* pin `invalid_grant`, because the problem statement demands
   "rejected with a standard *invalid grant* error".
2. **No axis requires an audit-event detail to be stamped.** Gold sets `Details.REASON` before
   `event.error(...)`; requiring it would punish a precedent-following implementation (V6).
3. **A3 does not assert the grant-type literal.** The solving agent will pick its own URI, so A3 grades the
   *properties* a wire value must have (non-blank, whitespace-free, absolute URI per RFC 6749 §4.5, distinct
   from all shipped ids) rather than gold's string. Same for the short code in A4: uniqueness, not `"or"`.
4. **A5 re-derives `grant_types_supported` instead of reading a live discovery document** — see the
   dedicated note above. This is a re-derivation of a `private` method, flagged rather than silently
   substituted.
5. **A8 grades delegation indirectly.** Whether the accepting path re-uses the shipped issuance code cannot
   be observed without a token store and a user session. It is graded by the deepest point reachable without
   infrastructure: the client-policy trigger the shipped renewal grant performs first. An implementation
   that fully reimplements issuance without triggering client policies would fail A8; that is the intended
   reading of obligation D1 ("delegate, do not reimplement"), but it is an *indirect* observation and is
   flagged as such.
6. **A2's fallback path.** When the provider registry cannot boot (only reachable via the duplicate-short-code
   trap), A2 resolves the registered factory from the `META-INF/services` walk and calls `create(...)` rather
   than `session.getProvider(...)`. In that state the real server would not start at all, so A2 arguably
   "should" fail too — it is deliberately allowed to pass so that the boot-breaking defect costs exactly the
   one axis that owns it (A4).

## Remaining states the oracle exercises that the runtime cannot produce

Disclosed in full:

1. **The audit event is queued instead of written through.** `invokeWith(...)` calls
   `event.storeImmediately(false)`; `TokenEndpoint` leaves the default, so `EventBuilder.error(...)` writes
   through immediately. The immediate path calls
   `KeycloakModelUtils.runJobInTransaction(...)`, which opens a nested transaction and re-reads the realm
   through `session.realms()` — unreachable without a database. `storeImmediately` is public API and both
   modes are ordinary runtime states; the grant cannot observe the difference (it neither reads the event
   back nor sees a return value). Commented at the call site.
2. **The realm and the client are neutral proxies**: every getter answers `null` / `false` / an empty
   stream. The runtime has populated models. No path the oracle grades reads a realm or client attribute
   (the shipped renewal grant reaches `session.clientPolicy()` before touching either), but an
   implementation that consulted, say, a realm attribute before its credential-kind check would see `null`
   here where the server would see a value.
3. **The session context carries no HTTP request, response or headers** (`null`), and no DPoP state. The
   runtime always has them. Again unused by the graded paths, but an implementation that read the HTTP
   request before refusing would NPE here.
4. **`session.clientPolicy()` never returns** — it throws the delegation marker. In the runtime it returns
   a manager and the request continues into issuance. This is the instrumentation itself: it is the only way
   to observe "handed on vs. refused" without a token store, and it is applied uniformly to every behavioural
   axis, so no axis can be satisfied by an implementation that merely avoids the marker.
5. **The uniqueness axis enumerates every *compiled* grant factory, including the feature-gated one.** A
   running server with default features would only see nine. This is deliberately stricter: the invariant is
   global and a profile can enable the tenth, so a short code colliding with a gated grant is still a latent
   boot failure. It is stricter than the runtime, never laxer.

## Worktree state

`/Users/matvei.ludzskii/Work/keycloak-c3`: `git status --porcelain` → empty; `git rev-parse HEAD` →
`60c4d5e9321ff5462a772ceb896f8cb2e639e04b`. No commits were made; `~/.m2` was never written to
(`mvnw` was always run with `-o`, and never with `install` or `-am`).

## The re-weighted contract: `oracle-v2.patch`

`OAuth2GrantTypeWiringAndInvariantContractTest` grades six of the ten axes above and is what the case
declares. It exists because the ten-axis reading, taken **per axis** on the note wave's anchors rather
than as a ladder aggregate, showed four axes measuring only whether an implementation was written:

| axis | gold note ×3 | no note ×2 | retained? |
|---|---|---|---|
| A2 `theGrantIsRegisteredSoTheTokenEndpointCanDispatchToIt` | P | **F** | yes |
| A5 `theGrantAppearsInThePublishedGrantTypesSupported` | P | **F** | yes |
| A7 `anOrdinaryInteractiveCredentialIsRefusedBeforeAnyTokenIsMinted` | P | **F** | yes |
| A9 `anUnreadableCredentialIsAProtocolErrorNotAServerError` | P | **F** | yes |
| A4 `theTokenContextShortCodeIsGloballyUnique` | P | P | yes — trap |
| A10 `theShippedGrantsAreUnchanged` | P | P | yes — trap, true of a pristine tree |
| A1 `aNewGrantImplementationIsCompiledIntoTheModule` | P | P | **no** |
| A3 `theGrantTypeUriAClientWouldSendIsExposed` | P | P | **no** |
| A6 `aRequestWithoutACredentialIsRefusedAsAProtocolError` | P | P | **no** |
| A8 `aLongLivedCredentialIsAcceptedAndHandedToTheShippedRenewalPath` | P | P | **no** |

Source cells: gold `1044788466` / `1044788468` / `1044788470`, no-note `1044788472` / `1044803876`, all
read from the surefire XML embedded in each build log, each total reproducing the published one.

Re-scaled from the trees already measured above: pristine **1** (A10 alone — A4 needs a new factory to
exist and `factoryUnderTest()` fails without one), V3 implementation-only **4**, V5 naive short code
**5**, V7 delegate-without-checking **4** losing a different pair, gold **6**.

### The independence caveat, stated rather than discovered later

Two pairs among the retained six have never been observed to move apart:

- **A2 and A5** flip together on the ServiceLoader line. That is defended in the case's own record —
  the line has two distinct runtime consequences, dispatch and discovery, and a wrong grant URI fails
  discovery while dispatch still works — but no tree measured so far separates them.
- **A7 and A9** fail together in every tree that has lost either: V7 loses both, and both no-note
  anchors lose both. V6, which removed a defensive guard, lost neither.

So the six-point scale may in practice take fewer levels than six, and the honest description of it
today is "three groups plus two traps". The tree that would settle A7 against A9 — one that checks the
credential kind but still leaks a parse failure — does not exist yet and is the next artifact worth
writing if the wave's resolution turns out to be what limits the reading.
