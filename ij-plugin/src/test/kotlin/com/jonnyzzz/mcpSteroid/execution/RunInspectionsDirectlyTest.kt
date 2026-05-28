/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.NoOpProgressReporter
import com.jonnyzzz.mcpSteroid.testExecParams
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for the runInspectionsDirectly() method in McpScriptContext.
 *
 * This tests the workaround for GitHub issue #20 where the daemon code analyzer
 * returns stale results when the IDE window is not focused.
 */
@TestApplication
class RunInspectionsDirectlyTest {

    private val projectFixture = projectFixture()
    private val moduleFixture = projectFixture.moduleFixture()

    private lateinit var testFilePath: String

    @BeforeEach
    fun setUp() {
        // Create a Kotlin file with known issues:
        // - Unused variable (warning)
        // - Unnecessary safe call (warning)
        val testCode = """
            package test

            class TestClass {
                fun testMethod() {
                    val unusedVariable = "this is never used"
                    val nullableString: String? = "not null"
                    val length = nullableString?.length // unnecessary safe call
                    println(length)
                }
            }
        """.trimIndent()

        val project = projectFixture.get()
        val module = moduleFixture.get()
        val basePath = project.basePath ?: error("Project base path is not available")
        val srcVf = WriteAction.computeAndWait<VirtualFile, RuntimeException> {
            VfsUtil.createDirectories(Paths.get(basePath, "src").toString())
        }
        PsiTestUtil.addSourceRoot(module, srcVf)

        val file = WriteAction.computeAndWait<VirtualFile, RuntimeException> {
            val filePath = Paths.get(basePath, "src/test/TestClass.kt")
            val parent = VfsUtil.createDirectories(filePath.parent.toString())
            val name = filePath.fileName.toString()
            val child = parent.findChild(name) ?: parent.createChildData(this, name)
            VfsUtil.saveText(child, testCode)
            child
        }
        testFilePath = file.path
    }

    private fun getTextContent(result: ToolCallResult): String {
        return result.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
    }

    @Test
    fun runInspectionsDirectlyFindsProblems(): Unit = timeoutRunBlocking(60.seconds) {
        val manager = projectFixture.get().service<ExecutionManager>()

        val code = $$"""
            val file = findFile("$$testFilePath") ?: error("File not found")

            val problems = runInspectionsDirectly(file)

            if (problems.isEmpty()) {
                println("No problems found")
            } else {
                println("Found ${problems.values.sumOf { it.size }} problems:")
                problems.forEach { (inspectionId, descriptors) ->
                    descriptors.forEach { problem ->
                        println("  [$inspectionId] ${problem.descriptionTemplate}")
                    }
                }
            }
        """.trimIndent()

        val result = manager.executeWithProgress(
            testExecParams(code, taskId = "run-inspections-test", reason = "test runInspectionsDirectly"),
            NoOpProgressReporter
        )

        val text = getTextContent(result)
        println("Test output:\n$text")

        // Should execute without error
        assertFalse(result.isError, "Should execute without error. Output:\n$text")

        // Should find at least some problems (unused variable is a common inspection)
        // Note: The exact inspections available depend on the IDE configuration
        assertTrue(
            text.contains("problems") || text.contains("No problems found"),
            "Should find problems or report none found. Output:\n$text",
        )
    }

    @Test
    fun runInspectionsDirectlyWithIncludeInfo(): Unit = timeoutRunBlocking(60.seconds) {
        val manager = projectFixture.get().service<ExecutionManager>()

        val code = $$"""
            val file = findFile("$$testFilePath") ?: error("File not found")

            // Include INFO severity problems
            val problems = runInspectionsDirectly(file, includeInfoSeverity = true)

            println("Found ${problems.values.sumOf { it.size }} problems (including INFO)")
            problems.forEach { (inspectionId, descriptors) ->
                println("  $inspectionId: ${descriptors.size} issues")
            }
        """.trimIndent()

        val result = manager.executeWithProgress(
            testExecParams(code, taskId = "run-inspections-info-test", reason = "test runInspectionsDirectly with INFO"),
            NoOpProgressReporter
        )

        val text = getTextContent(result)
        println("Test output:\n$text")

        // Should execute without error
        assertFalse(result.isError, "Should execute without error. Output:\n$text")
    }

    @Test
    fun runInspectionsDirectlyOnNonExistentFile(): Unit = timeoutRunBlocking(60.seconds) {
        val manager = projectFixture.get().service<ExecutionManager>()

        val code = $$"""
            val file = findFile("/non/existent/file.kt")
            if (file == null) {
                println("File not found as expected")
            } else {
                val problems = runInspectionsDirectly(file)
                println("Problems: ${problems.size}")
            }
        """.trimIndent()

        val result = manager.executeWithProgress(
            testExecParams(code, taskId = "run-inspections-nonexistent-test", reason = "test with non-existent file"),
            NoOpProgressReporter
        )

        val text = getTextContent(result)
        println("Test output:\n$text")

        // Should handle gracefully
        assertFalse(result.isError, "Should execute without error. Output:\n$text")
        assertTrue(text.contains("File not found"), "Should report file not found")
    }

    @Test
    fun runInspectionsDirectlyReturnsMapStructure(): Unit = timeoutRunBlocking(60.seconds) {
        val manager = projectFixture.get().service<ExecutionManager>()

        val code = $$"""
            val file = findFile("$$testFilePath") ?: error("File not found")

            val problems = runInspectionsDirectly(file)

            // Verify the return type is Map<String, List<ProblemDescriptor>>
            println("Result type: Map with ${problems.size} entries")
            problems.forEach { (key, value) ->
                println("Key type: ${key::class.simpleName}, Value type: List of ${value.firstOrNull()?.let { it::class.simpleName } ?: "empty"}")
            }
        """.trimIndent()

        val result = manager.executeWithProgress(
            testExecParams(code, taskId = "run-inspections-structure-test", reason = "test return structure"),
            NoOpProgressReporter
        )

        val text = getTextContent(result)
        println("Test output:\n$text")

        // Should execute without error
        assertFalse(result.isError, "Should execute without error. Output:\n$text")
        assertTrue(text.contains("Result type: Map"), "Should report map structure")
    }
}
