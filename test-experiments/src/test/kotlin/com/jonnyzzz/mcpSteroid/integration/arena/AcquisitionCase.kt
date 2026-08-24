/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * The brief of the acquisition case, in the wording every arm receives.
 *
 * Written in the vocabulary of a Keycloak administrator reading the release notes — "security
 * profile", "confidential client", "client credentials grant" — and in no other. It names no file, no
 * class, no package, no provider id and no JSON resource, and the four sentences that describe the
 * behaviour are deliberately ordered as an administrator would experience it (create, update, partial
 * update, default) rather than as an implementer would build it.
 *
 * The third bullet is the case. It reads like a corner of the specification and it is in fact the only
 * sentence that forces the implementation to consult the client that is already stored rather than the
 * representation that arrived; every naive implementation, and every implementation copied from the
 * two executors whose names are closest to this task, satisfies bullets one, two and four and fails
 * that one. It is stated behaviourally on purpose — "an update that does not mention it" is what an
 * administrator sees; `ClientCRUDContext#getTargetClient` is what a developer has to find out.
 */
const val CC_REFRESH_TOKEN_STATEMENT: String =
    "Keycloak can be started with a **security profile** that hardens the clients of every realm with no " +
        "administrator configuration at all; the server ships several of them, and `strict` is the tightest.\n\n" +
        "Under `strict`, a confidential OpenID Connect client must never be able to obtain a refresh token from " +
        "the client credentials grant — OAuth 2.1 forbids it. Make the server enforce that:\n\n" +
        "- creating such a client with that behaviour switched on must be refused with an error, whether the " +
        "request arrives through the admin REST API or through dynamic client registration;\n" +
        "- updating an existing client to switch that behaviour on must be refused in the same way;\n" +
        "- an update that does not mention that behaviour at all must still be refused when the client it " +
        "targets already has it switched on;\n" +
        "- a client created without mentioning that behaviour must end up with it switched off;\n" +
        "- the rule must be in force out of the box under `strict`, must apply to confidential OpenID " +
        "Connect clients only, and must leave a server that runs without a security profile behaving exactly " +
        "as it does today."

/**
 * The leakage audit of [CC_REFRESH_TOKEN_STATEMENT], measured at the base commit with
 * `grep -ril <token> --include='*.java'` over the clone with `.git` excluded.
 *
 * Read the numbers as an upper bound on what a reader of the statement gets for free. Every phrase
 * returns between 23 and 592 files, so none of them localizes anything; and the single most specific
 * conjunction a reader can form — the files that mention BOTH "refresh token" and "client credentials"
 * — is 31 files, of which **not one** lives in `clientpolicy/` or `securityprofile/`, the two
 * directories where the whole change happens. The statement's vocabulary points at the OIDC token
 * endpoint, which is where the setting is *honoured*; the change is where it is *governed*, and those
 * are different subsystems. That gap is the case.
 */
val CC_REFRESH_TOKEN_LEAKAGE: Map<String, Int> = mapOf(
    "OAuth 2.1" to 23,
    "security-profile" to 25,
    "dynamic client registration" to 31,
    "security profile" to 36,
    "client credentials" to 70,
    "confidential" to 120,
    "refresh token" to 141,
    "strict" to 592,
)

/**
 * The pre-registered architecture checklist of the acquisition case.
 *
 * Built from the repository, the gold change, the hidden oracle and the real runtime path, and
 * committed before a single trajectory of either arm existed. Fifteen facts, one point each.
 *
 * Five of them — `B2`, `E1`, `E2`, `G1`, `H1` — cannot be answered by any reference query, semantic
 * or textual. `E1` is a rule about when executors run at all; `E2` is about where the shipped
 * configuration enters the request at all; `G1` is a three-file JSON indirection whose middle file is
 * chosen by a *name* in a config; `H1` is a statement about which of two data sources is authoritative
 * during a partial update. An agent can hold the complete
 * declaration hierarchy of `ClientPolicyExecutorProvider` and still get every one of them wrong, which
 * is the property that keeps this checklist from being a benchmark for find-usages.
 *
 * The evidence bundles are written against the CONTENT that carries each fact, never against the file
 * name that holds it. `G1` is the clearest example: seeing `keycloak-strict-client-policies.json` in a
 * directory listing scores nothing, and quoting the policy inside it that binds
 * `oauth-2-1-for-confidential-client` scores the point.
 */
val CC_REFRESH_TOKEN_CHECKLIST: AcquisitionChecklist = AcquisitionChecklist(
    caseId = "acquisition__keycloak__cc-refresh-token",
    facts = listOf(
        AcquisitionFact(
            id = "A1",
            category = AcquisitionFactCategory.PRECEDENT,
            statement = "The right structural precedent is a client-policy executor that acts on the client " +
                "CRUD events AND consults the already-stored client on update — `ConsentRequiredExecutor` " +
                "(its `beforeUpdate(ClientModel, ClientRepresentation)`), or `FullScopeDisabledExecutor`, " +
                "which has the same shape.",
            evidenceBundles = listOf(
                listOf("ConsentRequiredExecutor", "beforeUpdate"),
                listOf("FullScopeDisabledExecutor", "getTargetClient"),
                listOf("ConsentRequiredExecutor", "getTargetClient"),
            ),
            judgeQuestion = "Does the note tell the reader to imitate an executor that, on update, looks at " +
                "the client that is already stored (ConsentRequiredExecutor or FullScopeDisabledExecutor), " +
                "rather than one that only validates the incoming representation?",
        ),
        AcquisitionFact(
            id = "A2",
            category = AcquisitionFactCategory.PRECEDENT,
            statement = "`RejectImplicitGrantExecutor` and `RejectResourceOwnerPasswordCredentialsGrantExecutor` " +
                "are near misses: their names match the task, they do handle REGISTER and UPDATE, but they " +
                "validate the proposed representation only and additionally hook a runtime request event.",
            evidenceBundles = listOf(
                listOf("RejectImplicitGrantExecutor", "AUTHORIZATION_REQUEST"),
                listOf("RejectResourceOwnerPasswordCredentialsGrantExecutor", "RESOURCE_OWNER_PASSWORD_CREDENTIALS_REQUEST"),
            ),
            judgeQuestion = "Does the note distinguish enforcement at client create/update time from " +
                "enforcement at token/authorization request time, or warn that the similarly named " +
                "reject-*-grant executors are not the shape to copy?",
        ),
        AcquisitionFact(
            id = "B1",
            category = AcquisitionFactCategory.ENTRY_POINT,
            statement = "The admin REST API raises the policy events from `ClientsResource#createClient` and " +
                "`ClientResource#update`, which construct `AdminClientRegisterContext` / " +
                "`AdminClientUpdateContext`.",
            evidenceBundles = listOf(
                listOf("AdminClientRegisterContext", "triggerOnEvent"),
                listOf("AdminClientUpdateContext", "triggerOnEvent"),
                listOf("ClientsResource", "AdminClientRegisterContext"),
            ),
            judgeQuestion = "Does the note say that the admin REST API's client create/update path is what " +
                "raises the event, naming the resource class or the admin context class?",
        ),
        AcquisitionFact(
            id = "B2",
            category = AcquisitionFactCategory.ENTRY_POINT,
            statement = "Dynamic client registration raises the SAME events through " +
                "`DynamicClientRegisterContext` / `DynamicClientUpdateContext`, so a check written into one " +
                "REST resource would cover only half of the requirement while an executor covers both.",
            evidenceBundles = listOf(
                listOf("DynamicClientRegisterContext"),
                listOf("DynamicClientUpdateContext"),
            ),
            judgeQuestion = "Does the note point out that dynamic client registration goes through the same " +
                "mechanism, so that a single executor covers both entry points?",
        ),
        AcquisitionFact(
            id = "C1",
            category = AcquisitionFactCategory.ABSTRACTION,
            statement = "The extension point is `ClientPolicyExecutorProvider` plus its " +
                "`ClientPolicyExecutorProviderFactory`, whose `executeOnEvent(ClientPolicyContext)` is " +
                "dispatched on a `ClientPolicyEvent`.",
            evidenceBundles = listOf(
                listOf("ClientPolicyExecutorProvider", "executeOnEvent"),
                listOf("ClientPolicyExecutorProviderFactory", "executeOnEvent"),
            ),
            judgeQuestion = "Does the note name the client-policy executor provider interface (and its " +
                "factory) as the extension point to implement?",
        ),
        AcquisitionFact(
            id = "C2",
            category = AcquisitionFactCategory.ABSTRACTION,
            statement = "On REGISTER and UPDATE the context is a `ClientCRUDContext`, which offers " +
                "`getProposedClientRepresentation()` and `getTargetClient()`; the latter is null on REGISTER.",
            evidenceBundles = listOf(
                listOf("ClientCRUDContext", "getProposedClientRepresentation"),
                listOf("ClientCRUDContext", "getTargetClient"),
            ),
            judgeQuestion = "Does the note explain that the CRUD context carries both the proposed " +
                "representation and the existing client, and that the existing client is absent on create?",
        ),
        AcquisitionFact(
            id = "D1",
            category = AcquisitionFactCategory.IMPLEMENTATION,
            statement = "The behaviour is stored as the client attribute `client_credentials.use_refresh_token` " +
                "(`OIDCConfigAttributes.USE_REFRESH_TOKEN_FOR_CLIENT_CREDENTIALS_GRANT`), a string in " +
                "`ClientRepresentation#getAttributes` and in `ClientModel#getAttribute`.",
            evidenceBundles = listOf(
                listOf("client_credentials.use_refresh_token"),
                listOf("USE_REFRESH_TOKEN_FOR_CLIENT_CREDENTIALS_GRANT"),
            ),
            judgeQuestion = "Does the note name the client attribute key that holds this behaviour?",
        ),
        AcquisitionFact(
            id = "D2",
            category = AcquisitionFactCategory.IMPLEMENTATION,
            statement = "Refusal is a thrown `ClientPolicyException`; the executor never writes to the store " +
                "itself, it mutates the proposed representation or throws.",
            evidenceBundles = listOf(
                listOf("ClientPolicyException", "Errors.INVALID_REGISTRATION"),
                listOf("throw new ClientPolicyException"),
            ),
            judgeQuestion = "Does the note say that the way to refuse is to throw the client-policy exception " +
                "from the executor?",
        ),
        AcquisitionFact(
            id = "E1",
            category = AcquisitionFactCategory.FLOW,
            statement = "`DefaultClientPolicyManager#triggerOnEvent` runs the executors of a profile only " +
                "through an ENABLED policy whose conditions are satisfied; an executor added to a profile that " +
                "no enabled policy binds never runs, and a policy with no conditions is never satisfied.",
            evidenceBundles = listOf(
                listOf("DefaultClientPolicyManager", "getEnabledClientPolicies"),
                listOf("triggerOnEvent", "isSatisfied"),
                listOf("POLICY OPERATION", "No enabled policy"),
            ),
            judgeQuestion = "Does the note explain that a profile's executors only run when an enabled policy " +
                "whose conditions match binds that profile?",
        ),
        AcquisitionFact(
            id = "E2",
            category = AcquisitionFactCategory.FLOW,
            statement = "The profiles and policies the server ships arrive as GLOBAL ones through " +
                "`ClientPoliciesUtil.getGlobalClientProfiles/getGlobalClientPolicies`, which read them from the " +
                "`SecurityProfileProvider`, and are merged with whatever the realm itself stores.",
            evidenceBundles = listOf(
                listOf("ClientPoliciesUtil", "SecurityProfileProvider"),
                listOf("getGlobalClientProfiles"),
                listOf("getGlobalClientPolicies"),
            ),
            judgeQuestion = "Does the note explain that the shipped profiles/policies come from the security " +
                "profile provider as global ones, rather than being stored in each realm?",
        ),
        AcquisitionFact(
            id = "F1",
            category = AcquisitionFactCategory.WIRING,
            statement = "The new factory has to be listed in " +
                "`services/src/main/resources/META-INF/services/" +
                "org.keycloak.services.clientpolicy.executor.ClientPolicyExecutorProviderFactory`.",
            evidenceBundles = listOf(
                listOf("META-INF/services", "ClientPolicyExecutorProviderFactory"),
                listOf("ConsentRequiredExecutorFactory", "FullScopeDisabledExecutorFactory"),
            ),
            judgeQuestion = "Does the note say the factory must be added to the client-policy executor " +
                "service file under META-INF/services?",
        ),
        AcquisitionFact(
            id = "F2",
            category = AcquisitionFactCategory.WIRING,
            statement = "The executor id has to be added to a profile in " +
                "`services/src/main/resources/keycloak-default-client-profiles.json`, as an entry of the form " +
                "`{\"executor\": <id>, \"configuration\": {...}}`.",
            evidenceBundles = listOf(
                listOf("keycloak-default-client-profiles", "\"executor\""),
                listOf("oauth-2-1-for-confidential-client", "\"executor\""),
                listOf("fapi-1-baseline", "consent-required"),
            ),
            judgeQuestion = "Does the note say the executor must be added to the shipped default client " +
                "profiles JSON, with its id and configuration?",
        ),
        AcquisitionFact(
            id = "G1",
            category = AcquisitionFactCategory.SECONDARY_INTEGRATION,
            statement = "WHICH profile is fixed by a three-file indirection: `strict-security-profile.json` " +
                "names `keycloak-default-client-profiles` and `keycloak-strict-client-policies`, and the " +
                "enabled policy in the latter that matches a confidential OpenID Connect client binds the " +
                "profile `oauth-2-1-for-confidential-client` — so that profile is the one to join, and " +
                "joining any of the nine others would leave the rule switched off under `strict`.",
            evidenceBundles = listOf(
                listOf("oauth-2-1-for-confidential-client", "client-access-type"),
                listOf("oauth-2-1-for-confidential-client", "\"enabled\""),
                listOf("keycloak-strict-client-policies", "\"client-profiles\""),
            ),
            judgeQuestion = "Does the note identify the specific profile that the strict security profile's " +
                "enabled confidential-client policy binds, and say that this is the profile to add to?",
        ),
        AcquisitionFact(
            id = "H1",
            category = AcquisitionFactCategory.INVARIANT,
            statement = "On UPDATE the proposed representation is partial: when it carries no value for the " +
                "attribute the executor must fall back to `getTargetClient().getAttribute(...)`, otherwise a " +
                "partial update silently leaves the forbidden setting in place.",
            evidenceBundles = listOf(
                listOf("getTargetClient", "getAttribute"),
                listOf("beforeUpdate", "clientToBeUpdated"),
                listOf("proposedClient", "clientToBeUpdated"),
            ),
            judgeQuestion = "Does the note warn that an update request may omit the setting entirely and that " +
                "the executor must then read the value from the stored client?",
        ),
        AcquisitionFact(
            id = "I1",
            category = AcquisitionFactCategory.VERIFICATION,
            statement = "The right test pattern is a plain JUnit test in the `services` module beside the " +
                "executors (`SecureRedirectUrisEnforcerExecutorTest`), which constructs the executor directly " +
                "with no server; `DefaultSecurityProfileProverFactoryTest` is the pattern for asserting that " +
                "the shipped profile JSONs still load.",
            evidenceBundles = listOf(
                listOf("SecureRedirectUrisEnforcerExecutorTest"),
                listOf("DefaultSecurityProfileProverFactoryTest"),
                listOf("services/src/test/java/org/keycloak/services/clientpolicy"),
            ),
            judgeQuestion = "Does the note point at the existing plain-JUnit executor tests in the services " +
                "module as the pattern to follow for verification?",
        ),
    ),
)

/**
 * Every case of the acquisition-curve experiment, addressed by id.
 *
 * One entry today. The registry exists because the instrument — budget grid, checkpoint slicing,
 * checklist scoring — is deliberately a property of the harness and not of the task, and the first
 * thing a positive result will need is a second case that does not look like Keycloak.
 */
object AcquisitionCases {
    /**
     * `acquisition__keycloak__cc-refresh-token`.
     *
     * The change is four files and under two hundred lines; the research is a chain of seven hops that
     * the statement does not hint at. It is registered as an [UnderstandingCase] because the research
     * and downstream phases of the understanding-note experiment already do exactly what this one
     * needs — pristine tree, budget hook, hidden oracle applied only at grading time — and a second
     * copy of that machinery would be a second place for the same silent defects to live.
     */
    val ccRefreshToken: UnderstandingCase = UnderstandingCase(
        instanceId = "acquisition__keycloak__cc-refresh-token",
        problemStatement = CC_REFRESH_TOKEN_STATEMENT,
        // v2, and the v1 patch is kept beside it as the provenance of the first downstream wave rather
        // than as an alternative. v1's eight assertions all discovered the executor through one line of
        // the profile JSON, so they failed together until that line existed: the residual scale it
        // actually offered was `{0} u {5..8}` — one boolean wearing eight names — and the first wave was
        // graded on it before anyone noticed. v2 discovers the implementation by scanning the compiled
        // classes of the module, so each mechanism fails on its own terms.
        oracleTestPatchResource = "acquisition-cases/acquisition__keycloak__cc-refresh-token/oracle-v2.patch",
        failToPass = listOf(
            "org.keycloak.services.clientpolicy.executor.ClientCredentialsRefreshTokenResidualContractTest",
        ),
        // Nine INDEPENDENT assertions, one per obligation of the gold change: the class, the two
        // registrations, the blast radius, the two CRUD events, the auto-configure branch, the
        // partial-update invariant and the absence of over-rejection. Measured on five trees before
        // use — pristine 1, class only 7, plus META-INF 8, naive invariant 8, gold 9 — which is what
        // makes the count a reading of residual work rather than a noisier pass/fail.
        //
        // The floor is ONE and not zero: the blast-radius assertion passes on a pristine tree by
        // construction, because "changed no other shipped profile" is true of a tree that changed
        // nothing. Stated here so that nobody reads 1/9 as partial progress.
        oracleTestCount = 9,
        gradingScopeSelector = ":keycloak-services",
        // On, and it changes what the round 2 numbers mean: they were read through a build that
        // recompiled `services` alone. Nothing in THIS case's gold reaches outside that module, so the
        // closure is not a fix here — it is the condition under which a solver that wanders upstream is
        // graded on its solution rather than on the scope of the evaluation build.
        gradingBuildsDependencyClosure = true,
        goldPatchResource = "acquisition-cases/acquisition__keycloak__cc-refresh-token/gold.patch",
        statementLeakageTokens = CC_REFRESH_TOKEN_LEAKAGE,
        precedentPaths = listOf(
            "services/src/main/java/org/keycloak/services/clientpolicy/executor/ConsentRequiredExecutor.java",
            "services/src/main/java/org/keycloak/services/clientpolicy/executor/FullScopeDisabledExecutor.java",
        ),
        goldRolePaths = mapOf(
            "behaviour" to listOf(
                "services/src/main/java/org/keycloak/services/clientpolicy/executor/" +
                    "RejectClientCredentialsRefreshTokenExecutor.java",
                "services/src/main/java/org/keycloak/services/clientpolicy/executor/" +
                    "RejectClientCredentialsRefreshTokenExecutorFactory.java",
            ),
            "discovery" to listOf(
                "services/src/main/resources/META-INF/services/" +
                    "org.keycloak.services.clientpolicy.executor.ClientPolicyExecutorProviderFactory",
            ),
            "strict-profile-membership" to listOf(
                "services/src/main/resources/keycloak-default-client-profiles.json",
            ),
        ),
    )

    /**
     * The navigational POSITIVE CONTROL of the generalization round: the ripple family's wide method
     * rename, run through the acquisition instrument instead of through a PSI postcondition.
     *
     * Research-only, and it could not be anything else: the ripple grade is a compile gate over
     * fourteen modules plus a PSI query, not a Maven test class, so there is no hidden oracle to
     * declare here. What this round buys on it is a curve, and the curve is what anchors the top of
     * the round's scale — see [RENAME_METHOD_WIDE_CHECKLIST].
     *
     * Its statement names the symbol, which every other case in this family is forbidden to do. That
     * is not an oversight but the definition of the control: the knowledge under test is the reference
     * topology of a name that 336 files mention and 109 actually use.
     */
    val renameMethodWide: UnderstandingCase = UnderstandingCase(
        instanceId = "acquisition__keycloak__rename-method-wide",
        problemStatement = RENAME_METHOD_WIDE_STATEMENT,
        gradingScopeSelector = ":keycloak-server-spi",
        statementLeakageTokens = RENAME_METHOD_WIDE_LEAKAGE,
        precedentPaths = listOf(
            "server-spi/src/main/java/org/keycloak/models/KeycloakContext.java",
        ),
        goldRolePaths = mapOf(
            "declaration" to listOf(
                "server-spi/src/main/java/org/keycloak/models/KeycloakContext.java",
            ),
            "override-family" to listOf(
                "services/src/main/java/org/keycloak/services/DefaultKeycloakContext.java",
                "quarkus/runtime/src/main/java/org/keycloak/quarkus/runtime/integration/resteasy/" +
                    "QuarkusKeycloakContext.java",
                "services/src/test/java/org/keycloak/services/resteasy/ResteasyKeycloakContext.java",
            ),
            "fan-out" to listOf("496 references across 109 files in 14 reactor modules"),
        ),
    )

    /**
     * The shallow control: the note-bottleneck round's mapper case, re-registered under an
     * `acquisition__` id so this round's artifacts cannot be confused with that round's.
     *
     * Derived by `copy` from the case that round ran rather than retyped, so the statement, the leakage
     * audit and the oracle are the same objects the published numbers were produced with. A retyped
     * copy would be a second definition of "the mapper case" and the first divergence would be
     * invisible.
     */
    val emailDomainMapper: UnderstandingCase = UnderstandingCases.ALL
        .single { it.instanceId == "understanding__keycloak__email-domain-mapper" }
        .copy(instanceId = "acquisition__keycloak__email-domain-mapper")

    /**
     * `acquisition__keycloak__client-auth-method` — the first NEW architecture case: RFC 8705 §2.2
     * self-signed-certificate client authentication, which the tree lacks while shipping its §2.1
     * sibling.
     *
     * Research-only when the generalization round bought its curves; GRADABLE since the downstream
     * replication (`DESIGN-DOWNSTREAM-3.md`) gave it a hidden oracle. The gold patch beside the case is
     * the reference both the checklist and that oracle were derived FROM — it compiles at the pinned
     * commit and applies to a pristine checkout, which is what makes the discovery chain a fact about
     * the repository rather than a story about it.
     */
    val clientAuthMethod: UnderstandingCase = UnderstandingCase(
        instanceId = "acquisition__keycloak__client-auth-method",
        problemStatement = CLIENT_AUTH_METHOD_STATEMENT,
        oracleTestPatchResource = "acquisition-cases/acquisition__keycloak__client-auth-method/oracle.patch",
        failToPass = listOf(
            "org.keycloak.authentication.authenticators.client.ClientAuthenticationMethodResidualContractTest",
        ),
        // Nine INDEPENDENT assertions, measured on six trees before use: pristine 1, implementation
        // only 7, plus the ServiceLoader line 8, plus the profile allow-lists 9 — and the naive
        // neighbour (subject-DN comparison instead of the certificate itself) 8, losing exactly the
        // invariant axis. The floor is ONE and not zero because the blast-radius assertion is true of
        // a tree that changed nothing; stated here so nobody reads 1/9 as partial progress.
        oracleTestCount = 9,
        gradingScopeSelector = ":keycloak-services",
        gradingBuildsDependencyClosure = true,
        goldPatchResource = "acquisition-cases/acquisition__keycloak__client-auth-method/gold.patch",
        statementLeakageTokens = CLIENT_AUTH_METHOD_LEAKAGE,
        precedentPaths = listOf(
            "services/src/main/java/org/keycloak/authentication/authenticators/client/" +
                "X509ClientAuthenticator.java",
            "services/src/main/java/org/keycloak/authentication/authenticators/client/" +
                "JWTClientAuthenticator.java",
        ),
        goldRolePaths = mapOf(
            "behaviour" to listOf(
                "services/src/main/java/org/keycloak/authentication/authenticators/client/" +
                    "SelfSignedX509ClientAuthenticator.java",
            ),
            "method-string" to listOf(
                "services/src/main/java/org/keycloak/protocol/oidc/OIDCLoginProtocol.java",
            ),
            "discovery" to listOf(
                "services/src/main/resources/META-INF/services/" +
                    "org.keycloak.authentication.ClientAuthenticatorFactory",
            ),
            "profile-allow-list" to listOf(
                "services/src/main/resources/keycloak-default-client-profiles.json",
            ),
        ),
    )

    /**
     * `acquisition__keycloak__oauth-grant-type` — the second NEW architecture case: a dedicated token-
     * endpoint grant for renewing long-lived, non-interactive credentials.
     *
     * Research-only for the generalization round, GRADABLE since the downstream replication. Its
     * interest is that the invariant lives in ANOTHER subsystem than the change: the shortcut a grant
     * factory declares is consumed by the token-context encoder, which checks global uniqueness at
     * start-up and refuses to boot on a collision — while the nearest precedent publishes its own
     * shortcut as a public constant, inviting exactly that collision.
     */
    val oauthGrantType: UnderstandingCase = UnderstandingCase(
        instanceId = "acquisition__keycloak__oauth-grant-type",
        problemStatement = OAUTH_GRANT_TYPE_STATEMENT,
        oracleTestPatchResource = "acquisition-cases/acquisition__keycloak__oauth-grant-type/oracle.patch",
        failToPass = listOf("org.keycloak.protocol.oidc.grants.OAuth2GrantTypeResidualContractTest"),
        // Ten INDEPENDENT assertions, measured on seven trees: pristine 1, implementation only 8,
        // plus the ServiceLoader line 10 — and, off the ladder, 9 for the collided short code (the
        // uniqueness axis alone) and 8 for a grant that delegates without checking the credential
        // kind. The registration line flips TWO axes because it has two distinct runtime consequences,
        // dispatch and discovery; folding them into one would hide a wrong grant URI, which fails
        // discovery while dispatch still works.
        oracleTestCount = 10,
        gradingScopeSelector = ":keycloak-services",
        // The case that produced the requirement. Its shipped precedent names its grant through a
        // constant in `core`, so every solver that imitated the precedent added one there — and a
        // build scoped to `:keycloak-services` recompiles none of `core`, so eight of twelve note
        // cells were graded on `cannot find symbol`. The gold avoided the module with a local literal,
        // which is exactly why the ceiling looked fine.
        gradingBuildsDependencyClosure = true,
        goldPatchResource = "acquisition-cases/acquisition__keycloak__oauth-grant-type/gold.patch",
        statementLeakageTokens = OAUTH_GRANT_TYPE_LEAKAGE,
        precedentPaths = listOf(
            "services/src/main/java/org/keycloak/protocol/oidc/grants/RefreshTokenGrantType.java",
            "services/src/main/java/org/keycloak/protocol/oidc/grants/RefreshTokenGrantTypeFactory.java",
        ),
        goldRolePaths = mapOf(
            "behaviour" to listOf(
                "services/src/main/java/org/keycloak/protocol/oidc/grants/" +
                    "OfflineRefreshTokenGrantType.java",
            ),
            "factory" to listOf(
                "services/src/main/java/org/keycloak/protocol/oidc/grants/" +
                    "OfflineRefreshTokenGrantTypeFactory.java",
            ),
            "discovery" to listOf(
                "services/src/main/resources/META-INF/services/" +
                    "org.keycloak.protocol.oidc.grants.OAuth2GrantTypeFactory",
            ),
        ),
    )

    val all: List<UnderstandingCase> = listOf(
        ccRefreshToken,
        clientAuthMethod,
        oauthGrantType,
        renameMethodWide,
        emailDomainMapper,
    )

    fun byId(instanceId: String): UnderstandingCase =
        all.singleOrNull { it.instanceId == instanceId }
            ?: error("unknown acquisition case '$instanceId'; known: ${all.map { it.instanceId }}")

    fun checklistFor(instanceId: String): AcquisitionChecklist =
        when (instanceId) {
            ccRefreshToken.instanceId -> CC_REFRESH_TOKEN_CHECKLIST
            clientAuthMethod.instanceId -> CLIENT_AUTH_METHOD_CHECKLIST
            oauthGrantType.instanceId -> OAUTH_GRANT_TYPE_CHECKLIST
            renameMethodWide.instanceId -> RENAME_METHOD_WIDE_CHECKLIST
            emailDomainMapper.instanceId -> EMAIL_DOMAIN_MAPPER_CHECKLIST
            else -> error("no pre-registered checklist for '$instanceId'")
        }
}
