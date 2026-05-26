/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.components.service
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.server.NoOpProgressReporter
import com.jonnyzzz.mcpSteroid.setSystemPropertyForTest
import com.jonnyzzz.mcpSteroid.testExecParams
import kotlin.time.Duration.Companion.seconds

class ScriptExecutionAvailabilityTest : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
    }

    fun testScriptExecutionCompiles(): Unit = timeoutRunBlocking(30.seconds) {
        val manager = project.service<ExecutionManager>()
        val result = manager.executeWithProgress(
            testExecParams(
                """
                println("engine-ok")
                """.trimIndent()
            ),
            NoOpProgressReporter
        )

        val text = result.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
        assertFalse(
            "Execution should succeed; script engine may be missing or broken. Output: $text",
            result.isError
        )
        assertTrue("Execution output should include marker text", text.contains("engine-ok"))
    }
}
