/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.execution.ExecutionResultBuilder
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.ExecCodeParams
import com.jonnyzzz.mcpSteroid.server.SteroidsMcpServer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path

/**
 * Test utilities for MCP server tests.
 * Provides access to the MCP server service in tests.
 */
object McpTestUtil {
    /**
     * Get the SSE URL if the server is running.
     */
    fun getSseUrlIfRunning(): String {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()
        assert(server.port > 0)
        return server.mcpUrl
    }
}

/**
 * Common test implementation of ExecutionResultBuilder.
 * Collects all output for assertions in tests.
 */
class TestResultBuilder : ExecutionResultBuilder {
    val messages = mutableListOf<String>()
    val progressMessages = mutableListOf<String>()
    val exceptions = mutableListOf<Pair<String, Throwable>>()
    val images = mutableListOf<TestImage>()
    private var failed = false
    var failureMessage: String? = null
    private var _userOutputCount = 0

    override val isFailed: Boolean get() = failed
    override val userOutputCount: Int get() = _userOutputCount

    override fun noteUserOutput() {
        _userOutputCount++
    }

    override fun logMessage(message: String) {
        messages += message
    }

    override fun logProgress(message: String) {
        progressMessages += message
    }

    override fun logImage(mimeType: String, data: String, fileName: String) {
        images += TestImage(mimeType = mimeType, data = data, fileName = fileName)
    }

    override fun logException(message: String, throwable: Throwable) {
        exceptions += message to throwable
    }

    override fun reportFailed(message: String) {
        failed = true
        failureMessage = message
    }

    fun hasAnyOutput(): Boolean {
        return failed || messages.isNotEmpty() || exceptions.isNotEmpty() || progressMessages.isNotEmpty() || images.isNotEmpty()
    }

    fun hasDaemonDyingError(): Boolean {
        val msg = failureMessage ?: ""
        return msg.contains("Service is dying") || msg.contains("Could not connect to Kotlin compile daemon")
    }

    override fun toString() = buildString {
        appendLine("TestResultBuilder")
        appendLine("failed=$failed")
        messages.forEach { appendLine("MESSAGE: $it") }
        progressMessages.forEach { appendLine("PROGRESS: $it") }
        images.forEach { appendLine("IMAGE: ${it.fileName} (${it.mimeType})") }
        exceptions.forEach {
            appendLine("EXCEPTION: ${it.first}")
            appendLine(it.second.toString())
            appendLine(it.second.stackTraceToString())
        }
        appendLine("Failure message: $failureMessage")
    }
}

data class TestImage(
    val mimeType: String,
    val data: String,
    val fileName: String,
)

/**
 * Creates ExecCodeParams for tests with sensible defaults.
 * Note: cancelOnModal defaults to false for tests because most tests don't need modal detection.
 */
fun testExecParams(
    code: String,
    taskId: String = "test-task",
    reason: String = "test",
    timeout: Int = 60,
    cancelOnModal: Boolean = false
) = ExecCodeParams(
    taskId = taskId,
    code = code,
    reason = reason,
    timeout = timeout,
    cancelOnModal = cancelOnModal,
    rawParams = buildJsonObject { }
)


/**
 * Extracts the execution ID from the ToolCallResult's structuredContent.
 * The structuredContent is a JSON object with executionId as a key.
 */
fun getExecutionIdFromResult(result: ToolCallResult): String {
    val prefix = "execution_id:"
    val executionId = result.content.filterIsInstance<ContentItem.Text>().firstNotNullOfOrNull { item ->
        val match = Regex("""execution_id:\s*([\w-]+)""").find(item.text)
        match?.groupValues?.get(1)
    } ?: error("No execution_id in result")
    println("Result: $executionId")
    return executionId
}
