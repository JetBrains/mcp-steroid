/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.logger
import com.jonnyzzz.mcpSteroid.devrig.server.runStubStdioMcpServer
import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import java.io.PrintStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun main(rawArgs: Array<String>) {
    // Replace stdout immediately. MCP stdio reserves the original stdout for
    // frames, and command detection / service setup must not leak there.
    val mcpStdin = System.`in`
    val mcpStdout = System.out
    System.setOut(System.err)

    val lifetime = CloseableStackHost()
    val exitCode = try {
        // Parsing sits INSIDE the crash handler: the schema-driven CLI fails fast at construction time
        // (duplicate aliases, colliding CLI names, an unsupported schema type all throw before any
        // command exists), and an exception escaping main() would make the JVM exit 1 — the TOOL_ERROR
        // slot of the frozen exit table — instead of the SOFTWARE=70 that names devrig's own fault.
        val command = parseDevrigCommand(rawArgs)
        val headliner = buildHeadliner()
        if (command is DevrigCommand.MCP) {
            System.err.println(headliner)
        } else {
            System.setOut(mcpStdout)
        }

        val homePaths = resolveHomePathsOrDie()

        //setup logging. That is essential to avoid logger usages BEFORE this statement
        configureLoggingAndLogStarted(homePaths, rawArgs.toList(), command.debug)

        DevrigServices(
            lifetime = lifetime,
            homePaths = homePaths,
            mcpStdin = mcpStdin,
            mcpStdout = mcpStdout,
        ).mainImpl1(command, headliner)
    } catch (t: Throwable) {
        System.err.println("Unexpected error ${t.message}")
        t.printStackTrace(System.err)
        logger<DevrigLastResortCrashHandler>().error("Unexpected error running $command. ${t.message}", t)
        CliExit.SOFTWARE
    } finally {
        lifetime.closeAllStacks()
    }
    exitProcess(exitCode)
}

private fun buildHeadliner(): String = buildString {
    val devrigVersion = DevrigVersionMetadata.getDevrigVersion()
    appendLine("devrig v$devrigVersion — This environment empowers your AI with the best deterministic coding tools.")
    appendLine()
}

fun DevrigServices.mainImpl1(
    command: DevrigCommand,
    headliner: String,
): Int {
    class DevrigCoroutineExceptionHandler

    val log = logger<DevrigCoroutineExceptionHandler>()
    val exceptionHandler = CoroutineExceptionHandler { context, throwable ->
        log.warn("devrig coroutine exception: ${throwable.message} in $context", throwable)
    }

    return runBlocking(Dispatchers.IO + CoroutineName("devrig") + exceptionHandler + SupervisorJob()) {
        coroutineScope {
            mainImpl2(command, headliner)
        }
    }
}

suspend fun DevrigServices.mainImpl2(
    command: DevrigCommand,
    headliner: String,
): Int = coroutineScope {
    // The devrig binary owns ~/.mcp-steroid/bin/devrig: (re)create/update it on start so it self-heals
    // and always points at this running install + JDK. Best-effort and stderr-only — never blocks
    // serving. It writes atomically, so an agent mid-read of the launcher never sees a torn file.
    //
    // Gated on [selfHealsLauncherOnStart]: the MCP-as-CLI tool facades are thin, stateless bridge
    // forwarders and must NOT mutate on-disk launcher/PATH state (Tenet 3). The self-heal is a
    // bootstrap/lifecycle concern reserved for `mcp` + the interactive commands.
    if (command.selfHealsLauncherOnStart()) {
        ensureBinLauncher(homePaths)
    }

    // For the MCP command, the running McpServerCore becomes available once the
    // stdio server is built; the update check broadcasts its notice over it (in
    // addition to stderr) as a `notifications/message`. For non-MCP commands the
    // deferred is never completed, so the notice falls back to stderr only.
    val mcpServerReady = CompletableDeferred<McpServerCore>()

    if (command.runsTool()) {
        backgroundScope.launch {
            delay(Random.nextInt(200, 1300).milliseconds)
            val onNotice: (String) -> Unit = { message ->
                if (command is DevrigCommand.MCP) {
                    backgroundScope.launch {
                        val core = mcpServerReady.await()
                        core.broadcastLogMessage("warning", "devrig.updates", JsonPrimitive(message))
                    }
                }
            }
            // One flow for every command (docs/updates-check/devrig-auto-update.md): the first
            // check runs right after the short startup delay above; MCP sessions then keep
            // re-checking/retrying every 3–8 h, everything else gets the passive notice once.
            runAutoUpdateFlow(
                homePaths = homePaths,
                mcpSession = command is DevrigCommand.MCP,
                notify = onNotice,
                onUpdateEvent = { phase, promoted, exitCode ->
                    val properties = LinkedHashMap<String, Any>()
                    properties["target_version"] = promoted
                    if (exitCode != null) properties["exit_code"] = exitCode
                    beacon.capture("self_update_$phase", properties)
                },
            )
        }

        backgroundScope.launch {
            beacon.captureStarted(command)
        }
    }

    if (command is DevrigCommand.MCP) {
        // Orphan back-stop (#132): stdin EOF only reaps a parent that CLOSES the pipe; a SIGKILL'd
        // agent leaves this JVM alive forever. exitProcess (not scope cancellation) because the read
        // loop is parked in a blocking stream read that cancellation cannot interrupt.
        ParentDeathWatchdog(
            ancestorsAlive = watchedAncestorLiveness(),
            onParentDeath = {
                val message = "parent process died without closing stdin — exiting orphaned 'devrig mcp'"
                System.err.println("[mcp-steroid] $message")
                logger<ParentDeathWatchdog>().warn(message)
                exitProcess(0)
            },
        ).launchIn(backgroundScope)
        beacon.runHeartbeat()
        try {
            mainImplMcp(onServerReady = { mcpServerReady.complete(it) })
            return@coroutineScope 0
        } catch (t: Throwable) {
            System.err.println("Unexpected error ${t.message}")
            t.printStackTrace(System.err)
            logger<DevrigLastResortCrashHandler>().error("Unexpected error serving 'devrig mcp'. ${t.message}", t)
            return@coroutineScope CliExit.SOFTWARE
        }
    }

    if (command.printsHeadliner()) {
        mcpStdout.println(headliner)
    }
    runCliWithLastResortHandling(command, mcpStdout) { runCli(command) }
}

private class DevrigLastResortCrashHandler

/**
 * Runs [block] (in production, [runCli] for [command]) and converts an unhandled failure into the
 * last-resort exit code. The trace ALWAYS goes to [System.err] — never swallowed, per the root
 * `CLAUDE.md` rule that every catch must rethrow, log, or both — so an operator/agent can diagnose an
 * NPE deep in the bridge even under `--json`, where stdout stays a single clean envelope and therefore
 * cannot also carry the trace. A [CliUserFacingException] renders as its message alone (and its own
 * exit code), since a stack trace would bury the one line the user must read.
 * [kotlinx.coroutines.CancellationException] is rethrown as-is rather than treated as a failure:
 * swallowing it here would stop the surrounding coroutine scope from unwinding through structured
 * concurrency. (The outer `main()` handler still logs a rethrown cancellation as an "unexpected
 * error" of its own — that duplicate log line is unavoidable but harmless, since the process exits
 * either way.)
 *
 * Extracted from [mainImpl2] so this exit-code mapping is unit-testable without booting the CLI.
 */
fun runCliWithLastResortHandling(command: DevrigCommand, mcpStdout: PrintStream, block: () -> Int): Int {
    val log = logger<DevrigLastResortCrashHandler>()
    return try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (e: CliUserFacingException) {
        System.err.println(e.message)
        // INFO, not WARN: logback.xml filters the stderr appender at WARN, so anything at WARN or above
        // would print this trace straight back onto the console we just kept clean. At INFO the record
        // reaches the (unfiltered) log file only — and `--debug`, which lowers the console threshold to
        // DEBUG, deliberately shows it again.
        log.info("Command $command failed: ${e.message}", e)
        e.exit
    } catch (t: Throwable) {
        System.err.println("Unexpected error calling $command. ${t.message}")
        t.printStackTrace(System.err)
        log.error("Unexpected error calling $command. ${t.message}", t)
        if (command.json) {
            Presentation.Json().renderError(
                // The envelope's `command` key carries the invoked CLI command everywhere else
                // (RunTool.commandName); a consumer correlating envelopes by command must not lose
                // exactly the crash envelope. Lifecycle verbs have no commandName — they stay "devrig".
                command = (command as? DevrigCommand.RunTool)?.commandName ?: "devrig",
                message = "unexpected error: ${t.message ?: t.javaClass.simpleName}",
                // An unhandled crash is devrig's own fault, not the caller's — SOFTWARE (70), never the
                // USAGE (64) that flags an argument mistake. The same code main() and the MCP branch return.
                exit = CliExit.SOFTWARE, out = mcpStdout,
            )
        } else {
            CliExit.SOFTWARE
        }
    }
}

private fun DevrigCommand.runsTool(): Boolean = when (this) {
    is DevrigCommand.MCP,
    is DevrigCommand.DevrigCommandBackend,
    is DevrigCommand.DevrigCommandBackendDownload,
    is DevrigCommand.DevrigCommandBackendStart,
    is DevrigCommand.DevrigCommandBackendStop,
    is DevrigCommand.DevrigCommandBackendProvision,
    is DevrigCommand.DevrigCommandProject,
    is DevrigCommand.DevrigCommandInstall,
    is DevrigCommand.DevrigCommandInstallDevrig,
    is DevrigCommand.DevrigCommandInstallPlugin -> true
    is DevrigCommand.RunTool -> true
    is DevrigCommand.DevrigCommandInstallOverview,
    is DevrigCommand.DevrigCommandInstallConfig,
    is DevrigCommand.DevrigCommandHelp,
    is DevrigCommand.DevrigCommandVersion,
    is DevrigCommand.DevrigCommandParseError -> false
}

/**
 * The MCP-as-CLI tool commands (a generated `devrig <tool>` subcommand calling `steroid_*` directly,
 * e.g. `devrig execute_code`) emit data (markdown, JSON, tool output) to stdout that must stay clean
 * for piping, so they never print the human headliner banner and never self-heal the on-disk launcher
 * just to forward one bridge call (Tenet 3: devrig is stateless) — unlike the interactive
 * `project` / `backend` / `install` listings.
 *
 * Written as an exhaustive `when` over every [DevrigCommand] case (rather than a single `is` check) so a
 * new variant must be classified deliberately, forced by the compiler, and never becomes a silently-stale
 * predicate.
 */
private fun DevrigCommand.isMcpAsCliToolCommand(): Boolean = when (this) {
    is DevrigCommand.RunTool -> true
    is DevrigCommand.MCP,
    is DevrigCommand.DevrigCommandBackend,
    is DevrigCommand.DevrigCommandBackendDownload,
    is DevrigCommand.DevrigCommandBackendStart,
    is DevrigCommand.DevrigCommandBackendStop,
    is DevrigCommand.DevrigCommandBackendProvision,
    is DevrigCommand.DevrigCommandProject,
    is DevrigCommand.DevrigCommandInstall,
    is DevrigCommand.DevrigCommandInstallDevrig,
    is DevrigCommand.DevrigCommandInstallOverview,
    is DevrigCommand.DevrigCommandInstallConfig,
    is DevrigCommand.DevrigCommandInstallPlugin,
    is DevrigCommand.DevrigCommandHelp,
    is DevrigCommand.DevrigCommandVersion,
    is DevrigCommand.DevrigCommandParseError -> false
}

/**
 * Whether this command performs the on-start `~/.mcp-steroid/bin` launcher + PATH self-heal
 * ([ensureBinLauncher]). The self-heal is a bootstrap/lifecycle concern: it runs for the long-lived
 * `mcp` server and the interactive lifecycle commands (backend / project / install / help / version) —
 * but NOT for the thin, stateless MCP-as-CLI tool facades ([isMcpAsCliToolCommand]), which must never
 * mutate on-disk launcher/PATH state just to forward a single bridge call (Tenet 3: devrig is
 * stateless). Pure and side-effect-free so it is unit-testable across every [DevrigCommand] variant.
 */
fun DevrigCommand.selfHealsLauncherOnStart(): Boolean = !isMcpAsCliToolCommand()

/**
 * Whether this command prints the `devrig vX.Y.Z — ...` banner before its output. Tool-backed,
 * non-`mcp`, non-MCP-as-CLI commands print it — but only in human console mode: `--json` must stay a
 * single clean stdout document with no banner line ahead of it. Public (not `private`) so it is
 * unit-testable across every [DevrigCommand] variant, mirroring [selfHealsLauncherOnStart].
 */
fun DevrigCommand.printsHeadliner(): Boolean =
    runsTool() && this !is DevrigCommand.MCP && !json && !isMcpAsCliToolCommand()

suspend fun DevrigServices.mainImplMcp(
    onServerReady: (McpServerCore) -> Unit = {},
) = coroutineScope {
    // devrig boots a real MCP stdio server backed by McpStdioServer and
    // McpSteroidTools. Alongside the stdio server, the IDE monitor runs discovery from
    // <pid>.mcp-steroid JSON markers in the devrig home markers directory
    // plus legacy .<pid>.mcp-steroid markers from $HOME during the transition.
    // The monitor opens one POST <rpcBaseUrl>/projects/stream per IDE and receives
    // push notifications on project open/close.
    runStubStdioMcpServer(this@mainImplMcp, onServerReady = onServerReady)
}
