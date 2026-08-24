# Case evidence — `acquisition__keycloak__client-auth-method`

Repository: Keycloak, pinned commit `60c4d5e9321ff5462a772ceb896f8cb2e639e04b`
Worktree used for all measurements and for the gold implementation: `/Users/matvei.ludzskii/Work/keycloak-c2`
(left pristine — `git status --porcelain` prints nothing).

**Status: complete.** The gold implementation exists, compiles and is captured in `gold.patch`
(`./mvnw -o -pl services test-compile` → `BUILD SUCCESS`; `git apply --check` passes on the pristine
checkout). Every number below was produced by actually running the quoted command in that worktree at
the pinned commit, with the gold change reverted (pristine tree), so no measurement is contaminated by
the gold files.

**Feature chosen:** `self_signed_tls_client_auth` — the second mutual-TLS client authentication method of
RFC 8705 (§2.2), where the client's certificate is matched against the certificate registered for the
client instead of being chained to a CA.

Proof it does not exist at this commit:

```
$ grep -ril "self_signed_tls_client_auth" . --exclude-dir=.git | wc -l
0
$ grep -rl "self_signed" --include=*.java . | wc -l
0
$ grep -rn "SELF_SIGNED" --include=*.java services/ server-spi-private/ core/
(no output)
```

whereas the conceptual analogue ships:

```
$ grep -rl "tls_client_auth" --include=*.java . | wc -l
7
$ grep -rl "client-x509" --include=*.java . | wc -l
9
$ grep -rl "client-x509" --include=*.java . | grep -v "/test/" | wc -l
2
```

Gold change (4 files, `+237 / -7`):

| file | kind |
|---|---|
| `services/src/main/java/org/keycloak/authentication/authenticators/client/SelfSignedX509ClientAuthenticator.java` | new, 221 lines |
| `services/src/main/java/org/keycloak/protocol/oidc/OIDCLoginProtocol.java` | +1 line (method-name constant) |
| `services/src/main/resources/META-INF/services/org.keycloak.authentication.ClientAuthenticatorFactory` | +1 line (ServiceLoader registration) |
| `services/src/main/resources/keycloak-default-client-profiles.json` | +14 / −7 (7 allow-lists) |

---

## 1. Behavioural problem statement (draft, admin vocabulary)

> Some of our clients hold a certificate they issued to themselves — no authority signed it — and that
> exact certificate is already uploaded to the client's credential configuration in the admin console.
> We want such a client to be able to prove who it is to the token endpoint simply by presenting that
> same certificate during the TLS handshake.
>
> Today the only certificate-based way of authenticating refuses them: it insists that the presented
> certificate chains up to a trusted authority and that its subject name matches a value configured on
> the client, so a self-issued certificate never gets through.
>
> After the change, a client is authenticated exactly when the certificate it presents is byte-for-byte
> the certificate stored for it, and is refused the moment it differs — including a freshly re-issued
> certificate that carries the very same subject name and the very same key. No trust store and no
> authority chain is consulted at any point.
>
> The new way of authenticating has to show up in the realm's public discovery document among the
> authentication methods the token endpoint accepts, so that a client registering itself dynamically can
> ask for it by name; and clients configured with it must be accepted by the built-in security profiles
> that already tolerate certificate-based clients.

No file, class, package, provider id or JSON basename is named.

## 2. Leakage audit

All counts are `grep -ril '<phrase>' --include=*.java . | wc -l`, run from the repository root of the
pristine worktree.

| phrase from the statement | files |
|---|---|
| `token endpoint` | 32 |
| `trust store` | 8 |
| `subject name` | 7 |
| `self-issued` | 4 |
| `discovery document` | 1 |
| `trusted authority` | 0 |
| `byte-for-byte` | 0 |
| `security profiles` | 0 |

Do any of those hits lie in the gold set? Checked by filtering each hit list through
`grep -E "OIDCLoginProtocol\.java|authenticators/client/"`:

```
--- token endpoint ---    (no gold-dir hit)
--- trust store ---       (no gold-dir hit)
--- discovery document ---(no gold-dir hit)
--- subject name ---      (no gold-dir hit)
--- self-issued ---       (no gold-dir hit)
```

Two-phrase conjunctions (files containing **both** phrases), narrowest first:

| conjunction | files | any in gold dirs |
|---|---|---|
| `self-issued` ∧ `token endpoint` | 0 | — |
| `byte-for-byte` ∧ `token endpoint` | 0 | — |
| `trust store` ∧ `token endpoint` | 0 | — |

The narrowest conjunction available is empty, and no single phrase reaches a gold file. The statement
does not localize the gold; no rewrite was needed.

(The two non-Java gold files — the ServiceLoader registration file and the client-profiles JSON — cannot
be reached by any of these phrases at all: they contain no English prose beyond a license header.)

## 3. The discovery chain

1. `services/src/main/java/org/keycloak/protocol/oidc/utils/AuthorizeClientUtil.java`
   (`authorizeClient`, `getAuthenticationProcessor`, `findClientAuthenticatorForOIDCAuthMethod`).
   *Not deducible from the statement:* the admin speaks about "the token endpoint"; nothing in the
   behaviour tells you that client authentication is a **separate authentication flow** run by
   `AuthenticationProcessor.authenticateClient()` rather than a check inside the token endpoint.
2. `server-spi-private/src/main/java/org/keycloak/authentication/ClientAuthenticatorFactory.java`
   and `.../ClientAuthenticator.java`.
   *Not deducible from hop 1:* hop 1 only shows a stream of factories being filtered; that the extension
   point is an SPI living in a **different Maven module** (`server-spi-private`, not `services`), and that
   `getProtocolAuthenticatorMethods(String loginProtocol)` is the mapping surface, is only visible here.
3. `services/src/main/java/org/keycloak/authentication/authenticators/client/X509ClientAuthenticator.java`
   (precedent for mTLS), `.../JWTClientAuthenticator.java` (precedent for the per-client certificate
   attribute), `.../ClientIdAndSecretAuthenticator.java` (precedent for one factory owning **two** method
   names), `.../AbstractClientAuthenticator.java`.
   *Not deducible from hop 2:* the SPI says nothing about where the presented certificate comes from
   (`X509ClientCertificateLookup`), nor that provider and factory are the **same object**
   (`AbstractClientAuthenticator implements ClientAuthenticator, ClientAuthenticatorFactory`).
4. `services/src/main/resources/META-INF/services/org.keycloak.authentication.ClientAuthenticatorFactory`.
   *Not deducible from hop 3:* nothing in the Java sources references this file; the classes do not carry
   an annotation. It is a plain-text ServiceLoader list in `src/main/resources`, i.e. invisible to every
   Java-only search and to every "find implementations" query.
5. `services/src/main/java/org/keycloak/protocol/oidc/OIDCLoginProtocol.java`, lines 124–130
   (`// Client authentication methods`, `TLS_CLIENT_AUTH`, `PRIVATE_KEY_JWT`, …).
   *Not deducible from hop 4:* the ServiceLoader file only carries a class name. That the **provider id**
   (`client-x509`) and the **OIDC method string** (`tls_client_auth`) are two different namespaces
   bridged only by `getProtocolAuthenticatorMethods`, and that the protocol strings are centralized in
   the protocol class rather than in the authenticator, is a separate discovery.
6. `services/src/main/resources/keycloak-default-client-profiles.json` (7 × `allowed-client-authenticators`)
   consumed by
   `services/src/main/java/org/keycloak/services/clientpolicy/executor/SecureClientAuthenticatorExecutorFactory.java`
   and `.../SecureClientAuthenticatorExecutor.java`.
   *Not deducible from hop 5:* this is a **second, independent** registry, keyed by provider id (not by
   method string), living in a different directory and enforced by a different subsystem (client
   policies). No compile-time reference connects it to the authenticator.
7. `services/src/main/java/org/keycloak/protocol/oidc/OIDCWellKnownProvider.java`
   (`getClientAuthMethodsSupported`, `setTokenEndpointAuthMethodsSupported`,
   `setRevocationEndpointAuthMethodsSupported`).
   *Not deducible from hop 6:* the discovery list is **computed at runtime** from the factory stream; the
   literal `token_endpoint_auth_methods_supported` does not appear in this file at all (it lives in
   `core/.../OIDCConfigurationRepresentation.java` as a `@JsonProperty`), so grepping for the JSON key
   never lands here.
8. `testsuite/integration-arquillian/tests/base/src/test/java/org/keycloak/testsuite/oidc/AbstractWellKnownProviderTest.java:179`
   plus `tests/base/src/test/java/org/keycloak/tests/admin/authentication/ProvidersTest.java:87-91` and
   `.../InitialFlowsTest.java:161-168`.
   *Not deducible from hop 7:* these assert **exact** sets, so the change is not additive from the test
   suite's point of view.

## 4. The two-plus integration mechanisms

| # | mechanism | exact path | keyed by |
|---|---|---|---|
| 1 | ServiceLoader registration of the factory | `services/src/main/resources/META-INF/services/org.keycloak.authentication.ClientAuthenticatorFactory` | fully-qualified class name |
| 2 | provider-id → OIDC method-string mapping | `getProtocolAuthenticatorMethods` in the authenticator + constant in `services/src/main/java/org/keycloak/protocol/oidc/OIDCLoginProtocol.java` | protocol method string |
| 3 | client-policy allow-lists (**different directory, different subsystem**) | `services/src/main/resources/keycloak-default-client-profiles.json`, 7 × `allowed-client-authenticators`, consumed by `services/src/main/java/org/keycloak/services/clientpolicy/executor/SecureClientAuthenticatorExecutorFactory.java` | provider id |

What "copy the neighbour and register it" produces, and why it is wrong:

* Copying `X509ClientAuthenticator` and adding the new class to the ServiceLoader file yields a provider
  that **is** loaded, but whose `getProtocolAuthenticatorMethods` still returns
  `OIDCLoginProtocol.TLS_CLIENT_AUTH`. Now two factories claim the same method string, and
  `AuthorizeClientUtil.findClientAuthenticatorForOIDCAuthMethod` picks with `findFirst()` over an
  unordered `getProviderFactoriesStream`. The result is a silent, order-dependent hijack: dynamic client
  registrations asking for `tls_client_auth` may be wired to the self-signed authenticator, and the
  discovery document lists `tls_client_auth` **twice** (`getClientAuthMethodsSupported` uses `flatMap` +
  `Collectors.toList()`, not a set).
* Copying it faithfully also carries over `validateCertificateChain(...)` with
  `.trustValidation().enabled(true)`, which rejects precisely the self-issued certificates the feature
  exists for — the copy is functionally the opposite of the requirement.
* Even a correct authenticator with a correct new method string is **still** broken under any FAPI /
  OAuth 2.1 profile: `SecureClientAuthenticatorExecutor` throws
  `Configured client authentication method not allowed for client` for a provider id absent from
  `allowed-client-authenticators`. Nothing fails at compile time; the symptom appears only in a realm
  that has a built-in profile attached.

## 5. The invariant and the misleading precedent

**Invariant:** every OIDC client-authentication method string must be claimed by **exactly one**
`ClientAuthenticatorFactory`, and the presented certificate must be accepted on *identity*, never on
*trust*. Behaviourally: turning on the new method must not change how an existing certificate client
behaves, and the new method must reject a re-issued certificate with the same subject and key.

Misleading precedent — `services/src/main/java/org/keycloak/authentication/authenticators/client/X509ClientAuthenticator.java`:

* line 262 `results.add(OIDCLoginProtocol.TLS_CLIENT_AUTH);` — copied verbatim it silently duplicates the
  method claim (there is no uniqueness check anywhere; `findFirst()` just wins).
* lines 217–223 `new CertificateValidator.CertificateValidatorBuilder().session(session).trustValidation().enabled(true)` and
  line 227 `validator.getCertPathBuilderResult().getTrustAnchor().getTrustedCert()` — trust-anchor
  validation, exactly what must **not** happen for a self-signed certificate.
* lines 130–135 `client.getAttribute(ATTR_SUBJECT_DN)` (the attribute name is composed at line 37 as
  `ATTR_PREFIX + ".subjectdn"`, so the effective key never appears as one literal) — suggests the identity
  check is a DN string comparison; for the new method the comparison must be over the certificate bytes.
* line 158 `// TODO: enforce CA subject for keycloak 27.0` followed by `context.success()` when
  `ATTR_CA_SUBJECT_DN` is blank — a copied "missing config ⇒ success" branch would authenticate any
  client presenting any certificate.

A second, subtler precedent trap for the discovery half:
`FederatedJWTClientAuthenticator.getProtocolAuthenticatorMethods` returns `Collections.emptySet()` while
`AttestationBasedClientAuthenticator` returns a real set and hides itself with
`isSupported(Config.Scope) → Profile.isFeatureEnabled(Profile.Feature.CLIENT_AUTH_ABCA)`. Copying the
first neighbour produces an authenticator that works when explicitly selected but never appears in the
discovery document and can never be resolved from a `token_endpoint_auth_method` value.

## 6. Shell audit

The three most obvious commands an agent would issue from the statement, actually run in the pristine
worktree. Gold set = the 4 files listed at the top.

| # | command | files returned | gold files revealed |
|---|---|---|---|
| S1 | `grep -rl "tls_client_auth" --include=*.java .` | 7 | **1 / 4** — `services/src/main/java/org/keycloak/protocol/oidc/OIDCLoginProtocol.java` |
| S2 | `find . -path "*authenticators/client*" -name "*.java" -not -path "*/target/*"` | 15 | **0 / 4** (reveals the *directory* the new class belongs in, and the precedent `X509ClientAuthenticator.java`) |
| S3 | `grep -rl "token_endpoint_auth_methods_supported" . --exclude-dir=target --exclude-dir=.git` | 3 | **0 / 4** |

S1 hits: `core/.../OIDCClientRepresentation.java`, `testsuite/.../AbstractWellKnownProviderTest.java`,
`testsuite/.../OIDCClientRegistrationTest.java`, `services/.../OIDCAdvancedConfigWrapper.java`,
`services/.../OIDCLoginProtocol.java`, `services/.../TlsClientAuthCASubjectDNExecutorFactory.java`,
`services/.../clientregistration/oidc/DescriptionConverter.java`.

S3 hits: `core/.../OIDCConfigurationRepresentation.java`,
`js/apps/admin-ui/src/identity-providers/OIDCConfigurationRepresentation.ts`,
`authz/client/.../ServerConfiguration.java` — note that the *producer*,
`services/.../OIDCWellKnownProvider.java`, is **not** among them.

**Union of S1–S3 = 1 of 4 gold files = 25 %.** Below the ~50 % threshold, so the case holds.

Explicitly still hidden after all three commands:

* `services/src/main/resources/META-INF/services/org.keycloak.authentication.ClientAuthenticatorFactory`
  — not a `.java` file, contains no method string, referenced from no source file.
* `services/src/main/resources/keycloak-default-client-profiles.json` — contains neither
  `tls_client_auth` nor `token_endpoint_auth_methods_supported`; it speaks only provider ids.
* `services/src/main/java/org/keycloak/protocol/oidc/OIDCWellKnownProvider.java` — the composer of the
  advertised list, invisible to a search for the JSON key it produces.
* The runtime resolution rule in `AuthorizeClientUtil.findClientAuthenticatorForOIDCAuthMethod`.

## 7. Draft checklist — 15 atomic architectural facts

Each evidence bundle is a set of literal tokens that must **all** appear in one tool result.

### PRECEDENT

**A1.** A mutual-TLS client authentication method already ships as a single class that claims the OIDC
method string and validates the certificate against a trust anchor.
* Bundle 1: `client-x509`, `TLS_CLIENT_AUTH`, `validateCertificateChain`
* Bundle 2: `ATTR_CA_SUBJECT_DN`, `trustValidation`, `getTrustAnchor`

**A2.** The certificate presented in the TLS handshake is obtained from a separate SPI, not from the HTTP
request directly.
* Bundle 1: `X509ClientCertificateLookup`, `getCertificateChain`, `getProvider`

**A3.** The certificate registered for a client is stored as a PEM client attribute owned by the signed-JWT
authenticator.
* Bundle 1: `CERTIFICATE_ATTR`, `jwt.credential.certificate`, `ATTR_PREFIX`

### ENTRY_POINT

**B1.** Client authentication at the token endpoint is a full authentication flow executed by
`AuthenticationProcessor`, not an inline check.
* Bundle 1: `authorizeClient`, `processor.authenticateClient()`, `getClientAuthenticationFlow`

**B2.** Dynamic client registration turns the requested `token_endpoint_auth_method` into a provider id
through a single lookup helper.
* Bundle 1: `getTokenEndpointAuthMethod`, `findClientAuthenticatorForOIDCAuthMethod`, `setClientAuthenticatorType`

### ABSTRACTION

**C1.** A client authenticator is one class acting as both provider and factory.
* Bundle 1: `AbstractClientAuthenticator`, `implements ClientAuthenticator, ClientAuthenticatorFactory`, `create()`

**C2.** The provider-id → protocol-method-name mapping is a declared SPI method taking the login protocol
id, defined in `server-spi-private`.
* Bundle 1: `ClientAuthenticatorFactory`, `getProtocolAuthenticatorMethods`, `loginProtocol`
* Bundle 2: `getProtocolAuthenticatorMethod`, `Constants.OIDC_PROTOCOL`, `setClientAuthenticationMethod`

### IMPLEMENTATION

**D1.** The OIDC client-authentication method strings are centralized as constants in the protocol class,
not in the authenticators.
* Bundle 1: `Client authentication methods`, `TLS_CLIENT_AUTH`, `PRIVATE_KEY_JWT`

**D2.** One factory may claim several method names (secret-basic and secret-post come from a single
provider), so the mapping is one-to-many, not one-to-one.
* Bundle 1: `ClientIdAndSecretAuthenticator`, `CLIENT_SECRET_BASIC`, `CLIENT_SECRET_POST`

### DATA_FLOW

**E1.** The advertised list of token-endpoint authentication methods is computed at runtime by streaming
every registered factory and flat-mapping its protocol methods — it is not a static list.
* Bundle 1: `getClientAuthMethodsSupported`, `getProviderFactoriesStream`, `flatMap`
* Bundle 2: `setTokenEndpointAuthMethodsSupported`, `clientAuthMethodsSupported`, `setRevocationEndpointAuthMethodsSupported`

**E2.** There are two different ways an authenticator stays out of that list, and they are not
interchangeable: returning an empty method set, or declaring itself unsupported behind a feature flag.
* Bundle 1: `FederatedJWTClientAuthenticator`, `getProtocolAuthenticatorMethods`, `Collections.emptySet`
* Bundle 2: `AttestationBasedClientAuthenticator`, `isSupported`, `CLIENT_AUTH_ABCA`

### WIRING

**F1.** Factories are discovered through a plain-text ServiceLoader file in the resources of the services
module; no annotation or Java reference registers them.
* Bundle 1: `org.keycloak.authentication.authenticators.client.X509ClientAuthenticator`,
  `org.keycloak.authentication.authenticators.client.JWTClientAuthenticator`,
  `org.keycloak.authentication.authenticators.client.ClientIdAndSecretAuthenticator`

**F2.** The built-in client-authentication flow is seeded with executions referencing provider ids from a
different module, so "registered" and "enabled in the default flow" are separate things.
* Bundle 1: `DefaultAuthenticationFlows`, `client-x509`, `setAuthenticator`

### SECONDARY

**G1.** A second registry keyed by provider id lives in the default client-profiles resource and is read
by the client-policy executor factory.
* Bundle 1: `allowed-client-authenticators`, `default-client-authenticator`, `fapi-2-security-profile`
* Bundle 2: `ALLOWED_CLIENT_AUTHENTICATORS`, `SecureClientAuthenticatorExecutorFactory`, `secure-client-authenticator`

**G2.** A client whose authenticator id is missing from that allow-list is rejected at request time by the
client-policy executor, with no compile-time signal.
* Bundle 1: `isValidClientAuthenticator`, `Configured client authentication method not allowed for client`, `ClientPolicyException`

### INVARIANT

**H1.** A method string must be claimed by exactly one factory: resolution filters the factory stream and
takes `findFirst()`, over an order that is not specified anywhere.
* Bundle 1: `findClientAuthenticatorForOIDCAuthMethod`, `getProviderFactoriesStream`, `findFirst`

**H2.** A client authenticator that cannot authenticate must mark the execution as *attempted*, not as
*failed*, so that the remaining alternative executions of the flow still get their chance; only
client-not-found / client-disabled are hard failures.
* Bundle 1: `context.attempted()`, `CLIENT_NOT_FOUND`, `x509 client certificate is not available`

### VERIFICATION

**I1.** The well-known endpoint test pins the **exact** set of advertised token-endpoint auth methods, so
any new method fails it until that list is updated.
* Bundle 1: `getTokenEndpointAuthMethodsSupported`, `client_secret_basic`, `tls_client_auth`

**I2.** Admin-API tests enumerate client authenticator provider ids together with their display types and
their place in the client flow, so a new provider changes those expectations too.
* Bundle 1: `addClientAuthenticatorProviderInfo`, `client-x509`, `X509 Certificate`
* Bundle 2: `addExecInfo`, `client-x509`, `ALTERNATIVE`

### The facts no reference query can answer

A find-usages / callers / implementations query returns *edges between declarations*. These five facts are
not edges:

* **H1** — "exactly one factory may claim a method string" is a **runtime selection rule** implied by
  `findFirst()` on an unordered stream. Find-usages of `getProtocolAuthenticatorMethods` lists the
  overrides; it cannot tell you that two overrides returning the same string collide, nor which one wins.
* **E1** — the discovery list is assembled by reflection over the provider registry. There is no call edge
  from any authenticator to `OIDCWellKnownProvider`; asking "who uses my new class?" returns nothing,
  yet the new class changes that endpoint's output.
* **E2** — which of two exclusion mechanisms is in force for a given neighbour (empty set vs
  `isSupported` feature gate) is a property of the *bodies* of two sibling overrides. A query on the
  interface method shows both implementations as equal citizens.
* **G1 + G2** — a three-file indirection with **no symbolic link at all**: a JSON string
  (`"client-x509"`) → a config property name in the executor factory → a runtime comparison in the
  executor. The provider id in the JSON is not a Java symbol, so it participates in no reference index;
  find-usages of `X509ClientAuthenticator.PROVIDER_ID` never reaches the JSON.
* **I1** — the pinned expectation is a list of **string literals** in a test that mentions neither the new
  class nor the new constant. No reference query can predict that adding a provider breaks it.

## 8. Estimated implementation effort

For a downstream agent that already holds the architecture note:

| file | action | lines |
|---|---|---|
| `services/.../authenticators/client/SelfSignedX509ClientAuthenticator.java` | new class, mostly reused shape of the neighbour | ~220 new (≈120 without license/imports/boilerplate overrides) |
| `services/.../protocol/oidc/OIDCLoginProtocol.java` | one constant | +1 |
| `services/src/main/resources/META-INF/services/org.keycloak.authentication.ClientAuthenticatorFactory` | one line | +1 |
| `services/src/main/resources/keycloak-default-client-profiles.json` | 7 allow-lists | +14 / −7 |

Total: **4 files, +237 / −7**. Realistically 30–45 minutes for an agent with the note; the two
registration edits are one line each once known, and the JSON edit is mechanical. Without the note, the
JSON allow-list and the ServiceLoader file are the two edits that are routinely missed, and the exact-set
test assertion is the failure that surfaces last.

Follow-up (deliberately **not** part of the gold change, to keep it minimal): updating
`AbstractWellKnownProviderTest:179` and the admin-API provider-enumeration tests. They are recorded here
as VERIFICATION facts because the checklist should reward discovering them.
