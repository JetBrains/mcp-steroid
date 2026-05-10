/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.ApplicationInfo
import com.jonnyzzz.mcpSteroid.prompts.PromptsContext

class PromptsContextHandlerIJ : PromptsContextHandler {
    override fun buildPromptsContext(projectName: String?): PromptsContext {
        val buildInfo = ApplicationInfo.getInstance().build
        return PromptsContext(
            productCode = buildInfo.productCode,
            baselineVersion = buildInfo.baselineVersion,
        )
    }
}
