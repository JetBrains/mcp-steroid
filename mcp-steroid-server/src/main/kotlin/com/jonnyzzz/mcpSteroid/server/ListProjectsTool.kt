package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.McpToolBase
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Handler for the steroid_list_projects MCP tool.
 */
class ListProjectsToolSpec(val handler: () -> ListProjectsToolHandler) : McpToolBase() {
    override val name = "steroid_list_projects"
    override val description = "List all open projects in the IDE. Returns project names that can be used with steroid_execute_code and steroid_open_project."

    override suspend fun call(context: ToolCallContext): ToolCallResult {
        val response = handler().collectListProjectsResponse()
        val json = McpJson.encodeToString(response)

        return ToolCallResult(
            content = listOf(ContentItem.Text(text = json))
        )
    }
}

interface ListProjectsToolHandler {
    suspend fun collectListProjectsResponse(): ListProjectsResponse
}

/**
 * MCP-only output of `steroid_list_projects` — never crosses the devrig<->IDE wire. There is no
 * top-level `ide`/`plugin`/`pid` header: the responding server's identity lives in the MCP server
 * info, and per-entry attribution happens via `backend_name` on each project entry.
 */
@Serializable
data class ListProjectsResponse(
    /**
     * Projects reachable through this connection. On a direct in-IDE connection `project_name == name`
     * and `backend_name` is this IDE's self-id; on devrig `project_name` is the disambiguated exposed
     * name and `backend_name` is the owning discovered IDE.
     */
    val projects: List<ListedProject>,
)

/**
 * The wire DTO carried over `/projects/stream` (devrig<->IDE). Pristine `{name, path}` — the per-project
 * backend reference lives on the MCP-only [ListedProject], never here.
 */
@Serializable
data class ProjectInfo(
    val name: String,
    val path: String,
)

/**
 * Shared marker locator — `"build <build>, pid <pid>"`, with the `build ` segment omitted when [build] is
 * null or blank. devrig appends its own `", managed"` suffix where applicable.
 */
fun markerLocator(build: String?, pid: Long): String = buildString {
    build?.trim()?.takeIf { it.isNotEmpty() }?.let {
        append("build ").append(it).append(", ")
    }
    append("pid ").append(pid)
}

@Serializable
data class ListedProject(
    /** devrig: exposed disambiguated name; IDE-direct: the real project name. */
    @SerialName("project_name") val projectName: String,
    /** Raw folder name (R3.7) — kept so existing `jq '.projects[].name'` consumers do not break. */
    val name: String,
    val path: String,
    /** Owning backend's backend_name; null only when unknown. */
    @SerialName("backend_name") val backendName: String? = null,
)
