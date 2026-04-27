/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.components.service
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
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

class LspExamplesExecutionTest : BasePlatformTestCase() {
    private val index = LspIndex()
    private lateinit var sampleFilePath: String
    private lateinit var positions: SamplePositions

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        setSystemPropertyForTest("mcp.steroid.review.mode", "NEVER")
        val sampleText = $$"""
            package sample

            class Greeter(val name: String) {
                fun greet(times: Int): String {
                    return "Hello, $name".repeat(times)
                }
            }

            fun main() {
                val greeter = Greeter("World")
                val message = greeter.greet(2)
                println(message)
            }
        """.trimIndent()
        val basePath = project.basePath ?: error("Project base path is not available")
        val srcVf = WriteAction.computeAndWait<VirtualFile, RuntimeException> {
            VfsUtil.createDirectories(Paths.get(basePath, "src").toString())
        }
        PsiTestUtil.addSourceRoot(module, srcVf)
        val file = WriteAction.computeAndWait<VirtualFile, RuntimeException> {
            val filePath = Paths.get(basePath, "src/sample/LspSample.kt")
            val parent = VfsUtil.createDirectories(filePath.parent.toString())
            val name = filePath.fileName.toString()
            val child = parent.findChild(name) ?: parent.createChildData(this, name)
            VfsUtil.saveText(child, sampleText)
            child
        }
        sampleFilePath = file.path
        positions = SamplePositions(
            classDeclaration = lineColumnFor(sampleText, "class Greeter", "class ".length),
            classUsage = lineColumnFor(sampleText, "Greeter(\"World\")"),
            methodDeclaration = lineColumnFor(sampleText, "fun greet", "fun ".length),
            methodCallName = lineColumnFor(sampleText, "greeter.greet(2)", "greeter.".length),
            methodCallArg = lineColumnFor(sampleText, "greeter.greet(2)", "greeter.greet(".length),
            messageUsage = lineColumnFor(sampleText, "println(message)", "println(".length),
            codeActionTarget = lineColumnFor(sampleText, "return \"Hello", "return ".length),
        )
    }

    private data class LineColumn(val line: Int, val column: Int)

    private data class SamplePositions(
        val classDeclaration: LineColumn,
        val classUsage: LineColumn,
        val methodDeclaration: LineColumn,
        val methodCallName: LineColumn,
        val methodCallArg: LineColumn,
        val messageUsage: LineColumn,
        val codeActionTarget: LineColumn,
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

    private fun configureExample(
        code: String,
        filePath: String? = null,
        line: Int? = null,
        column: Int? = null,
        query: String? = null,
        searchType: String? = null,
        newName: String? = null,
        dryRun: Boolean? = null,
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
        if (query != null) {
            updated = updated.replace(Regex("val query = \".*?\""), "val query = \"$query\"")
        }
        if (searchType != null) {
            updated = updated.replace(Regex("val searchType = \".*?\""), "val searchType = \"$searchType\"")
        }
        if (newName != null) {
            updated = updated.replace(Regex("val newName = \".*?\""), "val newName = \"$newName\"")
        }
        if (dryRun != null) {
            updated = updated.replace(Regex("val dryRun\\s*=\\s*\\w+"), "val dryRun = $dryRun")
        }
        return updated
    }

    private suspend fun executeExample(exampleId: String, code: String): ToolCallResult {
        val manager = project.service<ExecutionManager>()
        return withContext(Dispatchers.Default) {
            manager.executeWithProgress(
                testExecParams(code, taskId = "lsp-$exampleId", reason = "lsp example"),
                NoOpProgressReporter
            )
        }
    }

    private fun getTextContent(result: ToolCallResult): String {
        return result.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
    }

    private fun assertExampleResult(
        result: ToolCallResult,
        header: String,
        ignoreCase: Boolean = false
    ) {
        val text = getTextContent(result)
        assertTrue("Should execute without error. Output:\n$text", !result.isError)
        assertTrue(
            "Expected output to contain \"$header\". Output:\n$text",
            text.contains(header, ignoreCase = ignoreCase)
        )
    }

    fun testGoToDefinitionExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = index.goToDefinitionMd.ktBlock000.readPrompt()
        val code = configureExample(
            raw,
            filePath = sampleFilePath,
            line = positions.classUsage.line,
            column = positions.classUsage.column
        )

        val result = executeExample("go-to-definition", code)
        assertExampleResult(result, "definition", ignoreCase = true)
    }

    fun testFindReferencesExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = index.findReferencesMd.ktBlock000.readPrompt()
        val code = configureExample(
            raw,
            filePath = sampleFilePath,
            line = positions.methodDeclaration.line,
            column = positions.methodDeclaration.column
        )

        val result = executeExample("find-references", code)
        assertExampleResult(result, "references", ignoreCase = true)
    }

    fun testHoverExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = index.hoverMd.ktBlock000.readPrompt()
        val code = configureExample(
            raw,
            filePath = sampleFilePath,
            line = positions.messageUsage.line,
            column = positions.messageUsage.column
        )

        val result = executeExample("hover", code)
        assertExampleResult(result, "Hover Information")
    }

    fun testCompletionExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = index.completionMd.ktBlock000.readPrompt()
        val code = configureExample(
            raw,
            filePath = sampleFilePath,
            line = positions.methodCallName.line,
            column = positions.methodCallName.column
        )

        val result = executeExample("completion", code)
        assertExampleResult(result, "Completion at")
    }

    fun testDocumentSymbolsExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = index.documentSymbolsMd.ktBlock000.readPrompt()
        val code = configureExample(raw, filePath = sampleFilePath)

        val result = executeExample("document-symbols", code)
        assertExampleResult(result, "Document Symbols")
    }

    fun testRenameExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = index.renameMd.ktBlock000.readPrompt()
        val code = configureExample(
            raw,
            filePath = sampleFilePath,
            line = positions.classDeclaration.line,
            column = positions.classDeclaration.column,
            newName = "GreeterRenamed",
            dryRun = false
        )

        val result = executeExample("rename", code)
        // Recipe rewritten in iter-02 (commit ed24e475) from a regex-based
        // `document.replaceString` pass to a semantic RenameProcessor. The old heading
        // was "Rename Analysis"; the new heading is "Rename (semantic, PSI-backed): ...".
        assertExampleResult(result, "Rename (semantic, PSI-backed)")
        val updatedText = readAction {
            val vf = VfsUtil.findFile(Paths.get(sampleFilePath), true) ?: return@readAction ""
            val document = FileDocumentManager.getInstance().getDocument(vf) ?: return@readAction ""
            document.text
        }
        assertTrue("Rename should update class declaration", updatedText.contains("class GreeterRenamed"))
    }

    fun testFormattingExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = index.formattingMd.ktBlock000.readPrompt()
        val code = configureExample(raw, filePath = sampleFilePath)

        val result = executeExample("formatting", code)
        assertExampleResult(result, "Format Preview")
    }

    fun testCodeActionExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = index.codeActionMd.ktBlock000.readPrompt()
        val code = configureExample(
            raw,
            filePath = sampleFilePath,
            line = positions.codeActionTarget.line,
            column = positions.codeActionTarget.column
        )

        val result = executeExample("code-action", code)
        assertExampleResult(result, "Code Actions")
    }

    fun testSignatureHelpExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = index.signatureHelpMd.ktBlock000.readPrompt()
        val code = configureExample(
            raw,
            filePath = sampleFilePath,
            line = positions.methodCallArg.line,
            column = positions.methodCallArg.column
        )

        val result = executeExample("signature-help", code)
        assertExampleResult(result, "Signature Help")
    }

    fun testWorkspaceSymbolExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = index.workspaceSymbolMd.ktBlock000.readPrompt()
        val code = configureExample(raw, query = "Greeter")

        val result = executeExample("workspace-symbol", code)
        assertExampleResult(result, "Workspace Symbol Search")
    }
}
