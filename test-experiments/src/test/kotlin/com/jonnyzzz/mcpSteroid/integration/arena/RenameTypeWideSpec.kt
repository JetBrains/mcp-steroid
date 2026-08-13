/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * The pinned specification of the wide rename-type case of the keycloak-semantic-ripple family.
 *
 * Every count here was measured by PSI at [baseCommit] and is meaningless at any other commit. They
 * are asserted as tripwires before the agent runs: without them an index failure yields an empty
 * gold set, which would score as 100% recall over nothing.
 *
 * The rename is behaviour-preserving by construction, not by assertion: `ValidationContext` has
 * zero hits across `*.json *.xml *.properties *.yaml *.yml` at [baseCommit], its FQN appears in no
 * non-`.java` file (checked without a pathspec filter, so extension-less service-loader entries
 * under `META-INF/services` are covered), and nothing names it via `Class.forName` or `getMethod`
 * — so a correct rename is not observable from outside the code.
 *
 * [newName] is deliberately not `ValidationRunContext`-adjacent to anything already declared: it is
 * free at [baseCommit] — zero declarations, zero string occurrences anywhere in the tree.
 */
object RenameTypeWideSpec {

    const val cloneUrl: String = "https://github.com/keycloak/keycloak.git"
    const val repoOwnerAndName: String = "keycloak/keycloak"
    const val baseCommit: String = "60c4d5e9321ff5462a772ceb896f8cb2e639e04b"

    const val targetClassFqn: String = "org.keycloak.validate.ValidationContext"
    const val oldName: String = "ValidationContext"
    const val newName: String = "ValidationRunContext"

    /** Resolved references to the target at [baseCommit]. */
    const val expectedGoldReferences: Int = 198

    /** Distinct files holding those references at [baseCommit]. */
    const val expectedGoldFiles: Int = 41

    /** Project declarations sharing the simple name [oldName], excluding the target itself. */
    const val expectedDecoyDeclarations: Int = 3

    /**
     * The declaring module plus every module holding a reference — complete w.r.t. the ripple.
     *
     * These are Maven artifactIds, verified against the poms at [baseCommit]. Selecting them needs
     * [compileGateSelectors], not these strings.
     */
    val compileGateModules: List<String> = listOf(
        "keycloak-server-spi",
        "keycloak-server-spi-private",
        "keycloak-services",
        "integration-arquillian-testsuite-providers",
        "integration-arquillian-tests-base",
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

    /** The module declaring the rename target. */
    const val declaringModuleArtifactId: String = "keycloak-server-spi"

    /** The module owning the hidden consumer. */
    const val consumerModuleArtifactId: String = "keycloak-server-spi-private"

    /**
     * The `-pl` scope of the grading run: the consumer's module plus the module DECLARING the renamed
     * type.
     *
     * Two separate reasons, both measured. Scoping at all is required because Keycloak's default
     * reactor cannot be built to completion — `keycloak-quarkus-dist` depends on a `:zip` artifact
     * only the `distribution` profile produces, so a root `mvn test` stops there and skips every test
     * module behind it. Including the declaring module is required because without it the consumer
     * compiles against whatever `keycloak-server-spi` jar happens to sit in the shared local
     * repository, which is a mutable leftover of earlier runs rather than the tree under test.
     *
     * Note also that the directory (`tests/base`) and the artifactId disagree, which is why the
     * grading run identifies its modules by these selectors and not by the patch's directory.
     */
    val gradingScopeSelector: String =
        listOf(declaringModuleArtifactId, consumerModuleArtifactId).joinToString(",") { ":$it" }

    /**
     * Maven arguments that populate the shared local repository from the tree under test, run before
     * the agent starts.
     *
     * Two problems, one command. First, nothing else installs this reactor: on a cold agent `~/.m2`
     * holds no `999.0.0-SNAPSHOT` artifact and none can be downloaded, because that version exists
     * only where it was built — so every `-pl` invocation fails on missing upstream POMs
     * (`keycloak-core`, `keycloak-common`), including the ones the prompt recommends to the agent.
     * Second, `~/.m2` is a host bind mount shared by every container of every run and agents are told
     * to install into it, so an arm can otherwise begin against the PREVIOUS arm's renamed API:
     * measured locally, where the leftover `keycloak-admin-client-core` declared `realmLevelRoles()`
     * and no `roles()`, under which a pristine tree does not compile at all.
     *
     * Whole reactor rather than `-pl`, because the upstream closure is what is missing and computing
     * it by hand would be a hand-maintained copy of `-am`. `-fae` because the reactor cannot be built
     * to completion — the distribution modules need a `:zip` artifact only their own profile produces
     * — and those failures must not stop the modules this track actually needs. So the exit code of
     * this command is not evidence of anything; the pre-agent compile gate is what proves the
     * environment, and it fails the run when it cannot pass.
     */
    fun reactorInstallArgs(): List<String> = listOf(
        "install",
        "-P", reactorProfile,
        "-DskipTests",
        "-fae",
    )

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
     * Maven arguments for the compile gate. `test-compile` rather than `compile` because the 198
     * references span both main and test sources (unlike the pilot's target, which lives entirely in
     * test code) and `test-compile` compiles both; `-pl` without `-am` because the harness prewarm
     * already installed the siblings, and `-am` OOM-kills the container.
     */
    fun compileGateArgs(): List<String> = listOf(
        "test-compile",
        "-P", reactorProfile,
        "-pl", compileGateSelectors.joinToString(","),
        "-DskipTests",
    )
}
