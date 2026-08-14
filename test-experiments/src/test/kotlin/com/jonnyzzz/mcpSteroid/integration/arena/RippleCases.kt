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
     * **This case was retargeted; the original was ill-posed.** It renamed
     * `org.keycloak.admin.client.resource.RealmResource.roles()` on the recorded premise that the
     * method's `@Path("roles")` annotation carried the HTTP contract, so the Java name was free. Two
     * files call `UriBuilder.fromUri("").path(RealmResource.class, "roles")` —
     * `tests/utils/.../AdminEventPaths.java:218` and
     * `testsuite/integration-arquillian/tests/base/.../AdminEventPaths.java:217` — and RESTEasy
     * resolves that second argument as a METHOD NAME, answering
     * `RESTEASY003645: No public @Path annotated method for ...RealmResource.roles` after the rename.
     * No compiler and no reference search sees a string literal, so a live round graded an arm as a
     * perfect rename — P1 to P4 true, recall 1.0, precision 1.0, no missed sites, compile gate PASS,
     * hidden consumer green — over broken runtime behaviour. See [RippleNameEscapeRule].
     *
     * **Why THIS target instead.** `UserSessionProvider` is a server-SPI provider interface and
     * `getUserSession(RealmModel, String)` is a plain lookup on it: no `jakarta.ws.rs` annotation, so
     * `UriBuilder.path(Class, String)` cannot address it; the string `"getUserSession"` occurs in no
     * Java literal (measured through the string index by the survey: 0) and the word occurs in no
     * non-`.java` file at all at the base commit — no theme message, no realm JSON, no
     * `META-INF/services` entry, and none of the GraalJS scripts under `testsuite` that call model
     * methods by name at runtime (which is what disqualified `ClientModel#getClientId` and
     * `CommonClientSessionModel#getClient`). It is not reachable by name-derived reflection either:
     * the tree's only `Class.getMethods()` enumeration keyed by property name is
     * `ProtocolMapperUtils`, and it enumerates `UserModel` (which is what disqualified every
     * `UserModel` getter, `getUsername` and `getEmail` included). The name is declared exactly once on
     * the interface, so the transformation has a single unambiguous target, and the method overrides
     * nothing, so it is the root of its own ripple.
     *
     * `lookupUserSession` is free at the base commit: zero occurrences anywhere in the tree.
     */
    val renameMethodWideTarget: RenameMethod = RenameMethod(
        targetClassFqn = "org.keycloak.models.UserSessionProvider",
        oldName = "getUserSession",
        newName = "lookupUserSession",
        returnTypeSimpleName = "UserSessionModel",
        parameterList = "RealmModel realm, String id",
        behaviourPreservationEvidence =
            "The target carries no JAX-RS annotation and its name appears in no string literal, no " +
                "configuration file, no runtime script and no name-derived reflective lookup at the " +
                "base commit, so a correct rename is not observable from outside the code.",
    )

    /**
     * The compile gate is the seven modules PSI measured as holding references, plus the declaring
     * module — which holds none of its own, so without it the interface whose declaration changes
     * would never be compiled at all.
     */
    val renameMethodWide: RippleCase = RippleCase(
        instanceId = "ripple__keycloak__rename-method-wide",
        target = renameMethodWideTarget,
        expectedGoldReferences = 121,
        expectedGoldFiles = 42,
        // 21 declarations share the simple name at the base commit; the 6 inside the target's own
        // override family are excluded, because a correct solution MUST rename those too.
        expectedDecoyDeclarations = 15,
        compileGateModules = listOf(
            "keycloak-server-spi",
            "keycloak-server-spi-private",
            "keycloak-services",
            "keycloak-model-test",
            "integration-arquillian-tests-base",
            "keycloak-testsuite-utils",
            "keycloak-tests-base",
            "keycloak-tests-utils-shared",
        ),
        declaringModuleArtifactId = "keycloak-server-spi",
        consumerModuleArtifactId = "keycloak-server-spi",
        hiddenConsumerFqn = "org.keycloak.models.RenameMethodWideContractTest",
        patchResource = "arena-overlays/ripple-keycloak-rename-method-wide.patch",
        createdAt = "2026-08-14T00:00:00Z",
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
