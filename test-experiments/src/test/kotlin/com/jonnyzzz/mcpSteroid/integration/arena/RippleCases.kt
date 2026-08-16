/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * Every case of the keycloak-semantic-ripple family, built in code rather than loaded from the dpaia
 * dataset.
 *
 * All of them measure the same tree at [SemanticRippleSpec.baseCommit]; a per-case commit would make
 * the cases incomparable with each other, which is the whole point of a family. Every number below
 * came from a run of `RippleTargetSurveyScripts.survey`, so a constant here can always be traced back
 * to a measurement.
 */
object RippleCases {

    /**
     * **This case was retargeted twice.**
     *
     * The founding target — `RealmResource.roles()` — was ill-posed: two `AdminEventPaths` files
     * address it by name through `UriBuilder.path(RealmResource.class, "roles")`, which no compiler
     * and no reference search can see. See [RippleNameEscapeRule].
     *
     * The first retarget — `UserSessionProvider#getUserSession(RealmModel, String)` — was well-posed
     * and graded cleanly, but it did not separate the arms. On TeamCity build 1031488927 both
     * claude+mcp and claude+none returned f1=1.0000, compile gate PASS, verified FTP 1/1, SUCCESS
     * true. The method's two-argument form is distinctive enough that a text search for
     * `getUserSession(` plus the interface hierarchy finishes the ripple without IDE reference
     * search; the experiment measured nothing.
     *
     * **Why THIS target.** `KeycloakContext#setRealm(RealmModel)` is a plain mutator on a server-SPI
     * interface: no `jakarta.ws.rs` annotation, so `UriBuilder.path(Class, String)` cannot address
     * it; the string `"setRealm"` occurs in no Java literal (survey `SURVEY_STRING_LITERAL_NAMES`:
     * 0; confirmed offline with `git grep -nE '"setRealm"'` at the base commit); it is not a
     * `UserModel` property getter, so `ProtocolMapperUtils`' reflective enumeration cannot reach it;
     * and none of the GraalJS scripts under `testsuite` call it by name. The name is declared exactly
     * once on the interface and overrides nothing, so it is the root of its own ripple.
     *
     * What the previous target lacked is **same-signature decoy pressure**. At the base commit 39
     * methods are named `setRealm` (survey `sameNameDeclarations=38` others). Most of them are not
     * this method: representation setters take `String` / `List` / `Set`, JPA entities take
     * `RealmEntity`, and several service types — `OIDCLoginProtocol`, `SamlProtocol`,
     * `AuthenticationProcessor`, `AppAuthManager.BearerTokenAuthenticator`,
     * `FreeMarkerEmailTemplateProvider`, `PersistentUserSessionAdapter` — declare
     * `setRealm(RealmModel)` with the SAME parameter list on a DIFFERENT owner. A text replace of
     * `setRealm(` cannot tell `context.setRealm(realm)` from `protocol.setRealm(realm)` without the
     * receiver's type; `ReferencesSearch` can. That is the pressure `getUserSession` did not apply
     * (its decoys were almost all no-arg `getUserSession()` forms, filtered by arity alone).
     *
     * The word `setRealm` also appears as a React `useState` setter in
     * `js/apps/admin-ui/src/realm/add/NewRealmForm.tsx`. That is a different function in a different
     * language, not a string-addressed lookup of this Java method; renaming the Java method does not
     * change it, and [RippleNameEscapeRule] is about string literals (count zero). It is noted so a
     * later reader does not rediscover the hit and treat it as a load-bearing name.
     *
     * Numbers below are from the rename-method survey at the base commit
     * (`KeycloakRippleTargetSurveyTest` / `RippleTargetSurveyScripts.renameMethod`, log
     * `run-20260814-122936-ripple-target-survey`):
     * `SURVEY_CANDIDATE ...|setRealm|496|109|13|38|0`, `SURVEY_OVERRIDES ...|1`,
     * `SURVEY_STRING_LITERAL_NAMES ...|0`. Decoys = same-name others minus the one overriding
     * declaration (`DefaultKeycloakContext#setRealm` in `keycloak-services`) = 38 − 1 = 37.
     *
     * `bindRealm` is free at the base commit: zero occurrences anywhere in the tree
     * (`git grep -l bindRealm` over the bare repository returns nothing).
     *
     * **What none of the numbers above establishes, and what the family learned the hard way.** Every
     * count here is about REACH (496 references, 109 files, 14 modules) or about same-named
     * DECLARATIONS (37). A declaration is not a trap for a textual replacement: `sed` rewrites CALL
     * sites, and a `setRealm` declared on `SamlProtocol` that nothing in the tree calls is never
     * touched by it. So the argument three paragraphs up — "a text replace of `setRealm(` cannot tell
     * `context.setRealm(realm)` from `protocol.setRealm(realm)`" — is stated about declarations and
     * only holds for the foreign declarations that are actually CALLED, which this case never
     * measured. On `de26f1999` the arms tied here at f1 1.0000, which is the reading that argument
     * predicts when the number of foreign call sites is small.
     *
     * [TextAmbiguity] is the measurement that settles it, and it has now been made. The
     * text-ambiguity survey phase at the base commit (log `run-20260816-185913-ripple-target-survey`)
     * printed `SURVEY_TEXT_AMBIGUITY rename-method|org.keycloak.models.KeycloakContext|setRealm|696|496|151`:
     * 696 places in code spell the word `setRealm`, only 496 of them reference this method, and 151
     * are CALL sites of same-named declarations outside this method's override family. So the trap
     * is real and measured — a blind textual replacement of `setRealm` rewrites 151 call sites it
     * must not touch — and the target is KEPT rather than retargeted. The tie on `de26f1999` was
     * therefore not caused by a target a text tool cannot get wrong; whatever explains it, it is not
     * this.
     */
    val renameMethodWideTarget: RenameMethod = RenameMethod(
        targetClassFqn = "org.keycloak.models.KeycloakContext",
        oldName = "setRealm",
        newName = "bindRealm",
        returnTypeSimpleName = "void",
        parameterList = "RealmModel realm",
        behaviourPreservationEvidence =
            "The target carries no JAX-RS annotation and its name appears in no string literal, no " +
                "configuration file, no runtime script and no name-derived reflective lookup at the " +
                "base commit, so a correct rename is not observable from outside the code.",
    )

    /**
     * The compile gate is the union of three populations, and a rename-method case needs all three
     * — see [renameMethodGateModules].
     *
     * The thirteen modules PSI measured as holding REFERENCES
     * (`SURVEY_MODULE_NAMES org.keycloak.models.KeycloakContext|setRealm|...`); the DECLARING module
     * `keycloak-server-spi`, which holds no reference of its own, so without it the interface whose
     * declaration changes would never be compiled at all; and the module holding the sole OVERRIDING
     * IMPLEMENTATION — `keycloak-services`, where `DefaultKeycloakContext#setRealm` lives.
     * `QuarkusKeycloakContext` and `ResteasyKeycloakContext` extend `DefaultKeycloakContext` without
     * redeclaring the method, so they contribute no extra override modules
     * (`SURVEY_OVERRIDES ...|1`).
     *
     * Unlike the previous target, the override module is NOT disjoint from the reference set: it
     * also holds call sites. The union formula still applies; the override entry is just already
     * covered by the reference list. The gate below is exactly
     * `renameMethodGateModules(referenceModules, listOf("keycloak-services"), "keycloak-server-spi")`
     * as the survey printed those lists.
     */
    val renameMethodWide: RippleCase = RippleCase(
        instanceId = "ripple__keycloak__rename-method-wide",
        target = renameMethodWideTarget,
        expectedGoldReferences = 496,
        expectedGoldFiles = 109,
        // 38 other declarations share the simple name at the base commit; the 1 inside the target's
        // own override family (DefaultKeycloakContext) is excluded, because a correct solution MUST
        // rename that too.
        expectedDecoyDeclarations = 37,
        // Measured, not argued: 696 textual occurrences against 496 real references, and 151 foreign
        // CALL sites a text replacement would rewrite. `resolvedReferences` is the same 496 pinned as
        // expectedGoldReferences above, read back by an independent query in the same run.
        textAmbiguity = TextAmbiguityPin.Measured(
            reading = TextAmbiguity(
                kind = "rename-method",
                ownerFqn = "org.keycloak.models.KeycloakContext",
                name = "setRealm",
                textualOccurrences = 696,
                resolvedReferences = 496,
                foreignSameNameCallSites = 151,
            ),
            source = "run-20260816-185913-ripple-target-survey",
        ),
        compileGateModules = listOf(
            "integration-arquillian-tests-base",
            "keycloak-admin-v2-services",
            "keycloak-admin-v2-tests",
            "keycloak-ldap-federation",
            "keycloak-model-infinispan",
            "keycloak-model-jpa",
            "keycloak-model-storage-private",
            "keycloak-model-storage-services",
            "keycloak-model-test",
            "keycloak-server-spi",
            "keycloak-server-spi-private",
            "keycloak-services",
            "keycloak-ssf-transmitter",
            "keycloak-tests-base",
        ),
        declaringModuleArtifactId = "keycloak-server-spi",
        consumerModuleArtifactId = "keycloak-server-spi",
        hiddenConsumerFqn = "org.keycloak.models.RenameMethodWideContractTest",
        patchResource = "arena-overlays/ripple-keycloak-rename-method-wide.patch",
        createdAt = "2026-08-16T00:00:00Z",
    )

    /**
     * `ValidationContext` has zero hits across `*.json *.xml *.properties *.yaml *.yml`, its FQN
     * appears in no non-`.java` file (checked without a pathspec filter, so extension-less
     * service-loader entries under `META-INF/services` are covered), and nothing names it via
     * `Class.forName` or `getMethod` — so a correct rename is not observable from outside the code.
     * `ValidationRunContext` is free at the base commit: zero declarations, zero string occurrences.
     */
    val renameTypeWideTarget: RenameType = RenameType(
        oldFqn = "org.keycloak.validate.ValidationContext",
        newSimpleName = "ValidationRunContext",
        behaviourPreservationEvidence =
            "The type's name appears in no configuration file, no service descriptor and no " +
                "reflective lookup at the base commit, so the rename is not externally observable.",
    )

    val renameTypeWide: RippleCase = RippleCase(
        instanceId = "ripple__keycloak__rename-type-wide",
        target = renameTypeWideTarget,
        expectedGoldReferences = 198,
        expectedGoldFiles = 41,
        expectedDecoyDeclarations = 3,
        // Measured in run-20260816-185913-ripple-target-survey:
        // SURVEY_TEXT_AMBIGUITY rename-type|org.keycloak.validate.ValidationContext|ValidationContext|593|198|74
        textAmbiguity = TextAmbiguityPin.Measured(
            reading = TextAmbiguity(
                kind = "rename-type",
                ownerFqn = "org.keycloak.validate.ValidationContext",
                name = "ValidationContext",
                textualOccurrences = 593,
                resolvedReferences = 198,
                foreignSameNameCallSites = 74,
            ),
            source = "run-20260816-185913-ripple-target-survey",
        ),
        compileGateModules = listOf(
            "keycloak-server-spi",
            "keycloak-server-spi-private",
            "keycloak-services",
            "integration-arquillian-testsuite-providers",
            "integration-arquillian-tests-base",
        ),
        declaringModuleArtifactId = "keycloak-server-spi",
        consumerModuleArtifactId = "keycloak-server-spi-private",
        hiddenConsumerFqn = "org.keycloak.validate.RenameTypeContractTest",
        patchResource = "arena-overlays/ripple-keycloak-rename-type-wide.patch",
        createdAt = "2026-08-13T00:00:00Z",
    )

    /**
     * `Resource` is a server-SPI model interface: it imports only `java.util.List/Map/Set`, carries
     * no `jakarta.ws.rs` and no Jackson annotation, and is not a representation class — the admin
     * REST path serialises `ResourceRepresentation`, a different type — so no HTTP contract can move
     * with its signature. Its three implementers are adapters and a wrapper, none of them a JPA
     * entity (the JPA-mapped `ResourceEntity` does not implement this interface), so persistence
     * property access cannot be disturbed either. The added parameter is passed as the literal
     * `false` at every call site and read by no implementer, which is what preserves behaviour.
     */
    val changeSignatureWideTarget: ChangeSignature = ChangeSignature(
        targetClassFqn = "org.keycloak.authorization.model.Resource",
        methodName = "getId",
        addedParameterType = "boolean",
        addedParameterName = "refresh",
        returnTypeSimpleName = "String",
        newArity = 1,
        behaviourPreservationEvidence =
            "The added parameter is passed as the literal false at every call site and read by no " +
                "implementer, and the declaring interface takes no part in any HTTP contract.",
    )

    /**
     * The compile gate here is a SUPERSET of the six modules PSI measured, trimmed to the modules
     * that actually carry evidence.
     *
     * The six were counted as IntelliJ modules by the survey, which did not print their names, and
     * mapping references back to Maven artifactIds needs the index that produced them. What can be
     * established from the repository alone is stronger than it sounds: a module can only hold a
     * reference to this method if some file in it obtains a `Resource`, and every module that does
     * names `org.keycloak.authorization.model.Resource` somewhere in its sources. The list below is
     * every Maven module at the base commit holding a source file that names that FQN exactly and
     * contains a `getId(` call, all reachable in the reactor under the `testsuite` profile and
     * verified against the module lists in the poms. Three modules that a looser substring match had
     * pulled in are deliberately absent: `keycloak-model-test` names the FQN nowhere at all, and
     * `keycloak-tests-custom-providers` and `keycloak-authzen-tests-providers` name only
     * `ResourceServer`, a different type. Each of them was a module whose `test-compile` had to go
     * green offline on an untouched tree or `prepareAndProveGateEnvironment` voids the arm, bought
     * with no reference to protect. A superset is safe for a gate — it compiles more than the ripple,
     * never less — but only where the extra modules are paid for by evidence.
     */
    val changeSignatureWide: RippleCase = RippleCase(
        instanceId = "ripple__keycloak__change-signature-wide",
        target = changeSignatureWideTarget,
        expectedGoldReferences = 104,
        expectedGoldFiles = 49,
        textAmbiguity = TextAmbiguityPin.NotApplicable,
        // 1021 declarations share the simple name at the base commit; the three that implement the
        // target — `ResourceAdapter` (infinispan), `ResourceAdapter` (jpa) and `ResourceWrapper`,
        // one `getId()` each, no further subtypes and no anonymous implementers — are excluded by
        // the hierarchy rule in [ChangeSignature], because a correct solution must change them.
        expectedDecoyDeclarations = 1018,
        compileGateModules = listOf(
            "keycloak-server-spi-private",
            "keycloak-services",
            "keycloak-model-jpa",
            "keycloak-model-infinispan",
            "keycloak-authz-policy-common",
            "keycloak-authzen-services",
            "keycloak-tests-base",
            "integration-arquillian-tests-base",
            "integration-arquillian-testsuite-providers",
        ),
        declaringModuleArtifactId = "keycloak-server-spi-private",
        consumerModuleArtifactId = "keycloak-server-spi-private",
        hiddenConsumerFqn = "org.keycloak.authorization.model.ChangeSignatureContractTest",
        patchResource = "arena-overlays/ripple-keycloak-change-signature-wide.patch",
        createdAt = "2026-08-13T00:00:00Z",
    )

    /**
     * The fan-out ablation of [renameTypeWide], read only against that twin: same kind, the same
     * lexical ambiguity (both collide with exactly three other same-named declarations —
     * `TestKeyUtils` collides with `org.keycloak.common.util.KeyUtils` among others), and a ripple two
     * orders of magnitude smaller. A run that separates the arms on the wide member and not on this
     * one is evidence that fan-out drove the difference, not ambiguity; a run with no separation on
     * either says nothing about fan-out by itself, because ambiguity was never varied to compare against.
     *
     * Name not load-bearing, exactly like the wide twin: zero hits across `*.json *.xml *.properties
     * *.yaml *.yml`, no reflective naming beyond the `Class.forName` this case's own consumer uses, and
     * the FQN appears in no non-`.java` file. `TestKeyUtils` is free at the base commit: zero hits
     * anywhere in the tree.
     */
    val renameTypeNarrowTarget: RenameType = RenameType(
        oldFqn = "org.keycloak.tests.utils.KeyUtils",
        newSimpleName = "TestKeyUtils",
        behaviourPreservationEvidence =
            "The type's name appears in no configuration file, no service descriptor and no " +
                "reflective lookup at the base commit, so the rename is not externally observable.",
    )

    val renameTypeNarrow: RippleCase = RippleCase(
        instanceId = "ripple__keycloak__rename-type-narrow",
        target = renameTypeNarrowTarget,
        expectedGoldReferences = 12,
        expectedGoldFiles = 3,
        expectedDecoyDeclarations = 3,
        // Measured in run-20260816-185913-ripple-target-survey:
        // SURVEY_TEXT_AMBIGUITY rename-type|org.keycloak.tests.utils.KeyUtils|KeyUtils|496|12|287
        // The sharpest reading in the family: 496 places spell `KeyUtils`, only 12 of them reference
        // this class, and 287 are call sites of the OTHER same-named classes (chiefly
        // org.keycloak.common.util.KeyUtils). A textual rename here is wrong 287 times over.
        textAmbiguity = TextAmbiguityPin.Measured(
            reading = TextAmbiguity(
                kind = "rename-type",
                ownerFqn = "org.keycloak.tests.utils.KeyUtils",
                name = "KeyUtils",
                textualOccurrences = 496,
                resolvedReferences = 12,
                foreignSameNameCallSites = 287,
            ),
            source = "run-20260816-185913-ripple-target-survey",
        ),
        compileGateModules = listOf("keycloak-tests-utils"),
        declaringModuleArtifactId = "keycloak-tests-utils",
        consumerModuleArtifactId = "keycloak-tests-utils",
        hiddenConsumerFqn = "org.keycloak.tests.utils.RenameTypeNarrowContractTest",
        patchResource = "arena-overlays/ripple-keycloak-rename-type-narrow.patch",
        createdAt = "2026-08-13T00:00:00Z",
    )

    /**
     * The fan-out ablation of [changeSignatureWide], read only against that twin: same kind, ambiguity
     * still far above the family's floor of 3, and a ripple confined to a single file where the wide
     * twin spans 49. A run that separates the arms on the wide member and not on this one is evidence
     * that fan-out drove the difference, not ambiguity.
     *
     * `Attributes` carries the same behaviour-preservation shape as `Resource`: it imports only
     * `java.util.*`, `java.util.function.*` and `org.keycloak.validate.ValidationError` — no
     * `jakarta.ws.rs`, no Jackson annotation, and it is not a representation class, so no HTTP contract
     * can move with its signature. The added parameter is passed as the literal `false` at every call
     * site and read by no implementer.
     *
     * **The decoy count is 18, not the 19 the raw same-simple-name survey reports.** The survey counts
     * every other `contains` declaration in the project; the actual grading script (like the wide
     * twin's) excludes the target's own override family, because a correct solution MUST move those
     * declarations and a key-set comparison would otherwise fail every correct run as over-reach.
     * `Attributes` has exactly one implementer that declares its own `contains(String)`:
     * `DefaultAttributes` (`server-spi-private`) — `ServiceAccountAttributes` extends it without
     * overriding `contains` again, so it contributes no separate declaration to exclude. 19 minus that
     * one override is 18.
     *
     * **The compile gate is two modules, not the one the reference survey reports.** The 11 references
     * all sit inside `keycloak-server-spi` itself (which is why the survey's module count is 1), but
     * `DefaultAttributes` — the override a correct solution must also change — lives in
     * `keycloak-server-spi-private`. Leaving that module out of the gate would let an agent forget the
     * implementer and still pass: `DefaultAttributes` would silently stop implementing `Attributes`
     * (an abstract-method error), and a gate that never compiles that module never sees it.
     */
    val changeSignatureNarrowTarget: ChangeSignature = ChangeSignature(
        targetClassFqn = "org.keycloak.userprofile.Attributes",
        methodName = "contains",
        addedParameterType = "boolean",
        addedParameterName = "includeUnmanaged",
        returnTypeSimpleName = "boolean",
        newArity = 2,
        behaviourPreservationEvidence =
            "The added parameter is passed as the literal false at every call site and read by no " +
                "implementer, and the declaring interface takes no part in any HTTP contract.",
    )

    val changeSignatureNarrow: RippleCase = RippleCase(
        instanceId = "ripple__keycloak__change-signature-narrow",
        target = changeSignatureNarrowTarget,
        expectedGoldReferences = 11,
        expectedGoldFiles = 1,
        textAmbiguity = TextAmbiguityPin.NotApplicable,
        expectedDecoyDeclarations = 18,
        compileGateModules = listOf("keycloak-server-spi", "keycloak-server-spi-private"),
        declaringModuleArtifactId = "keycloak-server-spi",
        consumerModuleArtifactId = "keycloak-server-spi",
        hiddenConsumerFqn = "org.keycloak.userprofile.ChangeSignatureNarrowContractTest",
        patchResource = "arena-overlays/ripple-keycloak-change-signature-narrow.patch",
        createdAt = "2026-08-13T00:00:00Z",
    )

    /**
     * `ResourceType`'s simple name IS load-bearing outside code — 39 non-`.java` files: admin-console
     * theme message bundles whose keys the theme reads at runtime, and imported realm JSON fixtures
     * whose enum values are matched against it (`git grep -c ResourceType` over `*.json *.xml
     * *.properties *.yaml *.yml` at the base commit). Renaming it would break lookups no compiler
     * checks, so `ResourceType` is disqualified as a [RenameType] target — a [MoveClass] changes only
     * the package, never the simple name or the enum constant names, so every one of those 39 files
     * stays valid. Its fully-qualified name, separately, appears in NO non-`.java` file — no
     * `META-INF/services` entry, no Quarkus reflect-config, no persistence XML, no `Class.forName` —
     * so the move itself is compile-visible only. This asymmetry between a load-bearing simple name
     * and a non-load-bearing fully-qualified name is the whole justification for this being a MOVE
     * and never a rename, and it is why [renameTypeWide] and this case are deliberately different
     * targets even though the two kinds' candidate lists are otherwise identical by construction.
     *
     * `resource` is free at the base commit: no `org/keycloak/models/workflow/resource/` directory
     * exists under `models/workflow` (whose only existing subpackage is `expression`).
     */
    val moveClassWideTarget: MoveClass = MoveClass(
        oldFqn = "org.keycloak.models.workflow.ResourceType",
        newPackage = "org.keycloak.models.workflow.resource",
        behaviourPreservationEvidence =
            "The simple name ResourceType is load-bearing in 39 theme-message and realm-JSON files, " +
                "which a move leaves untouched because it never changes the simple name; the " +
                "fully-qualified name that DOES change appears in no non-.java file, so the move " +
                "itself is not externally observable.",
    )

    val moveClassWide: RippleCase = RippleCase(
        instanceId = "ripple__keycloak__move-class-wide",
        target = moveClassWideTarget,
        expectedGoldReferences = 145,
        expectedGoldFiles = 50,
        textAmbiguity = TextAmbiguityPin.NotApplicable,
        expectedDecoyDeclarations = 4,
        compileGateModules = listOf(
            "keycloak-server-spi-private",
            "keycloak-model-jpa",
            "keycloak-services",
            "keycloak-tests-base",
        ),
        declaringModuleArtifactId = "keycloak-server-spi-private",
        consumerModuleArtifactId = "keycloak-server-spi-private",
        hiddenConsumerFqn = "org.keycloak.models.workflow.MoveClassWideContractTest",
        patchResource = "arena-overlays/ripple-keycloak-move-class-wide.patch",
        createdAt = "2026-08-13T00:00:00Z",
    )

    /**
     * The fan-out ablation of [moveClassWide], read only against that twin: same kind, ambiguity still
     * above the family's floor of 3 (`sameName=3`), and a ripple confined to 3 files where the wide
     * twin spans 50.
     *
     * Neither `ClientAdapter`'s simple name nor its fully-qualified name is load-bearing: zero hits
     * across `*.json *.xml *.properties *.yaml *.yml`, no reflective naming, and the fully-qualified
     * name appears in no non-`.java` file — so unlike its wide twin, this case's target would also
     * have tolerated a rename; it is a move because the family compares a move kind's wide and narrow
     * member against each other, the same way the other kinds do. `client` is free at the base commit:
     * no `org/keycloak/models/cache/infinispan/client/` directory exists.
     */
    val moveClassNarrowTarget: MoveClass = MoveClass(
        oldFqn = "org.keycloak.models.cache.infinispan.ClientAdapter",
        newPackage = "org.keycloak.models.cache.infinispan.client",
        behaviourPreservationEvidence =
            "The type's fully-qualified name appears in no configuration file, no service descriptor " +
                "and no reflective lookup at the base commit, so the move is not externally observable.",
    )

    val moveClassNarrow: RippleCase = RippleCase(
        instanceId = "ripple__keycloak__move-class-narrow",
        target = moveClassNarrowTarget,
        expectedGoldReferences = 9,
        expectedGoldFiles = 3,
        textAmbiguity = TextAmbiguityPin.NotApplicable,
        expectedDecoyDeclarations = 3,
        compileGateModules = listOf(
            "keycloak-model-infinispan",
            "keycloak-tests-base",
            "integration-arquillian-tests-base",
        ),
        declaringModuleArtifactId = "keycloak-model-infinispan",
        consumerModuleArtifactId = "keycloak-model-infinispan",
        hiddenConsumerFqn = "org.keycloak.models.cache.infinispan.MoveClassNarrowContractTest",
        patchResource = "arena-overlays/ripple-keycloak-move-class-narrow.patch",
        createdAt = "2026-08-13T00:00:00Z",
    )

    val all: List<RippleCase> = listOf(
        renameMethodWide, renameTypeWide, changeSignatureWide, renameTypeNarrow, changeSignatureNarrow,
        moveClassWide, moveClassNarrow,
    )
}
