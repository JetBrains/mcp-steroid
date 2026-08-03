/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * `fetch_resource`'s `uri` is a bare CLI positional (see `FetchResourceToolHandler.uri`), and `prompt` is
 * its declared alias (issue #284). `bindPositional` (`SchemaCliBinding.kt`) registers a Clikt argument,
 * never an option, so there is no `--uri` flag under either name — this pins the bare-positional form
 * under both the `prompt` alias and the canonical `fetch_resource` command name.
 */
class FetchResourceCommandTest {

    @Test
    fun `devrig prompt uri parses as a bare positional to fetch_resource`() {
        // project_name stays mandatory on the CLI (see ToolSpecCliMetadataTest: "project-scoped tools
        // demand project_name on the CLI, not just MCP-required"), so it is still passed as a flag here —
        // only `uri` moves to a bare positional.
        val skill = com.jonnyzzz.mcpSteroid.prompts.generated.prompt.SkillPromptArticle().uri
        val run = requireNotNull(parseDevrigCommand(arrayOf("prompt", skill, "--project_name=key")).generatedTool)
        assertEquals("steroid_fetch_resource", run.toolName)
        assertEquals(JsonPrimitive(skill), run.arguments["uri"])
    }

    @Test
    fun `devrig fetch_resource uri still works as the canonical name`() {
        val skill = com.jonnyzzz.mcpSteroid.prompts.generated.prompt.SkillPromptArticle().uri
        val run = requireNotNull(parseDevrigCommand(arrayOf("fetch_resource", skill, "--project_name=key")).generatedTool)
        assertEquals("steroid_fetch_resource", run.toolName)
        assertEquals(JsonPrimitive(skill), run.arguments["uri"])
    }
}
