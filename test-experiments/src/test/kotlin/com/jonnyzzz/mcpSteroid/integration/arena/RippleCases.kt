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
     * The target's `@Path("roles")` annotation is what defines the HTTP contract, so the Java method
     * name is free to change; `realmLevelRoles` is deliberately not `realmRoles`, which is already
     * declared five times in the project.
     */
    val renameMethodWideTarget: RenameMethod = RenameMethod(
        targetClassFqn = "org.keycloak.admin.client.resource.RealmResource",
        oldName = "roles",
        newName = "realmLevelRoles",
        returnTypeSimpleName = "RolesResource",
        behaviourPreservationEvidence =
            "The target carries @Path(\"roles\"), so the HTTP contract is defined by the annotation " +
                "and cannot change with the Java method name.",
    )

    val renameMethodWide: RippleCase = RippleCase(
        instanceId = "ripple__keycloak__realm-roles-rename",
        target = renameMethodWideTarget,
        expectedGoldReferences = 445,
        expectedGoldFiles = 79,
        expectedDecoyDeclarations = 16,
        compileGateModules = listOf(
            "keycloak-admin-client-core",
            "integration-arquillian-tests-base",
            "keycloak-admin-v2-tests",
            "keycloak-authzen-tests-base",
            "keycloak-test-framework-tests",
            "keycloak-tests-base",
            "keycloak-tests-utils",
        ),
        declaringModuleArtifactId = "keycloak-admin-client-core",
        consumerModuleArtifactId = "keycloak-tests-base",
        hiddenConsumerFqn = "org.keycloak.tests.admin.RealmResourceRenameContractTest",
        patchResource = "arena-overlays/semantic-ripple-keycloak-roles.patch",
        createdAt = "2026-08-11T00:00:00Z",
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

    val all: List<RippleCase> = listOf(renameMethodWide, renameTypeWide, changeSignatureWide)
}
