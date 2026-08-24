/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * The two NEW architecture cases of the generalization round, in Keycloak subsystems the first round
 * never touched.
 *
 * Both were designed the same way and admitted on the same four gates, with every number measured on
 * the pinned checkout (`60c4d5e9`) and recorded in `CASE-EVIDENCE.md` beside each case's `gold.patch`:
 * the statement localizes nothing, three obvious shell commands reveal at most a quarter of the gold
 * set, at least four checklist facts are of a kind no reference query answers, and the reference
 * implementation compiles.
 *
 * Neither declares a hidden oracle. That is the deliberate saving that makes a four-case round
 * affordable: `U(B)` is produced from a statement and a checklist, and an oracle is only needed to
 * grade a SOLVER, which this round does not buy. See `DESIGN-GENERALIZATION.md`.
 *
 * Every evidence bundle below was verified to co-occur inside ONE file of the PRISTINE tree, by literal
 * substring search, before this file was written. Two classes of defect were found and removed that way:
 * bundles naming tokens that only exist in the gold (a detector that can never fire, silently lowering
 * `U` for both arms), and bundles whose tokens live in sibling files and never co-occur at all.
 */

/**
 * `acquisition__keycloak__client-auth-method` — the behavioural brief.
 *
 * The feature is RFC 8705 §2.2 self-signed-certificate client authentication, which Keycloak lacks
 * while shipping its §2.1 sibling. The statement is written in an administrator's vocabulary: it
 * describes the certificate, the refusal, the invariant ("a re-issued certificate with the same subject
 * and key must still be refused") and the two places the result has to become visible — and it names no
 * file, class, package, provider id or resource basename.
 */
const val CLIENT_AUTH_METHOD_STATEMENT: String = """
Some of our clients hold a certificate they issued to themselves — no authority signed it — and that
exact certificate is already uploaded to the client's credential configuration in the admin console. We
want such a client to be able to prove who it is to the token endpoint simply by presenting that same
certificate during the TLS handshake.

Today the only certificate-based way of authenticating refuses them: it insists that the presented
certificate chains up to a trusted authority and that its subject name matches a value configured on the
client, so a self-issued certificate never gets through.

After the change, a client is authenticated exactly when the certificate it presents is byte-for-byte
the certificate stored for it, and is refused the moment it differs — including a freshly re-issued
certificate that carries the very same subject name and the very same key. No trust store and no
authority chain is consulted at any point.

The new way of authenticating has to show up in the realm's public discovery document among the
authentication methods the token endpoint accepts, so that a client registering itself dynamically can
ask for it by name; and clients configured with it must be accepted by the built-in security profiles
that already tolerate certificate-based clients.
"""

/**
 * The leakage audit of [CLIENT_AUTH_METHOD_STATEMENT] — `grep -ril '<phrase>' --include=*.java | wc -l`.
 *
 * These counts are low, and the earlier proxy rule ("a phrase of the statement must match at least
 * twenty files, or it is a pointer") would have rejected the case. The proxy is the wrong test and the
 * evidence file shows why: what makes a statement safe is not that its words are common but that their
 * hit sets do not touch the gold. Measured here: no phrase reaches any of the four gold files, and the
 * narrowest two-phrase conjunctions a reader can form (`self-issued` with `token endpoint`,
 * `byte-for-byte` with `token endpoint`, `trust store` with `token endpoint`) each return ZERO files.
 * A rare phrase that points nowhere is safer than a common one that happens to point home.
 */
val CLIENT_AUTH_METHOD_LEAKAGE: Map<String, Int> = mapOf(
    "token endpoint" to 32,
    "credential configuration" to 30,
    "trust store" to 8,
    "subject name" to 7,
    "self-issued" to 4,
    "discovery document" to 1,
    "TLS handshake" to 1,
)

/** The pre-registered checklist of `acquisition__keycloak__client-auth-method`: fifteen facts. */
val CLIENT_AUTH_METHOD_CHECKLIST: AcquisitionChecklist = AcquisitionChecklist(
    caseId = "acquisition__keycloak__client-auth-method",
    facts = listOf(
        AcquisitionFact(
            id = "A1",
            category = AcquisitionFactCategory.PRECEDENT,
            statement = "A mutual-TLS client authentication method already ships as one class that claims " +
                "the OIDC method string and validates the presented certificate against a trust anchor.",
            evidenceBundles = listOf(
                listOf("client-x509", "TLS_CLIENT_AUTH", "validateCertificateChain"),
                listOf("ATTR_CA_SUBJECT_DN", "trustValidation", "getTrustAnchor"),
            ),
            judgeQuestion = "Does the note identify the existing certificate-based client authenticator as " +
                "the precedent, and say that it authenticates on trust rather than on identity?",
        ),
        AcquisitionFact(
            id = "A2",
            category = AcquisitionFactCategory.PRECEDENT,
            statement = "The certificate presented during the handshake is obtained from a separate lookup " +
                "SPI, not from the HTTP request.",
            evidenceBundles = listOf(
                listOf("X509ClientCertificateLookup", "getCertificateChain", "getProvider"),
            ),
            judgeQuestion = "Does the note say where the presented certificate comes from at run time?",
        ),
        AcquisitionFact(
            id = "A3",
            category = AcquisitionFactCategory.PRECEDENT,
            statement = "The certificate registered FOR a client is stored as a PEM client attribute owned " +
                "by the signed-JWT authenticator, so identity comparison has a source already.",
            evidenceBundles = listOf(
                listOf("CERTIFICATE_ATTR", "jwt.credential.certificate", "ATTR_PREFIX"),
            ),
            judgeQuestion = "Does the note say where the certificate stored for a client lives?",
        ),
        AcquisitionFact(
            id = "B1",
            category = AcquisitionFactCategory.ENTRY_POINT,
            statement = "Client authentication at the token endpoint is a full authentication FLOW executed " +
                "by the authentication processor, not an inline check inside the endpoint.",
            evidenceBundles = listOf(
                listOf("authorizeClient", "processor.authenticateClient()", "getClientAuthenticationFlow"),
            ),
            judgeQuestion = "Does the note say that client authentication runs as a flow, and name where it " +
                "is started?",
        ),
        AcquisitionFact(
            id = "B2",
            category = AcquisitionFactCategory.ENTRY_POINT,
            statement = "Dynamic client registration turns a requested `token_endpoint_auth_method` into a " +
                "provider id through one lookup helper, which is the second entry point into the same rule.",
            evidenceBundles = listOf(
                listOf(
                    "getTokenEndpointAuthMethod",
                    "findClientAuthenticatorForOIDCAuthMethod",
                    "setClientAuthenticatorType",
                ),
            ),
            judgeQuestion = "Does the note explain how a client asking for the method by name gets wired to " +
                "an implementation?",
        ),
        AcquisitionFact(
            id = "C1",
            category = AcquisitionFactCategory.ABSTRACTION,
            statement = "The provider-id-to-protocol-method mapping is a declared SPI method taking the login " +
                "protocol id, defined in a different Maven module from the implementations.",
            evidenceBundles = listOf(
                listOf("ClientAuthenticatorFactory", "getProtocolAuthenticatorMethods", "loginProtocol"),
                listOf("getProtocolAuthenticatorMethod", "Constants.OIDC_PROTOCOL", "setClientAuthenticationMethod"),
            ),
            judgeQuestion = "Does the note distinguish the provider id from the protocol method string and name " +
                "the SPI that bridges them?",
        ),
        AcquisitionFact(
            id = "D1",
            category = AcquisitionFactCategory.IMPLEMENTATION,
            statement = "The OIDC client-authentication method strings are centralized as constants in the " +
                "protocol class, not in the authenticators that claim them.",
            evidenceBundles = listOf(
                listOf("Client authentication methods", "TLS_CLIENT_AUTH", "PRIVATE_KEY_JWT"),
            ),
            judgeQuestion = "Does the note say where a new method string has to be declared?",
        ),
        AcquisitionFact(
            id = "E1",
            category = AcquisitionFactCategory.FLOW,
            statement = "The advertised list of token-endpoint authentication methods is computed at run time " +
                "by streaming every registered factory and flat-mapping its protocol methods; it is not a list " +
                "anyone edits.",
            evidenceBundles = listOf(
                listOf("getClientAuthMethodsSupported", "getProviderFactoriesStream", "flatMap"),
                listOf(
                    "setTokenEndpointAuthMethodsSupported",
                    "clientAuthMethodsSupported",
                    "setRevocationEndpointAuthMethodsSupported",
                ),
            ),
            judgeQuestion = "Does the note explain how the method becomes visible in the discovery document, " +
                "and that no static list needs editing?",
        ),
        AcquisitionFact(
            id = "E2",
            category = AcquisitionFactCategory.FLOW,
            statement = "There are two different ways an authenticator stays OUT of that advertised list — an " +
                "empty method set, or declaring itself unsupported behind a feature flag — and they are not " +
                "interchangeable.",
            evidenceBundles = listOf(
                listOf("FederatedJWTClientAuthenticator", "getProtocolAuthenticatorMethods", "Collections.emptySet"),
                listOf("AttestationBasedClientAuthenticator", "isSupported", "CLIENT_AUTH_ABCA"),
            ),
            judgeQuestion = "Does the note notice that some shipped authenticators deliberately do not appear in " +
                "discovery, and by which mechanism?",
        ),
        AcquisitionFact(
            id = "F1",
            category = AcquisitionFactCategory.WIRING,
            statement = "Factories are discovered through a plain-text service descriptor in the resources of " +
                "the services module; no annotation and no Java reference registers them.",
            evidenceBundles = listOf(
                listOf(
                    "org.keycloak.authentication.authenticators.client.X509ClientAuthenticator",
                    "org.keycloak.authentication.authenticators.client.JWTClientAuthenticator",
                    "org.keycloak.authentication.authenticators.client.ClientIdAndSecretAuthenticator",
                ),
            ),
            judgeQuestion = "Does the note name the service descriptor the new factory must be listed in?",
        ),
        AcquisitionFact(
            id = "G1",
            category = AcquisitionFactCategory.SECONDARY_INTEGRATION,
            statement = "A SECOND registry, keyed by provider id rather than by method string, lives in the " +
                "shipped client-profiles resource and is read by a client-policy executor factory.",
            evidenceBundles = listOf(
                listOf("allowed-client-authenticators", "default-client-authenticator", "fapi-2-security-profile"),
                listOf(
                    "ALLOWED_CLIENT_AUTHENTICATORS",
                    "SecureClientAuthenticatorExecutorFactory",
                    "secure-client-authenticator",
                ),
            ),
            judgeQuestion = "Does the note state that a second, independent registration keyed by provider id " +
                "exists, and where?",
        ),
        AcquisitionFact(
            id = "G2",
            category = AcquisitionFactCategory.SECONDARY_INTEGRATION,
            statement = "A client whose authenticator id is missing from that allow-list is rejected at request " +
                "time by the client-policy executor, with no compile-time signal anywhere.",
            evidenceBundles = listOf(
                listOf(
                    "isValidClientAuthenticator",
                    "Configured client authentication method not allowed for client",
                    "ClientPolicyException",
                ),
                listOf("SecureClientAuthenticatorExecutor", "allowed-client-authenticators"),
            ),
            judgeQuestion = "Does the note say what happens under a built-in security profile if the second " +
                "registration is skipped?",
        ),
        AcquisitionFact(
            id = "H1",
            category = AcquisitionFactCategory.INVARIANT,
            statement = "A protocol method string must be claimed by exactly ONE factory: resolution filters " +
                "the factory stream and takes the first match, over an order nothing specifies.",
            evidenceBundles = listOf(
                listOf("findClientAuthenticatorForOIDCAuthMethod", "getProviderFactoriesStream", "findFirst"),
            ),
            judgeQuestion = "Does the note warn that two factories claiming the same method string collide, and " +
                "that the winner is not defined?",
        ),
        AcquisitionFact(
            id = "H2",
            category = AcquisitionFactCategory.INVARIANT,
            statement = "An authenticator that cannot authenticate must mark the execution ATTEMPTED rather " +
                "than failed, so the flow's alternative executions still run; only client-not-found and " +
                "client-disabled are hard failures.",
            evidenceBundles = listOf(
                listOf("context.attempted()", "CLIENT_NOT_FOUND", "x509 client certificate is not available"),
                listOf("context.attempted()", "X509ClientCertificateLookup"),
            ),
            judgeQuestion = "Does the note say how a client authenticator must behave when it is not the right " +
                "one for the request?",
        ),
        AcquisitionFact(
            id = "I1",
            category = AcquisitionFactCategory.VERIFICATION,
            statement = "The well-known endpoint test pins the EXACT set of advertised token-endpoint auth " +
                "methods, so a new method breaks it until that expectation is updated.",
            evidenceBundles = listOf(
                listOf("getTokenEndpointAuthMethodsSupported", "client_secret_basic", "tls_client_auth"),
            ),
            judgeQuestion = "Does the note point at a test whose expectations the change invalidates?",
        ),
    ),
)

/**
 * `acquisition__keycloak__oauth-grant-type` — the behavioural brief.
 *
 * The feature is a dedicated token-endpoint grant for renewing long-lived, non-interactive credentials,
 * which the tree does not have: offline refresh tokens are today indistinguishable from interactive
 * ones at the `grant_type` level. The invariant is stated behaviourally — every renewal operation must
 * carry a distinct short identifier the server stamps into issued tokens, and a collision must stop the
 * server from starting rather than produce tokens of ambiguous origin.
 */
const val OAUTH_GRANT_TYPE_STATEMENT: String = """
Long-running background integrations and interactive applications currently renew their access tokens
through the very same request shape, so an administrator cannot tell them apart, cannot allow one while
forbidding the other, and cannot audit them separately.

We want a dedicated, separately named renewal operation at the token endpoint, reserved for long-lived,
non-interactive credentials: a caller that presents a long-lived credential to it gets a new access
token exactly as before, while a caller that presents an ordinary interactive-session credential is
rejected with a standard "invalid grant" error rather than silently succeeding. Rejecting the wrong kind
of credential must happen before any new token is minted, and the failure must be an ordinary protocol
error rather than a server error.

The new operation must appear in the server's published capability metadata, so that a client can detect
it without being told.

One invariant: every renewal operation the server offers carries a distinct short identifier that the
server uses internally to stamp the tokens it issues. Two operations sharing that short identifier must
not be possible — the server must refuse to start rather than issue tokens whose origin is ambiguous.
"""

/**
 * The leakage audit of [OAUTH_GRANT_TYPE_STATEMENT] — `grep -ril '<phrase>' --include=*.java | wc -l`.
 *
 * The narrowest conjunction a reader can form from the statement's vocabulary lands on a constants
 * holder in another module, and the grant package itself contains zero occurrences of the statement's
 * central phrase before the patch. Note what is deliberately ABSENT from the statement: the words
 * "offline" and "refresh". Both appear all over the tree (58 and 85 files), and either would have turned
 * the first grep into a map of the precedent.
 */
val OAUTH_GRANT_TYPE_LEAKAGE: Map<String, Int> = mapOf(
    "access token" to 151,
    "token endpoint" to 32,
    "renewal" to 3,
    "non-interactive" to 3,
)

/** The pre-registered checklist of `acquisition__keycloak__oauth-grant-type`: fifteen facts. */
val OAUTH_GRANT_TYPE_CHECKLIST: AcquisitionChecklist = AcquisitionChecklist(
    caseId = "acquisition__keycloak__oauth-grant-type",
    facts = listOf(
        AcquisitionFact(
            id = "A1",
            category = AcquisitionFactCategory.PRECEDENT,
            statement = "The nearest analogue is the refresh-token grant: a grant class extending the grant " +
                "base plus a factory that publishes a two-letter shortcut constant.",
            evidenceBundles = listOf(
                listOf("RefreshTokenGrantTypeFactory", "GRANT_SHORTCUT", "\"rt\""),
                listOf("class RefreshTokenGrantType extends OAuth2GrantTypeBase", "EventType.REFRESH_TOKEN"),
            ),
            judgeQuestion = "Does the note name the existing refresh grant and its factory as the precedent to " +
                "build on?",
        ),
        AcquisitionFact(
            id = "A2",
            category = AcquisitionFactCategory.PRECEDENT,
            statement = "Ten grant factories are registered at this commit, and their shortcuts form a " +
                "namespace with no free duplicate, so a new grant has to pick an unused one.",
            evidenceBundles = listOf(
                listOf(
                    "org.keycloak.protocol.oidc.grants.RefreshTokenGrantTypeFactory",
                    "org.keycloak.protocol.oidc.grants.JWTAuthorizationGrantTypeFactory",
                    "org.keycloak.protocol.oidc.grants.device.DeviceGrantTypeFactory",
                ),
                listOf("OAuth2GrantTypeFactory", "getShortcut", "unique"),
            ),
            judgeQuestion = "Does the note enumerate the existing grants, or at least state that the shortcut " +
                "namespace is shared and already occupied?",
        ),
        AcquisitionFact(
            id = "A3",
            category = AcquisitionFactCategory.PRECEDENT,
            statement = "Half of the shipped grants hide themselves behind a feature flag through a supported-" +
                "check on the factory, which is a precedented shape a new grant may or may not want.",
            evidenceBundles = listOf(
                listOf("isSupported(Config.Scope", "Profile.isFeatureEnabled", "Profile.Feature.DEVICE_FLOW"),
                listOf("EnvironmentDependentProviderFactory", "OAuth2GrantTypeFactory", "isSupported"),
            ),
            judgeQuestion = "Does the note notice that a grant can be feature-gated, and how?",
        ),
        AcquisitionFact(
            id = "B1",
            category = AcquisitionFactCategory.ENTRY_POINT,
            statement = "The token endpoint dispatches by a provider lookup keyed on the raw `grant_type` form " +
                "parameter — not a switch — and an unknown value becomes `unsupported_grant_type`.",
            evidenceBundles = listOf(
                listOf("session.getProvider(OAuth2GrantType.class, grantType)", "newUnsupportedGrantTypeException"),
                listOf("GRANT_TYPE_PARAM", "checkGrantType()", "grant.process("),
            ),
            judgeQuestion = "Does the note say how the endpoint decides which grant handles a request?",
        ),
        AcquisitionFact(
            id = "B2",
            category = AcquisitionFactCategory.ENTRY_POINT,
            statement = "Client authentication, CORS, duplicate-parameter checks and DPoP handling all happen " +
                "BEFORE the grant is called, so a grant must not repeat them.",
            evidenceBundles = listOf(
                listOf("checkParameterDuplicated", "DPoPUtil", "grant.process(context)"),
            ),
            judgeQuestion = "Does the note say what the endpoint has already done by the time a grant runs?",
        ),
        AcquisitionFact(
            id = "C1",
            category = AcquisitionFactCategory.ABSTRACTION,
            statement = "The grant SPI is one `process(Context)` method, and the context carries the session, " +
                "the form parameters, the event builder, CORS and the token manager.",
            evidenceBundles = listOf(
                listOf("interface OAuth2GrantType extends Provider", "Response process(Context context)"),
                listOf("public static class Context", "MultivaluedMap<String, String> formParams", "Cors cors"),
            ),
            judgeQuestion = "Does the note name the grant SPI and what its context provides?",
        ),
        AcquisitionFact(
            id = "C2",
            category = AcquisitionFactCategory.ABSTRACTION,
            statement = "The grant FACTORY SPI demands a second identifier next to the grant id: a short " +
                "unique code.",
            evidenceBundles = listOf(
                listOf("OAuth2GrantTypeFactory", "String getShortcut()"),
            ),
            judgeQuestion = "Does the note mention that a grant factory must declare a shortcut in addition to " +
                "its id?",
        ),
        AcquisitionFact(
            id = "D1",
            category = AcquisitionFactCategory.IMPLEMENTATION,
            statement = "Deciding whether a refresh token belongs to a long-lived session is an existing helper " +
                "that compares the token's type against the offline constant — not a hand-rolled JWT parse.",
            evidenceBundles = listOf(
                listOf("public static boolean isOfflineToken", "TOKEN_TYPE_OFFLINE"),
                listOf("TOKEN_TYPE_OFFLINE", "isOfflineToken"),
            ),
            judgeQuestion = "Does the note say how the two kinds of credential are told apart, and that the " +
                "repository already has that check?",
        ),
        AcquisitionFact(
            id = "E1",
            category = AcquisitionFactCategory.FLOW,
            statement = "The grant base class populates the form parameters, the event and CORS from the " +
                "context, so its context-setting call must run before any of them is touched.",
            evidenceBundles = listOf(
                listOf("protected void setContext(Context context)", "this.cors = context.cors"),
                listOf("setContext(context)", "formParams.getFirst"),
            ),
            judgeQuestion = "Does the note describe the order a grant implementation has to respect when it " +
                "starts handling a request?",
        ),
        AcquisitionFact(
            id = "E2",
            category = AcquisitionFactCategory.FLOW,
            statement = "A wrong-kind credential has to be refused as an OAuth protocol error with an " +
                "invalid-token event and HTTP 400, which is a different path from a server error.",
            evidenceBundles = listOf(
                listOf("OAuthErrorException.INVALID_GRANT", "Response.Status.BAD_REQUEST", "Errors.INVALID_TOKEN"),
            ),
            judgeQuestion = "Does the note say what shape the refusal must take?",
        ),
        AcquisitionFact(
            id = "F1",
            category = AcquisitionFactCategory.WIRING,
            statement = "A grant becomes reachable only by being listed in a plain-text service descriptor; " +
                "without that line nothing fails to compile and the grant is simply absent at run time.",
            evidenceBundles = listOf(
                listOf(
                    "org.keycloak.protocol.oidc.grants.RefreshTokenGrantTypeFactory",
                    "org.keycloak.protocol.oidc.grants.AuthorizationCodeGrantTypeFactory",
                ),
            ),
            judgeQuestion = "Does the note name the descriptor the new factory has to be added to?",
        ),
        AcquisitionFact(
            id = "G1",
            category = AcquisitionFactCategory.SECONDARY_INTEGRATION,
            statement = "The advertised grant list in the discovery document is DERIVED from the factory " +
                "stream, so the new grant appears automatically and no list has to be edited — unlike the " +
                "response types three lines above it, which are hardcoded.",
            evidenceBundles = listOf(
                listOf("getGrantTypesSupported", "getProviderFactoriesStream(OAuth2GrantType.class)", "ProviderFactory::getId"),
                listOf("setGrantTypesSupported", "OAuth2Constants.IMPLICIT"),
            ),
            judgeQuestion = "Does the note establish whether the published capability list needs an edit, and " +
                "why?",
        ),
        AcquisitionFact(
            id = "G2",
            category = AcquisitionFactCategory.SECONDARY_INTEGRATION,
            statement = "The shortcut is consumed by a token-context encoder in a different package, which " +
                "builds an id-to-shortcut map at start-up and refreshes unknown grants lazily.",
            evidenceBundles = listOf(
                listOf("DefaultTokenContextEncoderProviderFactory", "grantsByShortcuts", "grantsToShortcuts"),
                listOf("getShortcutByGrantType", "sessionFactory.getProviderFactory(OAuth2GrantType.class, grantType)"),
            ),
            judgeQuestion = "Does the note say WHO consumes the shortcut, and that it lives outside the grant " +
                "package?",
        ),
        AcquisitionFact(
            id = "H1",
            category = AcquisitionFactCategory.INVARIANT,
            statement = "Shortcut uniqueness is a global start-up invariant: duplicate shortcuts make the two " +
                "maps differ in size and the server throws during factory initialisation, so reusing the " +
                "precedent's constant stops the server from booting.",
            evidenceBundles = listOf(
                listOf("grantsByShortcuts.size() != grantsToShortcuts.size()", "IllegalStateException"),
                listOf("same ID or shortcut like other grants", "postInit"),
            ),
            judgeQuestion = "Does the note state the consequence of picking a shortcut that is already taken, " +
                "and where that is enforced?",
        ),
        AcquisitionFact(
            id = "I1",
            category = AcquisitionFactCategory.VERIFICATION,
            statement = "The well-known test asserts the EXACT NUMBER of advertised grant types, so adding a " +
                "grant fails it even though the discovery list needs no edit.",
            evidenceBundles = listOf(
                listOf("assertEquals(10, oidcConfig.getGrantTypesSupported().size())"),
                listOf("getGrantTypesSupported().size()", "assertContains"),
            ),
            judgeQuestion = "Does the note point at an existing test that a new grant invalidates?",
        ),
    ),
)
