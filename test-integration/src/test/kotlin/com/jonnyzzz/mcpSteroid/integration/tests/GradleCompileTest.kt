/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.BuildSystem
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Validates that ProjectTaskManager.build() compiles a Gradle project
 * inside a Docker IntelliJ container. Replaces 4 Bash `gradlew compile`
 * calls observed in arena analysis.
 *
 * Uses the default test-project (Gradle/Kotlin) fixture.
 */
class GradleCompileTest {

    companion object {
        val lifetime by lazy { CloseableStackHost(GradleCompileTest::class.java.simpleName) }
        val session by lazy {
            IntelliJContainer.create(
                lifetime,
                "ide-agent",
                consoleTitle = "Gradle Compile",
            ).waitForProjectReady(
                buildSystem = BuildSystem.GRADLE,
            )
        }

        @AfterAll
        @JvmStatic
        fun cleanup() {
            lifetime.closeAllStacks()
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `ProjectTaskManager builds Gradle project`() {
        val console = session.console

        console.writeStep(1, "Compiling Gradle project via ProjectTaskManager.build()")
        val result = session.mcpSteroid.mcpExecuteCode(
            code = """
                import com.intellij.task.ProjectTaskManager
                import com.intellij.openapi.module.ModuleManager
                import org.jetbrains.concurrency.await

                val modules = ModuleManager.getInstance(project).modules
                println("MODULES=${'$'}{modules.size}")
                modules.forEach { println("  module: ${'$'}{it.name}") }

                val result = ProjectTaskManager.getInstance(project).build(*modules).await()
                println("BUILD_ERRORS=${'$'}{result.hasErrors()}")
                println("BUILD_ABORTED=${'$'}{result.isAborted}")
            """.trimIndent(),
            taskId = "gradle-compile",
            reason = "Compile Gradle project via ProjectTaskManager (replaces Bash gradlew compile)",
            timeout = 300,
            dialogKiller = true,
        )

        result.assertExitCode(0, "Gradle compile via ProjectTaskManager should succeed")
        result.assertOutputContains("BUILD_ERRORS=false", message = "Gradle compilation should have no errors")

        console.writeSuccess("Gradle project compilation via ProjectTaskManager works")
    }
}
