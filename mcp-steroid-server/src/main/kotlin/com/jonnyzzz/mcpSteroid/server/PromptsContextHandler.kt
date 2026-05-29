package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.prompts.PromptsContext

interface PromptsContextHandler {
    suspend fun buildPromptsContext(projectName: String): PromptsContext
}
