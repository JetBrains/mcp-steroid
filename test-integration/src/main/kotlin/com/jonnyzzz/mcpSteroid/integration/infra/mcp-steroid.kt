/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerPort
import com.jonnyzzz.mcpSteroid.testHelper.docker.RunningContainerProcess
import com.jonnyzzz.mcpSteroid.testHelper.docker.mapGuestPortToHostPort
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.writeFileInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResultValue
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/** Build system type for project setup. Must be specified explicitly per test. */
enum class BuildSystem {
    MAVEN,
    GRADLE,
    NONE,
}

/**
 * How `steroid_execute_code` should treat IDE modality around the script — the client-side mirror of
 * the server's `modal` wire protocol values. The test infra is an MCP client, so it owns its own copy
 * of the protocol value instead of depending on the server module.
 *
 * - [SMART_NON_MODAL]: close leftover modals, require non-modal IDE, commit+save+VFS, wait for smart
 *   mode, monitor for modals during the run (default — for PSI / code-management flows).
 * - [NON_MODAL]: require non-modal at start only; no sweep / sync / smart-wait / during-run monitor.
 * - [UNLEASHED]: no checks at all; runs against whatever IDE state exists, modals included (for
 *   intentional modal workflows and trivial hardcoded actions).
 */
enum class ModalMode(val wire: String) {
    SMART_NON_MODAL("smart_non_modal"),
    NON_MODAL("non_modal"),
    UNLEASHED("unleashed"),
    ;

    companion object {
        val DEFAULT = SMART_NON_MODAL
    }
}

data class McpProjectInfo(
    /**
     * The within-IDE-unique `project_name` ROUTING KEY (opaque, `<name>-<hash>`), the value the
     * project-scoped tools (`steroid_execute_code`, …) require. NOT stable across a Gradle import:
     * the first sync renames the project from its folder name to `rootProject.name`, which changes
     * this key too — see [McpSteroidDriver.mcpExecuteCode] for the re-resolve-on-rename handling.
     */
    val projectName: String,
    /** Raw IntelliJ `Project.name` (folder name, or `rootProject.name` after import) — display only. */
    val name: String,
    val path: String,
)

data class McpWindowInfo(
    val projectName: String?,
    val projectPath: String?,
    val modalDialogShowing: Boolean,
    val indexingInProgress: Boolean?,
    val projectInitialized: Boolean?,
    val windowId: String? = null,
    val title: String? = null,
    val isVisible: Boolean? = null,
)

internal fun ProcessResult.resolveJavaHomeLookup(jdkVersion: String): String {
    val javaHome = stdout.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("/") }
    if (javaHome != null) return javaHome

    require(exitCode == 0) {
        "[COMPILE] JDK $jdkVersion not found under /usr/lib/jvm; stdout=${stdout.take(500)} stderr=${stderr.take(500)}"
    }
    error("[COMPILE] JDK $jdkVersion lookup returned no path; stdout=${stdout.take(500)} stderr=${stderr.take(500)}")
}

/**
 * Marker the server's `waitForSmartMode` emits when the IDE is still indexing — the signal to keep
 * polling (call again). Mirrors the message in `McpScriptContextImpl.waitForSmartMode`; the coupling is
 * the documented tool-result contract.
 */
internal const val INDEXING_IN_PROGRESS_MARKER = "INDEXING IN PROGRESS"

/** Overall budget for polling through "still indexing" — large projects can take a long time. */
private const val INDEXING_POLL_BUDGET_MS = 60 * 60 * 1000L

/** Pure: does this tool-result text say the IDE is still indexing (so we should call again)? */
internal fun isIndexingInProgress(text: String): Boolean = text.contains(INDEXING_IN_PROGRESS_MARKER)

/**
 * Pure: does this tool-result text say the server could not route the given `project_name`? Mirrors the
 * error `ProjectScopedToolHandler.resolveProject` raises (`Project not found: "<key>". Available
 * project_name values: …`) — the coupling is the documented tool-result contract, same as
 * [INDEXING_IN_PROGRESS_MARKER]. Matches the quoted key exactly so a script's own output can never
 * masquerade as the routing failure.
 */
fun isProjectNotFound(text: String, projectName: String): Boolean =
    text.contains("Project not found: \"$projectName\"")

/**
 * The modality-gate failure `ScriptExecutor.requireNonModalOrFail` raises, pinned to the
 * `smart_non_modal` profile. Mirrors the server text, the same documented tool-result coupling as
 * [INDEXING_IN_PROGRESS_MARKER].
 *
 * The profile is part of the marker on purpose. [TRANSIENT_MODALITY_DETAIL] is the gate's FALLBACK
 * detail, and the gate also emits it on the `non_modal` path — where no dialog sweep and no
 * dialog-less wait run at all, so an observed modality there says nothing about a race and may well
 * be a stuck dialog nobody tried to close. Only under `smart_non_modal` does that detail carry the
 * information the retry depends on: the sweep ran, the wait ran, and neither found anything.
 */
const val MODALITY_GATE_MARKER = "modal=smart_non_modal requires a non-modal IDE, but "

/**
 * The gate's variant detail for "modality was there when the gate looked, and there was nothing to
 * wait for when the pre-flight looked" — i.e. modality was entered BETWEEN the two checks. The two
 * other variants the gate can report are deliberately NOT this string: a surviving modal dialog
 * window, and a dialog-less progress that outlived the bounded wait. It is the gate's `else` branch,
 * which is why it must always be read together with [MODALITY_GATE_MARKER].
 */
const val TRANSIENT_MODALITY_DETAIL = "a modal dialog/progress is present and could not be cleared"

/**
 * The modality check `McpScriptContextImpl.requireNonModal` raises from INSIDE a running script — a
 * different failure from [MODALITY_GATE_MARKER], which `ScriptExecutor` raises BEFORE the script.
 *
 * Every guarantee the `smart_non_modal` profile gives is a point-in-time observation: the sweep
 * closed the dialogs it saw, the gate found a non-modal instant, and the script started. A
 * concurrent write-action storm can then enter modality WHILE the script runs, and the first
 * `requireNonModal`-guarded operation the script reaches (`waitForSmartMode`, `syncDocuments`, …)
 * fails on it — build `1031488960` lost its `claude+mcp` arm exactly that way, in the
 * JDK-registration setup step, with the gate's retry blind to it because the text is not the gate's.
 *
 * The text carries no `modal=` profile (the check does not know one), so unlike the gate marker it
 * cannot be judged alone — [isScriptModalityRace] pairs it with the profile the CALLER used.
 */
const val SCRIPT_MODALITY_MARKER = " requires a non-modal IDE, but a modal dialog is present."

/**
 * Pure: is this tool-result text the in-script modality race — [SCRIPT_MODALITY_MARKER] raised on a
 * call the harness made under `smart_non_modal`?
 *
 * The profile comes from [modal], not from the text. Under `smart_non_modal` the harness knows the
 * dialogs were swept and a non-modal instant existed at gate time, so modality observed later in the
 * same execution is by construction something that arrived DURING the run — transient, and worth one
 * more ask. Under `non_modal` (no sweep) and `unleashed` (no gate) that reasoning does not hold and
 * the failure must surface: it may be a stuck dialog nobody ever tried to close.
 */
fun isScriptModalityRace(text: String, modal: ModalMode): Boolean =
    modal == ModalMode.SMART_NON_MODAL && text.contains(SCRIPT_MODALITY_MARKER)

/**
 * Pure: is [text] a modality race worth re-issuing — either the gate's own
 * ([isTransientModalityRace], self-describing) or an in-script one ([isScriptModalityRace], judged
 * against the caller's [modal] profile)? The single predicate the retry loop asks.
 */
fun isRetriableModalityRace(text: String, modal: ModalMode): Boolean =
    isTransientModalityRace(text) || isScriptModalityRace(text, modal)

/**
 * How long to keep re-issuing an exec_code that lost the modality race. Chosen at the plugin's own
 * dialog-less modality bound (`mcp.steroid.execution.dialogless.modal.wait.ms`, default 120s): the
 * race window itself is milliseconds wide, so the budget only has to outlast the burst of write
 * actions that keeps re-entering modality — a project-open + build-model-import storm. Deliberately
 * NOT the hour-long [INDEXING_POLL_BUDGET_MS]: an IDE that cannot offer a single non-modal instant
 * in two minutes is a problem to surface, not to wait out, and every attempt prints a line so the
 * console never goes quiet while the budget burns.
 */
private const val MODALITY_RACE_RETRY_BUDGET_MS = 120_000L

/**
 * Pure: is this tool-result text the transient modality race — the failure mode where the
 * `smart_non_modal` pre-flight found no modality to wait for, modality was then entered by a
 * concurrent write-action storm, and the gate's own check observed it milliseconds later?
 *
 * Only that variant is transient. A modal dialog window that survived the sweep, a dialog-less
 * progress that outlived the bounded wait, and any modality seen on the `non_modal` profile (which
 * neither sweeps nor waits) are all states no retry should assume out of existence — retrying there
 * would hide a real problem (a stuck dialog, a genuinely wedged IDE) behind a silent loop, which is
 * the failure the harness's fail-fast-on-modal rules exist to prevent.
 */
fun isTransientModalityRace(text: String): Boolean =
    text.contains(MODALITY_GATE_MARKER) && text.contains(TRANSIENT_MODALITY_DETAIL)

/**
 * Pure: the start of the modality-race window after an attempt that either was ([isRaceAttempt]) or
 * was not a race.
 *
 * The window covers the CURRENT storm, not the whole call. An attempt that is not a race clears it,
 * because the retries in between are other conditions entirely — an `INDEXING IN PROGRESS` poll can
 * legitimately run for minutes after the gate passes, and charging that time to a storm that has
 * since ended would refuse a fresh storm on its very first loss. Total time stays bounded: the
 * enclosing `waitForValue` budget caps the call no matter how the windows alternate.
 */
fun modalityRaceWindowStart(currentStartMs: Long?, isRaceAttempt: Boolean, nowMs: Long): Long? =
    if (!isRaceAttempt) null else currentStartMs ?: nowMs

/**
 * Pure: should an exec_code that came back with [text] be re-issued, given how long the caller has
 * been losing the CURRENT storm's races ([modalityRaceWindowStart])? Bounded by [budgetMs] so an
 * unrecoverable state fails with the ORIGINAL gate error instead of looping — the caller returns the
 * failing result untouched once this says no.
 */
fun shouldRetryModalityRace(
    text: String,
    elapsedSinceFirstRaceMs: Long,
    budgetMs: Long = MODALITY_RACE_RETRY_BUDGET_MS,
    modal: ModalMode = ModalMode.DEFAULT,
): Boolean = isRetriableModalityRace(text, modal) && elapsedSinceFirstRaceMs < budgetMs

/** The message the plugin logs (SteroidsMcpServer) when its MCP web server cannot start. */
internal const val MCP_SERVER_STARTUP_FAILURE_MARKER = "Failed to start MCP server"

/**
 * Returns the first IDE-log line that reports the MCP web server failed to start, or null. The plugin
 * logs [MCP_SERVER_STARTUP_FAILURE_MARKER] when it cannot bind its server (busy ports, a startup
 * exception, etc.). We key on that symptom — "the web server did not come up" — not on any specific root
 * cause, so the check is independent of why it failed.
 */
internal fun findMcpServerStartupFailure(logLines: List<String>): String? =
    logLines.firstOrNull { it.contains(MCP_SERVER_STARTUP_FAILURE_MARKER) }

/**
 * Thrown when an MCP request's curl was killed (exit -1) because the IDE was too busy — saturated by a
 * big-project import/indexing — to answer in time. It means "still busy, call again": a plain [Exception]
 * (NOT a [WaitAbortedError]) so [waitFor] swallows-and-retries it, and the hand-rolled project-open
 * poll loops that `catch (Exception)` retry it too. The silent twin of the clean `INDEXING IN PROGRESS`
 * marker ([isIndexingInProgress]). A *script-level* error (compile/runtime) instead comes back as a clean
 * isError result with a real exit code, never this killed-process -1, so it is not transient and surfaces
 * immediately. The request layer ([McpSteroidDriver] curl handling) throws this type directly, so the
 * retry policy is implicit for every MCP call. Its fatal twin is [McpRequestFailedError]. See
 * jonnyzzz/mcp-steroid#169.
 */
class TransientMcpRequestException(message: String) : RuntimeException(message)

/**
 * Thrown when an MCP request genuinely failed: curl could not reach the server (a non-`-1` non-zero exit —
 * curl uses no `--max-time`, so our process timeout is the only thing that kills a *busy* server with -1;
 * any other non-zero exit means connection refused / unreachable), or the server returned a malformed HTTP
 * envelope. A [WaitAbortedError] (an [Error]), so [waitFor] stops the loop at once — a full MCP failure
 * fails the test immediately instead of being retried for the whole indexing budget, and a `catch
 * (Exception)` retry loop does not swallow it. This is the fatal twin of the transient
 * [TransientMcpRequestException]; we expect it only on a real crash, which is rare, so we deliberately do
 * not retry it. The request layer throws this type directly, so the policy is implicit for every MCP call.
 */
class McpRequestFailedError(message: String) : WaitAbortedError(message)

/**
 * Parse an MCP response body, or fail terminally. A malformed/non-JSON envelope is a protocol breakage,
 * not a busy IDE — so it throws [McpRequestFailedError] (an [Error]) to stop a [waitFor] at once rather
 * than letting a deterministic parse failure be retried for the whole indexing budget. Used by every MCP
 * request-parse boundary reachable from `mcpExecuteCode`'s poll (`mcpInitialize`, `executeMcpRequest`), so
 * the "every terminal path is typed" invariant the typed-retry design relies on actually holds.
 */
fun parseMcpResponseOrFail(body: String): JsonElement =
    try {
        Json.parseToJsonElement(body)
    } catch (e: SerializationException) {
        throw McpRequestFailedError("Malformed JSON in MCP response: ${e.message}")
    }

/**
 * The body of a `tools/call` MCP response — its content texts, newline-joined — or throw. The body is
 * returned whether the tool reported success or an error (an error text like `INDEXING IN PROGRESS` or a
 * compile failure IS the payload the caller inspects); [parseMcpToolResultIsError] reads the error flag.
 * A *valid-JSON-but-wrong-shape* envelope (e.g. `result` is a string, `content` is not an array) is a
 * protocol breakage, not a busy IDE — any kotlinx accessor type-mismatch (an [IllegalArgumentException])
 * throws a terminal [McpRequestFailedError] rather than a plain exception a [waitFor] would retry for the
 * whole indexing budget; a non-JSON body throws the same via [parseMcpResponseOrFail]. *Missing* optional
 * fields degrade gracefully (empty body), preserving the normal "script returned an error result" path.
 */
fun parseMcpToolResultBody(response: String): String =
    try {
        buildString {
            parseMcpResponseOrFail(response).jsonObject["result"]?.jsonObject?.get("content")?.jsonArray?.forEach { item ->
                item.jsonObject["text"]?.jsonPrimitive?.contentOrNull?.let { appendLine(it) }
            }
        }
    } catch (e: IllegalArgumentException) {
        throw McpRequestFailedError("Malformed MCP tool response shape: ${e.message}")
    }

/**
 * The `isError` flag of a `tools/call` MCP response (a missing `result`/`isError` counts as an error —
 * the graceful "script returned an error result" path). Same terminal typing as [parseMcpToolResultBody]:
 * wrong shape → [McpRequestFailedError].
 */
fun parseMcpToolResultIsError(response: String): Boolean =
    try {
        parseMcpResponseOrFail(response).jsonObject["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.booleanOrNull ?: true
    } catch (e: IllegalArgumentException) {
        throw McpRequestFailedError("Malformed MCP tool response shape: ${e.message}")
    }

class McpSteroidDriver(
    val driver: ContainerDriver,
    val ijDriver: IntelliJDriver,
    /**
     * The IDE process this MCP server lives in — the liveness signal for [waitForMcpReady]. Passed in
     * from [IntelliJDriver.startIde]'s return value (the driver stays stateless — it can start multiple
     * container processes and holds no mutable process field); kept private so the process-level surface
     * does not leak past this driver.
     */
    private val ideProcess: RunningContainerProcess,
) {
    companion object {
        val MCP_STEROID_PORT = ContainerPort(6754)
        private const val SESSION_HEADER = "Mcp-Session-Id"
    }

    private val json = Json { prettyPrint = true }

    val guestMcpUrl = "http://localhost:${MCP_STEROID_PORT.containerPort}/mcp"
    val hostMcpUrl get() = "http://localhost:${driver.mapGuestPortToHostPort(MCP_STEROID_PORT)}/mcp"

    fun waitForMcpReady() {
        waitFor(300_000, "MCP Steroid server ready") {
            // Dead-IDE/dead-container fail-fast: a dead IDE process can never serve MCP, but its symptoms —
            // `docker exec` exiting non-zero (125 on a dead container) or curl connection-refused — are
            // indistinguishable from "server still starting", so they alone must keep retrying. The process
            // liveness (`kill -0` via docker exec; also false when the whole container is gone) is the real
            // signal: when it drops, log the process details and stop the 300s poll at once instead of
            // burning it to the deadline (quorum follow-up to the typed-retry rework).
            if (!ideProcess.isRunning()) {
                ideProcess.printProcessInfo() // exit code + output tails, logged by the process itself
                throw McpRequestFailedError("IDE died while waiting for the MCP Steroid server")
            }

            // The container interactions here are terminal-by-default: reading the IDE log and running the
            // health-check curl both go through `docker exec`, which THROWS if the container has died — a
            // terminal infrastructure failure, so we map it to McpRequestFailedError (an Error) and the wait
            // stops at once instead of polling to the 300s deadline. A still-STARTING server is NOT this: it
            // keeps the container alive and merely makes curl exit nonzero (handled below as "not ready →
            // retry"). The other fail-fast signal is the startup-failure log marker (WaitAbortedError, an
            // Error too — not caught by the `catch (Exception)` below, so it also propagates).
            val healthCheckExit = try {
                findMcpServerStartupFailure(ijDriver.readLogs())?.let { line ->
                    throw WaitAbortedError(
                        "MCP Steroid web server failed to start in ${ijDriver.ideProduct.displayName}: $line",
                    )
                }
                driver.startProcessInContainer {
                    this
                        .args("curl", "-s", "-f", guestMcpUrl, "-H", "Accept: application/json")
                        .timeoutSeconds(5)
                        .quietly()
                        .description("curl health check $guestMcpUrl")
                }.awaitForProcessFinish().exitCode
            } catch (e: Exception) {
                throw McpRequestFailedError("MCP health-check transport failed (${e.javaClass.simpleName}): ${e.message}")
            }

            // The nullable resolveProjectName(dir) overload returns null while the project is simply not open
            // yet (→ retry, the normal startup case) but PROPAGATES a terminal McpRequestFailedError from the
            // MCP call (→ stop). Using it instead of `runCatching { resolveProjectName() }` — which would
            // swallow even that Error and retry to the 300s deadline — keeps the terminal-by-type contract.
            // A transient busy (-1) stays a plain TransientMcpRequestException that waitFor retries.
            healthCheckExit == 0 && resolveProjectName(ijDriver.getGuestProjectDir()) != null
        }

        mcpInitialize()
        resolveProjectName()

        println("[IDE-AGENT] MCP Steroid is ready in the container at $guestMcpUrl")
        println("[IDE-AGENT] MCP Steroid is ready in the host at $hostMcpUrl")
    }


    /**
     * List all open projects in the IDE via steroid_list_projects tool.
     */
    fun mcpListProjects(): List<McpProjectInfo> {
        val sessionId = mcpInitialize()

        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 2)
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "steroid_list_projects")
                putJsonObject("arguments") { }
            }
        }.toString()

        val payload = parseMcpToolResultBody(executeMcpRequest(sessionId, request)).trim()

        // A malformed/missing projects payload is a deterministic protocol breakage, not a busy IDE: type it
        // as McpRequestFailedError so a poll (waitForMcpReady) stops at once instead of retrying to its budget.
        return try {
            parseMcpResponseOrFail(payload).jsonObject["projects"]
                ?.jsonArray
                ?.map {
                    McpProjectInfo(
                        projectName = it.jsonObject["project_name"]?.jsonPrimitive?.contentOrNull
                            ?: throw McpRequestFailedError("steroid_list_projects entry missing 'project_name': $payload"),
                        name = it.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                            ?: throw McpRequestFailedError("steroid_list_projects entry missing 'name': $payload"),
                        path = it.jsonObject["path"]?.jsonPrimitive?.contentOrNull
                            ?: throw McpRequestFailedError("steroid_list_projects entry missing 'path': $payload"),
                    )
                }
                ?: throw McpRequestFailedError("steroid_list_projects returned no projects payload: $payload")
        } catch (e: IllegalArgumentException) {
            throw McpRequestFailedError("steroid_list_projects malformed payload: ${e.message}")
        }
    }

    /**
     * The current `project_name` ROUTING KEY of the project at the guest project directory.
     *
     * Resolves by PATH (rename-immune), but the returned KEY itself is invalidated when Gradle's
     * first sync renames the project (folder name → `rootProject.name` — the key hashes the name);
     * treat it as a snapshot, not a constant. [mcpExecuteCode] re-resolves on that rename.
     */
    fun resolveProjectName(): String {
        val guestProjectDir = ijDriver.getGuestProjectDir()
        return resolveProjectName(guestProjectDir) ?: error("Project is not open: $guestProjectDir")
    }

    /**
     * List all open IDE windows with project/indexing/modal status.
     */
    fun mcpListWindows(timeoutSeconds: Long = 120): List<McpWindowInfo> {
        val sessionId = mcpInitialize()

        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 2)
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "steroid_list_windows")
                putJsonObject("arguments") { }
            }
        }.toString()

        val payload = parseMcpToolResultBody(executeMcpRequest(sessionId, request, timeoutSeconds = timeoutSeconds)).trim()

        // A malformed/missing windows payload is a deterministic protocol breakage, not a busy IDE: type it as
        // McpRequestFailedError so waitForIdeWindow's `catch (Exception)` poll does not retry it to the deadline
        // (it lets this Error fail fast). The normal "not ready yet" path returns a valid windows list, never an
        // exception, so this does not affect legitimate polling.
        return try {
            parseMcpResponseOrFail(payload).jsonObject["windows"]
                ?.jsonArray
                ?.map {
                    val window = it.jsonObject
                    McpWindowInfo(
                        // snake_case keys since #381 — the shared ListedWindow contract (project_name/project_path).
                        projectName = window["project_name"]?.jsonPrimitive?.contentOrNull,
                        projectPath = window["project_path"]?.jsonPrimitive?.contentOrNull,
                        modalDialogShowing = window["modalDialogShowing"]?.jsonPrimitive?.booleanOrNull ?: false,
                        indexingInProgress = window["indexingInProgress"]?.jsonPrimitive?.booleanOrNull,
                        projectInitialized = window["projectInitialized"]?.jsonPrimitive?.booleanOrNull,
                        windowId = window["windowId"]?.jsonPrimitive?.contentOrNull,
                        title = window["title"]?.jsonPrimitive?.contentOrNull,
                        isVisible = window["isVisible"]?.jsonPrimitive?.booleanOrNull,
                    )
                }
                ?: throw McpRequestFailedError("steroid_list_windows returned no windows payload: $payload")
        } catch (e: IllegalArgumentException) {
            throw McpRequestFailedError("steroid_list_windows malformed payload: ${e.message}")
        }
    }

    /**
     * Open a project directory in IntelliJ IDEA via steroid_open_project.
     * Call this during the pre-warm phase (before the measured agent run).
     */
    fun mcpOpenProject(projectPath: String, trustProject: Boolean? = true) {
        val sessionId = mcpInitialize()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 2)
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "steroid_open_project")
                putJsonObject("arguments") {
                    put("task_id", "prewarm-open-project")
                    put("project_path", projectPath)
                    put("reason", "Pre-warm: open arena project before measured agent run")
                    if (trustProject != null) {
                        put("trust_project", trustProject)
                    }
                }
            }
        }.toString()
        val response = executeMcpRequest(sessionId, request, timeoutSeconds = 60)
        val responseJson = json.parseToJsonElement(response).jsonObject
        val isError = responseJson["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.booleanOrNull == true
        if (isError) {
            val errorText = responseJson["result"]?.jsonObject?.get("content")?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: "unknown error"
            error("steroid_open_project failed: $errorText")
        }
    }

    private fun resolveProjectName(projectPath: String): String? {
        // Match by PATH (stable across the Gradle import rename), return the project_name routing
        // key — the value the server-side ProjectScopedToolHandler resolves FIRST and unambiguously.
        // The raw `name` is only an ambiguity-prone fallback on the server (#92).
        return mcpListProjects().firstOrNull { it.path == projectPath }?.projectName
    }

    /**
     * Open README.md (or fallback source file) in the editor and show the Maven/Gradle tool window.
     *
     * Helps AI agents orient themselves from the IDE view immediately after project import.
     * All operations are best-effort — failures are logged but do not propagate.
     */
    fun mcpOpenFileAndBuildToolWindow(openFileOnStart: String? = null) {
        // Escape the openFileOnStart path for embedding in Kotlin string template
        val filePathLiteral = if (openFileOnStart != null) {
            "\"$openFileOnStart\""
        } else {
            "null"
        }

        val code = """
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.withContext

// 1. Open a file for agent orientation.
// Use refreshAndFindFileByPath so VFS content is loaded from disk —
// git clone happened outside IntelliJ's file watcher, so findFileByPath
// may return a VirtualFile whose content cache is empty (black editor).
// Skip files > 10 KB — large README.md files (e.g. JHipster) cause the
// Markdown preview renderer to hang the IDE during startup.
val basePath = project.basePath ?: ""
val openFileRelPath: String? = $filePathLiteral
val maxFileSize = 10_000L

val fileToOpen = if (openFileRelPath != null) {
    val targetPath = "${'$'}basePath/${'$'}openFileRelPath"
    LocalFileSystem.getInstance().refreshAndFindFileByPath(targetPath)
} else {
    // Fallback chain: README.md (if small), then first small source file
    val baseDir = java.io.File(basePath)
    val readme = java.io.File(basePath, "README.md")
    if (readme.exists() && readme.length() <= maxFileSize) {
        LocalFileSystem.getInstance().refreshAndFindFileByPath(readme.absolutePath)
    } else {
        val sourceFile = baseDir.walkTopDown()
            .filter { it.isFile && it.length() <= maxFileSize }
            .filter { it.extension in listOf("java", "kt", "ts", "js") }
            .firstOrNull()
        if (sourceFile != null) {
            LocalFileSystem.getInstance().refreshAndFindFileByPath(sourceFile.absolutePath)
        } else {
            null
        }
    }
}

if (fileToOpen != null) {
    withContext(Dispatchers.EDT) {
        FileEditorManager.getInstance(project).openFile(fileToOpen, true)
        println("[UX-SETUP] Opened file: ${'$'}{fileToOpen.path}")
    }
} else {
    println("[UX-SETUP] No file found to open (configured=${'$'}openFileRelPath)")
}

// 2. Show the Commit tool window (local changes) — more useful for agents than
// the build tool window, and avoids the Markdown preview hang issue.
withContext(Dispatchers.EDT) {
    try {
        ToolWindowManager.getInstance(project).getToolWindow("Commit")?.show()
        println("[UX-SETUP] Commit tool window shown")
    } catch (e: Exception) {
        println("[UX-SETUP] Could not show Commit tool window: ${'$'}{e.message}")
    }
}

// 3. Show Maven or Gradle tool window depending on what build file exists
val pomFile = java.io.File(basePath, "pom.xml")
val gradleFile = java.io.File(basePath, "build.gradle")
val gradleKtsFile = java.io.File(basePath, "build.gradle.kts")

withContext(Dispatchers.EDT) {
    try {
        when {
            pomFile.exists() -> {
                ToolWindowManager.getInstance(project).getToolWindow("Maven")?.show()
                println("[UX-SETUP] Maven tool window shown")
            }
            gradleFile.exists() || gradleKtsFile.exists() -> {
                ToolWindowManager.getInstance(project).getToolWindow("Gradle")?.show()
                println("[UX-SETUP] Gradle tool window shown")
            }
            else -> println("[UX-SETUP] No pom.xml or build.gradle found — skipping build tool window")
        }
    } catch (e: Exception) {
        println("[UX-SETUP] Could not show build tool window: ${'$'}{e.message}")
    }
}

// 3. Expand project tree root node (best-effort)
try {
    withContext(Dispatchers.EDT) {
        ProjectView.getInstance(project).currentProjectViewPane?.tree?.expandRow(0)
        println("[UX-SETUP] Project tree root expanded")
    }
} catch (e: Exception) {
    println("[UX-SETUP] Could not expand project tree: ${'$'}{e.message}")
}

"done"
""".trimIndent()

        try {
            mcpExecuteCode(
                code = code,
                reason = "Open project file and build tool window for agent orientation",
                timeout = 30,
            )
        } catch (e: Exception) {
            println("[UX-SETUP] Warning: UX setup failed: ${e.message}")
        }
    }

    /**
     * Execute Kotlin code via steroid_execute_code tool.
     *
     * This makes a direct HTTP call to the MCP server, bypassing AI agents.
     * Useful for integration tests that need reliable, deterministic behavior.
     *
     * @param code Kotlin code to execute (suspend function body)
     * @param taskId Task identifier (default: "integration-test")
     * @param reason Human-readable reason for execution
     * @param timeout Timeout in seconds (default: 600)
     * @param projectName Explicit `project_name` routing key, or null (default) for "the project at
     *        the guest project dir" — resolved when the call starts and RE-resolved when the Gradle
     *        import rename invalidates the key mid-call (see below)
     * @return MCP tool result as JSON string
     *
     * If the IDE is still indexing, each call waits a short window and returns an "INDEXING IN PROGRESS"
     * result; we poll exactly as an agent would (call again), since indexing always makes progress and a
     * large project can legitimately take a long time. Polling is bounded by [INDEXING_POLL_BUDGET_MS].
     */
    fun mcpExecuteCode(
        code: String,
        taskId: String = "integration-test",
        reason: String = "Integration test execution",
        timeout: Int = 600,
        projectName: String? = null,
        /**
         * How exec_code treats IDE modality around the script. Mindfully defaulted to [ModalMode.DEFAULT]
         * and always sent explicitly on the wire, so every driver-issued exec_code makes a deliberate
         * modality choice rather than relying on the server's implicit default.
         */
        modal: ModalMode = ModalMode.DEFAULT,
    ): ProcessResult {
        // Resolved eagerly (not per attempt) so a genuinely-not-open project still fails fast here
        // instead of being retried for the whole indexing budget.
        var effectiveProjectName = projectName ?: resolveProjectName()

        // Start of the CURRENT storm's race window, or null when the last attempt was not a race —
        // see [modalityRaceWindowStart]. Measuring from the start of the call would charge the
        // window for minutes of legitimate indexing polls; never resetting it would charge a fresh
        // storm for a race that ended long before.
        var modalityRaceWindowStartMs: Long? = null

        // Retry-while-busy, the same way an agent would: just call again. The exception types from the
        // request layer drive the wait, so no try/catch is needed here: a transient TransientMcpRequestException
        // (IDE too busy to answer, curl killed, exit -1) is a plain exception that waitFor swallows-and-retries,
        // while a fatal McpRequestFailedError (a WaitAbortedError) stops the loop at once instead of
        // retrying a genuine crash for the whole budget. The last "busy" signal is a clean INDEXING IN
        // PROGRESS result → null → call again.
        return waitForValue(INDEXING_POLL_BUDGET_MS, "exec_code '$taskId' to run (IDE busy with import/indexing)") {
            val result = mcpExecuteCodeOnce(code, taskId, reason, timeout, effectiveProjectName, modal)
            val isRace = result.exitCode != 0 && isRetriableModalityRace(result.stdout, modal)
            // Stamped (or cleared) on EVERY attempt, before the branches: an attempt that is not a
            // race ends the current storm's window, so the next storm starts its budget fresh.
            modalityRaceWindowStartMs =
                modalityRaceWindowStart(modalityRaceWindowStartMs, isRace, System.currentTimeMillis())
            when {
                isIndexingInProgress(result.stdout) -> null // still indexing → call again

                // Gradle's first sync renames the project (folder name → rootProject.name, e.g.
                // project-home → demo-project), which changes the project_name routing key mid-run —
                // a key resolved before the rename stops routing and the server answers "Project not
                // found" (#412). Only when the caller did NOT pin a name: re-resolve by path, and if
                // the key genuinely changed retry with the fresh one. Any other not-found (key
                // unchanged, project gone, or an explicitly pinned name) stays a hard failure.
                projectName == null && result.exitCode != 0 && isProjectNotFound(result.stdout, effectiveProjectName) -> {
                    val freshName = resolveProjectName(ijDriver.getGuestProjectDir())
                    if (freshName != null && freshName != effectiveProjectName) {
                        println("[MCP] project_name '$effectiveProjectName' was renamed to '$freshName' (build-system import) — retrying")
                        effectiveProjectName = freshName
                        null // retry with the fresh routing key
                    } else result
                }

                // The `smart_non_modal` pre-flight checks for modality to wait out, then the gate
                // requires non-modality — and a write-action storm (project open + build-model
                // import on a 189-module project) can enter modality between the two. The wait sees
                // nothing to wait for, the gate sees modality, and no budget can close a race: the
                // only fix is to ask again for another instant. Bounded, logged, and only for the
                // transient variant, and only under the profile that actually swept and waited —
                // see [isTransientModalityRace]. The same storm can also enter modality one step
                // later, while the script already runs, and fail an in-script `requireNonModal`
                // operation instead of the gate — [isScriptModalityRace], same remedy. The budget
                // covers the current storm, not the whole call. On exhaustion the failing result is
                // returned as-is, so the caller's
                // assertExitCode reports the original gate error with its screenshot and
                // thread-dump pointers.
                isRace -> {
                    val windowStart = modalityRaceWindowStartMs ?: System.currentTimeMillis()
                    val elapsed = System.currentTimeMillis() - windowStart
                    if (shouldRetryModalityRace(result.stdout, elapsed, modal = modal)) {
                        println("[MCP] exec_code '$taskId' lost the modality race (IDE entered modality " +
                            "after the pre-flight wait, at the gate or inside the script) — retrying, " +
                            "${elapsed}ms spent so far")
                        null
                    } else {
                        println("[MCP] exec_code '$taskId' kept losing the modality race for ${elapsed}ms — " +
                            "giving up and failing with the IDE's own gate error")
                        result
                    }
                }

                else -> result
            }
        }
    }

    private fun mcpExecuteCodeOnce(
        code: String,
        taskId: String,
        reason: String,
        timeout: Int,
        projectName: String,
        modal: ModalMode,
    ): ProcessResult {
        // First, initialize MCP session
        val sessionId = mcpInitialize()

        // Build the tool call request using kotlinx.serialization
        val toolCallRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 2)
            putJsonObject("params") {
                put("name", "steroid_execute_code")
                putJsonObject("arguments") {
                    put("project_name", projectName)
                    put("code", code)
                    put("task_id", taskId)
                    put("reason", reason)
                    put("timeout", timeout)
                    put("modal", modal.wire)
                }
            }
            put("method", "tools/call")
        }.toString()

        // Execute the tool call (curl timeout must exceed the server-side execution timeout)
        val run = executeMcpRequest(sessionId, toolCallRequest, timeoutSeconds = timeout.toLong() + 30)
        val body = parseMcpToolResultBody(run)
        body.lineSequence().filter { it.isNotBlank() }.forEach { println("[MCP LOG]: $it ") }
        return ProcessResultValue(
            exitCode = if (parseMcpToolResultIsError(run)) 1 else 0,
            stdout = body,
            stderr = "",
        )
    }

    /**
     * Fetch a `mcp-steroid://` resource by URI via the `steroid_fetch_resource` tool.
     *
     * Like [mcpExecuteCode], this makes a direct MCP call (no AI agent), so a test can
     * deterministically assert that an article resolves for the running IDE. The handler gates
     * each article on `IdeFilter.matches(productCode)`, where `productCode` comes from the
     * running IDE's `ApplicationInfo` — so fetching in a non-IDEA IDE is an end-to-end check
     * that the article is genuinely un-gated for that product.
     *
     * @return [ProcessResult] with exitCode 0 + the article payload on success; exitCode 1 +
     *         `ERROR: Resource not found: <uri>` when the article filter rejects the running IDE.
     */
    fun mcpFetchResource(
        uri: String,
        projectName: String = resolveProjectName(),
        timeout: Int = 120,
    ): ProcessResult {
        val sessionId = mcpInitialize()

        val toolCallRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 2)
            putJsonObject("params") {
                put("name", "steroid_fetch_resource")
                putJsonObject("arguments") {
                    put("project_name", projectName)
                    put("uri", uri)
                }
            }
            put("method", "tools/call")
        }.toString()

        val run = executeMcpRequest(sessionId, toolCallRequest, timeoutSeconds = timeout.toLong() + 30)
        return ProcessResultValue(
            exitCode = if (parseMcpToolResultIsError(run)) 1 else 0,
            stdout = parseMcpToolResultBody(run),
            stderr = "",
        )
    }

    /**
     * Send an input sequence to an IDE window via the `steroid_input` tool.
     *
     * Direct MCP call (no AI agent). [timeoutSeconds] bounds the curl request; a server-side hang
     * (issue #309: EDT dispatch withheld under a modal dialog) surfaces as the transport's
     * [TransientMcpRequestException] ("curl killed, exit -1") — callers reproducing the hang catch
     * exactly that type.
     */
    fun mcpInput(
        windowId: String,
        sequence: String,
        taskId: String = "integration-test-input",
        reason: String = "Integration test input",
        projectName: String = resolveProjectName(),
        timeoutSeconds: Long = 60,
    ): ProcessResult {
        val sessionId = mcpInitialize()

        val toolCallRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 2)
            putJsonObject("params") {
                put("name", "steroid_input")
                putJsonObject("arguments") {
                    put("project_name", projectName)
                    put("task_id", taskId)
                    put("reason", reason)
                    put("window_id", windowId)
                    put("sequence", sequence)
                }
            }
            put("method", "tools/call")
        }.toString()

        val run = executeMcpRequest(sessionId, toolCallRequest, timeoutSeconds = timeoutSeconds)
        return ProcessResultValue(
            exitCode = if (parseMcpToolResultIsError(run)) 1 else 0,
            stdout = parseMcpToolResultBody(run),
            stderr = "",
        )
    }

    /**
     * Capture a screenshot via the `steroid_take_screenshot` tool and return its TEXT content
     * (the `window_id: …` / artifact-path lines); the image content item carries no `text` key and
     * is skipped by [parseMcpToolResultBody].
     */
    fun mcpTakeScreenshot(
        windowId: String? = null,
        taskId: String = "integration-test-screenshot",
        reason: String = "Integration test screenshot",
        projectName: String = resolveProjectName(),
        timeoutSeconds: Long = 120,
    ): ProcessResult {
        val sessionId = mcpInitialize()

        val toolCallRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 2)
            putJsonObject("params") {
                put("name", "steroid_take_screenshot")
                putJsonObject("arguments") {
                    put("project_name", projectName)
                    put("task_id", taskId)
                    put("reason", reason)
                    if (windowId != null) {
                        put("window_id", windowId)
                    }
                }
            }
            put("method", "tools/call")
        }.toString()

        val run = executeMcpRequest(sessionId, toolCallRequest, timeoutSeconds = timeoutSeconds)
        return ProcessResultValue(
            exitCode = if (parseMcpToolResultIsError(run)) 1 else 0,
            stdout = parseMcpToolResultBody(run),
            stderr = "",
        )
    }

    private val mcpSessionIdHolder = AtomicReference<String?>(null)
    private fun mcpInitialize(): String {
        mcpSessionIdHolder.get()?.let {
            return it
        }

        val initRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            putJsonObject("params") {
                put("protocolVersion", "2025-11-25")
                putJsonObject("capabilities") { }
                putJsonObject("clientInfo") {
                    put("name", "integration-test")
                    put("version", "1.0")
                }
            }
        }.toString()

        val (responseBody, responseHeaders) = executeMcpRequestRaw(
            sessionId = null,
            requestBody = initRequest,
        )
        parseMcpResponseOrFail(responseBody)

        // A missing session header on an otherwise-OK response is a protocol breakage, not a busy IDE —
        // McpRequestFailedError (an Error) so a retrying caller stops at once instead of looping the budget.
        val sessionId = responseHeaders[SESSION_HEADER]
            ?.takeIf { it.isNotBlank() }
            ?: throw McpRequestFailedError("MCP initialize response missing $SESSION_HEADER header")

        mcpSessionIdHolder.set(sessionId)
        return sessionId
    }

    /**
     * Execute an MCP request via curl in the container.
     */
    private fun executeMcpRequest(
        sessionId: String,
        requestBody: String,
        timeoutSeconds: Long = 30,
    ): String {
        val responseBody = executeMcpRequestRaw(
            sessionId = sessionId,
            requestBody = requestBody,
            timeoutSeconds = timeoutSeconds,
        ).first
        return json.encodeToString(parseMcpResponseOrFail(responseBody.trim()))
    }

    private fun executeMcpRequestRaw(
        sessionId: String?,
        requestBody: String,
        timeoutSeconds: Long = 30,
    ): Pair<String, Map<String, String>> {
        //TODO: call it directly from the host with an HTTP client

        // Write the request body to a file inside the container and read it with `curl -d @file`.
        // Passing JSON inline via `-d '...'` through `bash -c` is broken on Windows: Java's
        // ProcessBuilder does not escape double-quote characters when building the Windows
        // command-line string, so CommandLineToArgvW strips all `"` from the JSON, producing
        // unquoted keys/values that the MCP server rejects (-32600 "jsonrpc must be 2.0").
        val bodyFile = "/tmp/mcp-steroid-request.json"

        // Create curl command
        val curlCommand = buildList {
            add("curl")
            add("-s")  // Silent
            add("-D")  // Dump response headers to stdout
            add("-")
            add("-X")
            add("POST")
            add(guestMcpUrl)
            add("-H")
            add("Content-Type: application/json")
            add("-H")
            add("Accept: application/json")

            // Add MCP session header when available.
            if (sessionId != null) {
                add("-H")
                add("$SESSION_HEADER: $sessionId")
            }

            add("-d")
            add("@$bodyFile")
        }

        // The container/process transport is terminal-by-default. Writing the request file or spawning curl
        // can fail outright when the IDE container has died or is unreachable — a *full* failure, not a busy
        // IDE — so any thrown transport error becomes a McpRequestFailedError (an Error): a poll stops at once
        // instead of retrying it for the whole budget. A merely *busy* IDE never throws here; it surfaces as
        // the returned exit code -1 handled below. This makes the primitive's only outcomes: a valid response,
        // a TransientMcpRequestException (retry), or a McpRequestFailedError (terminal) — no untyped escape.
        val result = try {
            driver.writeFileInContainer(bodyFile, requestBody)
            driver.startProcessInContainer {
                this
                    .args(curlCommand)
                    .timeoutSeconds(timeoutSeconds)
                    .description("curl MCP request")
            }.awaitForProcessFinish()
        } catch (e: Exception) {
            throw McpRequestFailedError("MCP request transport failed (${e.javaClass.simpleName}): ${e.message}")
        }

        // The request layer throws the typed exceptions that drive every retrying caller (mcpExecuteCode's
        // waitForValue and the hand-rolled project-open poll loops); the fatal-vs-retry decision is implicit
        // in the type, so no caller needs a try/catch.
        //  - exit -1 = curl was killed by our process timeout because the IDE was too busy to even answer in
        //    time → transient "call again" (a plain TransientMcpRequestException, swallowed-and-retried).
        //  - any OTHER non-zero exit = curl could not reach the server (no --max-time is set, so a *busy*
        //    server only ever yields -1; a non-`-1` failure means connection refused / unreachable) → a real
        //    crash, McpRequestFailedError (a WaitAbortedError) so the wait stops at once.
        if (result.exitCode == -1) {
            throw TransientMcpRequestException("MCP request did not complete: IDE too busy to answer (curl killed, exit -1)")
        }
        if (result.exitCode != 0) {
            throw McpRequestFailedError("MCP request failed (exit ${result.exitCode}): ${result.stdout} ${result.stderr}")
        }

        val raw = result.stdout.replace("\r\n", "\n")
        val splitIndex = raw.indexOf("\n\n")
        if (splitIndex < 0) {
            throw McpRequestFailedError("Invalid HTTP response from MCP server: missing headers/body separator")
        }

        val headerLines = raw.substring(0, splitIndex)
            .lineSequence()
            .drop(1) // Skip HTTP status line.
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains(":") }
            .toList()

        val headers = buildMap {
            for (line in headerLines) {
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val name = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                put(name, value)
            }
        }

        val body = raw.substring(splitIndex + 2)
        return body to headers
    }
}
