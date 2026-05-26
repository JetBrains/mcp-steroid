/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.prompts

import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeToolDescriptionPromptArticle
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptRoutingContractTest {

    @Test
    fun `execute code tool routes multi-site literal edits to the applyPatch DSL`() {
        val prompt = ExecuteCodeToolDescriptionPromptArticle().readPayload(PromptsContext("IU", 253))

        assertFalse(
            prompt.contains("steroid_apply_patch"),
            "steroid_apply_patch was removed — the prompt must not name it",
        )
        assertTrue(
            prompt.contains("`applyPatch { }` DSL"),
            "execute-code tool description must route multi-site edits through the applyPatch { } DSL",
        )
        assertTrue(
            prompt.contains("mcp-steroid://ide/apply-patch"),
            "prompt should link to the apply-patch recipe",
        )
    }
}
