/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.prompts

import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeGradlePromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeOverviewPromptArticle
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GradlePromptContractTest {

    @Test
    fun `gradle prompt routes steroid code to external system runner`() {
        val prompt = ExecuteCodeGradlePromptArticle().readPayload(PromptsContext("IU", 253))

        assertTrue(
            prompt.contains("ExternalSystemUtil.runTask"),
            "Gradle prompt should use IntelliJ ExternalSystemUtil for Gradle tasks",
        )
        assertTrue(
            prompt.contains("GradleConstants.SYSTEM_ID"),
            "Gradle prompt should use the Gradle external system id",
        )
        assertTrue(
            prompt.contains("ProjectDataImportListener"),
            "Gradle prompt should wait for Gradle import events before indexed work",
        )
        assertTrue(
            prompt.contains("onFinalTasksFinished"),
            "Gradle prompt should use final import tasks as the sync boundary",
        )
        assertTrue(
            prompt.contains("waitForSmartMode()"),
            "Gradle prompt should wait for smart mode after final import tasks",
        )
        assertFalse(
            prompt.contains("Observation.awaitConfiguration(project)"),
            "Gradle prompt should not use the flaky Observation await path for Gradle sync",
        )
        assertTrue(
            prompt.contains("--rerun-tasks"),
            "Gradle prompt should guard against UP-TO-DATE skipped-test false positives",
        )
        assertTrue(
            prompt.contains("JUnit XML"),
            "Gradle prompt should tell agents to inspect test XML before rerunning",
        )
        assertTrue(
            prompt.contains("Bash tool outside `steroid_execute_code`"),
            "Gradle prompt should keep Bash Gradle fallback outside steroid_execute_code",
        )
        assertFalse(
            prompt.contains("ProcessBuilder(\"./gradlew\")` as primary"),
            "Gradle prompt must not present ProcessBuilder as a primary Gradle path",
        )
    }

    @Test
    fun `execute code overview links gradle work to dedicated gradle prompt`() {
        val prompt = ExecuteCodeOverviewPromptArticle().readPayload(PromptsContext("IU", 253))

        assertTrue(
            prompt.contains("mcp-steroid://skill/execute-code-gradle"),
            "execute-code overview should route Gradle test/sync guidance to the dedicated Gradle prompt",
        )
    }
}
