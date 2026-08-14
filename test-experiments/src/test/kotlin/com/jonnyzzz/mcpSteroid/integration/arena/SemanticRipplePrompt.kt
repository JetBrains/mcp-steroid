/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.StartContainerRequest

/**
 * The task brief for one case of the keycloak-semantic-ripple family.
 *
 * Deliberately states the declaration exactly — the benchmark does not test guessing the starting
 * point, it tests finding the whole ripple — while revealing nothing about how to find the reference
 * sites. `SemanticRipplePromptContractTest` and `RippleCaseRegistryTest` pin both halves of that: the
 * required content, and the absence of every mechanism hint and of the answer itself (no decoy names,
 * no counts, no file list).
 *
 * Only the `## Task` section belongs to the kind; every other paragraph is the same in all cases,
 * which is what makes two arms of two different cases comparable at all.
 */
fun buildRipplePrompt(case: RippleCase, projectDir: String, withMcp: Boolean): String = buildString {
    val jdkPrefix = "/usr/lib/jvm/temurin-${SemanticRippleSpec.projectJdkVersion}-jdk-"

    appendLine("You are working on a large multi-module Java project located at: `$projectDir`")
    appendLine()
    appendLine("**OUTPUT REQUIREMENT** (read now, apply at the end): when the task is complete and the")
    appendLine("project compiles, your LAST message MUST contain `$ARENA_FIX_APPLIED_MARKER` on its own line.")
    appendLine("The harness detects only that exact string, not a build tool's own success output.")
    appendLine()
    appendLine("## Task")
    appendLine()
    appendLine(case.target.promptTaskSection())
    appendLine()
    appendLine("## Environment Facts")
    appendLine()
    appendLine("- Use the project wrapper only: `./mvnw`")
    appendLine("- Configured project JDK version: **${SemanticRippleSpec.projectJdkVersion}**")
    appendLine("- Resolve a concrete path whose name starts with `$jdkPrefix`, then run")
    appendLine("  `JAVA_HOME=<that exact path> ./mvnw ...`. Do not use wildcard JAVA_HOME assignments and")
    appendLine("  do not try a lower JDK first.")
    appendLine("- This project has many modules. Build one module at a time with")
    appendLine("  `./mvnw <goal> -pl <module>`; never ask the build to also build upstream dependencies,")
    appendLine("  which exhausts the container's memory.")
    appendLine("- The dependencies of every module are already installed locally, so `-pl` works on its own.")
    appendLine("- Never end your turn while a command you started is still running. If you start a build")
    appendLine("  in the background, poll it until it has finished and read its outcome before you answer.")
    appendLine("  Ending a turn to wait for a completion notification ends the whole run instead, with")
    appendLine("  your work unverified.")
    appendLine("- A full build-and-test run across the whole reactor does not fit this task's time budget.")
    appendLine("  Verify your work by compiling the modules you changed, one at a time, with")
    appendLine("  `./mvnw compile -pl <module>` (and `test-compile` for test sources); do not launch a")
    appendLine("  reactor-wide test run to check yourself.")
    if (withMcp) {
        appendLine("- Bash build commands must use the exact `Recommended JAVA_HOME` printed by your first")
        appendLine("  tool call, which starts with `$jdkPrefix`.")
    }
    appendLine()
    appendLine("## Success Markers")
    appendLine()
    appendLine("End your last message with exactly:")
    appendLine()
    appendLine("    $ARENA_FIX_APPLIED_MARKER")
    appendLine("    ARENA_SUMMARY: <one line on what you changed and how you verified it>")
    appendLine()
    appendLine("If you could not complete the task, end with `ARENA_FIX_APPLIED: no` and one line saying why.")
}

/**
 * The dpaia brief a ripple case USED to be sent, kept only so a test can show what changed.
 *
 * `RippleScenarioBaseTest` went through `ArenaTestRunner.runTest`, which wrapped the case's
 * `problemStatement` — the `## Task` section alone — in the dpaia track's scaffolding. That
 * scaffolding opens with "You are working on a Java Spring project", prints the FAIL_TO_PASS class
 * and the whole test patch (the hidden consumer, which names the answer), and twice orders a
 * reactor-wide test run that Keycloak cannot complete. Every environment paragraph
 * [buildRipplePrompt] was written for was dropped on the floor. Nothing in production builds this
 * string for a ripple case any more; `SemanticRipplePromptWiringTest` uses it to pin the difference.
 */
fun legacyDpaiaWrappedRipplePrompt(case: RippleCase, projectDir: String, withMcp: Boolean): String =
    ArenaTestRunner(
        container = ContainerDriver(
            logPrefix = "ripple-prompt",
            containerId = "unused",
            startRequest = StartContainerRequest(),
        ),
        projectGuestDir = projectDir,
    ).buildPrompt(case.dpaiaCase(), projectDir, withMcp)

/**
 * The pilot's prompt AS THE HARNESS SENDS IT, kept as a one-line delegate.
 *
 * It exists so `SemanticRipplePromptContractTest` — written before the family had a seam — keeps
 * compiling and passing. That test is the regression check on the extraction: if the shared
 * scaffolding drifted by a single line while the `## Task` section moved into [RippleTarget], it is
 * what says so.
 *
 * It routes through [RippleScenarioBaseTest.promptFor] rather than through [buildRipplePrompt]
 * because a contract that checks a builder nobody sends is what let a dpaia-shaped brief reach the
 * agent for five measured rounds while the reviewed prompt sat unused.
 */
fun buildSemanticRipplePrompt(projectDir: String, withMcp: Boolean): String =
    object : RippleScenarioBaseTest() {
        override val case: RippleCase get() = RippleCases.renameMethodWide
    }.promptFor(projectDir, withMcp)
