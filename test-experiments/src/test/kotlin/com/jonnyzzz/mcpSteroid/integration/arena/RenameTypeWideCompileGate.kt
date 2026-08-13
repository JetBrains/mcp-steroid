/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer

/**
 * The scoped compile gate for the wide rename-type case, pointed at [RenameTypeWideSpec] rather than
 * [SemanticRippleSpec].
 *
 * A sibling of [buildCompileGateScript]/[runCompileGate]/[prepareAndProveGateEnvironment] rather than
 * a reuse of them: those functions call `SemanticRippleSpec.compileGateArgs()` by name, not by
 * parameter, so calling them here would gate the pilot's method-rename modules instead of this case's
 * type-rename modules. Duplicated on purpose — see the family's task-2 brief; [CompileGateResult]
 * itself is shared unchanged.
 */
fun buildRenameTypeWideCompileGateScript(projectDir: String): String =
    // Single source of truth for the goal and the module list — RenameTypeWideSpec.compileGateArgs()
    // is what RenameTypeWideCaseTest asserts against, so the script cannot drift from it.
    buildRenameTypeWideMavenScript(projectDir, RenameTypeWideSpec.compileGateArgs())

/**
 * The bash script that populates the shared local repository from the tree under test — see
 * [RenameTypeWideSpec.reactorInstallArgs] for why neither a cold repository nor a warm one can be
 * trusted.
 *
 * Online, unlike the gate: on a cold agent the third-party dependencies of 152 modules are not there
 * yet, and an offline install would fail on the first of them.
 */
fun buildRenameTypeWideReactorInstallScript(projectDir: String): String =
    buildRenameTypeWideMavenScript(projectDir, RenameTypeWideSpec.reactorInstallArgs(), offline = false)

private fun buildRenameTypeWideMavenScript(
    projectDir: String,
    args: List<String>,
    offline: Boolean = true,
): String {
    val mavenArgs = (if (offline) listOf("-o") else emptyList()).plus(args).joinToString(" ")
    val jdkPrefix = "/usr/lib/jvm/temurin-${RenameTypeWideSpec.projectJdkVersion}-jdk-"
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
 * network could fail on a repository outage and read as a missed reference site.
 */
fun runRenameTypeWideCompileGate(container: ContainerDriver, projectDir: String): CompileGateResult =
    runRenameTypeWideMaven(container, buildRenameTypeWideCompileGateScript(projectDir),
        "Rename-type-wide scoped compile gate")

/**
 * Populate the local repository from the tree under test, then prove the environment by running the
 * gate on that untouched tree.
 *
 * The install is best-effort by construction (see [RenameTypeWideSpec.reactorInstallArgs]), so its
 * exit code is logged and nothing more. The gate is the assertion, and it is a strict one in both
 * directions: an untouched tree is self-consistent, so a gate that cannot pass here would report a
 * missed reference site for every run whatever the agent did, and grading against it would be
 * meaningless. Running it before the agent also makes each arm carry its own positive control rather
 * than relying on one taken on some other machine.
 */
fun prepareAndProveRenameTypeWideGateEnvironment(container: ContainerDriver, projectDir: String) {
    val install = runRenameTypeWideMaven(
        container,
        buildRenameTypeWideReactorInstallScript(projectDir),
        "Install the rename-type-wide reactor into the local repository",
    )
    println(
        "[RIPPLE] reactor install finished with exit ${install.exitCode} — best-effort by design, the " +
            "pre-agent gate is what decides"
    )

    val gate = runRenameTypeWideCompileGate(container, projectDir)
    check(gate.passed) {
        "The compile gate does not pass on the UNTOUCHED tree (exit ${gate.exitCode}), so it cannot " +
            "distinguish a missed reference site from a broken environment and this arm would grade " +
            "noise. Install exit was ${install.exitCode}.\n${gate.tail}"
    }
    println("[RIPPLE] pre-agent compile gate PASS — the gate measures the rename, not the environment")
}

private fun runRenameTypeWideMaven(
    container: ContainerDriver,
    script: String,
    description: String,
): CompileGateResult {
    val result = container.startProcessInContainer {
        this.args("bash", "-lc", script).timeoutSeconds(3_600).description(description)
    }.awaitForProcessFinish()
    val tail = (result.stdout + "\n" + result.stderr).lines().takeLast(40).joinToString("\n")
    return CompileGateResult(exitCode = result.exitCode ?: -1, tail = tail)
}
