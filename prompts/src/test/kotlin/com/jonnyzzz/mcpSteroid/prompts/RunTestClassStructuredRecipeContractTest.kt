/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.prompts

import com.jonnyzzz.mcpSteroid.prompts.generated.test.RunTestClassStructuredPromptArticle
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RunTestClassStructuredRecipeContractTest {

    private val payload = RunTestClassStructuredPromptArticle()
        .readPayload(PromptsContext("IU", 261))

    @Test
    fun testCorrelatesTestEventsByPublicProcessHandlerIdentity() {
        assertTrue(payload.contains("root.handler === handler"))
        assertTrue(payload.contains("targetProcessHandler.set(handler)"))
        assertTrue(payload.contains("SMRootTestProxy.getHandler()` identity"))
        assertFalse(payload.contains("SMTRunnerConsoleView.resultsViewer.testsRootNode"))
    }

    @Test
    fun testDoesNotAssumeDescriptorExposesSmRunnerConsoleDirectly() {
        assertFalse(payload.contains("executionConsole as? SMTRunnerConsoleView"))
        assertFalse(payload.contains("import com.intellij.build.BuildView"))
        assertFalse(payload.contains("as? BuildView"))
    }
}
