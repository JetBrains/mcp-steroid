package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore

abstract class McpSteroidTools {
    fun registerAll(server: McpServerCore) {
        val tools = server.toolRegistry

        tools.registerTool(ListProjectsToolSpec { handler<ListProjectsToolHandler>() })
        tools.registerTool(ListWindowsToolSpec { handler<ListWindowsToolHandler>() })
        tools.registerTool(ExecuteCodeToolSpec { handler<ExecuteCodeToolHandler>() })
        tools.registerTool(ExecuteFeedbackToolSpec { handler<ExecuteFeedbackToolHandler>() })
        tools.registerTool(VisionScreenshotToolSpec { handler<VisionScreenshotToolHandler>() })
        tools.registerTool(VisionInputToolSpec { handler<VisionInputToolHandler>() })
        tools.registerTool(OpenProjectToolSpec { handler<OpenProjectToolHandler>() })
        tools.registerTool(FetchResourceToolHandler { handler<PromptsContextHandler>() })
    }

    inline fun <reified T : Any> handler(): T = handler(T::class.java)
    abstract fun <T> handler(type: Class<T>): T
}
