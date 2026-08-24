# Oracle evidence — `acquisition__keycloak__client-auth-method`

Repository: Keycloak, pinned commit `60c4d5e9321ff5462a772ceb896f8cb2e639e04b`
Worktree used for every measurement and every scenario run: `/Users/matvei.ludzskii/Work/keycloak-c2`
(left pristine — `git status --porcelain` prints nothing).

Build: `JAVA_HOME=$(/usr/libexec/java_home -v 21)`, `./mvnw -o -pl services clean test -Dtest=… -DfailIfNoTests=false`.
`clean` was used for **every** scenario so that no stale `services/target/classes` could poison the class scan.
`mvn install` was never run; `-am` was never used.

## 1. The test file

| item | value |
|---|---|
| path | `services/src/test/java/org/keycloak/authentication/authenticators/client/ClientAuthenticationMethodResidualContractTest.java` |
| lines | 1314 |
| patch | `oracle.patch` (1320 lines, one new file, nothing else) |
| framework | JUnit 4 (`org.junit.Test` / `org.junit.Assert`) — `services` (`keycloak-services`) declares `junit:junit` at test scope and **no Mockito** (verified: `services/pom.xml` has zero `mockito` occurrences) |
| test doubles | `java.lang.reflect.Proxy` + small `private static` handler classes only |
| infrastructure | none: no server, no database, no network, no container. One in-process provider registry (`ResteasyKeycloakSessionFactory`) is booted for the SPI route, exactly as the sibling reference oracle does |
| `@Test` methods | 9 |
| assertion statements inside `@Test` bodies | **25** (34 `Assert.*` calls in the file; the other 9 are fail-fast guards in the discovery/fixture helpers, which is what keeps a missing implementation a FAILURE and never an ERROR) |

`failToPass` string to register:

```
org.keycloak.authentication.authenticators.client.ClientAuthenticationMethodResidualContractTest
```

## 2. The axes

Per-test assertion counts: A1 = 1, A2 = 4, A3 = 3, A4 = 4, A5 = 6, B1 = 2, B2 = 3, B3 = 1, B4 = 1 (total 25).

| id | `@Test` name | obligation graded | passes on pristine tree? |
|---|---|---|---|
| A1 | `anImplementationOfTheClientAuthenticatorSpiExists` | exactly one new concrete `ClientAuthenticator` is compiled into the module (route 1: compiled-class scan, minus the measured baseline) | no |
| A2 | `theAuthenticatorIsRegisteredThroughTheProviderSpi` | the factory is listed in `META-INF/services/org.keycloak.authentication.ClientAuthenticatorFactory`, i.e. the server can load it and `create(session)` yields a provider (route 2: provider SPI, minus the measured baseline of ids) | no |
| A3 | `itAdvertisesTheSelfSignedMutualTlsTokenEndpointAuthMethod` | it claims exactly one **new** OIDC token-endpoint auth method; that method is the RFC 8705 §2.2 registered token; and the flat-mapped list the discovery document is built from contains **no duplicate** method string (the "exactly one factory per method name" invariant) | no |
| A4 | `theShippedCertificateAwareProfilesAllowTheNewAuthenticator` | the second, non-obvious registry: all 7 `allowed-client-authenticators` lists of `keycloak-default-client-profiles.json` that already allow `client-x509` gain the same one new provider id — and it is the id the newly registered factory answers to | no |
| A5 | `nothingThatAlreadyWorkedWasTakenAway` | **floor / "did not break anything"**: no shipped authenticator class vanished, no shipped provider id vanished from the SPI, the 10 shipped profiles are unchanged in name and order, no allow-list entry was removed, no `default-client-authenticator` changed | **yes — by construction** (see the comment on the method: a run in which only A5 passes means *no progress at all*, not partial progress) |
| B1 | `aClientPresentingItsRegisteredCertificateIsAuthenticated` | presenting exactly the registered certificate ⇒ `success` **and** the client is attached to the flow. The certificate is self-issued and the session exposes no truststore, so passing this also proves no trust anchor / CA chain is consulted | no |
| B2 | `aReIssuedCertificateWithTheSameSubjectAndKeyIsRefused` | **THE INVARIANT**: a re-issued certificate with the identical subject DN and identical public key but different bytes must not authenticate. This is precisely what the shipped neighbour's subject-DN comparison gets wrong | no |
| B3 | `aCertificateThatIsNotTheRegisteredOneIsRefused` | an unrelated certificate (different subject, different key) must not authenticate | no |
| B4 | `aRequestWithoutAClientCertificateLeavesTheFlowToOtherExecutions` | no certificate presented ⇒ `attempted`, never `success` and never a hard flow failure, so the remaining alternative executions still get their chance (CASE-EVIDENCE fact H2) | no |

### How the cascade was broken

* **Two independent discovery routes, unioned.**
  Route 1 walks the module's compiled classes — located through
  `X509ClientAuthenticator.class.getProtectionDomain().getCodeSource().getLocation()`, never a relative
  path — over the whole `org/keycloak` subtree (1994 classes, ~210 ms), loads each with
  `Class.forName(name, false, loader)` inside a per-class `catch (Throwable)` so one unloadable class
  cannot abort the scan, and keeps concrete `ClientAuthenticator` implementations that are absent from a
  **hardcoded baseline measured on the pristine tree**. Route 2 asks the booted provider registry for
  `ClientAuthenticatorFactory` ids absent from a second measured baseline.
* **A2 never needs A1** (pure SPI) and **A1 never needs A2** (pure classpath). The cross-check inside A2
  ("the registered factory really creates the discovered class") is guarded by `if (discovered.size() == 1)`
  so it can only ever *add* information, never turn A2 into a copy of A1. The same guard shape is used in A4.
* **A3 never needs A2**: the simulated discovery-document list is the SPI factories **plus** any newly
  compiled authenticator that is not registered yet (deduplicated by class), so A3 passes on a tree that has
  the implementation but no ServiceLoader line. That is exactly what makes the V3 → V4 step visible.
* **A4 and A5 read only JSON** and pass/fail with no implementation present at all.
* **B1..B4 never read the profile JSON and never need the registration.** They obtain the provider from
  route 1 when it finds the class, and fall back to route 2 otherwise.
* **Discovery failure is an assertion, not an error.** `authenticatorUnderTest` ends in
  `Assert.fail("No new ClientAuthenticator was found: …")`; V2 shows `Errors: 0` for all four behaviour axes.

### The runtime-reachable state (the round-2 lesson)

Every behavioural axis goes through **one** helper, `authenticate(X509Certificate[], String)`, which puts the
provider in exactly the state the token endpoint puts it in — so no axis added later can forget a step:

* the provider is obtained via `factory.create(session)` (the flow never `new`s a provider);
* the certificate chain is offered through **both** routes a reasonable implementation may use:
  `session.getProvider(X509ClientCertificateLookup.class).getCertificateChain(request)` **and**
  `HttpRequest.getClientCertificateChain()`, with `isProxyTrusted() == true`;
* the request is a real form-encoded token request: `Content-Type: application/x-www-form-urlencoded`
  and `client_id` in the decoded form parameters;
* `KeycloakContext.getUri()` returns a **real** `KeycloakUriInfo` (it is a concrete class, not an
  interface — see §8), built over a delegate whose query parameters are empty, as on a POST to the token
  endpoint;
* the realm resolves that `client_id` to an **enabled** client that **has** a certificate registered under
  the shipped PEM attribute `jwt.credential.certificate`;
* `context.getEvent()` is a real `EventBuilder` (also a concrete class) constructed from the proxied realm
  and session.

No axis exercises a client with a blank or malformed registered PEM — that is the state whose guards the
gold happens to carry and the shipped precedent does not, and grading it would punish an implementation for
following the precedent (this is the V6 regression).

## 3. Measured baselines hardcoded in the oracle

All four were produced by running a throwaway probe **on the pristine tree** at the pinned commit.

| baseline | value |
|---|---|
| concrete `ClientAuthenticator` classes compiled under `org/keycloak` (6) | `AttestationBasedClientAuthenticator`, `ClientIdAndSecretAuthenticator`, `FederatedJWTClientAuthenticator`, `JWTClientAuthenticator`, `JWTClientSecretAuthenticator`, `X509ClientAuthenticator` |
| `ClientAuthenticatorFactory` ids resolvable through the SPI (5) | `client-jwt`, `client-secret`, `client-secret-jwt`, `client-x509`, `federated-jwt` — the attestation authenticator is compiled but feature-gated, hence in the class baseline and **not** in the id baseline |
| OIDC token-endpoint auth methods published (5) | `client_secret_basic`, `client_secret_post`, `client_secret_jwt`, `private_key_jwt`, `tls_client_auth` |
| profiles with a `secure-client-authenticator` executor (7, all already allowing `client-x509`) | `fapi-1-baseline` `[client-jwt, client-secret-jwt, client-x509]`; `fapi-1-advanced`, `fapi-2-security-profile`, `fapi-2-message-signing`, `oauth-2-1-for-confidential-client`, `fapi-2-dpop-security-profile`, `fapi-2-dpop-message-signing` — each `[client-jwt, client-x509]`, all with `default-client-authenticator: client-jwt` |
| shipped profile names (10, order pinned) | `fapi-1-baseline`, `fapi-1-advanced`, `fapi-ciba`, `fapi-2-security-profile`, `fapi-2-message-signing`, `oauth-2-1-for-confidential-client`, `oauth-2-1-for-public-client`, `fapi-2-dpop-security-profile`, `fapi-2-dpop-message-signing`, `saml-security-profile` |

## 4. The six verdicts (verbatim `Tests run` lines)

Each scenario was built from a pristine checkout (`git checkout -- . && git clean -fd services/src/main`),
then the scenario's sources applied, then `clean test` run. Nothing was simulated.

### V1 — gold + oracle → everything passes

```
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.765 s -- in org.keycloak.authentication.authenticators.client.ClientAuthenticationMethodResidualContractTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
```

Failing tests: none. **Score 9/9.**

### V2 — oracle only, pristine sources → only the floor passes

```
[ERROR] Tests run: 9, Failures: 8, Errors: 0, Skipped: 0, Time elapsed: 1.464 s <<< FAILURE! -- in org.keycloak.authentication.authenticators.client.ClientAuthenticationMethodResidualContractTest
[ERROR] Tests run: 9, Failures: 8, Errors: 0, Skipped: 0
```

Failing tests (all FAILURES, **0 ERRORS**): `anImplementationOfTheClientAuthenticatorSpiExists`,
`theAuthenticatorIsRegisteredThroughTheProviderSpi`,
`itAdvertisesTheSelfSignedMutualTlsTokenEndpointAuthMethod`,
`theShippedCertificateAwareProfilesAllowTheNewAuthenticator`,
`aClientPresentingItsRegisteredCertificateIsAuthenticated`,
`aReIssuedCertificateWithTheSameSubjectAndKeyIsRefused`,
`aCertificateThatIsNotTheRegisteredOneIsRefused`,
`aRequestWithoutAClientCertificateLeavesTheFlowToOtherExecutions`.
Passing: `nothingThatAlreadyWorkedWasTakenAway` (A5) only. **Score 1/9.**

Each failure carries its own message, e.g.:

* A1 — `Exactly one new concrete ClientAuthenticator implementation is expected under org/keycloak on top of the 6 measured on the pristine tree, but found [] expected:<1> but was:<0>`
* A2 — `Exactly one new ClientAuthenticatorFactory id is expected on top of the 5 measured on the pristine tree, but found []. Is the factory listed in META-INF/services/org.keycloak.authentication.ClientAuthenticatorFactory? expected:<1> but was:<0>`
* A3 — `Exactly one new OIDC token-endpoint authentication method is expected on top of the 5 measured on the pristine tree […], but the registered and newly compiled client authenticators together publish [private_key_jwt, client_secret_basic, client_secret_post, tls_client_auth, client_secret_jwt] expected:<1> but was:<0>`
* A4 — `All 7 shipped profiles that already allow 'client-x509' have to allow the new client authenticator as well; these still do not: [fapi-1-baseline, …]`
* B1..B4 — `No new ClientAuthenticator was found: neither a new implementation compiled under org/keycloak nor a new provider id registered through the SPI. The behaviour under test is not implemented.`

### V3 — only the new implementation source, no registration edit → clearly intermediate

Tree: gold's `SelfSignedX509ClientAuthenticator.java` **plus** gold's one-line constant in
`OIDCLoginProtocol.java` (the implementation does not compile without it, so the constant is part of the
implementation, not of the registration). **No** ServiceLoader line, **no** profile JSON edit.

```
[ERROR] Tests run: 9, Failures: 2, Errors: 0, Skipped: 0, Time elapsed: 1.494 s <<< FAILURE! -- in org.keycloak.authentication.authenticators.client.ClientAuthenticationMethodResidualContractTest
[ERROR] Tests run: 9, Failures: 2, Errors: 0, Skipped: 0
```

Failing: `theAuthenticatorIsRegisteredThroughTheProviderSpi` (A2),
`theShippedCertificateAwareProfilesAllowTheNewAuthenticator` (A4).
Passing (7): A1, A3, A5, B1, B2, B3, B4. **Score 7/9.**

### V4 — V3 + the ServiceLoader registration line, still no profile JSON edit → one more axis

```
[ERROR] Tests run: 9, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 1.468 s <<< FAILURE! -- in org.keycloak.authentication.authenticators.client.ClientAuthenticationMethodResidualContractTest
[ERROR] Tests run: 9, Failures: 1, Errors: 0, Skipped: 0
```

Failing: `theShippedCertificateAwareProfilesAllowTheNewAuthenticator` (A4) only.
Newly passing versus V3: `theAuthenticatorIsRegisteredThroughTheProviderSpi` (A2). **Score 8/9.**

### V5 — naive tree: full gold, certificate comparison degraded to the neighbour's subject-DN check

The only change versus gold:

```java
private boolean isSameCertificate(X509Certificate presented, X509Certificate registered) {
    // V5 NAIVE VARIANT: what the nearest shipped neighbour does - compare the subject distinguished name
    return presented.getSubjectX500Principal().equals(registered.getSubjectX500Principal());
}
```

```
[ERROR] Tests run: 9, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 1.475 s <<< FAILURE! -- in org.keycloak.authentication.authenticators.client.ClientAuthenticationMethodResidualContractTest
[ERROR] Tests run: 9, Failures: 1, Errors: 0, Skipped: 0
```

Failing: `aReIssuedCertificateWithTheSameSubjectAndKeyIsRefused` (B2, the invariant) only, with

```
A re-issued certificate with the same subject name and the same key is NOT the registered certificate and has to be refused; comparing the subject distinguished name, as the shipped certificate-authority based method does, accepts it, but the authenticator answered SUCCESS. Actual: SUCCESS
```

**Score 8/9 — everything passes except the invariant axis, as required.**

### V6 — robustness: full gold with the defensive guards the shipped precedent lacks removed

The only change versus gold — both guards around the registered-certificate attribute deleted, so the
provider dereferences it as bluntly as `X509ClientAuthenticator` does:

```java
// V6 ROBUSTNESS VARIANT: both defensive guards around the registered certificate attribute removed,
// so the provider dereferences it as bluntly as the shipped precedent does
String registeredCertificatePem = client.getAttribute(JWTClientAuthenticator.CERTIFICATE_ATTR);
X509Certificate registeredCertificate = PemUtils.decodeCertificate(registeredCertificatePem);
```

(i.e. gold's `StringUtil.isBlank(...) → attempted()` guard and its `catch (PemException) → attempted()`
guard are both gone.)

```
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.489 s -- in org.keycloak.authentication.authenticators.client.ClientAuthenticationMethodResidualContractTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
```

Failing: none. **Score 9/9 with zero errors** — the oracle never grades a state (blank / unparsable
registered PEM) that only gold's extra defensiveness can survive.

## 5. Regression sweep (gold applied)

```
./mvnw -o -pl services clean test \
  -Dtest='*ClientAuthenticat*Test,*X509*Test,*Certificate*Test,*Executor*Test,ProtocolFactoryTest,DefaultKeycloakSessionFactoryTest,DefaultKeycloakContextTest' \
  -DfailIfNoTests=false
```

```
[INFO] Tests run: 98, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Per class: `DefaultKeycloakContextTest` 1, `ClientAuthenticationMethodResidualContractTest` 9,
`CertificateValidatorTest` 27, `X509AuthenticatorConfigModelTest` 3, `ProtocolFactoryTest` 1,
`DefaultKeycloakSessionFactoryTest` 2, `SecureRedirectUrisEnforcerExecutorTest` 20,
`EnvoyProxySslClientCertificateLookupTest` 8, `HaProxySslClientCertificateLookupTest` 10,
`Rfc9440ClientCertificateLookupTest` 13, `TraefikProxySslClientCertificateLookupTest` 4.

(Note for the record: the `services` module ships **no** unit test for `X509ClientAuthenticator` or for any
other `ClientAuthenticator` — the client-authenticator subsystem is covered only by the Arquillian
integration testsuite, which is out of scope here. The sweep above is therefore the whole of the
neighbouring shipped unit coverage of this subsystem.)

## 6. Score scale

```
pristine (V2)                              1
+ implementation only (V3)                 7
+ ServiceLoader registration (V4)          8
+ profile allow-lists  = gold (V1)         9
naive neighbour copy of the cert check (V5) 8
gold minus its extra null guards (V6)       9
```

As a list: **1 → 7 → 8 → 9**, with 8 also reachable from the "everything but the invariant" direction (V5).
No scenario collapses to all-fail or all-pass, and no scenario produced a single ERROR.

The step from 1 to 7 is large on purpose: writing the authenticator *is* the bulk of the work (221 of the
237 added lines), and the two registration edits plus the JSON edit are one axis each. What matters for
grading is that the four behavioural axes, the class axis and the method-string axis each fail for their own
reason — V5 demonstrates that by moving exactly one of them.

## 7. Assertions weakened or re-scoped, and why

1. **B2 and B3 assert "not `SUCCESS`", not "`ATTEMPTED`".**
   The behavioural statement says a non-matching certificate "is refused"; it does not say *how*. Gold answers
   `attempted()`, but a `challenge` or a hard `failure` would also be a defensible refusal. Pinning
   `ATTEMPTED` here would grade an implementation detail, so both axes grade the observable refusal only.
   B4 *does* pin `ATTEMPTED`, because there the weaker reading is wrong: an authenticator that aborts the
   flow when no certificate is presented breaks every other client of the realm, and CASE-EVIDENCE fact H2
   names exactly this.
2. **A3 pins one literal string, `self_signed_tls_client_auth`.**
   This is the single value in the contract that is not derived from the tree. Decision and justification:
   the behavioural statement (CASE-EVIDENCE §1) requires the method to show up "in the realm's public
   discovery document … so that a client registering itself dynamically can ask for it **by name**". The name
   a third party asks by is not free-form: it must be the IANA-registered token. RFC 8705 §2.1 ships in
   this tree as `tls_client_auth`, and §2.2 — the method the statement describes — is registered as
   `self_signed_tls_client_auth`. So the string *is* discoverable from the statement plus the RFC the
   shipped neighbour already follows, and any other spelling would make the advertised method unusable.
   It is an RFC/IANA token, **not** an internal name of the gold change: the oracle contains zero
   occurrences of gold's class name and does not reference gold's `OIDCLoginProtocol` constant (which is
   why the oracle still compiles on a pristine tree). Everything else — the provider id, the class name, the
   package, the display text, the help text — is derived from the discovered implementation and never pinned.
3. **A4 does not pin the new provider id.** It asserts that all seven certificate-aware profiles gain the
   *same single* id and (only when route 2 found exactly one new factory) that this id is the one the
   factory answers to. A solver choosing a different id than gold's `client-self-signed-x509` still passes.
4. **A3's "no duplicates" assertion covers the H1 invariant** ("a method string must be claimed by exactly
   one factory") but is not a separate axis, to avoid a third test that moves in lockstep with A3's first
   two assertions. This is a deliberate re-scoping: uniqueness is graded, just inside A3.
5. **No axis grades the well-known endpoint itself.** `OIDCWellKnownProvider` needs a live realm and session
   to run. A3 instead reproduces its documented computation (stream the factories, flat-map
   `getProtocolAuthenticatorMethods(openid-connect)`, no de-duplication) over the same factory set. This is
   a faithful but *simulated* publication, and it is flagged here rather than silently substituted.
6. **No axis grades the client-policy executor at runtime.** `SecureClientAuthenticatorExecutor` rejecting a
   client whose id is missing from `allowed-client-authenticators` would need a `ClientPolicyContext` and a
   configured policy chain. A4 grades the JSON registry that feeds it instead — one indirection short of the
   runtime rejection, and stated as such.
7. **`X509ClientAuthenticator.PROVIDER_ID` is referenced as a symbol.** It is a *shipped* constant used to
   describe which profiles are "certificate-aware"; it is not part of the gold change.

## 8. What genuinely could not be exercised without infrastructure the module cannot give

Stated plainly, as required:

* **A real mutual-TLS handshake.** There is no TLS in a unit test. The oracle substitutes the two in-process
  seams the runtime itself uses — the `X509ClientCertificateLookup` SPI and
  `HttpRequest.getClientCertificateChain()` — and stubs **both**, so an implementation that reads the chain
  either way is graded identically. This is the narrowest observable substitute; it is not a
  "the class contains a method named X" check.
* **A live `KeycloakSession` with a store.** The behavioural axes use a proxied session; only the
  route-2/A2/A3 factory lookup boots a real in-process provider registry.
* **The certificate-lookup provider is stubbed, not resolved.** A production deployment resolves it from
  configuration; the oracle always supplies one, which is the *configured* state and therefore a state the
  runtime does produce. The *unconfigured* state (provider absent) is deliberately never graded — gold's
  early `return` there sets no flow status at all, and grading a "no decision" outcome would punish an
  implementation for copying the shipped precedent verbatim.
* **A realm with a real client-policy chain**, so the runtime rejection of a client whose authenticator id
  is not allow-listed is graded one level up, at the JSON registry (see §7.6).
* **`AbstractWellKnownProviderTest` / the admin-API provider-enumeration tests** (CASE-EVIDENCE facts I1,
  I2) live in `testsuite/integration-arquillian` and need a running server; they are out of scope for this
  oracle and were deliberately not mirrored.

### Remaining state the oracle exercises that the runtime cannot produce

One, and it is a fixture detail rather than a provider state:

* `KeycloakContext.getUri()` is typed to the **concrete** class `KeycloakUriInfo`, so it cannot be a dynamic
  proxy. The oracle builds a genuine `KeycloakUriInfo(session, UrlType.FRONTEND, delegate)` whose hostname
  provider is a stub returning `https://localhost:8443/` and whose delegate answers an **empty** query-parameter
  map. A real token-endpoint request would carry a request URI and possibly query parameters; here the query
  string is always empty, which is the normal shape of a form-encoded `POST /token` but is not literally the
  URI a server would construct. Nothing in the graded behaviour depends on the URI: it exists only because the
  shipped precedent (and gold) read `getQueryParameters()` as a fallback source of `client_id`, and the form
  body always supplies `client_id` first.

Everything else — provider obtained from its factory, enabled client, registered PEM attribute, form-encoded
`client_id`, certificate chain from the lookup SPI — is exactly what the token endpoint sets up.

## 9. Export checks (all three actually run)

On a pristine checkout at the pinned commit:

| check | result |
|---|---|
| `git apply --check oracle.patch` (alone) | OK |
| `git apply gold.patch && git apply --check oracle.patch` | OK |
| `git apply oracle.patch && git apply --check gold.patch` | OK |

Content checks on `oracle.patch`:

| token | occurrences |
|---|---|
| `Mockito` | **0** |
| `mock(` | **0** |
| `SelfSignedX509ClientAuthenticator` (gold's implementation class name) | **0** |

Files touched by `oracle.patch`: exactly one —
`services/src/test/java/org/keycloak/authentication/authenticators/client/ClientAuthenticationMethodResidualContractTest.java`
(new file). `gold.patch` was not modified.
