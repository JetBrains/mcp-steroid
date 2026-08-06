/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * `fetch_resource`'s `uri` is a bare CLI positional (see `FetchResourceToolHandler.uri`), and `prompt` is
 * its declared alias (issue #284). Help advertises that concise form under both names. The schema-driven
 * binding also retains the former `--uri` option as a hidden compatibility spelling so existing scripts
 * survive the presentation change without creating a second command implementation.
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

    @Test
    fun `the former uri flag remains accepted without becoming the advertised form`() {
        val skill = com.jonnyzzz.mcpSteroid.prompts.generated.prompt.SkillPromptArticle().uri
        for (command in listOf("fetch_resource", "prompt")) {
            val run = requireNotNull(
                parseDevrigCommand(arrayOf(command, "--uri=$skill", "--project_name=key")).generatedTool,
            )
            assertEquals("steroid_fetch_resource", run.toolName)
            assertEquals(JsonPrimitive(skill), run.arguments["uri"])
        }

        val help = requireNotNull(parseDevrigCommand(arrayOf("fetch_resource", "--help")).informationalText)
        assertEquals(false, "--uri" in help, help)
    }
}
