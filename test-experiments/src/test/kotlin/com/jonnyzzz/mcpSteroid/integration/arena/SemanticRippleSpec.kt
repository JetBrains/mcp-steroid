/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * The pinned specification of the semantic-ripple pilot task.
 *
 * Every count here was measured by PSI at [baseCommit] and is meaningless at any other commit. They
 * are asserted as tripwires before the agent runs: without them an index failure yields an empty
 * gold set, which would score as 100% recall over nothing.
 *
 * The rename is behaviour-preserving by construction, not by assertion: the target method carries
 * `@Path("roles")`, so the HTTP contract is defined by the annotation and cannot change with the
 * Java method name.
 *
 * [newName] is deliberately not `realmRoles`, which is already declared 5 times in the project.
 */
object SemanticRippleSpec {

    const val cloneUrl: String = "https://github.com/keycloak/keycloak.git"
    const val repoOwnerAndName: String = "keycloak/keycloak"
    const val baseCommit: String = "60c4d5e9321ff5462a772ceb896f8cb2e639e04b"

    const val targetClassFqn: String = "org.keycloak.admin.client.resource.RealmResource"
    const val targetReturnTypeSimpleName: String = "RolesResource"
    const val oldName: String = "roles"
    const val newName: String = "realmLevelRoles"

    /** Resolved references to the target at [baseCommit]. */
    const val expectedGoldReferences: Int = 445

    /** Distinct files holding those references at [baseCommit]. */
    const val expectedGoldFiles: Int = 79

    /** Project declarations sharing the simple name [oldName], excluding the target itself. */
    const val expectedDecoyDeclarations: Int = 16

    /**
     * The declaring module plus every module holding a reference — complete w.r.t. the ripple.
     *
     * These are Maven artifactIds, verified against the poms at [baseCommit]. Selecting them needs
     * [compileGateSelectors], not these strings.
     */
    val compileGateModules: List<String> = listOf(
        "keycloak-admin-client-core",
        "integration-arquillian-tests-base",
        "keycloak-admin-v2-tests",
        "keycloak-authzen-tests-base",
        "keycloak-test-framework-tests",
        "keycloak-tests-base",
        "keycloak-tests-utils",
    )

    /**
     * [compileGateModules] in the form Maven's `-pl` actually understands.
     *
     * A `-pl` token without a colon is read as a relative DIRECTORY PATH, not as an artifactId, so
     * the bare names answer `Could not find the selected project in the reactor` and the gate never
     * runs at all — measured in both arms of build 1028521545, where every reference module was
     * therefore left unchecked.
     */
    val compileGateSelectors: List<String> = compileGateModules.map { ":$it" }

    /**
     * The profile that puts the `testsuite` module tree into the reactor.
     *
     * `integration-arquillian-tests-base` lives under the root pom's `testsuite` profile, which is
     * NOT active by default, so without this the selector for it fails exactly like a mistyped
     * artifactId — and the arquillian references, a real part of the ripple, would go ungated.
     */
    const val reactorProfile: String = "testsuite"

    /**
     * The module owning the hidden consumer, as a `-pl` selector for the grading run.
     *
     * Keycloak's default reactor cannot be built to completion at all — `keycloak-quarkus-dist`
     * depends on a `:zip` artifact only the `distribution` profile produces — so a root `mvn test`
     * stops there and skips every test module behind it. Note the directory (`tests/base`) and the
     * artifactId disagree, which is why the grading run identifies the module by this selector rather
     * than by the patch's directory.
     */
    const val failToPassModuleSelector: String = ":keycloak-tests-base"

    const val projectJdkVersion: String = "21"

    /** Same budget the heaviest DPAIA cases already carry. */
    const val agentTimeoutSeconds: Long = 5_400L

    /**
     * Bounds `waitForProjectReady`. `SemanticRipplePrewarmProbeTest` measured 384 s on a warm
     * machine (Docker images and the Maven dependency cache already populated) on 2026-08-11 — see
     * Task 1's report, `MEASURED_PREWARM_SECONDS: 384`. This value is not that measurement doubled:
     * 3_600_000 ms is comfortable headroom for a genuinely cold Maven dependency cache, which has
     * never been measured and could plausibly add many more minutes on top of the warm figure.
     */
    const val projectReadyTimeoutMs: Long = 3_600_000L

    /**
     * Maven arguments for the compile gate. `test-compile` rather than `compile` because all 445
     * references live in test sources; `-pl` without `-am` because the harness prewarm already
     * installed the siblings, and `-am` OOM-kills the container.
     */
    fun compileGateArgs(): List<String> = listOf(
        "test-compile",
        "-P", reactorProfile,
        "-pl", compileGateSelectors.joinToString(","),
        "-DskipTests",
    )
}
