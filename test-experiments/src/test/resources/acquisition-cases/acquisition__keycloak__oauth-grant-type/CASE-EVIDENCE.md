# Case `acquisition__keycloak__oauth-grant-type`

- Repository: `keycloak`, pinned commit `60c4d5e9321ff5462a772ceb896f8cb2e639e04b`
- Gold worktree used: `/Users/matvei.ludzskii/Work/keycloak-c3` (restored to pristine; `git status --porcelain` empty)
- Gold status: **implemented and compiling.** `./mvnw -o -pl services clean test-compile -DskipTests` → `BUILD SUCCESS`
  (only pre-existing deprecation warnings in unrelated SAML/session test sources).
- `git apply --check gold.patch` on the pristine checkout → **OK** (exit 0, worktree unchanged afterwards).
- Gold diffstat:

```
 .../oidc/grants/OfflineRefreshTokenGrantType.java  | 68 ++++++++++++++++++++++
 .../OfflineRefreshTokenGrantTypeFactory.java       | 58 ++++++++++++++++++
 ...oak.protocol.oidc.grants.OAuth2GrantTypeFactory |  1 +
 3 files changed, 127 insertions(+)
```

Gold file set (3 paths):

1. `services/src/main/java/org/keycloak/protocol/oidc/grants/OfflineRefreshTokenGrantType.java` (new)
2. `services/src/main/java/org/keycloak/protocol/oidc/grants/OfflineRefreshTokenGrantTypeFactory.java` (new)
3. `services/src/main/resources/META-INF/services/org.keycloak.protocol.oidc.grants.OAuth2GrantTypeFactory` (modified, +1 line)

---

## 0. Inventory of existing grant factories and their shortcuts (at the pinned commit)

Registered in `services/src/main/resources/META-INF/services/org.keycloak.protocol.oidc.grants.OAuth2GrantTypeFactory`
(10 lines at the pinned commit):

| Factory (`services/src/main/java/org/keycloak/protocol/oidc/grants/…`) | `getId()` | `getShortcut()` | feature gate (`isSupported(Config.Scope)`) |
|---|---|---|---|
| `AuthorizationCodeGrantTypeFactory` | `OAuth2Constants.AUTHORIZATION_CODE` | `ac` | — |
| `ClientCredentialsGrantTypeFactory` | `OAuth2Constants.CLIENT_CREDENTIALS` | `cc` | — |
| `PermissionGrantTypeFactory` | `OAuth2Constants.UMA_GRANT_TYPE` | `pg` | — |
| `RefreshTokenGrantTypeFactory` | `OAuth2Constants.REFRESH_TOKEN` | `rt` | — |
| `ResourceOwnerPasswordCredentialsGrantTypeFactory` | `OAuth2Constants.PASSWORD` | `ro` | — |
| `TokenExchangeGrantTypeFactory` | `OAuth2Constants.TOKEN_EXCHANGE_GRANT_TYPE` | `te` | `TOKEN_EXCHANGE` or `TOKEN_EXCHANGE_STANDARD_V2` |
| `ciba/CibaGrantTypeFactory` | `OAuth2Constants.CIBA_GRANT_TYPE` | `ci` | `Profile.Feature.CIBA` |
| `device/DeviceGrantTypeFactory` | `OAuth2Constants.DEVICE_CODE_GRANT_TYPE` | `dg` | `Profile.Feature.DEVICE_FLOW` |
| `PreAuthorizedCodeGrantTypeFactory` | `PreAuthorizedCodeGrant.PRE_AUTH_GRANT_TYPE` | `pc` | `Profile.Feature.OID4VC_VCI_PREAUTH_CODE` |
| `JWTAuthorizationGrantTypeFactory` | `OAuth2Constants.JWT_AUTHORIZATION_GRANT` | `ag` | `Profile.Feature.JWT_AUTHORIZATION_GRANT` |

Taken shortcut namespace: `ac, cc, pg, rt, ro, te, ci, dg, pc, ag`. **`or` is free** — that is the shortcut the gold takes.

Verification that the feature does not already exist:

```
$ grep -ril 'offline-refresh' --include=*.java .              → 0
$ find . -not -path '*/target/*' \( -iname '*offline*grant*' -o -iname '*grant*offline*' \)   → 0
$ grep -ril 'offline token' --include=*.java services/src/main/java/org/keycloak/protocol/oidc/grants   → 0
```

There is no grant-typed entry point for offline sessions at this commit: offline refresh tokens are accepted by the
generic `refresh_token` grant and are indistinguishable from interactive session renewal at the `grant_type` level.

### What the gold adds

A new OAuth 2.0 extension grant `urn:ietf:params:oauth:grant-type:offline-refresh` (shortcut `or`) that renews access
tokens **only** for offline sessions: it rejects an ordinary (online) refresh token with `invalid_grant`, and otherwise
delegates the whole issuance path to the existing refresh grant. It is deliberately implemented by extending the
existing refresh grant rather than duplicating token issuance, and it is picked up by discovery automatically.

---

## 1. Behavioural problem statement (draft, no file/class/package/provider-id names)

> Long-running background integrations and interactive applications currently renew their access tokens through the
> very same request shape, so an administrator cannot tell them apart, cannot allow one while forbidding the other,
> and cannot audit them separately. We want a dedicated, separately named renewal operation at the token endpoint that
> is reserved for long-lived, non-interactive credentials: a caller that presents a long-lived credential to it gets a
> new access token exactly as before, while a caller that presents an ordinary interactive-session credential is
> rejected with a standard "invalid grant" error rather than silently succeeding. The new operation must appear in the
> server's published capability metadata so that clients can detect it. Invariant: every renewal operation the server
> offers must carry a distinct short identifier that the server uses internally to stamp issued tokens; two operations
> sharing that short identifier must not be possible — the server must refuse to start rather than issue tokens whose
> origin is ambiguous. Rejecting the wrong credential kind must happen before any new token is minted, and the failure
> must be an ordinary protocol error, not a server error.

## 2. Leakage audit

Counts are `grep -ril '<phrase>' --include=*.java . | wc -l` from the repo root on the **pristine** tree
(gold files absent), run for real:

| phrase | java files matched | any hit in a gold directory? |
|---|---|---|
| `offline token` | 35 | no (0 in `services/src/main/java/org/keycloak/protocol/oidc/grants`) |
| `offline session` | 58 | no |
| `refresh token` | 85 | precedent only (`RefreshTokenGrantType.java`), not a gold file |
| `grant type` | 24 | no |
| `token endpoint` | 32 | no |
| `long-lived credential` | 1 | no |
| `background job` | 0 | — |
| `audit` | 38 | no |

Narrowest two-phrase conjunctions (files matching **both**):

- `offline token` ∧ `grant type` → **1** file: `server-spi-private/src/main/java/org/keycloak/models/Constants.java`
  — a constants holder; **0** of those hits are in `services/src/main/java/org/keycloak/protocol/oidc/grants`.
- `offline token` ∧ `token endpoint` → **1** file (same shape).

No phrase of the statement localizes the gold: the tightest conjunction lands on a generic constants file in a
different module, and the grant package itself contains **zero** occurrences of `offline token` before the patch.
No rewrite was needed.

## 3. The discovery chain (exact paths, and why each hop is not deducible from the previous one)

1. `services/src/main/java/org/keycloak/protocol/oidc/endpoints/TokenEndpoint.java`
   — `processGrantRequest()` reads `OIDCLoginProtocol.GRANT_TYPE_PARAM`, `checkGrantType()` resolves
   `session.getProvider(OAuth2GrantType.class, grantType)` and calls `grant.process(context)`.
   *Not deducible from the statement:* the statement speaks of "a renewal operation"; nothing says the token endpoint
   dispatches by a **provider lookup keyed on the raw `grant_type` string** instead of a switch/if-chain, i.e. that
   adding an operation means adding a provider, not editing the endpoint.

2. `server-spi-private/src/main/java/org/keycloak/protocol/oidc/grants/OAuth2GrantType.java`
   — the SPI: `getEventType()`, `getTokenParameterNames()`, `process(Context)`, `Context` (carries `formParams`,
   `event`, `cors`, `tokenManager`, `grantType`), and `isTokenAllowed(session, token)`.
   *Not deducible from hop 1:* the endpoint only shows the interface **name**; that the grant receives a prepared
   `Context` value object (already client-authenticated, CORS-configured, DPoP-handled) and must not redo those steps
   is only visible in the SPI + endpoint ordering.

3. `server-spi-private/src/main/java/org/keycloak/protocol/oidc/grants/OAuth2GrantTypeFactory.java`
   — extends the provider factory with **`String getShortcut()`** alongside `getId()`.
   *Not deducible from hop 2:* `OAuth2GrantType` says nothing about a shortcut; the second identifier only exists on
   the factory, and its purpose is documented nowhere in the grant package.

4. `services/src/main/java/org/keycloak/protocol/oidc/grants/RefreshTokenGrantType.java` +
   `services/src/main/java/org/keycloak/protocol/oidc/grants/RefreshTokenGrantTypeFactory.java` (shortcut `rt`)
   — the nearest neighbour and the delegation target (`super.process(context)`); also
   `services/src/main/java/org/keycloak/protocol/oidc/grants/OAuth2GrantTypeBase.java` (`setContext`, protected
   `formParams`/`event`/`cors`/`tokenManager`, `useRefreshToken()`).
   *Not deducible from hop 3:* the SPI does not reveal that a base class already implements client checks, MTLS-HoK
   binding, client-policy triggering and response building — a naive implementation reimplements token issuance.

5. `services/src/main/resources/META-INF/services/org.keycloak.protocol.oidc.grants.OAuth2GrantTypeFactory`
   — the ServiceLoader registration; **without a line here the grant is invisible and the endpoint answers
   `unsupported_grant_type`**, with no compile error.
   *Not deducible from hop 4:* the Java sources contain no reference to this resource file; a compiling, fully correct
   implementation silently does nothing.

6. `services/src/main/java/org/keycloak/protocol/oidc/encode/DefaultTokenContextEncoderProviderFactory.java`
   — `postInit()` builds `grantsByShortcuts` / `grantsToShortcuts` from every `OAuth2GrantType` factory and throws
   `IllegalStateException` when the two maps differ in size.
   *Not deducible from hop 5:* this is a **different subsystem** (`protocol/oidc/encode`, token-context encoding used
   to stamp issued tokens). Nothing in the grants package, the SPI, or the registration file mentions it; it is only
   reachable by asking "who calls `getShortcut()`" — and only if one already knows the shortcut exists (hop 3).

7. `services/src/main/java/org/keycloak/protocol/oidc/OIDCWellKnownProvider.java`
   — `getGrantTypesSupported()` streams `getProviderFactoriesStream(OAuth2GrantType.class).map(ProviderFactory::getId)`
   and concatenates `OAuth2Constants.IMPLICIT`, so `grant_types_supported` updates **automatically**.
   *Not deducible from hop 6:* the requirement "must appear in published metadata" naturally suggests editing a
   hardcoded list (as `DEFAULT_RESPONSE_TYPES_SUPPORTED` etc. are hardcoded right next to it); the correct answer is
   that **no edit is needed** — and that a wrong `getId()` therefore corrupts discovery silently.

## 4. The two-plus integration mechanisms

**Mechanism A — ServiceLoader registration (resource file, different source root):**
`services/src/main/resources/META-INF/services/org.keycloak.protocol.oidc.grants.OAuth2GrantTypeFactory`.
A "copy the neighbour, write the class" implementation that stops at Java compiles perfectly and produces **zero**
warnings. Failure mode: **runtime, silent** — `TokenEndpoint.checkGrantType()` gets `null` from
`session.getProvider(OAuth2GrantType.class, "urn:ietf:params:oauth:grant-type:offline-refresh")` and returns
`unsupported_grant_type`; the grant is also **silently missing from `grant_types_supported`** in
`.well-known/openid-configuration`, because discovery enumerates the same factory stream.

**Mechanism B — global shortcut uniqueness enforced in the token-context encoder (different directory):**
`services/src/main/java/org/keycloak/protocol/oidc/encode/DefaultTokenContextEncoderProviderFactory.postInit()`.
It inserts every factory into `grantsByShortcuts` (keyed by shortcut) and `grantsToShortcuts` (keyed by grant id) and
then compares sizes. A "copy `RefreshTokenGrantTypeFactory` and change the id" implementation keeps
`GRANT_SHORTCUT = "rt"`. Failure mode: **server start-up crash**, `IllegalStateException("Different lengths of maps.
grantsByShortcuts.size=… , grantsToShortcuts.size=… . Make sure that there is no OAuth2GrantType implementation with
same ID or shortcut like other grants")`. Note the trap is asymmetric: nothing fails at compile time, nothing fails in
the grants package, and the message names a subsystem the author never touched.

**Mechanism C (weaker, but real) — discovery is derived, not declared:**
`OIDCWellKnownProvider.getGrantTypesSupported()`. An implementation that adds its grant to a hardcoded list somewhere
would produce a duplicate/incoherent `grant_types_supported`; an implementation whose `getId()` does not equal the
wire `grant_type` value advertises a value the endpoint cannot serve — **runtime, silent**, visible only to a client
that trusts discovery.

**Mechanism D — the base-class contract:** `OAuth2GrantTypeBase.setContext(context)` must run before
`formParams`/`event`/`cors` are touched. The gold calls `setContext(context)` first and then delegates to
`super.process(context)`. An implementation that reads `formParams` before `setContext` NPEs at runtime on the first
request.

## 5. The invariant and the misleading precedent

**Invariant.** Every `OAuth2GrantTypeFactory` must expose a shortcut that is unique across the whole server, because
the shortcut — not the grant id — is what the token-context encoder stamps into issued tokens; the uniqueness is
checked once, at start-up, in a different subsystem, and violating it makes the server refuse to boot. Behaviourally:
"two renewal operations must never share the short identifier the server writes into the tokens it issues."

**The misleading precedent.** The nearest neighbour is
`services/src/main/java/org/keycloak/protocol/oidc/grants/RefreshTokenGrantTypeFactory.java`, whose body invites a
verbatim copy:

```java
public class RefreshTokenGrantTypeFactory implements OAuth2GrantTypeFactory {

    public static final String GRANT_SHORTCUT = "rt";

    @Override
    public String getId() {
        return OAuth2Constants.REFRESH_TOKEN;
    }

    @Override
    public String getShortcut() {
        return GRANT_SHORTCUT;
    }
```

The lines that mislead: `GRANT_SHORTCUT` is a **public constant** on the neighbour, so the copying author's most
natural move — "reuse the neighbour's constant, since my grant *is* a refresh" (`return
RefreshTokenGrantTypeFactory.GRANT_SHORTCUT;`) — is both idiomatic-looking and fatal. The javadoc on
`OAuth2GrantTypeFactory.getShortcut()` is the only warning, and nothing in the grants package shows the consumer.

A second, softer trap in the same precedent: `RefreshTokenGrantType.getTokenParameterNames()` returns
`Collections.emptySet()` and `getEventType()` returns `EventType.REFRESH_TOKEN`; inheriting them is **correct** here
(the wire parameter is still `refresh_token`), but an author who "improves" this by inventing a new `EventType`
constant lands in another module (`server-spi`) and cannot compile against the installed snapshot.

## 6. Shell audit (commands actually run, pristine tree)

| # | command | result | gold paths revealed |
|---|---|---|---|
| 1 | `grep -ril 'offline token' --include=*.java .` | 35 files; 0 in `…/protocol/oidc/grants`; 0 matching the registration file, the encoder factory or the well-known provider | 0 / 3 |
| 2 | `find . -not -path '*/target/*' \( -iname '*offline*grant*' -o -iname '*grant*offline*' \)` | 0 files | 0 / 3 |
| 3 | `grep -rl 'grant_type' --include=*.java services/src/main/java` | 6 files, none of them a gold path (and none of them the encoder factory or the well-known provider) | 0 / 3 |

**Fraction of the gold file set revealed: 0/3 (0%).** Two of the three gold paths do not exist before the patch and
cannot be found by any search; the third — the ServiceLoader registration file — is a `META-INF/services` resource
whose *name* is a fully-qualified class name, so it is missed by every `--include=*.java` search and by every
filename search on the feature vocabulary. What stays hidden after all three commands: the registration file, the
start-up uniqueness check in `protocol/oidc/encode`, and the fact that `grant_types_supported` is derived from the
factory stream. Well below the "half revealed" threshold.

## 7. Draft checklist — 15 atomic architectural facts

Each fact carries 1–3 EVIDENCE BUNDLES; a bundle is 2–3 literal tokens that must ALL appear in ONE tool result.

### PRECEDENT

- **A1.** The nearest existing analogue is the refresh-token grant, implemented as `RefreshTokenGrantType` with the
  factory `RefreshTokenGrantTypeFactory`, whose shortcut constant is `"rt"`.
  - Bundle 1: `RefreshTokenGrantTypeFactory` + `GRANT_SHORTCUT` + `"rt"`
  - Bundle 2: `class RefreshTokenGrantType extends OAuth2GrantTypeBase` + `EventType.REFRESH_TOKEN`
- **A2.** Ten grant factories are registered at this commit and their shortcuts (`ac, cc, pg, rt, ro, te, ci, dg, pc,
  ag`) form a namespace with no free duplicate; the new grant must pick an unused one.
  - Bundle 1: `AuthorizationCodeGrantTypeFactory` + `RefreshTokenGrantTypeFactory` + `JWTAuthorizationGrantTypeFactory`
  - Bundle 2: `return "ro";` + `return "te";` + `return "pc";`
- **A3.** Five of those factories are feature-gated via `isSupported(Config.Scope)` + `Profile.isFeatureEnabled`, so
  gating is an available, precedented shape — and the gold deliberately does **not** use it (no new `Profile.Feature`
  can be added from `services` alone).
  - Bundle 1: `isSupported(Config.Scope` + `Profile.isFeatureEnabled` + `Profile.Feature.DEVICE_FLOW`

### ENTRY_POINT

- **B1.** The token endpoint dispatches grants by provider lookup on the raw `grant_type` form parameter, not by a
  hardcoded branch; an unknown value yields `unsupported_grant_type`.
  - Bundle 1: `session.getProvider(OAuth2GrantType.class, grantType)` + `newUnsupportedGrantTypeException`
  - Bundle 2: `GRANT_TYPE_PARAM` + `checkGrantType()` + `grant.process(`
- **B2.** The endpoint completes client authentication, CORS setup, duplicate-parameter checks and DPoP handling
  *before* `grant.process(...)`, so a grant implementation must not repeat them.
  - Bundle 1: `checkParameterDuplicated` + `DPoPUtil` + `grant.process(context)`

### ABSTRACTION

- **C1.** The grant SPI is `OAuth2GrantType` (`process(Context)`, `getEventType()`, `getTokenParameterNames()`), and
  its `Context` carries the session, form params, event builder, CORS and token manager.
  - Bundle 1: `interface OAuth2GrantType extends Provider` + `Response process(Context context)`
  - Bundle 2: `public static class Context` + `MultivaluedMap<String, String> formParams` + `Cors cors`
- **C2.** The factory SPI `OAuth2GrantTypeFactory` requires a **second** identifier next to `getId()`: a short unique
  `getShortcut()`.
  - Bundle 1: `OAuth2GrantTypeFactory` + `String getShortcut()`

### IMPLEMENTATION

- **D1.** The new grant is `OfflineRefreshTokenGrantType extends RefreshTokenGrantType`; it pre-validates and then
  delegates the entire issuance path with `super.process(context)` instead of reimplementing token issuance.
  - Bundle 1: `class OfflineRefreshTokenGrantType extends RefreshTokenGrantType` + `return super.process(context);`
- **D2.** The offline check uses the existing helper `TokenUtil.isOfflineToken(String)` (which compares the refresh
  token's `typ` against `TOKEN_TYPE_OFFLINE`) rather than a hand-rolled JWT parse.
  - Bundle 1: `TokenUtil.isOfflineToken(refreshToken)` + `JWSInputException`
  - Bundle 2: `TOKEN_TYPE_OFFLINE = "Offline"` + `token.getType().equals(TOKEN_TYPE_OFFLINE)`
- **D3.** The grant id literal is `urn:ietf:params:oauth:grant-type:offline-refresh`, declared in the grant class as
  `OFFLINE_REFRESH_GRANT_TYPE` because `OAuth2Constants` lives in another module.
  - Bundle 1: `OFFLINE_REFRESH_GRANT_TYPE` + `urn:ietf:params:oauth:grant-type:offline-refresh`

### DATA_FLOW

- **E1.** `setContext(context)` from `OAuth2GrantTypeBase` must be the first statement of `process`, because
  `formParams`, `event` and `cors` are populated by it.
  - Bundle 1: `setContext(context)` + `formParams.getFirst(OAuth2Constants.REFRESH_TOKEN)`
  - Bundle 2: `protected void setContext(Context context)` + `this.cors = context.cors`
- **E2.** A non-offline refresh token is rejected before any token is minted, as an OAuth protocol error
  (`invalid_grant`, HTTP 400) with an `Errors.INVALID_TOKEN` event — not a 500.
  - Bundle 1: `OAuthErrorException.INVALID_GRANT` + `Response.Status.BAD_REQUEST` + `Errors.INVALID_TOKEN`

### WIRING

- **F1.** The factory must be added to the ServiceLoader resource
  `services/src/main/resources/META-INF/services/org.keycloak.protocol.oidc.grants.OAuth2GrantTypeFactory`; without
  that line nothing fails to compile and the grant is simply absent at runtime.
  - Bundle 1: `META-INF/services/org.keycloak.protocol.oidc.grants.OAuth2GrantTypeFactory` +
    `org.keycloak.protocol.oidc.grants.OfflineRefreshTokenGrantTypeFactory`

### SECONDARY

- **G1.** `grant_types_supported` in the OIDC discovery document is **derived** from the factory stream, so the new
  grant appears automatically and no list has to be edited.
  - Bundle 1: `getGrantTypesSupported` + `getProviderFactoriesStream(OAuth2GrantType.class)` + `ProviderFactory::getId`
  - Bundle 2: `setGrantTypesSupported` + `OAuth2Constants.IMPLICIT`
- **G2.** The shortcut is consumed by the token-context encoder in a different package
  (`protocol/oidc/encode`), which maps grant id ↔ shortcut at `postInit` and lazily refreshes unknown grants.
  - Bundle 1: `DefaultTokenContextEncoderProviderFactory` + `grantsByShortcuts` + `grantsToShortcuts`
  - Bundle 2: `getShortcutByGrantType` + `sessionFactory.getProviderFactory(OAuth2GrantType.class, grantType)`

### INVARIANT

- **H1.** Shortcut uniqueness is a **global start-up invariant**: duplicate shortcuts make the two maps differ in size
  and the server throws `IllegalStateException` during `postInit`, so reusing `"rt"` for the new grant breaks boot.
  - Bundle 1: `grantsByShortcuts.size() != grantsToShortcuts.size()` + `IllegalStateException`
  - Bundle 2: `same ID or shortcut like other grants` + `postInit(KeycloakSessionFactory factory)`

### VERIFICATION

- **I1.** The gold change is verified by compiling the `services` module only (`./mvnw -pl services test-compile`);
  the runtime invariant (H1) is not covered by a unit test in `services` and would surface at server start-up, which
  is why the shortcut choice must be reasoned about, not tested into place.
  - Bundle 1: `mvnw` + `-pl services` + `test-compile`
  - Bundle 2: `BUILD SUCCESS` + `keycloak-services`

### Facts unanswerable by find-usages / callers / implementations queries

- **F1** — the registration lives in a `META-INF/services` **text resource**. No Java symbol references it; IDE
  "find usages" on `OfflineRefreshTokenGrantTypeFactory` returns nothing at all, since ServiceLoader instantiates it
  reflectively by name.
- **H1** — the invariant is a *runtime size comparison between two maps*, not a call relationship. Find-usages on
  `getShortcut()` reaches the encoder factory only if one already suspects the shortcut matters; the **consequence**
  (server refuses to boot, with that exact message) is not expressible as a usage query at all.
- **G1** — the fact that discovery needs **no** edit is a negative fact. No query returns "nothing to change here";
  it requires reading `getGrantTypesSupported()` and recognising the stream is derived, in contrast to the hardcoded
  `DEFAULT_RESPONSE_TYPES_SUPPORTED` sitting three lines above.
- **A2** — the shortcut namespace (`ac, cc, pg, rt, ro, te, ci, dg, pc, ag`) is an aggregate over ten unrelated
  classes' string literals. No implementations/callers query yields the *set of literal return values*; it has to be
  assembled by reading every factory.
- **B2** (bonus) — "what the endpoint already did before calling the grant" is an ordering fact inside one method
  body; a callers query on `process` shows the call site but not that DPoP/duplicate-parameter/client-auth handling
  precedes it.

## 8. Estimated implementation effort for a downstream agent holding the right architecture note

- **Files touched: 3** (2 new Java files in `services/src/main/java/org/keycloak/protocol/oidc/grants/`, 1 line added
  to the `META-INF/services` registration file).
- **Lines: +127** total (68 + 58 + 1), of which ~34 are Apache licence headers and ~35 are the boilerplate factory
  overrides; the genuinely new logic is **~25 lines**.
- **Time with the architecture note in hand:** one edit pass plus a `-pl services test-compile` (≈10 s incremental,
  ≈8 s clean on this machine). Without the note, the two expensive discoveries are the ServiceLoader resource (silent
  failure) and the shortcut uniqueness check in `protocol/oidc/encode` (start-up crash).

---

### Worktree state

`/Users/matvei.ludzskii/Work/keycloak-c3`: `git status --porcelain` → empty; `git rev-parse HEAD` →
`60c4d5e9321ff5462a772ceb896f8cb2e639e04b`. No commits were made; `~/.m2` was never written to
(`mvnw` was run with `-o` and only `test-compile`).
