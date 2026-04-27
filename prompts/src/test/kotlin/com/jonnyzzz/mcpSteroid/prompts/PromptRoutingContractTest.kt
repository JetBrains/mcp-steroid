/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.prompts

import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeToolDescriptionPromptArticle
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptRoutingContractTest {

    @Test
    fun `execute code tool routes multi-site literal edits to dedicated apply patch tool`() {
        val prompt = ExecuteCodeToolDescriptionPromptArticle().readPayload(PromptsContext("IU", 253))

        assertTrue(
            prompt.contains("steroid_apply_patch"),
            "execute-code tool description must name the dedicated apply-patch tool",
        )
        assertTrue(
            prompt.contains("older script-context `applyPatch` DSL only"),
            "execute-code tool description may mention the script-context DSL only as a fallback",
        )
        assertFalse(
            prompt.contains("Switch to a single `steroid_execute_code` call with the `applyPatch` DSL"),
            "execute-code tool description must not route ordinary multi-site edits through steroid_execute_code",
        )
        assertFalse(
            prompt.contains("| **Two or more literal-text edits, same or different files** | `applyPatch"),
            "decision tree must route multi-site literal edits to steroid_apply_patch",
        )
    }
}
