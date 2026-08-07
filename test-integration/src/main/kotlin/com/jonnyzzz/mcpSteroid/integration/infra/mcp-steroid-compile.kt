package com.jonnyzzz.mcpSteroid.integration.infra

/**
 * The IDE-side script [mcpCompileProject] runs, separated so its two modes are unit-testable without
 * a container.
 *
 * An **aborted** build always fails: the build runner never started, so nothing was warmed and the
 * container is misconfigured.
 *
 * Compile **errors** are a different question, which is what [requireCleanCompile] answers. Fixture
 * projects are expected to build, and an error there is a real defect. A DPAIA arena project is
 * expected NOT to build: the dataset's test patch is the task, and its tests call production code the
 * agent has not written yet, so `buildAllModules` reports errors by construction. Failing on those
 * aborts the run before the agent ever starts — which it did, on both Round 6 smoke builds.
 */
fun compileProjectScript(requireCleanCompile: Boolean): String {
    val errorCheck = if (requireCleanCompile) {
        """check(!build.hasErrors()) { "IntelliJ project build completed with errors" }"""
    } else {
        """println("[COMPILE] build errors present: ${'$'}{build.hasErrors()} (expected for a pre-fix task project)")"""
    }
    return """
        import com.intellij.task.ProjectTaskManager
        import org.jetbrains.concurrency.await

        val build = ProjectTaskManager.getInstance(project).buildAllModules().await()
        check(!build.isAborted()) { "IntelliJ project build was aborted" }
        $errorCheck
        println("[COMPILE] IntelliJ project build complete")
    """.trimIndent()
}

/** Compile every imported module through IntelliJ before the agent session — see [compileProjectScript]. */
fun McpSteroidDriver.mcpCompileProject(requireCleanCompile: Boolean = true) {
    val result = mcpExecuteCode(
        reason = "Compile every imported project module through IntelliJ before the agent session",
        timeout = 10 * 60,
        code = compileProjectScript(requireCleanCompile),
    )
    if (result.exitCode != 0) {
        throw RuntimeException("[COMPILE] IntelliJ project build failed:\n${result.stdout.trim().takeLast(1000)}")
    }
}
