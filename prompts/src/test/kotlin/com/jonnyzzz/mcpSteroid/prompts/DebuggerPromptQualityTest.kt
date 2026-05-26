/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.prompts

import com.jonnyzzz.mcpSteroid.prompts.generated.debugger.DebuggerIndex
import com.jonnyzzz.mcpSteroid.prompts.generated.prompt.PromptIndex
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DebuggerPromptQualityTest {
    private val debuggerIndex = DebuggerIndex()
    private val promptIndex = PromptIndex()

    private val ideaContext = PromptsContext("IU", 253)

    @Test
    fun testSetLineBreakpointUsesToggleLineBreakpoint() {
        val prompt = debuggerIndex.setLineBreakpointMd.ktBlock000.readPrompt()
        assertTrue(prompt.contains("toggleLineBreakpoint")) { "Expected toggleLineBreakpoint guidance" }
        assertTrue(prompt.contains("Dispatchers.EDT")) { "Expected EDT guidance" }
        assertFalse(
            prompt.contains("breakpointManager.addLineBreakpoint(")
        ) { "Should not use addLineBreakpoint(...) directly in code" }
    }

    @Test
    fun testDebugRunConfigurationUsesModernProgramRunnerUtilPackage() {
        val prompt = debuggerIndex.debugRunConfigurationMd.ktBlock000.readPrompt()
        assertTrue(
            prompt.contains("import com.intellij.execution.ProgramRunnerUtil")
        ) { "Expected modern ProgramRunnerUtil package" }
        assertFalse(
            prompt.contains("import com.intellij.execution.runners.ProgramRunnerUtil")
        ) { "Should not use outdated ProgramRunnerUtil package" }
    }

    @Test
    fun testDebuggerOverviewClarifiesZeroBasedLineNumbers() {
        val prompt = debuggerIndex.overviewMd.readPayload(ideaContext)
        assertTrue(
            prompt.contains("0-indexed", ignoreCase = true)
        ) { "Expected explicit 0-indexed line guidance" }
    }

    @Test
    fun testDebuggerSkillDocumentsCriticalImports() {
        val prompt = promptIndex.debuggerSkillMd.readPayload(ideaContext)
        assertTrue(
            prompt.contains("kotlinx.coroutines.suspendCancellableCoroutine")
        ) { "Expected suspendCancellableCoroutine import guidance" }
        assertTrue(
            prompt.contains("com.intellij.execution.ProgramRunnerUtil")
        ) { "Expected ProgramRunnerUtil package guidance" }
        assertTrue(
            prompt.contains("not `com.intellij.execution.runners.ProgramRunnerUtil`")
        ) { "Expected explicit anti-pattern warning for old ProgramRunnerUtil package" }
    }

    @Test
    fun testDebuggerSkillWarnsAboutMethodBreakpointSlowness() {
        val prompt = promptIndex.debuggerSkillMd.readPayload(ideaContext)
        assertTrue(
            prompt.contains("method", ignoreCase = true)
                && prompt.contains("slow", ignoreCase = true)
        ) { "Expected method-breakpoint slowness guidance in debugger-skill" }
        assertTrue(
            prompt.contains("MethodEntryRequest") || prompt.contains("method.breakpoints")
        ) { "Expected MethodEntryRequest reference or JDI explanation in debugger-skill" }
    }

    @Test
    fun testAddBreakpointWarnsAboutMethodBreakpointsAndPointsAtInlineVariant() {
        val prompt = debuggerIndex.addBreakpointMd.readPayload(ideaContext)
        assertTrue(
            prompt.contains("method", ignoreCase = true)
                && prompt.contains("slow", ignoreCase = true)
        ) { "Expected method-breakpoint slowness warning in add-breakpoint" }
        assertTrue(
            prompt.contains("mcp-steroid://debugger/add-inline-breakpoint")
        ) { "Expected pointer to add-inline-breakpoint for multi-statement lines" }
    }

    @Test
    fun testAddInlineBreakpointTeachesVariantApi() {
        val prompt = debuggerIndex.addInlineBreakpointMd.readPayload(ideaContext)
        assertTrue(
            prompt.contains("computeVariantsAsync")
        ) { "Expected XLineBreakpointType.computeVariantsAsync usage" }
        assertTrue(
            prompt.contains("createProperties")
        ) { "Expected variant.createProperties() usage" }
        assertTrue(
            prompt.contains("variantAndBreakpointMatch")
        ) { "Expected variantAndBreakpointMatch idempotency check" }
    }

    @Test
    fun testMonitorDebugEventsTeachesAppendOnlyAndDisposable() {
        val prompt = debuggerIndex.monitorDebugEventsMd.readPayload(ideaContext)
        assertTrue(
            prompt.contains("appendText")
        ) { "Expected append-only file write guidance" }
        assertTrue(
            prompt.contains("Disposer.newDisposable") && prompt.contains("Disposer.register")
        ) { "Expected Disposable creation + parent registration guidance" }
        assertTrue(
            prompt.contains("XDebugSessionListener") && prompt.contains("XBreakpointListener")
        ) { "Expected both session and breakpoint listeners" }
        assertTrue(
            prompt.contains("classloader", ignoreCase = true)
        ) { "Expected classloader-leak caveat" }
        assertTrue(
            prompt.contains("maxAgeMs") || prompt.contains("self-dispose") || prompt.contains("self-expir")
        ) { "Expected time-based self-disposal guidance" }
    }

    @Test
    fun testDebuggerOverviewListsNewResources() {
        val prompt = debuggerIndex.overviewMd.readPayload(ideaContext)
        assertTrue(
            prompt.contains("mcp-steroid://debugger/add-inline-breakpoint")
        ) { "Expected add-inline-breakpoint to be listed in overview" }
        assertTrue(
            prompt.contains("mcp-steroid://debugger/monitor-debug-events")
        ) { "Expected monitor-debug-events to be listed in overview" }
    }
}
