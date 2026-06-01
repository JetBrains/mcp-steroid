/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.ProjectHomeDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

/**
 * Prompt-corpus regression tests for the DPAIA failure clusters #47, #48, #51.
 *
 * Each test hands the Claude agent a *neutrally phrased* task that would
 * naturally tempt the failing pattern from the cited GitHub issue, then
 * inspects the **actual `steroid_execute_code` script bodies** the agent
 * submitted (parsed from the raw NDJSON agent transcript). The assertions
 * intentionally do NOT match the agent's final text, because the agent
 * routinely fetches the relevant skill articles via `steroid_fetch_resource`
 * and echoes their contents into the transcript -- any check against the
 * combined transcript would pass on article echo regardless of whether the
 * agent actually called the helper. Script-body inspection is the
 * regression-detection contract.
 *
 * Tests are outcome-lenient (LLM nondeterminism is real) but pin the
 * prompt-corpus contract: a future change that removes the "do not invent
 * buildProject" warning, the threading decision table, or the daemon-
 * internals warning would break the recipe agents are expected to follow;
 * the failing assertion message points the editor at the exact prompt content
 * that drifted.
 *
 * **End-to-end status**: this scaffold compiles cleanly but is not yet
 * validated end-to-end -- each test takes ~5--25 min (Docker IDE + Claude
 * agent) and requires `ANTHROPIC_API_KEY`. The first run on TC (or a local
 * dev with the key) will surface any marker-parsing or prompt-phrasing
 * issues; treat the first 1-2 runs as scaffold shakedown.
 *
 * @see <a href="https://github.com/jonnyzzz/mcp-steroid/issues/47">#47 -- invented helpers</a>
 * @see <a href="https://github.com/jonnyzzz/mcp-steroid/issues/48">#48 -- threading misuse</a>
 * @see <a href="https://github.com/jonnyzzz/mcp-steroid/issues/51">#51 -- daemon-highlighting internals</a>
 */
class DpaiaPromptCorpusRegressionTest {

    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `agent compiles project via supported IntelliJ build API not invented helper (issue 47)`() {
        val lifetime = CloseableStackHost()
        try {
            val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "dpaia-regression-buildProject",
                project = IntelliJProject.ThisLoggerProject,
            )).waitForProjectReady()

            val agent = session.aiAgents.claude
            // Deliberately *neutral* phrasing — never name the supported API or the forbidden
            // helpers. The point is to detect whether the corpus steers the agent; if the
            // prompt steers, we'd be testing prompt-engineering, not the corpus.
            val prompt = buildString {
                appendLine("# Task: compile this project through the IDE and report whether it builds.")
                appendLine()
                appendLine("Use the `steroid_execute_code` MCP tool to ask IntelliJ to build all modules.")
                appendLine("Do not run `./gradlew compileKotlin`, `./gradlew build`, or any Bash build -- ")
                appendLine("the build must be invoked via IntelliJ's own API from within a single")
                appendLine("`steroid_execute_code` call. Read any `mcp-steroid://` skill articles you")
                appendLine("need to find the right API.")
                appendLine()
                appendLine("## Required output (print these markers on separate lines in your final response)")
                appendLine("BUILD_INVOKED: yes")
                appendLine("BUILD_RESULT: success | errors | aborted")
            }

            session.console.writeStep("Running buildProject regression prompt")
            val result = agent.runPrompt(prompt, timeoutSeconds = 900).awaitForProcessFinish()
            val combined = result.stdout + "\n" + result.stderr

            val buildInvoked = findMarkerValue(combined, "BUILD_INVOKED")?.trim()
            check(buildInvoked.equals("yes", ignoreCase = true)) {
                "BUILD_INVOKED marker missing or not 'yes' (got '$buildInvoked'). The agent did not " +
                    "complete the task. Output:\n$combined"
            }

            val execBodies = readAgentExecCodeBodies(agent)
            check(execBodies.isNotEmpty()) {
                "No steroid_execute_code submissions captured -- check NDJSON log path."
            }

            // POSITIVE: at least one submitted script must use the supported API.
            check(execBodies.any { body ->
                body.contains("ProjectTaskManager") && body.contains("buildAllModules")
            }) {
                "Issue #47 regression: no submitted script invoked " +
                    "`ProjectTaskManager.getInstance(project).buildAllModules()`. " +
                    "The 'Real helpers vs invented names' table in " +
                    "`prompts/src/main/prompts/skill/coding-with-intellij-context-api.md` must list " +
                    "buildProject/compileProject as invented with this exact replacement. Submissions:\n" +
                    execBodies.joinToString("\n---\n")
            }

            // NEGATIVE: no submitted script may call the invented helpers as functions.
            val inventedCallRegex = Regex("""\b(buildProject|compileProject|createProjectFile)\s*\(""")
            val offending = execBodies.filter { inventedCallRegex.containsMatchIn(it) }
            check(offending.isEmpty()) {
                "Issue #47 regression: ${offending.size} submitted script(s) called an invented " +
                    "helper. Submissions:\n" + offending.joinToString("\n---\n")
            }
        } finally {
            lifetime.closeAllStacks()
        }
    }

    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `agent wraps VFS write in the correct threading wrapper (issue 48)`() {
        val lifetime = CloseableStackHost()
        try {
            val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "dpaia-regression-writeAction",
                project = IntelliJProject.ThisLoggerProject,
            )).waitForProjectReady()

            val agent = session.aiAgents.claude
            val markerLine = "// auto-generated marker for DpaiaPromptCorpusRegressionTest"
            // The native Edit/Write tools would bypass IntelliJ and our prompt corpus entirely.
            // Forbidding them keeps the test honest.
            val prompt = buildString {
                appendLine("# Task: append a single comment line to Logging.kt")
                appendLine()
                appendLine("Locate the `Logging.kt` file in this project (it defines `thisLogger`).")
                appendLine("Append exactly this line to the end of the file (preserving any final newline):")
                appendLine()
                appendLine("    $markerLine")
                appendLine()
                appendLine("The change must land through `steroid_execute_code` -- do NOT use the native")
                appendLine("`Edit` or `Write` tools, and do NOT use Bash `cat >>` or shell redirection.")
                appendLine("Read whatever `mcp-steroid://` skill articles you need to pick the right")
                appendLine("threading model for the VFS save.")
                appendLine()
                appendLine("## Required output (print these markers on separate lines in your final response)")
                appendLine("WRITE_SUCCESS: yes | no")
                appendLine("FILE_PATH: <absolute path of the file you modified>")
            }

            session.console.writeStep("Running writeAction regression prompt")
            val result = agent.runPrompt(prompt, timeoutSeconds = 900).awaitForProcessFinish()
            val combined = result.stdout + "\n" + result.stderr

            val writeSuccess = findMarkerValue(combined, "WRITE_SUCCESS")?.trim()
            check(writeSuccess.equals("yes", ignoreCase = true)) {
                "WRITE_SUCCESS marker missing or not 'yes' (got '$writeSuccess'). The agent did not " +
                    "complete the task. Output:\n$combined"
            }

            val execBodies = readAgentExecCodeBodies(agent)
            check(execBodies.isNotEmpty()) {
                "No steroid_execute_code submissions captured."
            }

            // POSITIVE: the actual write must be wrapped in writeAction (or its modality-specific
            // siblings). Regex requires the wrapper to be adjacent to a VFS-save call so a stray
            // `readAction` in the same script does not falsely pass.
            val correctlyWrappedSave = Regex(
                """(?s)(writeAction|backgroundWriteAction|edtWriteAction|writeIntentReadAction)\s*\{""" +
                    """[^}]{0,400}(VfsUtil\.saveText|setBinaryContent|getDocument\([^)]*\)\.\w+)"""
            )
            check(execBodies.any { correctlyWrappedSave.containsMatchIn(it) }) {
                "Issue #48 regression: no submitted script paired a write-action wrapper with a " +
                    "VFS save call. The decision table in " +
                    "`prompts/src/main/prompts/skill/coding-with-intellij-threading.md` must front " +
                    "this routing. Submissions:\n" + execBodies.joinToString("\n---\n")
            }
        } finally {
            lifetime.closeAllStacks()
        }
    }

    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `agent uses supported inspection helper not daemon highlighting internals (issue 51)`() {
        val lifetime = CloseableStackHost()
        try {
            val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "dpaia-regression-inspection",
                project = IntelliJProject.ThisLoggerProject,
            )).waitForProjectReady()

            val agent = session.aiAgents.claude
            val prompt = buildString {
                appendLine("# Task: report inspection problems on Logging.kt")
                appendLine()
                appendLine("Locate `Logging.kt` in this project and report every problem that IntelliJ's")
                appendLine("enabled inspections flag on that file. Use `steroid_execute_code`. Read any")
                appendLine("`mcp-steroid://` articles you need.")
                appendLine()
                appendLine("## Required output (print these markers on separate lines in your final response)")
                appendLine("PROBLEM_COUNT: <non-negative integer>")
            }

            session.console.writeStep("Running inspection regression prompt")
            val result = agent.runPrompt(prompt, timeoutSeconds = 900).awaitForProcessFinish()
            val combined = result.stdout + "\n" + result.stderr

            val problemCountStr = findMarkerValue(combined, "PROBLEM_COUNT")?.trim()
            val problemCount = problemCountStr?.takeWhile { it.isDigit() }?.toIntOrNull()
            check(problemCount != null && problemCount >= 0) {
                "PROBLEM_COUNT marker missing or not a non-negative integer (got '$problemCountStr'). " +
                    "Output:\n$combined"
            }

            val execBodies = readAgentExecCodeBodies(agent)
            check(execBodies.isNotEmpty()) {
                "No steroid_execute_code submissions captured."
            }

            // POSITIVE: at least one submitted script must use the supported helper.
            check(execBodies.any { body ->
                body.contains("runInspectionsDirectly") ||
                    body.contains("InspectionEngine.inspectEx") ||
                    body.contains("InspectionEngine.runInspectionOnFile")
            }) {
                "Issue #51 regression: no submitted script used runInspectionsDirectly or " +
                    "InspectionEngine. The boxed warning in " +
                    "`prompts/src/main/prompts/skill/coding-with-intellij-context-api.md` must point " +
                    "agents at these supported alternatives. Submissions:\n" +
                    execBodies.joinToString("\n---\n")
            }

            // NEGATIVE: no submitted script may touch the forbidden daemon internals as call sites.
            val forbiddenInternals = listOf(
                "DaemonCodeAnalyzerImpl",
                "DaemonProgressIndicator",
                "HighlightingSession",
            )
            val offending = execBodies.filter { body ->
                forbiddenInternals.any { sym -> body.contains(sym) }
            }
            check(offending.isEmpty()) {
                "Issue #51 regression: ${offending.size} submitted script(s) referenced a forbidden " +
                    "daemon-highlighting symbol. Submissions:\n" + offending.joinToString("\n---\n")
            }
        } finally {
            lifetime.closeAllStacks()
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────
    // `findMarkerValue` lives in `:test-integration` (DebuggerTestHelpers.kt) but the package-
    // private state makes a clean import awkward; the local copy below matches that contract
    // (returns the value after the *last* occurrence of `MARKER:` in `output`, trimmed).
    //
    // `readAgentExecCodeBodies` is copied from `StructuralSearchYoutrackdbPromptShared.kt` --
    // both helpers are file-private there. The function locates the latest raw NDJSON the
    // agent driver wrote, parses each line, and extracts the `code` field from every
    // `steroid_execute_code` tool call. This is the canonical "did the agent actually call X?"
    // signal -- agent text alone is unreliable because article echo is identical to API use.

    private fun findMarkerValue(output: String, marker: String): String? {
        val needle = "$marker:"
        return output.lineSequence()
            .filter { it.contains(needle) }
            .lastOrNull()
            ?.substringAfter(needle)
            ?.trim()
    }

    private fun readAgentExecCodeBodies(agent: AiAgentSession): List<String> {
        val ndjson = locateLatestRawNdjson(agent) ?: return emptyList()
        val codes = mutableListOf<String>()
        Files.newBufferedReader(ndjson).useLines { lines ->
            for (raw in lines) {
                if ('{' !in raw) continue
                val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: continue
                // Claude Code 2.1.x format: tool_use blocks inside `message.content[]`.
                val content = obj["message"]?.jsonObject?.get("content")?.let {
                    runCatching { it.jsonArray }.getOrNull()
                }
                if (content != null) {
                    for (entry in content) {
                        val item = runCatching { entry.jsonObject }.getOrNull() ?: continue
                        if (item["type"]?.jsonPrimitive?.contentOrNull != "tool_use") continue
                        val name = item["name"]?.jsonPrimitive?.contentOrNull ?: continue
                        if (!name.endsWith("steroid_execute_code")) continue
                        val code = item["input"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull
                        if (!code.isNullOrEmpty()) codes += code
                    }
                }
                // Codex format: `item.mcp_tool_call` at the top level.
                val item = obj["item"]?.let { runCatching { it.jsonObject }.getOrNull() }
                if (item != null && item["type"]?.jsonPrimitive?.contentOrNull == "mcp_tool_call") {
                    val tool = item["tool"]?.jsonPrimitive?.contentOrNull
                        ?: item["name"]?.jsonPrimitive?.contentOrNull
                    if (tool == "steroid_execute_code" || tool?.endsWith("__steroid_execute_code") == true) {
                        val args: JsonObject? = item["arguments"]?.let { runCatching { it.jsonObject }.getOrNull() }
                            ?: item["input"]?.let { runCatching { it.jsonObject }.getOrNull() }
                        val code = args?.get("code")?.jsonPrimitive?.contentOrNull
                        if (!code.isNullOrEmpty()) codes += code
                    }
                }
            }
        }
        return codes
    }

    private fun locateLatestRawNdjson(agent: AiAgentSession): Path? {
        val logsRoot = ProjectHomeDirectory.requireProjectHomeDirectory()
            .resolve("test-experiments/build/test-logs/test")
        if (!logsRoot.exists()) return null
        val agentSlug = agent.displayName.lowercase().replace(Regex("[^a-z0-9]+"), "-")
        val pattern = Regex("""agent-$agentSlug-\d+-raw\.ndjson""")
        return Files.walk(logsRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && pattern.matches(it.fileName.toString()) }.toList()
        }.maxByOrNull { Files.getLastModifiedTime(it).toMillis() }
    }
}
