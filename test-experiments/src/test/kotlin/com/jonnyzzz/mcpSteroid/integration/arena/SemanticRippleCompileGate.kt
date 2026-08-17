/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer

/** Outcome of the scoped compile gate. */
data class CompileGateResult(
    val exitCode: Int,
    /** Last lines of the build log, kept bounded — a full Keycloak build log is enormous. */
    val tail: String,
) {
    val passed: Boolean get() = exitCode == 0
}

/**
 * The bash script for [case]'s scoped compile gate, kept pure so its shape is unit-tested.
 *
 * For a behaviour-preserving transformation, compilation is a COMPLETE invariant over the ripple
 * rather than an approximation: a call site the agent missed still names a declaration that no longer
 * exists in that form, which is a compile error. Scoping to the declaring module plus every module
 * holding a reference is therefore complete — no reference exists outside that set.
 *
 * `package -DskipTests` rather than `test-compile`: it still compiles test sources, where the
 * references live, AND it leaves a jar on every selected module — without which
 * `integration-arquillian-tests-base` cannot copy its sibling providers artifact and reports
 * `MDEP-187` instead of a compile verdict. See [RippleCase.compileGateArgs]. `-pl` without `-am`
 * because the harness prewarm already installed the siblings, and `-am` OOM-kills the container.
 *
 * Takes the case rather than reading one by name, which is what made the family's second case grow a
 * duplicate of this whole file: a by-name gate silently graded a type rename against the pilot's
 * method-rename modules.
 */
fun buildCompileGateScript(case: RippleCase, projectDir: String): String =
    // Single source of truth for the goal and the module list — RippleCase.compileGateArgs() is what
    // RippleCaseRegistryTest asserts against, so the script cannot drift from it.
    buildMavenScript(projectDir, case.compileGateArgs())

/**
 * The bash script that populates the shared local repository from the tree under test — see
 * [SemanticRippleSpec.reactorInstallArgs] for why neither a cold repository nor a warm one can be
 * trusted.
 *
 * Online, unlike the gate: on a cold agent the third-party dependencies of 152 modules are not there
 * yet, and an offline install would fail on the first of them.
 */
fun buildReactorInstallScript(projectDir: String, resumeFrom: String? = null): String =
    buildMavenScript(
        projectDir,
        SemanticRippleSpec.reactorInstallArgs(resumeFrom = resumeFrom),
        offline = false,
    )

/**
 * Number of attempts the reactor install gets before the arm is abandoned.
 *
 * Not a retry for the sake of retrying: the install reaches the network by construction, and one of the
 * modules it must build reaches a network the harness does not control. `model/infinispan` runs
 * `proto-schema-compatibility-check`, whose `remoteLockFiles` names five `raw.githubusercontent.com`
 * URLs, and the plugin exposes no user property to skip it. Under a burst of concurrent runs that share
 * one egress address GitHub answers HTTP 429, the module fails, `-fae` then SKIPS every module that
 * depends on it — `integration-arquillian-tests-base` among them — and the offline gate later reports
 * `MDEP-187` on an artifact that was never packaged. Measured: 17 of 25 arms started inside the same
 * five minutes on 2026-08-17 died this way, while the 140-run series whose starts were spread out saw
 * `install` exit 0 every single time.
 */
const val REACTOR_INSTALL_ATTEMPTS: Int = 3

/**
 * Maven error lines of a build log tail, in order — what to print when the environment is the problem.
 */
fun mavenErrorLines(tail: String): List<String> =
    tail.lines().map { it.trim() }.filter { it.contains("[ERROR]") }

/**
 * True when [tail] shows a failure caused by the network rather than by the tree under test.
 *
 * Deliberately narrow: a compile error, a missing artifact or a plugin misconfiguration must NOT be
 * retried, because repeating it burns twenty minutes to reach the same verdict.
 */
fun isTransientInstallFailure(tail: String): Boolean =
    transientInstallFailureMarkers.any { tail.contains(it, ignoreCase = true) }

private val transientInstallFailureMarkers = listOf(
    "HTTP response code: 429",
    "HTTP response code: 502",
    "HTTP response code: 503",
    "HTTP response code: 504",
    "status code: 429",
    "Too Many Requests",
    "Connection reset",
    "Connection timed out",
    "Read timed out",
    "Temporary failure in name resolution",
)

/**
 * The module Maven itself names as the resume point (`mvn <args> -rf :module`), or null when the tail
 * does not carry one.
 *
 * Resuming there rather than from the top is what makes a retry affordable: the ~150 modules already
 * installed are not rebuilt, and everything `-fae` skipped after the failure still is.
 */
fun resumeModuleOrNull(tail: String): String? =
    Regex("""-rf\s+(:[A-Za-z0-9._\-]+)""").findAll(tail).lastOrNull()?.groupValues?.get(1)

private fun buildMavenScript(projectDir: String, args: List<String>, offline: Boolean = true): String {
    val mavenArgs = (if (offline) listOf("-o") else emptyList()).plus(args).joinToString(" ")
    val jdkPrefix = "/usr/lib/jvm/temurin-${SemanticRippleSpec.projectJdkVersion}-jdk-"
    return """
        set -o pipefail
        cd '$projectDir' || exit 1
        JAVA_HOME="${'$'}(ls -d $jdkPrefix* 2>/dev/null | head -1)"
        if [ -z "${'$'}JAVA_HOME" ]; then
          echo "No JDK found under $jdkPrefix*" >&2
          exit 1
        fi
        if [ ! -x ./mvnw ]; then
          echo "No executable ./mvnw wrapper in $projectDir" >&2
          exit 1
        fi
        export JAVA_HOME
        ./mvnw $mavenArgs
    """.trimIndent()
}

/**
 * Run the gate in the container and return its exit code with a bounded log tail.
 *
 * Uses the same `bash -lc` shape as `ArenaVerifier`, so it inherits the container's login
 * environment. Offline (`-o`) because the prewarm already populated `~/.m2`: a gate that reaches the
 * network could fail on a repository outage and read as a missed call site.
 */
fun runCompileGate(container: ContainerDriver, case: RippleCase, projectDir: String): CompileGateResult =
    runMaven(
        container,
        buildCompileGateScript(case, projectDir),
        "Semantic-ripple scoped compile gate for ${case.instanceId}",
    )

/**
 * Populate the local repository from the tree under test, retrying a network-caused failure from the
 * module Maven names as its resume point.
 *
 * Fails the arm when the last attempt is still a network failure: continuing to the gate would spend
 * twelve minutes to report `MDEP-187` on a module `-fae` skipped, and that message has cost this family
 * a whole 25-build wave already.
 */
fun installReactorWithNetworkRetries(container: ContainerDriver, projectDir: String): CompileGateResult {
    var attempt = 1
    var install = runMaven(
        container,
        buildReactorInstallScript(projectDir),
        "Install the semantic-ripple reactor into the local repository",
    )
    while (!install.passed && isTransientInstallFailure(install.tail) && attempt < REACTOR_INSTALL_ATTEMPTS) {
        val resumeFrom = resumeModuleOrNull(install.tail)
        attempt++
        println(
            "[RIPPLE] reactor install exit ${install.exitCode} on a NETWORK failure — retry $attempt of " +
                "$REACTOR_INSTALL_ATTEMPTS" + (resumeFrom?.let { ", resuming at $it" } ?: ", from the top") +
                "\n  " + mavenErrorLines(install.tail).take(3).joinToString("\n  ")
        )
        install = runMaven(
            container,
            buildReactorInstallScript(projectDir, resumeFrom = resumeFrom),
            "Retry $attempt of the semantic-ripple reactor install",
        )
    }
    check(install.passed || !isTransientInstallFailure(install.tail)) {
        "The reactor install kept failing on the NETWORK after $attempt attempts (exit " +
            "${install.exitCode}), so nothing downstream of the failed module got packaged and the gate " +
            "could only report MDEP-187 on it. This arm measures the environment, not the agent — " +
            "abandoning it here instead of after the gate. Reported:\n  " +
            mavenErrorLines(install.tail).take(5).joinToString("\n  ")
    }
    println(
        "[RIPPLE] reactor install finished with exit ${install.exitCode} after $attempt attempt(s) — " +
            "non-network failures are best-effort by design, the pre-agent gate is what decides"
    )
    return install
}

/**
 * Populate the local repository from the tree under test, then prove the environment by running the
 * gate on that untouched tree.
 *
 * The install is best-effort about the modules it cannot build at all (see
 * [SemanticRippleSpec.reactorInstallArgs]), so a non-zero exit is not by itself a verdict. It IS a
 * verdict when the tail names a network failure: that one cascades — `-fae` skips every module
 * downstream of the failed one — and the gate then fails on an artifact that was never packaged,
 * twelve minutes later, with a message (`MDEP-187`) that says nothing about the cause. So a transient
 * install is retried from the module Maven names as the resume point, and an install that keeps
 * failing on the network aborts the arm HERE, quoting the real error.
 *
 * The gate is the assertion, and it is a strict one in both directions: an untouched tree is
 * self-consistent, so a gate that cannot pass here would report a missed call site for every run
 * whatever the agent did, and grading against it would be meaningless. Running it before the agent
 * also makes each arm carry its own positive control rather than relying on one taken on some other
 * machine.
 */
fun prepareAndProveGateEnvironment(container: ContainerDriver, case: RippleCase, projectDir: String) {
    val install = installReactorWithNetworkRetries(container, projectDir)

    val gate = runCompileGate(container, case, projectDir)
    check(gate.passed) {
        "The compile gate does not pass on the UNTOUCHED tree (exit ${gate.exitCode}), so it cannot " +
            "distinguish a missed call site from a broken environment and this arm would grade noise. " +
            "Install exit was ${install.exitCode}" +
            mavenErrorLines(install.tail).take(5).joinToString("\n  ", prefix = ", reporting:\n  ")
                .takeIf { install.exitCode != 0 }.orEmpty() +
            "\n${gate.tail}"
    }
    println("[RIPPLE] pre-agent compile gate PASS — the gate measures the change, not the environment")
}

private fun runMaven(container: ContainerDriver, script: String, description: String): CompileGateResult {
    val result = container.startProcessInContainer {
        this.args("bash", "-lc", script).timeoutSeconds(3_600).description(description)
    }.awaitForProcessFinish()
    val tail = (result.stdout + "\n" + result.stderr).lines().takeLast(40).joinToString("\n")
    return CompileGateResult(exitCode = result.exitCode ?: -1, tail = tail)
}
