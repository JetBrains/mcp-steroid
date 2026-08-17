/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * The environment every case of the keycloak-semantic-ripple family shares.
 *
 * What used to live here was the pilot's target as well; that moved into [RippleCases] when the third
 * kind of transformation arrived and showed which half of the pilot was actually family-wide. What is
 * left is the tree under measurement and the container facts around it — the same repository, the same
 * commit, the same JDK, the same budgets — so that the cases stay comparable with each other.
 */
object SemanticRippleSpec {

    const val cloneUrl: String = "https://github.com/keycloak/keycloak.git"
    const val repoOwnerAndName: String = "keycloak/keycloak"
    const val baseCommit: String = "60c4d5e9321ff5462a772ceb896f8cb2e639e04b"

    /**
     * The profile that puts the `testsuite` module tree into the reactor.
     *
     * `integration-arquillian-tests-base` lives under the root pom's `testsuite` profile, which is
     * NOT active by default, so without this the selector for it fails exactly like a mistyped
     * artifactId — and the arquillian references, a real part of the ripple, would go ungated.
     */
    const val reactorProfile: String = "testsuite"

    /**
     * The pilot case's destination name.
     *
     * The one target-shaped name left on this otherwise environment-only object, and it is here for
     * one reason: `SemanticRippleOracleTest` is the regression check on the seam extraction and has to
     * keep passing byte-for-byte unedited, and it names this constant. Read from the registry rather
     * than restated, so the pilot's data still lives in exactly one place.
     */
    val newName: String get() = RippleCases.renameMethodWideTarget.newName

    /**
     * Maven arguments that populate the shared local repository from the tree under test, run before
     * the agent starts.
     *
     * Two problems, one command. First, nothing else installs this reactor: on a cold agent `~/.m2`
     * holds no `999.0.0-SNAPSHOT` artifact and none can be downloaded, because that version exists
     * only where it was built — so every `-pl` invocation fails on missing upstream POMs
     * (`keycloak-core`, `keycloak-common`), including the ones the prompt recommends to the agent.
     * Second, `~/.m2` is a host bind mount shared by every container of every run and agents are told
     * to install into it, so an arm can otherwise begin against the PREVIOUS arm's transformed API:
     * measured locally, where the leftover `keycloak-admin-client-core` declared `realmLevelRoles()`
     * and no `roles()`, under which a pristine tree does not compile at all.
     *
     * Whole reactor rather than `-pl`, because the upstream closure is what is missing and computing
     * it by hand would be a hand-maintained copy of `-am`. `-fae` because the reactor cannot be built
     * to completion — the distribution modules need a `:zip` artifact only their own profile produces
     * — and those failures must not stop the modules this track actually needs. So the exit code of
     * this command is not evidence of anything BY ITSELF; the pre-agent compile gate is what proves the
     * environment, and it fails the run when it cannot pass.
     *
     * The one exception is a network failure, which `-fae` turns into a cascade — see
     * [REACTOR_INSTALL_ATTEMPTS]. [resumeFrom] carries Maven's own resume point (`-rf :artifactId`) so
     * that retry rebuilds the failed module and everything skipped after it, not the ~150 modules that
     * already installed successfully.
     */
    fun reactorInstallArgs(resumeFrom: String? = null): List<String> = listOf(
        "install",
        "-P", reactorProfile,
        "-DskipTests",
        "-fae",
    ) + (resumeFrom?.let { listOf("-rf", it) } ?: emptyList())

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
}
