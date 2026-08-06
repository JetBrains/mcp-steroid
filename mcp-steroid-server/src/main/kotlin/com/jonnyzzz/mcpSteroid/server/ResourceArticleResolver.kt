/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import com.jonnyzzz.mcpSteroid.prompts.generated.ResourcesIndex

/**
 * Single source for mcp-steroid:// article resolution, shared by `steroid_fetch_resource`
 * and `devrig help`.
 */
fun resolveResourceArticle(uri: String, promptsContext: PromptsContext) =
    ResourcesIndex().roots.values.asSequence()
        .flatMap { it.articles.values.asSequence() }
        .firstOrNull { it.uri == uri && it.filter.matches(promptsContext) }
