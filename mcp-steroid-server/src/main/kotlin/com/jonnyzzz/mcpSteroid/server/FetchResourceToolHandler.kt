package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpTool
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.errorResult
import com.jonnyzzz.mcpSteroid.prompts.generated.ResourcesIndex
import com.jonnyzzz.mcpSteroid.prompts.generated.ide.FindDuplicatesPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.ide.InspectAndFixPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.prompt.DebuggerSkillPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.prompt.SkillPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.prompt.TestSkillPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.CodingWithIntelliJPromptArticle
import com.jonnyzzz.mcpSteroid.thisLogger
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Simple tool that fetches any MCP Steroid resource by URI and returns its Markdown content.
 * Agents can call this instead of ReadMcpResourceTool — it's a purpose-built MCP tool
 * visible in the tool list, making resource discovery more natural.
 */
class FetchResourceToolHandler(
    private val handler: () -> PromptsContextHandler,
) : McpTool {

    private val log = thisLogger()

    override val name = "steroid_fetch_resource"

    override val description: String get() {
        val testSkillUri = TestSkillPromptArticle().uri
        val debuggerUri = DebuggerSkillPromptArticle().uri
        val skillUri = SkillPromptArticle().uri
        val codingGuideUri = CodingWithIntelliJPromptArticle().uri
        val findDuplicatesUri = FindDuplicatesPromptArticle().uri
        val inspectAndFixUri = InspectAndFixPromptArticle().uri
        return "Fetch a mcp-steroid:// skill guide by URI. Returns markdown with copy-paste Kotlin code recipes for steroid_execute_code. " +
                "Running tests? → $testSkillUri | " +
                "Debugging? → $debuggerUri | " +
                "Find duplicates / clones / copy-pasted code / DRY violations? → $findDuplicatesUri | " +
                "Run a named inspection + quick fix? → $inspectAndFixUri | " +
                "Any IDE task? → $skillUri | " +
                "Full reference? → $codingGuideUri"
    }

    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("uri") {
                put("type", "string")
                put("description", "The resource URI to fetch (from ListMcpResourcesTool or MCP server instructions)")
            }
            putJsonObject("project_name") {
                put("type", "string")
                put("description", "Project name (from steroid_list_projects)")
            }
        }
        putJsonArray("required") {
            add("uri")
            add("project_name")
        }
    }

    override suspend fun call(context: ToolCallContext): ToolCallResult {
        val args = context.params.arguments
        val uri = args["uri"]?.jsonPrimitive?.content
            ?: return ToolCallResult.errorResult("Missing required parameter: uri")
        val projectName = args["project_name"]?.jsonPrimitive?.content
            ?: return ToolCallResult.errorResult("Missing required parameter: project_name")

        log.info("steroid_fetch_resource: $uri")

        val promptsContext = handler().buildPromptsContext(projectName)
        val article = ResourcesIndex().roots.values
            .asSequence()
            .flatMap { it.articles.values.asSequence() }
            .firstOrNull { it.uri == uri && it.filter.matches(promptsContext) }
            ?: return ToolCallResult(
                content = listOf(ContentItem.Text(text = "ERROR: Resource not found: $uri")),
                isError = true
            )

        return ToolCallResult(content = listOf(ContentItem.Text(text = article.readPayload(promptsContext))))
    }
}
