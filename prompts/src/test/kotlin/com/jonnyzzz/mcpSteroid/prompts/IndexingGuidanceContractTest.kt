/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.prompts

import com.jonnyzzz.mcpSteroid.prompts.generated.prompt.SkillPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.CodingWithIntelliJIntroPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.CodingWithIntelliJPatternsPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.CodingWithIntelliJThreadingPromptArticle
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IndexingGuidanceContractTest {

    @Test
    fun `prompt skill does not claim automatic smart mode is stable`() {
        val prompt = SkillPromptArticle().readPayload(PromptsContext("IU", 253))

        assertTrue(
            prompt.contains("point-in-time check"),
            "prompt skill should explain automatic smart-mode wait is only point-in-time",
        )
        assertTrue(
            prompt.contains("Observation.awaitConfiguration(project)"),
            "prompt skill should route initial import/sync readiness to Observation.awaitConfiguration",
        )
        assertTrue(
            prompt.contains("smartReadAction { }"),
            "prompt skill should route index-dependent PSI reads to smartReadAction",
        )
        assertFalse(
            prompt.contains("smart mode is confirmed for the duration"),
            "prompt skill must not promise a stable smart-mode lease",
        )
    }

    @Test
    fun `threading and intro resources route indexed reads through smart read action`() {
        val threading = CodingWithIntelliJThreadingPromptArticle().readPayload(PromptsContext("IU", 253))
        val intro = CodingWithIntelliJIntroPromptArticle().readPayload(PromptsContext("IU", 253))
        val patterns = CodingWithIntelliJPatternsPromptArticle().readPayload(PromptsContext("IU", 253))
        val combined = "$threading\n$intro\n$patterns"

        assertTrue(
            combined.contains("Observation.awaitConfiguration(project)"),
            "resources should mention Observation.awaitConfiguration for import/sync/configuration readiness",
        )
        assertTrue(
            combined.contains("smartReadAction"),
            "resources should recommend smartReadAction for index-dependent PSI reads",
        )
        assertFalse(
            combined.contains("Smart mode already waited - safe to use indices immediately"),
            "resources must not tell agents to use indices immediately after the automatic wait",
        )
        assertFalse(
            combined.contains("waitForSmartMode()  //"),
            "resources should not show waitForSmartMode as the indexed-read handoff",
        )
        assertFalse(
            combined.contains("Once smart mode is confirmed"),
            "resources must not describe smart mode as a durable confirmation",
        )
    }
}
