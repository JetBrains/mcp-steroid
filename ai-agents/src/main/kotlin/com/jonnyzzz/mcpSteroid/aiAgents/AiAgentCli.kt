/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.aiAgents

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

enum class AiAgentCli(
    val binary: String,
    val displayName: String,
) {
    CLAUDE("claude", "Claude"),
    CODEX("codex", "Codex"),
    GEMINI("gemini", "Gemini");

    fun mcpAddStdioArgs(command: StdioMcpCommand, serverName: String = DEFAULT_SERVER_NAME): List<String> = when (this) {
        CLAUDE -> claudeMcpAddStdioArgs(command, serverName)
        CODEX -> codexMcpAddStdioArgs(command, serverName)
        GEMINI -> geminiMcpAddStdioArgs(command, serverName)
    }

    fun mcpRemoveArgs(serverName: String = DEFAULT_SERVER_NAME): List<String> = when (this) {
        CLAUDE -> claudeMcpRemoveArgs(serverName)
        CODEX -> codexMcpRemoveArgs(serverName)
        GEMINI -> geminiMcpRemoveArgs(serverName)
    }

    fun mcpListArgs(): List<String> = when (this) {
        CLAUDE -> claudeMcpListArgs()
        CODEX -> codexMcpListArgs()
        GEMINI -> geminiMcpListArgs()
    }

    companion object {
        fun parse(value: String): AiAgentCli? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) || it.binary == value.lowercase() }
    }
}

data class AiAgentCliInvocation(
    val binary: String,
    val args: List<String>,
)

data class AiAgentCliResult(
    val exitCode: Int,
    val output: String,
)

fun interface AiAgentCliRunner {
    fun run(invocation: AiAgentCliInvocation): AiAgentCliResult
}

/**
 * The agent CLI process could not be LAUNCHED — the binary is missing from PATH, or is a shape the
 * OS cannot spawn directly (a Windows `.cmd` npm shim needs `cmd.exe /d /c`). Distinct from other
 * IOExceptions (temp-file creation, output read) so callers can turn exactly this case into
 * "install the CLI first" guidance (jonnyzzz/mcp-steroid#342) without masking infrastructure
 * failures as a missing CLI.
 */
class AgentCliNotLaunchableException(
    val binary: String,
    cause: IOException,
) : IOException("could not launch '$binary': ${cause.message}", cause)

/**
 * Runs an agent CLI to completion with a hard [timeout].
 *
 * Output goes to a TEMP FILE, not a pipe: with a pipe, draining via
 * `readText()` blocks until EOF and no timeout can ever fire — a hung agent
 * CLI then hangs devrig forever, uninterruptibly (pipe reads ignore thread
 * interrupts). With a file redirect, `waitFor(timeout)` is real timeout
 * enforcement. stderr stays merged into stdout (same interleaving as the
 * previous `redirectErrorStream(true)` behavior); stdin is closed right
 * after start so a CLI that reads stdin sees EOF instead of blocking on a
 * pipe nobody writes to.
 *
 * On timeout the child is killed and [IllegalStateException] is thrown:
 * a loud, bounded failure instead of an unbounded hang.
 *
 * The temp file is deleted on every path — success, timeout, and launch
 * failure. Windows needs care here (issue #407): the child (and any
 * descendant that inherited the redirect) holds an open handle on the
 * redirect file, and NTFS forbids deleting a file with an open handle, so
 * the runner waits for the killed process tree to actually die and retries
 * the delete briefly before giving up (loudly, on stderr).
 */
class ProcessAiAgentCliRunner(
    private val timeout: Duration = 120.seconds,
) : AiAgentCliRunner {
    override fun run(invocation: AiAgentCliInvocation): AiAgentCliResult {
        val outputFile = Files.createTempFile("devrig-agent-cli-", ".out")
        try {
            val process = try {
                ProcessBuilder(listOf(invocation.binary) + invocation.args)
                    .redirectErrorStream(true)
                    .redirectOutput(outputFile.toFile())
                    .start()
            } catch (e: IOException) {
                throw AgentCliNotLaunchableException(invocation.binary, e)
            }
            runCatching { process.outputStream.close() } // stdin: immediate EOF
            if (!process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
                killProcessTreeAndAwait(process, invocation.binary)
                throw IllegalStateException(
                    "'${invocation.binary} ${invocation.args.joinToString(" ")}' " +
                        "timed out after $timeout and was killed",
                )
            }
            return AiAgentCliResult(process.exitValue(), Files.readString(outputFile, Charsets.UTF_8))
        } finally {
            deleteOutputFileWithRetry(outputFile)
        }
    }

    /**
     * Forcibly kills the timed-out child AND its descendants, then waits (bounded) for them
     * to actually die. `destroyForcibly()` only INITIATES the kill, and the child's stdout IS
     * an open handle on the redirect file; NTFS refuses to delete a file with an open handle
     * (POSIX unlink-while-open is fine) — deleting in `finally` while the tree was still going
     * down leaked devrig-agent-cli-*.out on Windows (issue #407). Descendants are killed too
     * because they inherit the redirect handle and would keep the file locked past the direct
     * child's death (relevant once jonnyzzz/mcp-steroid#342 wraps `.cmd` shims in `cmd.exe`).
     * All waits share ONE [KILL_WAIT_MS] deadline, so a kill-resistant tree cannot re-introduce
     * the unbounded hang this runner exists to prevent. An interrupt while waiting re-sets the
     * interrupt flag and returns, so the caller still throws the documented timeout
     * [IllegalStateException].
     */
    private fun killProcessTreeAndAwait(process: Process, binary: String) {
        // snapshot BEFORE killing the parent — the kill re-parents children, emptying descendants()
        val descendants = process.toHandle().descendants().toList()
        process.destroyForcibly()
        descendants.forEach { it.destroyForcibly() }
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(KILL_WAIT_MS)
        try {
            if (!process.waitFor(KILL_WAIT_MS, TimeUnit.MILLISECONDS)) {
                System.err.println(
                    "[mcp-steroid] '$binary' is still alive ${KILL_WAIT_MS}ms after destroyForcibly()",
                )
            }
            for (handle in descendants) {
                val remainingNanos = (deadlineNanos - System.nanoTime()).coerceAtLeast(0)
                try {
                    // get(0) throws TimeoutException right away once the shared deadline is spent
                    handle.onExit().get(remainingNanos, TimeUnit.NANOSECONDS)
                } catch (e: TimeoutException) {
                    System.err.println(
                        "[mcp-steroid] descendant pid=${handle.pid()} of '$binary' is still alive " +
                            "${KILL_WAIT_MS}ms after destroyForcibly()",
                    )
                } catch (e: ExecutionException) {
                    // onExit() should never complete exceptionally; log defensively, never rethrow —
                    // the caller's timeout IllegalStateException must win
                    System.err.println(
                        "[mcp-steroid] failed to await killed descendant pid=${handle.pid()} of '$binary': $e",
                    )
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            System.err.println(
                "[mcp-steroid] interrupted while waiting for the killed '$binary' process tree to die",
            )
        }
    }

    /**
     * Deletes [outputFile], retrying a few times with a short backoff: even after the child
     * process is dead, Windows can transiently refuse the delete with a sharing violation
     * (e.g. an antivirus or search indexer briefly holds the freshly written file). On POSIX
     * the first attempt always succeeds and the loop returns immediately. The final failure
     * is never swallowed — it is logged to stderr with the last cause.
     */
    private fun deleteOutputFileWithRetry(outputFile: Path) {
        var lastFailure: Exception? = null
        var attemptsMade = 0
        for (attempt in 1..DELETE_ATTEMPTS) {
            attemptsMade = attempt
            try {
                Files.deleteIfExists(outputFile)
                return
            } catch (e: Exception) {
                lastFailure = e // logged below if no later attempt succeeds
            }
            if (attempt < DELETE_ATTEMPTS) {
                try {
                    Thread.sleep(DELETE_RETRY_DELAY_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break // stop retrying; fall through to the loud final log
                }
            }
        }
        System.err.println(
            "[mcp-steroid] could not delete agent CLI output file $outputFile " +
                "after $attemptsMade attempt(s): $lastFailure",
        )
    }

    private companion object {
        /** Shared deadline for the whole killed process tree to die after [Process.destroyForcibly]. */
        const val KILL_WAIT_MS = 10_000L
        const val DELETE_ATTEMPTS = 10
        const val DELETE_RETRY_DELAY_MS = 100L
    }
}

fun mcpAddStdioInvocation(
    agent: AiAgentCli,
    command: StdioMcpCommand,
    serverName: String = DEFAULT_SERVER_NAME,
): AiAgentCliInvocation =
    AiAgentCliInvocation(
        binary = agent.binary,
        args = agent.mcpAddStdioArgs(command, serverName),
    )

fun mcpRemoveInvocation(
    agent: AiAgentCli,
    serverName: String = DEFAULT_SERVER_NAME,
): AiAgentCliInvocation =
    AiAgentCliInvocation(
        binary = agent.binary,
        args = agent.mcpRemoveArgs(serverName),
    )

fun mcpListInvocation(
    agent: AiAgentCli,
): AiAgentCliInvocation =
    AiAgentCliInvocation(
        binary = agent.binary,
        args = agent.mcpListArgs(),
    )
