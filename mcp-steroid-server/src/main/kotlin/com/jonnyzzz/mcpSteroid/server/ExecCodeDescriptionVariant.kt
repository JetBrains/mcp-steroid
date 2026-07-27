/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeToolDescriptionPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeToolDescriptionSlimPromptArticle

/**
 * Which `steroid_execute_code` tool description the process serves.
 *
 * The tool definition is the one piece of prompt text every agent pays for on every request, so the
 * corpus carries two shapes of it: [FULL] spells every recipe out inline, [SLIM] names the gotchas and
 * links the article that owns each recipe. Which one wins is a per-process decision made from
 * [ENV_VAR] — the same server code runs inside the IDE and inside devrig, and neither has a per-caller
 * place to put this, so an environment variable is the switch that reaches both (and is settable per
 * Docker container, which is how the arena compares the two head to head).
 *
 * [marker] is a substring unique to the variant's rendered payload: a caller that only sees the served
 * description (an arena arm asserting it got the arm it asked for) can tell the variants apart without
 * re-deriving the whole text.
 */
enum class ExecCodeDescriptionVariant(val wire: String, val marker: String) {
    /** Every recipe inline. The repo default — what every agent has been measured against so far. */
    FULL(wire = "full", marker = "## Multi-site edits: one script, one write action"),

    /** Routing table plus the turn-costing rules; recipes live in the linked articles. */
    SLIM(wire = "slim", marker = "## Route the task before reaching for a native tool"),

    ;

    /** The description text this variant serves for [context]. */
    fun readDescription(context: PromptsContext): String = when (this) {
        FULL -> ExecuteCodeToolDescriptionPromptArticle().readPayload(context)
        SLIM -> ExecuteCodeToolDescriptionSlimPromptArticle().readPayload(context)
    }

    companion object {
        /** Environment variable selecting the variant; absent or empty means [DEFAULT]. */
        const val ENV_VAR = "MCP_STEROID_EXEC_CODE_DESCRIPTION"

        val DEFAULT = FULL

        /**
         * Maps a [ENV_VAR] value to a variant: absent / blank means [DEFAULT], a known [wire] value
         * (case- and whitespace-insensitive) means that variant, and anything else throws — a typo in
         * the container env must not silently serve the default and make an A/B comparison meaningless.
         */
        fun parse(raw: String?): ExecCodeDescriptionVariant {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return DEFAULT
            return entries.firstOrNull { it.wire.equals(value, ignoreCase = true) }
                ?: error(
                    "Unknown $ENV_VAR value '$value'. Supported values: " +
                        entries.joinToString(", ") { it.wire }
                )
        }

        fun fromEnvironment(): ExecCodeDescriptionVariant = parse(System.getenv(ENV_VAR))
    }
}
