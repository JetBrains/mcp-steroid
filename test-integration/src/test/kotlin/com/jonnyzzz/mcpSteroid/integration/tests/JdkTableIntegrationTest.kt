/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Infrastructure test that validates JDK registration in the Docker container.
 *
 * Uses [McpSteroidDriver.mcpRegisterJdks] to register Temurin JDKs via IntelliJ API
 * (`SdkConfigurationUtil.createAndAddSDK`), then validates:
 * 1. JDKs are visible in [ProjectJdkTable]
 * 2. Each registered JDK has a valid homePath with `bin/java`
 * 3. A JDK can be applied as the project SDK
 * 4. IntelliJ compilation works after SDK is set
 *
 * No AI agents — pure infrastructure validation via MCP Steroid.
 *
 * ```
 * ./gradlew :test-integration:test --tests '*JdkTableIntegrationTest*'
 * ```
 */
class JdkTableIntegrationTest {

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `JDK table has registered SDKs with valid paths`() = runWithCloseableStack { lifetime ->
        val session = IntelliJContainer.create(lifetime, consoleTitle = "jdk-table-test")
        val console = session.console
        val guestProjectDir = session.intellijDriver.getGuestProjectDir()

        // Step 1: Register JDKs via IntelliJ API
        console.writeStep(1, "Registering JDKs via mcpRegisterJdks")
        session.mcpSteroid.mcpRegisterJdks(guestProjectDir)
        console.writeSuccess("JDK registration call completed")

        // Step 2: Verify JDKs are visible in ProjectJdkTable
        console.writeStep(2, "Verifying ProjectJdkTable has Java SDKs")
        session.mcpSteroid.mcpExecuteCode(
            code = """
                import com.intellij.openapi.projectRoots.JavaSdk
                import com.intellij.openapi.projectRoots.ProjectJdkTable

                val javaSdks = ProjectJdkTable.getInstance().getSdksOfType(JavaSdk.getInstance())
                println("JAVA_SDK_COUNT: ${'$'}{javaSdks.size}")
                javaSdks.forEach { sdk ->
                    val home = sdk.homePath ?: "null"
                    val hasJava = java.io.File(home, "bin/java").exists()
                    println("JDK: name=${'$'}{sdk.name} home=${'$'}home valid=${'$'}hasJava")
                }
                require(javaSdks.isNotEmpty()) { "No Java SDKs in ProjectJdkTable after mcpRegisterJdks" }
                println("JDK_TABLE_OK")
            """.trimIndent(),
            taskId = "jdk-table-test",
            reason = "Validate JDK table has registered Java SDKs",
        ).assertExitCode(0, "JDK table query should succeed")
            .assertOutputContains("JDK_TABLE_OK", message = "should have registered Java SDKs")
        console.writeSuccess("JDK table has registered Java SDKs")

        // Step 3: Verify each JDK has valid home path with bin/java
        console.writeStep(3, "Validating JDK home paths")
        session.mcpSteroid.mcpExecuteCode(
            code = """
                import com.intellij.openapi.projectRoots.JavaSdk
                import com.intellij.openapi.projectRoots.ProjectJdkTable

                val javaSdks = ProjectJdkTable.getInstance().getSdksOfType(JavaSdk.getInstance())
                var allValid = true
                for (sdk in javaSdks) {
                    val home = sdk.homePath
                    if (home == null) {
                        println("INVALID: ${'$'}{sdk.name} — homePath is null")
                        allValid = false
                        continue
                    }
                    val javaFile = java.io.File(home, "bin/java")
                    if (!javaFile.exists()) {
                        println("INVALID: ${'$'}{sdk.name} — ${'$'}home/bin/java does not exist")
                        allValid = false
                    } else {
                        println("VALID: ${'$'}{sdk.name} — ${'$'}home")
                    }
                }
                require(allValid) { "Some JDKs have invalid home paths" }
                println("ALL_PATHS_VALID")
            """.trimIndent(),
            taskId = "jdk-table-test",
            reason = "Validate all JDK home paths have bin/java",
        ).assertExitCode(0, "JDK path validation should succeed")
            .assertOutputContains("ALL_PATHS_VALID", message = "all JDK paths should be valid")
        console.writeSuccess("All JDK paths are valid")

        // Step 4: Apply a JDK as project SDK
        console.writeStep(4, "Setting project SDK")
        session.mcpSteroid.mcpExecuteCode(
            code = """
                import com.intellij.openapi.projectRoots.JavaSdk
                import com.intellij.openapi.projectRoots.ProjectJdkTable
                import com.intellij.openapi.projectRoots.ex.JavaSdkUtil
                import com.intellij.openapi.roots.ProjectRootManager
                import com.intellij.openapi.application.edtWriteAction

                val javaSdks = ProjectJdkTable.getInstance().getSdksOfType(JavaSdk.getInstance())
                val sdk = javaSdks.firstOrNull { it.name == "21" }
                    ?: javaSdks.firstOrNull { it.name.contains("21") }
                    ?: javaSdks.first()

                edtWriteAction { JavaSdkUtil.applyJdkToProject(project, sdk) }

                val appliedSdk = ProjectRootManager.getInstance(project).projectSdk
                println("APPLIED_SDK: name=${'$'}{appliedSdk?.name} home=${'$'}{appliedSdk?.homePath}")
                require(appliedSdk != null) { "Project SDK should be set" }
                println("PROJECT_SDK_OK")
            """.trimIndent(),
            taskId = "jdk-table-test",
            reason = "Apply JDK 21 as project SDK",
        ).assertExitCode(0, "Applying project SDK should succeed")
            .assertOutputContains("PROJECT_SDK_OK", message = "project SDK should be set")
        console.writeSuccess("Project SDK set successfully")

        // Step 5: Verify compilation works
        console.writeStep(5, "Triggering compilation to verify SDK works")
        session.mcpSteroid.mcpExecuteCode(
            code = """
                val result = com.intellij.task.ProjectTaskManager.getInstance(project)
                    .buildAllModules().blockingGet(60_000)
                val hasErrors = result?.hasErrors() ?: false
                val isAborted = result?.isAborted ?: false
                println("BUILD_RESULT: errors=${'$'}hasErrors aborted=${'$'}isAborted")
                require(!hasErrors) { "Compilation should not have errors with a valid SDK" }
                println("COMPILATION_OK")
            """.trimIndent(),
            taskId = "jdk-table-test",
            reason = "Verify IntelliJ compilation works with project SDK",
            timeout = 120,
        ).assertExitCode(0, "Compilation should succeed")
            .assertOutputContains("COMPILATION_OK", message = "compilation should pass")

        console.writeSuccess("All JDK table checks passed")
    }
}
