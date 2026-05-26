/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.execution.ExecutionManager
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.prompts.generated.lsp.LspIndex
import com.jonnyzzz.mcpSteroid.setSystemPropertyForTest
import com.jonnyzzz.mcpSteroid.testExecParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Paths
import kotlin.time.Duration.Companion.seconds

class LanguageSupportExecutionTest : BasePlatformTestCase() {
    val index = LspIndex()
    private lateinit var samples: List<LanguageSample>

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()

        val basePath = project.basePath ?: error("Project base path is not available")
        val srcVf = WriteAction.computeAndWait<VirtualFile, RuntimeException> {
            VfsUtil.createDirectories(Paths.get(basePath, "src").toString())
        }
        PsiTestUtil.addSourceRoot(module, srcVf)

        samples = languageSpecs.map { spec ->
            val filePath = writeSampleFile("src/sample/lang/${spec.fileName}", spec.content)
            val usage = lineColumnFor(spec.content, spec.usageNeedle, spec.usageOffset)
            LanguageSample(spec, filePath, usage)
        }
    }

    fun testLanguageSupportViaGoToDefinition(): Unit = timeoutRunBlocking(120.seconds) {
        val available = resolveAvailableSpecs()

        val raw = index.goToDefinitionMd.ktBlock000.readPrompt()
        for (sample in samples.filter { it.spec in available }) {
            val code = configureExample(
                raw,
                filePath = sample.filePath,
                line = sample.usage.line,
                column = sample.usage.column
            )
            val result = executeExample("go-to-definition-${sample.spec.id}", code)
            assertExampleResult(result, "Definition found", ignoreCase = true)
        }
    }

    private fun resolveAvailableSpecs(): Set<LanguageSpec> {
        val actionIds = ActionManager.getInstance().getActionIdList("").toSet()
        val missing = languageSpecs.filter { spec ->
            spec.requiredActionIds.none { actionIds.contains(it) }
        }
        if (missing.isNotEmpty()) {
            val details = missing.joinToString("\n") { spec ->
                "${spec.displayName} missing actions: ${spec.requiredActionIds.joinToString(", ")}"
            }
            assertTrue("Language actions are missing:\n$details", false)
        }
        return languageSpecs.filter { spec ->
            spec.requiredActionIds.any { actionIds.contains(it) }
        }.toSet()
    }

    private data class LineColumn(val line: Int, val column: Int)

    private data class LanguageSpec(
        val id: String,
        val displayName: String,
        val fileName: String,
        val content: String,
        val usageNeedle: String,
        val usageOffset: Int,
        val requiredActionIds: List<String>
    )

    private data class LanguageSample(
        val spec: LanguageSpec,
        val filePath: String,
        val usage: LineColumn
    )

    private val languageSpecs = listOf(
        LanguageSpec(
            id = "java",
            displayName = "Java",
            fileName = "JavaSample.java",
            content = """
                package sample.lang;

                public class JavaSample {
                    public int add(int a, int b) {
                        return a + b;
                    }

                    public int use() {
                        return add(1, 2);
                    }
                }
            """.trimIndent(),
            usageNeedle = "add(1, 2)",
            usageOffset = 0,
            requiredActionIds = listOf("NewJavaSpecialFile")
        ),
        LanguageSpec(
            id = "kotlin",
            displayName = "Kotlin",
            fileName = "KotlinSample.kt",
            content = """
                package sample.lang

                class KotlinSample {
                    fun greet(name: String): String {
                        return "Hello, $name"
                    }

                    fun use(): String {
                        return greet("World")
                    }
                }
            """.trimIndent(),
            usageNeedle = "greet(\"World\")",
            usageOffset = 0,
            requiredActionIds = listOf("Kotlin.NewFile")
        )
    )

    private fun lineColumnAt(text: String, offset: Int): LineColumn {
        require(offset >= 0) { "Offset must be >= 0" }
        val line = text.substring(0, offset).count { it == '\n' } + 1
        val lineStart = text.lastIndexOf('\n', offset - 1).let { if (it == -1) 0 else it + 1 }
        val column = offset - lineStart + 1
        return LineColumn(line, column)
    }

    private fun lineColumnFor(text: String, needle: String, offsetInNeedle: Int = 0): LineColumn {
        val start = text.indexOf(needle)
        require(start >= 0) { "Needle not found: $needle" }
        return lineColumnAt(text, start + offsetInNeedle)
    }

    private fun writeSampleFile(relativePath: String, text: String): String {
        val basePath = project.basePath ?: error("Project base path is not available")
        val fullPath = Paths.get(basePath, relativePath)
        val file = WriteAction.computeAndWait<VirtualFile, RuntimeException> {
            val parent = VfsUtil.createDirectories(fullPath.parent.toString())
            val name = fullPath.fileName.toString()
            val child = parent.findChild(name) ?: parent.createChildData(this, name)
            VfsUtil.saveText(child, text)
            child
        }
        return file.path
    }

    private fun configureExample(
        code: String,
        filePath: String? = null,
        line: Int? = null,
        column: Int? = null
    ): String {
        var updated = code
        if (filePath != null) {
            updated = updated.replace(Regex("val filePath = \".*?\""), "val filePath = \"$filePath\"")
        }
        if (line != null) {
            updated = updated.replace(Regex("val line\\s*=\\s*\\d+"), "val line = $line")
        }
        if (column != null) {
            updated = updated.replace(Regex("val column\\s*=\\s*\\d+"), "val column = $column")
        }
        return updated
    }

    private suspend fun executeExample(exampleId: String, code: String): ToolCallResult {
        val manager = project.service<ExecutionManager>()
        return withContext(Dispatchers.Default) {
            manager.executeWithProgress(
                testExecParams(code, taskId = "lang-$exampleId", reason = "language support"),
                NoOpProgressReporter
            )
        }
    }

    private fun getTextContent(result: ToolCallResult): String {
        return result.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
    }

    private fun assertExampleResult(result: ToolCallResult, header: String, ignoreCase: Boolean = false) {
        val text = getTextContent(result)
        assertTrue("Should execute without error. Output:\n$text", !result.isError)
        assertTrue(
            "Expected output to contain \"$header\". Output:\n$text",
            text.contains(header, ignoreCase = ignoreCase)
        )
    }
}
