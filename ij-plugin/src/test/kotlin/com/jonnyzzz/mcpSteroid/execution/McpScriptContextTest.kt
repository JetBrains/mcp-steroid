/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.common.timeoutRunBlocking
import com.jonnyzzz.mcpSteroid.TestResultBuilder
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for McpScriptContext implementation.
 *
 * Note: Uses a TestResultBuilder to capture output instead of storage,
 * since the new API uses ExecutionResultBuilder interface.
 */
class McpScriptContextTest : BasePlatformTestCase() {

    private lateinit var executionId: ExecutionId

    override fun setUp() {
        super.setUp()
        executionId = ExecutionId("test-execution-id")
    }

    private fun createContext(resultBuilder: TestResultBuilder = TestResultBuilder()): Pair<McpScriptContextImpl, TestResultBuilder> {
        val disposable = Disposer.newDisposable(testRootDisposable, "test-context-$executionId")
        Disposer.register(testRootDisposable, disposable)
        val context = McpScriptContextImpl(
            project = project,
            executionId = executionId,
            disposable = disposable,
            resultBuilder = resultBuilder,
            // The modal monitor (unused by these tests) launches here; a throwaway scope is fine.
            executionScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        )
        return context to resultBuilder
    }

    fun testPrintlnVarargs() {
        val (context, builder) = createContext()

        // Test varargs println
        context.println("Hello", "World", 42)
        context.println()  // Empty line
        context.println(null, "test")

        assertEquals("Should have 3 messages", 3, builder.messages.size)
        assertEquals("Hello World 42", builder.messages[0])
        assertEquals("", builder.messages[1])  // Empty line
        assertEquals("null test", builder.messages[2])
    }

    fun testPrintlnSingleValue() {
        val (context, builder) = createContext()

        context.println("Single value")
        context.println(123)

        assertEquals(2, builder.messages.size)
        assertEquals("Single value", builder.messages[0])
        assertEquals("123", builder.messages[1])
    }

    fun testPrintJsonWithMap() {
        val (context, builder) = createContext()

        context.printJson(mapOf("name" to "test", "count" to 42))

        assertEquals(1, builder.messages.size)
        // Jackson output should contain the keys
        assertTrue(builder.messages[0].contains("\"name\""))
        assertTrue(builder.messages[0].contains("\"test\""))
        assertTrue(builder.messages[0].contains("\"count\""))
        assertTrue(builder.messages[0].contains("42"))
    }

    fun testPrintJsonWithNull() {
        val (context, builder) = createContext()

        context.printJson(null)

        assertEquals(1, builder.messages.size)
        assertEquals("null", builder.messages[0])
    }

    fun testPrintException() {
        val (context, builder) = createContext()

        val exception = RuntimeException("Test error")
        context.printException("Something failed", exception)

        assertEquals(1, builder.exceptions.size)
        assertEquals("Something failed", builder.exceptions[0].first)
        assertEquals(exception, builder.exceptions[0].second)
    }

    fun testProgressReporting() {
        val (context, builder) = createContext()

        context.progress("Starting work...")
        context.progress("Processing...")

        assertEquals(2, builder.progressMessages.size)
        assertEquals("Starting work...", builder.progressMessages[0])
        assertEquals("Processing...", builder.progressMessages[1])
    }

    fun testProjectAccess() {
        val (context, _) = createContext()

        assertEquals(project, context.project)
    }

    fun testDisposedContextRejectsOutput() {
        val (context, _) = createContext()
        // Dispose the context
        Disposer.dispose(context.disposable)

        try {
            context.println("Should fail")
            fail("Should throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("disposed") == true)
        }
    }

    fun testIsDisposedFlag() {
        val (context, _) = createContext()

        assertFalse("Should not be disposed initially", context.isDisposed)

        Disposer.dispose(context.disposable)

        assertTrue("Should be disposed after dispose()", context.isDisposed)
    }

    fun testFindProjectFilesByGlob(): Unit = timeoutRunBlocking(30.seconds) {
        val (context, _) = createContext()

        myFixture.addFileToProject("src/main/kotlin/demo/First.kt", "package demo\nclass First")
        myFixture.addFileToProject("src/main/kotlin/demo/Second.kt", "package demo\nclass Second")
        myFixture.addFileToProject("src/main/resources/demo.txt", "demo")

        val files = context.findProjectFiles("**/*.kt")
        val names = files.map { it.name }.sorted()

        assertEquals(listOf("First.kt", "Second.kt"), names)
    }
}
