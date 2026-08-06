package com.jonnyzzz.mcpSteroid.integration.infra

/** Compile every imported module through IntelliJ and fail when the IDE reports an aborted or erroneous build. */
fun McpSteroidDriver.mcpCompileProject() {
    val result = mcpExecuteCode(
        reason = "Compile every imported project module through IntelliJ before the agent session",
        timeout = 10 * 60,
        code = """
            import com.intellij.task.ProjectTaskManager
            import org.jetbrains.concurrency.await

            val build = ProjectTaskManager.getInstance(project).buildAllModules().await()
            check(!build.isAborted()) { "IntelliJ project build was aborted" }
            check(!build.hasErrors()) { "IntelliJ project build completed with errors" }
            println("[COMPILE] IntelliJ project build complete")
        """.trimIndent(),
    )
    if (result.exitCode != 0) {
        throw RuntimeException("[COMPILE] IntelliJ project build failed:\n${result.stdout.trim().takeLast(1000)}")
    }
}
