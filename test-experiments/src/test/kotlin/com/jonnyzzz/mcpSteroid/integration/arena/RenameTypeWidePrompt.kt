/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * The task brief for the wide rename-type case of the keycloak-semantic-ripple family.
 *
 * Deliberately states the declaration exactly — the benchmark does not test guessing the starting
 * point, it tests finding the whole ripple — while revealing nothing about how to find the reference
 * sites. `RenameTypeWideCaseTest` pins both halves of that: the required content, and the absence of
 * every mechanism hint and of the answer itself (no decoy names, no counts, no file list).
 */
fun buildRenameTypeWidePrompt(projectDir: String, withMcp: Boolean): String = buildString {
    val jdkPrefix = "/usr/lib/jvm/temurin-${RenameTypeWideSpec.projectJdkVersion}-jdk-"

    appendLine("You are working on a large multi-module Java project located at: `$projectDir`")
    appendLine()
    appendLine("**OUTPUT REQUIREMENT** (read now, apply at the end): when the task is complete and the")
    appendLine("project compiles, your LAST message MUST contain `ARENA_FIX_APPLIED: yes` on its own line.")
    appendLine("The harness detects only that exact string, not a build tool's own success output.")
    appendLine()
    appendLine("## Task")
    appendLine()
    appendLine("Rename the type")
    appendLine()
    appendLine("    ${RenameTypeWideSpec.targetClassFqn}")
    appendLine()
    appendLine("to the simple name `${RenameTypeWideSpec.newName}`, keeping it in its current package,")
    appendLine("throughout the whole project.")
    appendLine()
    appendLine("Requirements:")
    appendLine()
    appendLine("1. Every place that names this type must name it by its new name — declarations, uses,")
    appendLine("   imports and the file it lives in. When you are done, no reference to this type may")
    appendLine("   still use the old name.")
    appendLine("2. The old name must not survive as an alias of any kind — not as a subtype, not as a")
    appendLine("   deprecated empty interface extending the new one.")
    appendLine("3. Leave other types that happen to share the same simple name in other packages")
    appendLine("   unchanged — they are unrelated, and renaming one of them is a defect.")
    appendLine("4. External behaviour must not change: this type's name appears in no configuration")
    appendLine("   file, no reflective lookup and no service descriptor, so a correct rename is not")
    appendLine("   observable from outside the code.")
    appendLine("5. The project must compile, test sources included, when you are finished.")
    appendLine()
    appendLine("## Environment Facts")
    appendLine()
    appendLine("- Use the project wrapper only: `./mvnw`")
    appendLine("- Configured project JDK version: **${RenameTypeWideSpec.projectJdkVersion}**")
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
    appendLine("    ARENA_FIX_APPLIED: yes")
    appendLine()
    appendLine("If you could not complete the task, end with `ARENA_FIX_APPLIED: no` and one line saying why.")
}
