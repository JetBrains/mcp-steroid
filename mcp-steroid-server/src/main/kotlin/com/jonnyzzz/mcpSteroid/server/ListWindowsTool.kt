package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.McpToolBase
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import kotlinx.serialization.Serializable

/**
 * Handler for the steroid_list_windows MCP tool.
 */
class ListWindowsToolSpec(val handler: () -> ListWindowsToolHandler) : McpToolBase() {
    override val name = "steroid_list_windows"
    override val description = "List open IDE windows and their associated projects. Use this to choose project_name for screenshot/input tools in multi-window setups."

    override suspend fun call(context: ToolCallContext): ToolCallResult {
        val response = handler().collectListWindowsResponse()
        val json = McpJson.encodeToString(response)
        return ToolCallResult(
            content = listOf(ContentItem.Text(text = json))
        )
    }
}

interface ListWindowsToolHandler {
    suspend fun collectListWindowsResponse(): ListWindowsResponse
}

@Serializable
data class ListWindowsResponse(
    val ide: IdeInfo,
    val plugin: PluginInfo,
    val pid: Long,

    val windows: List<WindowInfo>,
    val backgroundTasks: List<ProgressTaskInfo>,
)

@Serializable
data class WindowInfo(
    val projectName: String?,
    val projectPath: String?,
    val title: String?,
    val isActive: Boolean,
    val isVisible: Boolean,
    val bounds: WindowBounds?,
    val windowId: String,
    /** True if a modal dialog is currently showing in the IDE */
    val modalDialogShowing: Boolean = false,
    /** True if the project is currently indexing (dumb mode) */
    val indexingInProgress: Boolean? = null,
    /** True if the project has been fully initialized */
    val projectInitialized: Boolean? = null,
)

@Serializable
data class WindowBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/** Information about a background task/progress indicator */
@Serializable
data class ProgressTaskInfo(
    /** Task title (e.g., "Indexing", "Building") */
    val title: String,
    /** Current status text */
    val text: String,
    /** Secondary status text */
    val text2: String,
    /** Progress fraction (0.0 to 1.0), null if indeterminate */
    val fraction: Double?,
    /** True if progress is indeterminate (no percentage) */
    val isIndeterminate: Boolean,
    /** True if the task can be canceled */
    val isCancellable: Boolean,
    /** Project name this task belongs to (if known) */
    val projectName: String?
)
