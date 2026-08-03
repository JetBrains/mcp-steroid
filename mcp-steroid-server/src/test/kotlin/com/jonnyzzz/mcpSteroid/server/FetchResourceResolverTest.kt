package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.prompts.Generic
import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import com.jonnyzzz.mcpSteroid.prompts.generated.prompt.SkillPromptArticle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FetchResourceResolverTest {
    @Test
    fun `resolves an existing uri in a Generic context`() {
        val uri = SkillPromptArticle().uri
        val article = resolveResourceArticle(uri, PromptsContext.Generic)
        assertEquals(uri, article?.uri, "the skill index article must resolve by its own uri")
    }

    @Test
    fun `returns null for an unknown uri`() {
        assertNull(resolveResourceArticle("mcp-steroid://does/not/exist", PromptsContext.Generic))
    }

    @Test
    fun `canonical entry points are non-empty and all resolvable`() {
        val entryPoints = canonicalResourceEntryPoints()
        assertTrue(entryPoints.isNotEmpty(), "there must be at least one canonical entry point")
        for (uri in entryPoints) {
            assertEquals(uri, resolveResourceArticle(uri, PromptsContext.Generic)?.uri, "entry point $uri must resolve")
        }
    }
}
