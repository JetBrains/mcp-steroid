/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.prompts

/**
 * Runtime context describing the current IDE environment.
 *
 * Constructed at runtime from `ApplicationInfo.getInstance()` and passed to
 * [PromptBase.readPrompt] for conditional content filtering.
 *
 * @param productCode IDE product code from `ApplicationInfo.build.productCode` — e.g. "IU", "RD", "CL"
 * @param baselineVersion baseline version from build number prefix — e.g. 253 for "253.31033.145"
 */
data class PromptsContext(
    val productCode: String,
    val baselineVersion: Int,
) {
    companion object
}

val PromptsContext.Companion.Generic get() = PromptsContext("Generic", 253)
