/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * The two control cases of the generalization round, and the checklists that bound the round's scale.
 *
 * The first round measured one architecture case and found that the semantic arm reached a given
 * checklist level in fewer environment interactions. The question that result cannot answer on its own
 * is how big "fewer" is — an effect has no size until something anchors the ends of the scale. So this
 * round runs, beside the two NEW architecture cases, two cases whose position is predicted BEFORE any
 * trajectory exists:
 *
 * - [RENAME_METHOD_WIDE_CHECKLIST] is the top anchor. Its facts really are reference facts: which
 *   declarations share a simple name, how many foreign call sites a textual rewrite would damage, which
 *   reactor modules the fan-out reaches. This is what semantic navigation is FOR, so the arm difference
 *   here should be the largest of the round. If it is not, the instrument is blind and nothing else in
 *   the round is interpretable.
 * - [EMAIL_DOMAIN_MAPPER_CHECKLIST] is the bottom anchor: the previous experiment's case, whose
 *   implementation is "copy one of twenty-two siblings in one directory and change the claim". Its
 *   discovery chain is short and its neighbours are found by one listing, so the arm difference should
 *   be the smallest of the round.
 *
 * Written before the round is queued, with every number below measured on the pinned checkout
 * (`60c4d5e9`) rather than argued. The predicted ORDERING — rename > the two architecture cases >
 * mapper — is the round's pre-registered contrast, and it is a stronger thing to test than another
 * replication of a single case, because a harness that produced a difference on all four cases equally
 * would be reporting something about the harness.
 */

/**
 * The brief of the navigational control, which NAMES ITS TARGET on purpose.
 *
 * Every other case in this family is admitted only after a leakage audit proves the statement localizes
 * nothing. This one is the exception and has to be: a rename task whose symbol is withheld is not a
 * rename task, it is a guessing game. The knowledge it demands is not "which file" but "which of the
 * 336 files that mention this name actually reference THIS declaration" — and that is precisely the
 * question a textual search answers wrongly and a resolved-reference query answers exactly.
 *
 * Kept verbatim from the ripple family's task text (see `RippleTarget.taskSection`), so that a reader
 * comparing the two rounds is comparing the same task.
 */
const val RENAME_METHOD_WIDE_STATEMENT: String =
    "Rename the method `setRealm(RealmModel)` declared on the interface `org.keycloak.models.KeycloakContext` " +
        "to `bindRealm`, everywhere in the repository.\n\n" +
        "The rename must be complete and behaviour-preserving: every declaration of that method in the " +
        "interface's override family, and every reference to it, must move to the new name, and nothing that " +
        "merely shares the simple name may be touched. The project must still compile afterwards, including " +
        "the modules whose only relationship to the method is that they call it."

/**
 * The leakage audit of [RENAME_METHOD_WIDE_STATEMENT], measured with `git grep -lI --ignore-case` over
 * `*.java` at the base commit.
 *
 * Read the other way round from every other case in this family. Here a large number is the POINT:
 * `setRealm` matches 336 files, and only 109 of them hold a real reference to the target declaration —
 * the remaining two hundred are the decoys, and the gap between 336 and 109 is the case. `bindRealm` at
 * zero is the ripple family's admission criterion: the new name is free, so the rename cannot collide
 * with anything that already exists.
 */
val RENAME_METHOD_WIDE_LEAKAGE: Map<String, Int> = mapOf(
    "bindRealm" to 0,
    "KeycloakContext" to 74,
    "setRealm" to 336,
    "RealmModel" to 1_215,
)

/**
 * The pre-registered checklist of the navigational control: eleven reference facts.
 *
 * Declared [AcquisitionChecklist.positiveControl], which is what allows it to be made of the categories
 * the architecture checklists are forbidden to consist of. The two facts that are NOT reference facts
 * are the ones a reference query genuinely cannot answer even here: that a textual rewrite corrupts
 * unrelated code (`H1`, `H2`) and that the fan-out reaches a module the default reactor does not even
 * contain (`I1`).
 */
val RENAME_METHOD_WIDE_CHECKLIST: AcquisitionChecklist = AcquisitionChecklist(
    caseId = "acquisition__keycloak__rename-method-wide",
    positiveControl = true,
    facts = listOf(
        AcquisitionFact(
            id = "A1",
            category = AcquisitionFactCategory.PRECEDENT,
            statement = "The declaration to rename is the interface method `void setRealm(RealmModel realm)` " +
                "in `server-spi/src/main/java/org/keycloak/models/KeycloakContext.java`; renaming an " +
                "implementation without the interface changes nothing about the contract.",
            evidenceBundles = listOf(
                listOf("org.keycloak.models.KeycloakContext", "setRealm"),
                listOf("KeycloakContext", "void setRealm"),
            ),
            judgeQuestion = "Does the note identify the interface declaration in the server-spi module as " +
                "the thing being renamed, rather than an implementation of it?",
        ),
        AcquisitionFact(
            id = "C1",
            category = AcquisitionFactCategory.ABSTRACTION,
            statement = "The override family is three classes: `DefaultKeycloakContext` (services), " +
                "`QuarkusKeycloakContext` (quarkus/runtime) and the test `ResteasyKeycloakContext`.",
            evidenceBundles = listOf(
                listOf("DefaultKeycloakContext", "setRealm"),
                listOf("QuarkusKeycloakContext", "KeycloakContext"),
            ),
            judgeQuestion = "Does the note name every class that overrides the method, including the one " +
                "outside the services module?",
        ),
        AcquisitionFact(
            id = "C2",
            category = AcquisitionFactCategory.ABSTRACTION,
            statement = "The interface is reached from a session as `session.getContext()`, so a caller " +
                "never holds the implementation type and the rename is invisible at the call site until " +
                "it does not compile.",
            evidenceBundles = listOf(
                listOf("getContext()", "KeycloakContext"),
                listOf("getContext", "setRealm"),
            ),
            judgeQuestion = "Does the note say how callers obtain the object they call the method on?",
        ),
        AcquisitionFact(
            id = "D1",
            category = AcquisitionFactCategory.IMPLEMENTATION,
            statement = "The real references number 496 across 109 files, and they span fourteen reactor " +
                "modules including `model/jpa`, `model/infinispan`, `federation/ldap` and `ssf/transmitter`.",
            evidenceBundles = listOf(
                listOf("setRealm", "model/jpa"),
                listOf("setRealm", "model/infinispan"),
                listOf("setRealm", "federation/ldap"),
            ),
            judgeQuestion = "Does the note state the breadth of the change in modules, naming persistence " +
                "and federation modules among them?",
        ),
        AcquisitionFact(
            id = "D2",
            category = AcquisitionFactCategory.IMPLEMENTATION,
            statement = "One implementation lives in `quarkus/runtime`, a module a `services`-scoped build " +
                "never compiles, so a green services build is not evidence that the rename is complete.",
            evidenceBundles = listOf(
                listOf("quarkus/runtime", "KeycloakContext"),
                listOf("quarkus", "QuarkusKeycloakContext"),
            ),
            judgeQuestion = "Does the note warn that part of the change lives outside the module the task " +
                "seems to be about?",
        ),
        AcquisitionFact(
            id = "D3",
            category = AcquisitionFactCategory.IMPLEMENTATION,
            statement = "Test sources are part of the fan-out: `test-framework` builders and the arquillian " +
                "test base call the method as well as production code.",
            evidenceBundles = listOf(
                listOf("setRealm", "test-framework"),
                listOf("setRealm", "integration-arquillian"),
            ),
            judgeQuestion = "Does the note say that test sources reference the method too?",
        ),
        AcquisitionFact(
            id = "F1",
            category = AcquisitionFactCategory.WIRING,
            statement = "Nothing resolves this method by name at run time: the simple name occurs in no " +
                "service descriptor, no configuration file and no reflective lookup, which is why the " +
                "rename is behaviour-preserving at all.",
            evidenceBundles = listOf(
                listOf("META-INF/services", "KeycloakContext"),
                listOf("getMethod", "setRealm"),
                listOf("Class.forName", "KeycloakContext"),
            ),
            judgeQuestion = "Does the note establish that no configuration or reflective lookup names the " +
                "method, i.e. that renaming it cannot change observable behaviour?",
        ),
        AcquisitionFact(
            id = "H1",
            category = AcquisitionFactCategory.INVARIANT,
            statement = "Thirty-seven other declarations share the simple name — `RealmRepresentation`, " +
                "`PolicyEnforcerConfig`, `OAuth2Error`, the `AdminConsole` inner classes — and a textual " +
                "replacement would rewrite 151 foreign call sites that belong to them.",
            evidenceBundles = listOf(
                listOf("RealmRepresentation", "setRealm(String"),
                listOf("PolicyEnforcerConfig", "setRealm"),
                listOf("AdminConsole", "setRealm"),
            ),
            judgeQuestion = "Does the note warn that other unrelated types declare a method of the same " +
                "name, so a text-level replacement is wrong?",
        ),
        AcquisitionFact(
            id = "H2",
            category = AcquisitionFactCategory.INVARIANT,
            statement = "The same simple name also appears in the admin console's TypeScript sources as " +
                "unrelated React state setters, which a repository-wide textual rewrite would corrupt.",
            evidenceBundles = listOf(
                listOf("setRealm", "js/apps/admin-ui"),
                listOf("setRealm", ".tsx"),
            ),
            judgeQuestion = "Does the note notice that the name is used outside Java, in code that must not " +
                "be touched?",
        ),
        AcquisitionFact(
            id = "I1",
            category = AcquisitionFactCategory.VERIFICATION,
            statement = "One module of the fan-out, `integration-arquillian-tests-base`, is in the reactor " +
                "only under the root pom's non-default `testsuite` profile, so proving the rename compiles " +
                "requires activating it.",
            evidenceBundles = listOf(
                listOf("<id>testsuite</id>"),
                listOf("testsuite", "profile", "integration-arquillian"),
            ),
            judgeQuestion = "Does the note say that part of the code that must still compile is not in the " +
                "default build at all?",
        ),
        AcquisitionFact(
            id = "I2",
            category = AcquisitionFactCategory.VERIFICATION,
            statement = "Nothing can be built module-by-module until the reactor's `999.0.0-SNAPSHOT` " +
                "artifacts are installed, because they exist nowhere but the machine that built them.",
            evidenceBundles = listOf(
                listOf("999.0.0-SNAPSHOT"),
            ),
            judgeQuestion = "Does the note tell the reader how to build a single module of this project at " +
                "all?",
        ),
    ),
)

/**
 * The pre-registered checklist of the shallow control — the previous round's mapper case.
 *
 * Twelve facts, built from the same three sources as every other checklist in this family: the
 * repository, the hidden oracle (`EmailDomainMapperContractTest`, seven assertions) and the runtime
 * path. It is NOT declared a positive control, because its facts genuinely are architecture facts: the
 * token-issuance flow, the base class that consults the administrator switches, and the second
 * registration that makes a mapper "shipped with the server".
 *
 * The prediction attached to it is the opposite of the rename case's. The chain is three hops and the
 * twenty-two sibling mappers sit in one directory, so a single listing puts an agent within one file of
 * everything except `G1` and `G2` — and the round expects the arm difference here to be the smallest it
 * measures. `FINDINGS.md` already showed the downstream ceiling of this case is reached at note lengths
 * of 2 000 characters, which is why it is a control now rather than a case.
 */
val EMAIL_DOMAIN_MAPPER_CHECKLIST: AcquisitionChecklist = AcquisitionChecklist(
    caseId = "acquisition__keycloak__email-domain-mapper",
    facts = listOf(
        AcquisitionFact(
            id = "A1",
            category = AcquisitionFactCategory.PRECEDENT,
            statement = "The right precedent is an existing OIDC claim mapper that extends " +
                "`AbstractOIDCProtocolMapper` and implements the token-type interfaces — `HardcodedClaim` " +
                "or `UserAttributeMapper` in `services/.../protocol/oidc/mappers/`.",
            evidenceBundles = listOf(
                listOf("HardcodedClaim", "AbstractOIDCProtocolMapper"),
                listOf("UserAttributeMapper", "OIDCAccessTokenMapper"),
            ),
            judgeQuestion = "Does the note name an existing claim mapper in the OIDC mappers package as the " +
                "thing to imitate?",
        ),
        AcquisitionFact(
            id = "B1",
            category = AcquisitionFactCategory.ENTRY_POINT,
            statement = "Claims are contributed while a token is being issued, in " +
                "`services/.../protocol/oidc/TokenManager.java`, which walks the client session's mappers " +
                "through `ProtocolMapperUtils.getSortedProtocolMappers`.",
            evidenceBundles = listOf(
                listOf("TokenManager", "transformAccessToken"),
                listOf("ProtocolMapperUtils", "getSortedProtocolMappers"),
            ),
            judgeQuestion = "Does the note say where in the runtime path a mapper is invoked?",
        ),
        AcquisitionFact(
            id = "C1",
            category = AcquisitionFactCategory.ABSTRACTION,
            statement = "The SPI is `org.keycloak.protocol.ProtocolMapper`; the OIDC side of it is " +
                "`AbstractOIDCProtocolMapper` plus the four marker interfaces `OIDCIDTokenMapper`, " +
                "`OIDCAccessTokenMapper`, `UserInfoTokenMapper` and `TokenIntrospectionTokenMapper`.",
            evidenceBundles = listOf(
                listOf("AbstractOIDCProtocolMapper", "OIDCIDTokenMapper"),
                listOf("TokenIntrospectionTokenMapper", "UserInfoTokenMapper"),
            ),
            judgeQuestion = "Does the note name the base class and the interfaces that decide which token " +
                "types a mapper participates in?",
        ),
        AcquisitionFact(
            id = "C2",
            category = AcquisitionFactCategory.ABSTRACTION,
            statement = "Which of the four interfaces a mapper implements IS the answer to the four " +
                "administrator switches: a mapper that does not implement the introspection interface can " +
                "never contribute to introspection however it is configured.",
            evidenceBundles = listOf(
                listOf("instanceof OIDCAccessTokenMapper"),
                listOf("instanceof OIDCIDTokenMapper"),
            ),
            judgeQuestion = "Does the note connect the interfaces a mapper implements to the token types it " +
                "can appear in?",
        ),
        AcquisitionFact(
            id = "D1",
            category = AcquisitionFactCategory.IMPLEMENTATION,
            statement = "A claim value is written onto the token's free-form claim map through " +
                "`OIDCAttributeMapperHelper.mapClaim(token, mappingModel, value)`, which reads the claim " +
                "name from the mapper's own configuration.",
            evidenceBundles = listOf(
                listOf("OIDCAttributeMapperHelper", "mapClaim"),
                listOf("mapClaim", "getOtherClaims"),
            ),
            judgeQuestion = "Does the note say how the value actually reaches the token?",
        ),
        AcquisitionFact(
            id = "D2",
            category = AcquisitionFactCategory.IMPLEMENTATION,
            statement = "The admin-console form comes from `getConfigProperties()` built with " +
                "`OIDCAttributeMapperHelper.addTokenClaimNameConfig` and " +
                "`addIncludeInTokensConfig`, and the console groups the mapper by `getDisplayCategory()`.",
            evidenceBundles = listOf(
                listOf("addTokenClaimNameConfig", "getConfigProperties"),
                listOf("addIncludeInTokensConfig", "ProviderConfigProperty"),
            ),
            judgeQuestion = "Does the note explain how the mapper becomes configurable in the admin console?",
        ),
        AcquisitionFact(
            id = "E1",
            category = AcquisitionFactCategory.FLOW,
            statement = "The base class calls the single overridden `setClaim` from all four of its " +
                "transform methods and it is the BASE class, not the mapper, that consults the " +
                "include-in-* switches.",
            evidenceBundles = listOf(
                listOf("AbstractOIDCProtocolMapper", "setClaim"),
                listOf("transformIDToken", "setClaim"),
            ),
            judgeQuestion = "Does the note say that one overridden method serves every token type, with the " +
                "switches handled above it?",
        ),
        AcquisitionFact(
            id = "E2",
            category = AcquisitionFactCategory.FLOW,
            statement = "The e-mail to read is the one on the token being issued (`IDToken#getEmail`), not " +
                "the one on the user model — the mapper sees the token that earlier mappers already filled.",
            evidenceBundles = listOf(
                listOf("IDToken", "getEmail"),
                listOf("token.getEmail"),
            ),
            judgeQuestion = "Does the note say where the e-mail value is read from at the moment the mapper " +
                "runs?",
        ),
        AcquisitionFact(
            id = "F1",
            category = AcquisitionFactCategory.WIRING,
            statement = "A mapper becomes resolvable by its provider id through " +
                "`services/src/main/resources/META-INF/services/org.keycloak.protocol.ProtocolMapper`.",
            evidenceBundles = listOf(
                listOf("META-INF/services", "org.keycloak.protocol.ProtocolMapper"),
                listOf("org.keycloak.protocol.ProtocolMapper", "mappers."),
            ),
            judgeQuestion = "Does the note name the service-descriptor file the new mapper must be listed in?",
        ),
        AcquisitionFact(
            id = "G1",
            category = AcquisitionFactCategory.SECONDARY_INTEGRATION,
            statement = "\"Shipped with the server\" is a SECOND, separate registration: " +
                "`OIDCLoginProtocolFactory.initBuiltIns()` puts `ProtocolMapperModel`s into its `builtins` " +
                "map, and only those appear in a fresh realm.",
            evidenceBundles = listOf(
                listOf("initBuiltIns", "builtins.put"),
                listOf("OIDCLoginProtocolFactory", "builtins"),
            ),
            judgeQuestion = "Does the note state that being listed in the service descriptor is not enough " +
                "to be offered out of the box, and name the second place?",
        ),
        AcquisitionFact(
            id = "G2",
            category = AcquisitionFactCategory.SECONDARY_INTEGRATION,
            statement = "The built-in entry is a `ProtocolMapperModel` built by a static helper on the " +
                "mapper class itself (the `createClaimMapper` pattern), so the built-in and the mapper " +
                "carry the same configuration twice.",
            evidenceBundles = listOf(
                listOf("createClaimMapper", "builtins"),
                listOf("createClaimMapper", "ProtocolMapperModel"),
            ),
            judgeQuestion = "Does the note say how a built-in mapper's default configuration is produced?",
        ),
        AcquisitionFact(
            id = "H1",
            category = AcquisitionFactCategory.INVARIANT,
            statement = "When the token carries no e-mail, or the e-mail has no `@`, the claim must be " +
                "ABSENT rather than empty — `mapClaim` itself returns early on a null value, so the mapper " +
                "must pass null and not an empty string.",
            evidenceBundles = listOf(
                listOf("mapClaim", "attributeValue == null"),
                listOf("if (attributeValue == null)"),
            ),
            judgeQuestion = "Does the note state what must happen when there is no e-mail to take a domain " +
                "from, and that an empty claim is not the same as no claim?",
        ),
    ),
)
