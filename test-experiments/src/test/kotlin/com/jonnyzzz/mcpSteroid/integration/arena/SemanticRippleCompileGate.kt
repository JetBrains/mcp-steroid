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
 * The bash script for the scoped compile gate, kept pure so its shape is unit-tested.
 *
 * For a behaviour-preserving rename, compilation is a COMPLETE invariant over the ripple rather than
 * an approximation: a call site the agent missed still names a method that no longer exists, which
 * is a compile error. Scoping to the declaring module plus every module holding a reference is
 * therefore complete — no reference exists outside that set.
 *
 * `test-compile` rather than `compile` because every reference is in test sources. `-pl` without
 * `-am` because the harness prewarm already installed the siblings, and `-am` OOM-kills the
 * container.
 */
fun buildCompileGateScript(projectDir: String): String =
    // Single source of truth for the goal and the module list — SemanticRippleSpec.compileGateArgs()
    // is what SemanticRippleSpecTest asserts against, so the script cannot drift from it.
    buildMavenScript(projectDir, SemanticRippleSpec.compileGateArgs())

/**
 * The bash script that populates the shared local repository from the tree under test — see
 * [SemanticRippleSpec.reactorInstallArgs] for why neither a cold repository nor a warm one can be
 * trusted.
 *
 * Online, unlike the gate: on a cold agent the third-party dependencies of 152 modules are not there
 * yet, and an offline install would fail on the first of them.
 */
fun buildReactorInstallScript(projectDir: String): String =
    buildMavenScript(projectDir, SemanticRippleSpec.reactorInstallArgs(), offline = false)

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
fun runCompileGate(container: ContainerDriver, projectDir: String): CompileGateResult =
    runMaven(container, buildCompileGateScript(projectDir), "Semantic-ripple scoped compile gate")

/**
 * Populate the local repository from the tree under test, then prove the environment by running the
 * gate on that untouched tree.
 *
 * The install is best-effort by construction (see [SemanticRippleSpec.reactorInstallArgs]), so its exit
 * code is logged and nothing more. The gate is the assertion, and it is a strict one in both
 * directions: an untouched tree is self-consistent, so a gate that cannot pass here would report a
 * missed call site for every run whatever the agent did, and grading against it would be meaningless.
 * Running it before the agent also makes each arm carry its own positive control rather than relying on
 * one taken on some other machine.
 */
fun prepareAndProveGateEnvironment(container: ContainerDriver, projectDir: String) {
    val install = runMaven(
        container,
        buildReactorInstallScript(projectDir),
        "Install the semantic-ripple reactor into the local repository",
    )
    println(
        "[RIPPLE] reactor install finished with exit ${install.exitCode} — best-effort by design, the " +
            "pre-agent gate is what decides"
    )

    val gate = runCompileGate(container, projectDir)
    check(gate.passed) {
        "The compile gate does not pass on the UNTOUCHED tree (exit ${gate.exitCode}), so it cannot " +
            "distinguish a missed call site from a broken environment and this arm would grade noise. " +
            "Install exit was ${install.exitCode}.\n${gate.tail}"
    }
    println("[RIPPLE] pre-agent compile gate PASS — the gate measures the rename, not the environment")
}

private fun runMaven(container: ContainerDriver, script: String, description: String): CompileGateResult {
    val result = container.startProcessInContainer {
        this.args("bash", "-lc", script).timeoutSeconds(3_600).description(description)
    }.awaitForProcessFinish()
    val tail = (result.stdout + "\n" + result.stderr).lines().takeLast(40).joinToString("\n")
    return CompileGateResult(exitCode = result.exitCode ?: -1, tail = tail)
}
