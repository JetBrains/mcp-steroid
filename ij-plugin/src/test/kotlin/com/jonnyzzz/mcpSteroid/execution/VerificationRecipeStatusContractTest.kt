/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.NoOpProgressReporter
import com.jonnyzzz.mcpSteroid.testExecParams
import java.nio.file.Paths
import kotlin.time.Duration.Companion.seconds

class VerificationRecipeStatusContractTest : BasePlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    private fun text(result: ToolCallResult): String =
        result.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }

    private fun createInspectableKotlinFile(): VirtualFile {
        val basePath = project.basePath ?: error("Project base path is not available")
        val srcVf = WriteAction.computeAndWait<VirtualFile, RuntimeException> {
            VfsUtil.createDirectories(Paths.get(basePath, "src").toString())
        }
        PsiTestUtil.addSourceRoot(module, srcVf)

        return WriteAction.computeAndWait<VirtualFile, RuntimeException> {
            val filePath = Paths.get(basePath, "src/test/RecipeStatus.kt")
            val parent = VfsUtil.createDirectories(filePath.parent.toString())
            val child = parent.findChild(filePath.fileName.toString())
                ?: parent.createChildData(this, filePath.fileName.toString())
            VfsUtil.saveText(
                child,
                """
                package test

                class RecipeStatus {
                    fun value(): Int = 42
                }
                """.trimIndent()
            )
            child
        }
    }

    private fun suppressExpectedInspectionCrashErrors(): com.intellij.openapi.application.AccessToken =
        LoggedErrorProcessor.executeWith(object : LoggedErrorProcessor() {
            override fun processError(
                category: String,
                message: String,
                details: Array<String>,
                t: Throwable?
            ): Set<Action> =
                if (message.contains("crashed while inspecting")) Action.NONE
                else super.processError(category, message, details, t)
        })

    fun testFileInspectionStatusIsFindingsWhenDiagnosticsArePresent(): Unit = timeoutRunBlocking(60.seconds) {
        val file = createInspectableKotlinFile()
        val filePath = file.path
        myFixture.enableInspections(HealthyStubInspection())

        val result = project.service<ExecutionManager>().executeWithProgress(
            testExecParams(
                code = $$"""
                    val file = findFile("$$filePath") ?: error("File not found")
                    val result = runInspectionsDirectly(file)
                    val findings = result.values.sumOf { it.size }
                    val status = when {
                        result.failedTools.isNotEmpty() -> "check_failed"
                        findings > 0 -> "findings"
                        else -> "clean"
                    }
                    println("STATUS=$status")
                    println("CHECK_RAN=true")
                    println("FINDINGS=$findings")
                    println("FAILED_TOOLS=${result.failedTools.size}")
                """.trimIndent(),
                taskId = "file-inspection-status-findings",
                reason = "verify file-inspection recipe status mapping for diagnostics"
            ),
            NoOpProgressReporter
        )

        val output = text(result)
        assertFalse("Script must not fail. Output:\n$output", result.isError)
        assertTrue("Diagnostics must map to findings. Output:\n$output", output.contains("STATUS=findings"))
        assertTrue("The check must be marked as run. Output:\n$output", output.contains("CHECK_RAN=true"))
        assertTrue("Healthy inspection should produce one finding. Output:\n$output", output.contains("FINDINGS=1"))
        assertTrue("Healthy inspection should not fail tools. Output:\n$output", output.contains("FAILED_TOOLS=0"))
    }

    fun testFileInspectionStatusIsCheckFailedWhenFailedToolsArePresent(): Unit = timeoutRunBlocking(60.seconds) {
        val file = createInspectableKotlinFile()
        val filePath = file.path
        myFixture.enableInspections(CrashingStubInspection())

        suppressExpectedInspectionCrashErrors().use {
            val result = project.service<ExecutionManager>().executeWithProgress(
                testExecParams(
                    code = $$"""
                        val file = findFile("$$filePath") ?: error("File not found")
                        val result = runInspectionsDirectly(file)
                        val findings = result.values.sumOf { it.size }
                        val status = when {
                            result.failedTools.isNotEmpty() -> "check_failed"
                            findings > 0 -> "findings"
                            else -> "clean"
                        }
                        println("STATUS=$status")
                        println("CHECK_RAN=true")
                        println("FINDINGS=$findings")
                        println("FAILED_TOOLS=" + result.failedTools.joinToString(";") { it.toolId })
                    """.trimIndent(),
                    taskId = "file-inspection-status-check-failed",
                    reason = "verify file-inspection recipe status mapping for failed tools"
                ),
                NoOpProgressReporter
            )

            val output = text(result)
            assertFalse("Failed tools must be reported in-band. Output:\n$output", result.isError)
            assertTrue("Failed tools must map to check_failed. Output:\n$output", output.contains("STATUS=check_failed"))
            assertTrue("The check must be marked as run. Output:\n$output", output.contains("CHECK_RAN=true"))
            assertTrue("The crashing tool should be named. Output:\n$output", output.contains("CrashingStubInspection"))
        }
    }

    fun testModuleInspectionStatusIsDidNotRunWhenModuleIsMissing(): Unit = timeoutRunBlocking(60.seconds) {
        val result = project.service<ExecutionManager>().executeWithProgress(
            testExecParams(
                code = """
                    import com.intellij.openapi.module.ModuleManager

                    val moduleName = "missing-module-for-status-contract"
                    val module = readAction {
                        ModuleManager.getInstance(project).findModuleByName(moduleName)
                    }
                    if (module == null) {
                        println("STATUS=did_not_run")
                        println("CHECK_RAN=false")
                        println("MESSAGE=Module was not found.")
                    } else {
                        println("STATUS=unexpected")
                    }
                """.trimIndent(),
                taskId = "module-inspection-status-missing-module",
                reason = "verify module-inspection recipe status mapping for missing modules"
            ),
            NoOpProgressReporter
        )

        val output = text(result)
        assertFalse("Script must not fail. Output:\n$output", result.isError)
        assertTrue("Missing module must map to did_not_run. Output:\n$output", output.contains("STATUS=did_not_run"))
        assertTrue("Missing module must not be marked as run. Output:\n$output", output.contains("CHECK_RAN=false"))
    }

    fun testModuleInspectionStatusIsDidNotRunWhenModuleHasNoSourceFiles(): Unit = timeoutRunBlocking(60.seconds) {
        PsiTestUtil.removeAllRoots(module, null)

        val result = project.service<ExecutionManager>().executeWithProgress(
            testExecParams(
                code = """
                    import com.intellij.openapi.module.ModuleManager
                    import com.intellij.openapi.roots.ModuleRootManager
                    import com.intellij.openapi.vfs.VirtualFile

                    val module = readAction {
                        ModuleManager.getInstance(project).modules.single()
                    }
                    val sourceExtensions = setOf("java", "kt", "kts")
                    fun collectSourceFiles(root: VirtualFile, output: MutableList<VirtualFile>) {
                        if (!root.isDirectory) {
                            if (root.extension in sourceExtensions) output += root
                            return
                        }
                        for (child in root.children) collectSourceFiles(child, output)
                    }
                    val sourceFiles = readAction {
                        val result = mutableListOf<VirtualFile>()
                        ModuleRootManager.getInstance(module).sourceRoots.forEach { root ->
                            collectSourceFiles(root, result)
                        }
                        result
                    }
                    if (sourceFiles.isEmpty()) {
                        println("STATUS=did_not_run")
                        println("CHECK_RAN=false")
                        println("SOURCE_FILES=0")
                    } else {
                        println("STATUS=unexpected")
                        println("SOURCE_FILES=${'$'}{sourceFiles.size}")
                    }
                """.trimIndent(),
                taskId = "module-inspection-status-empty-scope",
                reason = "verify module-inspection recipe status mapping for empty module scopes"
            ),
            NoOpProgressReporter
        )

        val output = text(result)
        assertFalse("Script must not fail. Output:\n$output", result.isError)
        assertTrue("Empty source scope must map to did_not_run. Output:\n$output", output.contains("STATUS=did_not_run"))
        assertTrue("Empty source scope must not be marked as run. Output:\n$output", output.contains("CHECK_RAN=false"))
    }

    fun testJpsBuildStatusIsDidNotRunWhenModuleIsMissing(): Unit = timeoutRunBlocking(60.seconds) {
        val result = project.service<ExecutionManager>().executeWithProgress(
            testExecParams(
                code = """
                    import com.intellij.openapi.module.ModuleManager

                    val moduleName = "missing-module-for-jps-status-contract"
                    val targetModule = readAction {
                        ModuleManager.getInstance(project).findModuleByName(moduleName)
                    }
                    if (targetModule == null) {
                        println("STATUS=did_not_run")
                        println("CHECK_RAN=false")
                        println("TARGET=${'$'}moduleName")
                    } else {
                        println("STATUS=unexpected")
                    }
                """.trimIndent(),
                taskId = "jps-build-status-missing-module",
                reason = "verify JPS recipe status mapping for missing modules"
            ),
            NoOpProgressReporter
        )

        val output = text(result)
        assertFalse("Script must not fail. Output:\n$output", result.isError)
        assertTrue("Missing module must map to did_not_run. Output:\n$output", output.contains("STATUS=did_not_run"))
        assertTrue("Missing module must not be marked as run. Output:\n$output", output.contains("CHECK_RAN=false"))
    }

}
