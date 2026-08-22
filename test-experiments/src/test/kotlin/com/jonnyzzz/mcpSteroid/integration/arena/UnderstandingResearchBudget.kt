/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * One Claude Code hook, as the settings file spells it: an event name and the script to run.
 *
 * A type rather than two strings, because [understandingHookSettingsJson] composes SEVERAL hooks into
 * one file and `useSettings` writes exactly one file per session — a second call overwrites the first,
 * so a research run that installed the recorder and the budget separately would silently run with only
 * whichever was installed last. That failure produces a plausible-looking run with no budget at all.
 */
data class AgentHook(val event: String, val scriptPath: String)

/**
 * The tools that do NOT consume research budget, and why each one is on the list.
 *
 * The budget counts ENVIRONMENT INTERACTIONS — the thing the two arms are being compared on — and two
 * tool names are not that:
 *
 * - `ToolSearch` is the CLI's own tool-discovery mechanism. The mcp arm spends its first one to three
 *   calls on it plus `steroid_list_projects` before it can address the IDE at all (measured on all
 *   five round-3 mcp captures: calls 1-3 are always bootstrap), while the shell arm needs none. At a
 *   budget of five, charging for it would hand the shell arm a 60% larger effective budget and the
 *   experiment would measure the CLI's tool plumbing instead of semantic access.
 * - `TodoWrite` writes the agent's own scratch list. It reads nothing from the repository.
 *
 * Everything else counts, including `steroid_list_projects`: it does query the environment, and an arm
 * that must spend one interaction learning where its project is has really spent one.
 */
val UNDERSTANDING_BUDGET_EXEMPT_TOOLS: List<String> = listOf("ToolSearch", "TodoWrite")

/**
 * The message the agent is shown when it reaches for tool number `budget + 1`.
 *
 * It has to do two things at once: stop the exploration and tell the agent what to do INSTEAD, because
 * an agent that is only refused will retry the same call with a different phrasing (round 3's shell arm
 * re-formulated one grep ten times). Every denial is counted, so an agent that keeps pushing is visible
 * in the record rather than merely expensive.
 */
const val UNDERSTANDING_BUDGET_EXHAUSTED_MESSAGE: String =
    "RESEARCH BUDGET EXHAUSTED. You have used every environment interaction allowed for this task. " +
        "No further tool call will succeed, and retrying with different arguments will not help. " +
        "Stop exploring now and write your hand-off note as your final message, between the " +
        "$UNDERSTANDING_NOTE_OPEN_MARKER and $UNDERSTANDING_NOTE_CLOSE_MARKER markers, using only " +
        "what you have already seen."

/**
 * The `PreToolUse` hook that enforces the research budget.
 *
 * PRE and not POST: a post hook can only observe a call that already ran, so the (budget + 1)-th
 * interaction would have been paid for and — worse — its output would already be in the model's
 * context, which is precisely the quantity the experiment holds fixed.
 *
 * Exit code 2 is the whole mechanism. Claude Code treats it as "the hook refused this call" and feeds
 * the hook's STDERR back to the model as the reason; every other exit code lets the call through. So
 * the script must exit 0 on its own failures (a missing counter file, an unparsable payload): an
 * instrument that cannot count must not silently turn into an instrument that blocks everything, which
 * would look exactly like an agent that gave up after one call.
 *
 * The counter is incremented only for calls that are ALLOWED, so `budget` interactions really happen —
 * incrementing first and comparing after would spend one interaction on the denial itself.
 */
fun understandingBudgetHookScript(
    budget: Int,
    counterFile: String,
    deniedFile: String,
    recordDir: String,
    exemptTools: List<String> = UNDERSTANDING_BUDGET_EXEMPT_TOOLS,
): String {
    require(budget > 0) { "a research budget must be positive, got $budget" }
    val d = "${'$'}"
    val exemptPattern = exemptTools.joinToString("|")
    return """
        #!/bin/sh
        # Research-budget gate: allow exactly $budget environment interactions, then refuse every
        # further tool call with exit code 2 so the CLI hands the reason back to the model.
        # Nothing is written to stdout: the agent CLI owns that channel.
        payload=${d}(cat)
        name=${d}(printf '%s' "${d}payload" | sed -n 's/.*"tool_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)

        # Every attempt is recorded before any decision, exempt or not: the record is how the analysis
        # knows WHICH tools an arm reached for, and the denied attempts are a measurement of their own.
        # Truncated, because a tool_input can carry a whole file. See HOOK_RECORD_MAX_BYTES.
        seq=${d}(( ${d}(cat $recordDir/attempts 2>/dev/null || echo 0) + 1 ))
        echo "${d}seq" > $recordDir/attempts
        echo "${d}name" >> $recordDir/tools.log
        printf '%s' "${d}payload" | head -c $HOOK_RECORD_MAX_BYTES > $recordDir/call-${d}seq$HOOK_RECORD_SUFFIX ||
            echo "understanding budget hook: could not record attempt ${d}seq" >&2

        case "${d}name" in
            $exemptPattern) exit 0 ;;
        esac

        used=${d}(cat $counterFile 2>/dev/null || echo 0)
        case "${d}used" in
            ''|*[!0-9]*) exit 0 ;;
        esac

        if [ "${d}used" -ge $budget ]; then
            denied=${d}(( ${d}(cat $deniedFile 2>/dev/null || echo 0) + 1 ))
            echo "${d}denied" > $deniedFile
            echo "$UNDERSTANDING_BUDGET_EXHAUSTED_MESSAGE" >&2
            exit 2
        fi

        echo ${d}(( ${d}used + 1 )) > $counterFile
        exit 0
    """.trimIndent() + "\n"
}

/**
 * One settings file that registers every hook a run needs.
 *
 * `matcher = "*"` on each event for the reason [checkpointHookSettingsJson] documents: a matcher that
 * named tools would drop whatever the list forgot, and the MCP tool names differ per arm — which is
 * the one difference that must never change what the instrument counts.
 *
 * [eagerMcpTools] turns OFF the CLI's lazy tool discovery, and it is not a tuning knob: it is the fix
 * for a defect the first acquisition pilot recorded in the open. Claude Code hands the model a tool
 * INDEX rather than the MCP tool schemas — every run's `system/init` event carries
 * `mcp_servers: [{name: mcp-steroid, status: pending}]` and a `tools` list with no `mcp__…` entry — and
 * the schemas arrive only after the model spends a `ToolSearch` call. Two of the three mcp trajectories
 * never spent it, despite a brief that tells them to list their tools first, and ran to completion on
 * `Bash` alone. Their transcripts are indistinguishable from the control arm's, so the cell labelled
 * "with semantic access" measured a coin flip about whether the model remembered to go looking.
 *
 * The switch is [ENABLE_TOOL_SEARCH_KEY] inside the settings file's `env` block, and BOTH halves of
 * that sentence were learned the expensive way. Written at the top level as `enable_tool_search`, the
 * CLI ignores it silently: three re-run trajectories reported the flag as installed and still came back
 * `{Bash=24}`, `{Bash=28}`, `{Bash=24}` with `semantic=0`. And the value is the counter-intuitive one —
 * `"false"` means "do NOT defer", i.e. load every tool up front, which is precisely what this arm needs.
 */
fun understandingHookSettingsJson(hooks: List<AgentHook>, eagerMcpTools: Boolean = false): String {
    require(hooks.isNotEmpty()) { "a settings file with no hooks would run the agent unrecorded" }
    val settings = buildJsonObject {
        if (eagerMcpTools) {
            putJsonObject("env") { put(ENABLE_TOOL_SEARCH_KEY, "false") }
        }
        putJsonObject("hooks") {
            hooks.groupBy { it.event }.forEach { (event, forEvent) ->
                putJsonArray(event) {
                    addJsonObject {
                        put("matcher", "*")
                        putJsonArray("hooks") {
                            forEvent.forEach { hook ->
                                addJsonObject {
                                    put("type", "command")
                                    put("command", hook.scriptPath)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    return understandingSettingsJson.encodeToString(JsonObject.serializer(), settings)
}

/**
 * What the budget hook counted, read back from the two files it maintains.
 *
 * [denied] is not an error count: it is the measurement of how much exploration the agent still WANTED
 * when the wall arrived, and a research budget where every run ends with dozens of denials is a budget
 * chosen too small to be informative.
 */
data class UnderstandingBudgetUsage(val used: Int, val denied: Int) {
    fun describe(budget: Int): String = "$used/$budget interactions used, $denied refused after the wall"
}

/**
 * Parses the counter files the hook writes; an absent file reads as zero.
 *
 * Zero and "missing" are deliberately NOT distinguished here: the files are created by the first hook
 * invocation, so a missing counter means the agent never called a budgeted tool. The research flow
 * refuses such a run separately, with the whole run's facts in hand, instead of guessing here.
 */
fun parseUnderstandingBudgetUsage(counterFileContent: String?, deniedFileContent: String?): UnderstandingBudgetUsage =
    UnderstandingBudgetUsage(
        used = counterFileContent?.trim()?.toIntOrNull() ?: 0,
        denied = deniedFileContent?.trim()?.toIntOrNull() ?: 0,
    )

/**
 * The environment variable Claude Code reads to decide whether MCP tool schemas are deferred.
 *
 * Named here rather than inlined so the test that pins the settings shape and the code that writes it
 * cannot drift apart into a passing test for a key the CLI never sees — which is exactly the failure
 * the previous attempt shipped.
 */
const val ENABLE_TOOL_SEARCH_KEY: String = "ENABLE_TOOL_SEARCH"

private val understandingSettingsJson = Json { prettyPrint = true }
