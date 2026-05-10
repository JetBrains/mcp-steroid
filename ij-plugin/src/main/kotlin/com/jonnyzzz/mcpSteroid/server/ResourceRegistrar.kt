/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpPromptRegistrar
import com.jonnyzzz.mcpSteroid.mcp.McpResourceRegistrar
import com.jonnyzzz.mcpSteroid.mcp.Prompt
import com.jonnyzzz.mcpSteroid.mcp.PromptContent
import com.jonnyzzz.mcpSteroid.mcp.PromptGetResult
import com.jonnyzzz.mcpSteroid.mcp.PromptMessage
import com.jonnyzzz.mcpSteroid.prompts.PromptIndexBase
import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import com.jonnyzzz.mcpSteroid.prompts.generated.ResourcesIndex


/**
 * Registers all generated prompt articles as MCP resources and prompts.
 *
 * Uses the generated [ResourcesIndex] to iterate over all folders and articles,
 * eliminating the need for generated registration code.
 *
 * Articles are filtered by their root [IdeFilter] — articles that don't match
 * the current IDE are skipped entirely.
 *
 * Content is rendered via [ArticleBase.readPayload] which handles per-part
 * filtering and see-also filtering internally.
 */
class ResourceRegistrar(
    private val handler: () -> PromptsContextHandler,
) {

    fun register(resources: McpResourceRegistrar, prompts: McpPromptRegistrar) {
        val resourcesIndex = ResourcesIndex()
        val context = handler().buildPromptsContext()

        for ((folder, index) in resourcesIndex.roots) {
            registerArticleResources(resources, index, context)
            if (folder == "prompt") {
                registerSkillPrompts(prompts, index, context)
            }
        }
    }

    private fun registerArticleResources(
        resources: McpResourceRegistrar,
        index: PromptIndexBase,
        context: PromptsContext,
    ) {
        for ((_, article) in index.articles) {
            if (!article.filter.matches(context)) continue

            resources.registerResource(
                uri = article.uri,
                name = article.title.readPrompt(),
                description = article.description.readPrompt(),
                mimeType = "text/markdown",
            ) {
                article.readPayload(context)
            }
        }
    }

    private fun registerSkillPrompts(
        prompts: McpPromptRegistrar,
        index: PromptIndexBase,
        context: PromptsContext,
    ) {
        for ((_, article) in index.articles) {
            if (!article.filter.matches(context)) continue

            prompts.registerPrompt(
                Prompt(
                    name = article.uri,
                    title = article.title.readPrompt(),
                    description = article.description.readPrompt(),
                )
            ) {
                PromptGetResult(
                    description = article.description.readPrompt(),
                    messages = listOf(
                        PromptMessage(
                            role = "user",
                            content = PromptContent.Text(article.readPayload(context))
                        )
                    )
                )
            }
        }
    }
}
