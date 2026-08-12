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
 * The bash script that reinstates the pristine artifacts of the gate modules in the shared local
 * repository — see [SemanticRippleSpec.pristineInstallArgs] for why an arm cannot trust what is
 * already there.
 */
fun buildPristineInstallScript(projectDir: String): String =
    buildMavenScript(projectDir, SemanticRippleSpec.pristineInstallArgs())

private fun buildMavenScript(projectDir: String, args: List<String>): String {
    val mavenArgs = args.joinToString(" ")
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
        ./mvnw -o $mavenArgs
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
 * Reinstate the pristine artifacts of the gate modules before the agent runs.
 *
 * Fails the run when it cannot: an arm whose local repository still holds a previous arm's renamed API
 * measures that leftover, not the agent, and it does so silently — the agent simply finds that the
 * pristine tree it was handed does not compile.
 */
fun installPristineGateArtifacts(container: ContainerDriver, projectDir: String) {
    val result = runMaven(
        container,
        buildPristineInstallScript(projectDir),
        "Reinstate pristine artifacts of the semantic-ripple modules",
    )
    check(result.passed) {
        "Could not reinstate the pristine artifacts of the semantic-ripple modules (exit " +
            "${result.exitCode}), so this arm would run against whatever an earlier run left in the " +
            "shared local repository:\n${result.tail}"
    }
}

private fun runMaven(container: ContainerDriver, script: String, description: String): CompileGateResult {
    val result = container.startProcessInContainer {
        this.args("bash", "-lc", script).timeoutSeconds(3_600).description(description)
    }.awaitForProcessFinish()
    val tail = (result.stdout + "\n" + result.stderr).lines().takeLast(40).joinToString("\n")
    return CompileGateResult(exitCode = result.exitCode ?: -1, tail = tail)
}
