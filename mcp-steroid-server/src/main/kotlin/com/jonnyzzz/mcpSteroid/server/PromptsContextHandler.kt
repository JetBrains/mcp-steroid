package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.prompts.PromptsContext

interface PromptsContextHandler {
    fun buildPromptsContext(projectName: String? = null): PromptsContext
}
