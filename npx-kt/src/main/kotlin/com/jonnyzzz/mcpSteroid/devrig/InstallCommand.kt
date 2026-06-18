/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCliResult
import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCliRunner
import com.jonnyzzz.mcpSteroid.aiAgents.ProcessAiAgentCliRunner
import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import com.jonnyzzz.mcpSteroid.aiAgents.mcpAddStdioInvocation
import com.jonnyzzz.mcpSteroid.aiAgents.mcpListInvocation
import com.jonnyzzz.mcpSteroid.aiAgents.mcpRemoveInvocation
import java.io.PrintStream
import kotlin.io.path.isRegularFile

private const val DEVRIG_MCP_SERVER_NAME = "mcp-steroid"
private const val DEVRIG_LEGACY_SERVER_NAME = "devrig"

/**
 * Registers the **user-facing launcher** (`~/.mcp-steroid/bin/devrig`) as the agent's MCP server. We
 * register that stable wrapper — never the content-addressed install tree, which changes on every
 * upgrade — so an upgrade repoints the launcher underneath without re-registering the agent. `install`
 * is explicit user intent, so we call [ensureBinLauncher] with `force = true`: the wrapper is written
 * even on a SNAPSHOT/dev dist (where the passive on-each-start self-heal is off by default). We then
 * **verify the launcher file actually exists before registering** and FAIL otherwise — registration must
 * never point at a path that does not exist. The registered command is just the launcher (POSIX
 * `~/.mcp-steroid/bin/devrig`; Windows `cmd.exe /d /c "…\bin\devrig.cmd"`), with no `JAVA_HOME` — the
 * launcher sets up its own runtime.
 */
fun DevrigServices.runInstallCommand(
    command: DevrigCommand.DevrigCommandInstall,
    runner: AiAgentCliRunner = ProcessAiAgentCliRunner(),
): Int {
    // Create the stable launcher first, then guarantee it exists before we register it.
    ensureBinLauncher(homePaths, force = true, registerWindowsPath = true)
    val launcher = DevrigUserLauncher.path(homePaths)
    if (!launcher.isRegularFile()) {
        System.err.println(
            "[mcp-steroid] ERROR: cannot register the MCP server — the launcher $launcher was not created.",
        )
        System.err.println(
            "  This usually means DEVRIG_BIN_NO_AUTO_REGISTER opts out of writing it. Unset that " +
                "(or create the launcher yourself), then re-run 'devrig install'.",
        )
        return 64
    }
    return runInstallCommand(
        command = command,
        mcpCommand = DevrigUserLauncher.invocation(homePaths, listOf("mcp")),
        out = mcpStdout,
        err = System.err,
        runner = runner,
    )
}

/**
 * Registers devrig as the `mcp-steroid` stdio MCP server in [command]'s agent, narrating each step so
 * the user understands exactly what is being changed.
 *
 * The registration is an **idempotent, consolidating upsert**:
 *  1. it reviews the agent's currently registered MCP servers;
 *  2. it removes every devrig-owned entry it finds — any named `mcp-steroid` or `devrig`, or whose
 *     launch command runs the devrig binary — collapsing duplicates/stale variants into one;
 *  3. it adds a single canonical `mcp-steroid` entry.
 *
 * That is what makes re-running `devrig install` safe and self-healing: it repairs a stale entry (old
 * launcher path or subcommand) and merges stray duplicates instead of failing with "already exists",
 * which is the trap users hit when an earlier version or a different bootstrapper had registered the
 * server under a different name (see issues #84/#86).
 */
fun runInstallCommand(
    command: DevrigCommand.DevrigCommandInstall,
    mcpCommand: StdioMcpCommand,
    out: PrintStream,
    err: PrintStream,
    runner: AiAgentCliRunner,
): Int {
    val agent = command.agent
    val renderedCommand = "${mcpCommand.command} ${mcpCommand.args.joinToString(" ")}"

    out.println("Installing devrig as the '$DEVRIG_MCP_SERVER_NAME' MCP server for ${agent.displayName}.")
    out.println()
    out.println("What this does:")
    out.println("  - Reviews ${agent.displayName}'s MCP servers — matching both by name " +
        "(${DEVRIG_SERVER_NAMES.joinToString(" / ") { "'$it'" }}) and by configuration (any entry whose " +
        "command runs the devrig binary) — and consolidates every match into a single " +
        "'$DEVRIG_MCP_SERVER_NAME' registration (user scope).")
    out.println("  - ${agent.displayName} will launch this command to start it:")
    out.println("      $renderedCommand")
    out.println("  - It points at the stable ~/.mcp-steroid/bin launcher, so it survives devrig upgrades")
    out.println("    without re-registering.")
    out.println()
    out.println("Re-running install is safe — existing devrig entries are replaced.")
    out.println()

    // Step 1 — review the agent's registered MCP servers so we can act on every devrig-owned entry,
    // not just the canonical name. Listing is best-effort: if it fails or we can't parse it, we fall
    // back to reconciling the known devrig names.
    out.println("Step 1/3: reviewing ${agent.displayName}'s registered MCP servers…")
    val listResult = runner.run(mcpListInvocation(agent))
    val listed = if (listResult.exitCode == 0) parseMcpServerList(agent, listResult.output) else emptyList()
    val detected = listed.filter { it.isDevrigOwned() }
    when {
        listResult.exitCode != 0 ->
            out.println("  could not read the server list; reconciling the known devrig names.")
        detected.isEmpty() ->
            out.println("  no existing devrig / '$DEVRIG_MCP_SERVER_NAME' registration found.")
        else -> {
            out.println("  found ${detected.size} devrig registration(s):")
            detected.forEach { out.println("    - '${it.name}' (matched by ${it.devrigMatchReason()})") }
        }
    }

    // Build the set of names to clear: every detected devrig entry, the canonical name (always, so the
    // re-add is clean even if the listing missed it), plus the legacy name when we couldn't read the list.
    val namesToRemove = LinkedHashSet<String>()
    detected.forEach { namesToRemove += it.name }
    namesToRemove += DEVRIG_MCP_SERVER_NAME
    if (listResult.exitCode != 0) namesToRemove += DEVRIG_LEGACY_SERVER_NAME

    // Step 2 — clear them all. Each removal is best-effort: a non-zero exit means "not present", which is
    // expected and not fatal. Underlying agent messages go to stderr; stdout reports confirmed removals.
    out.println("Step 2/3: consolidating into a single '$DEVRIG_MCP_SERVER_NAME' entry…")
    val removed = mutableListOf<String>()
    for (name in namesToRemove) {
        val removeResult = runner.run(mcpRemoveInvocation(agent, name))
        if (removeResult.exitCode == 0) removed += name
        emitAgentOutput(removeResult, err)
    }
    if (removed.isEmpty()) {
        out.println("  nothing to clean up.")
    } else {
        removed.forEach { out.println("  - removed '$it'") }
    }

    // Step 3 — add the single canonical registration.
    out.println("Step 3/3: registering '$DEVRIG_MCP_SERVER_NAME' with ${agent.displayName}…")
    val addInvocation = mcpAddStdioInvocation(agent, mcpCommand, DEVRIG_MCP_SERVER_NAME)
    val addResult = runner.run(addInvocation)
    emitAgentOutput(addResult, err)
    if (addResult.exitCode != 0) {
        err.println()
        err.println(
            "Registration FAILED: '${agent.binary} ${addInvocation.args.joinToString(" ")}' " +
                "exited with code ${addResult.exitCode}.",
        )
        err.println("Fix the error reported above, then re-run 'devrig install ${agent.binary}'.")
        return addResult.exitCode
    }

    out.println()
    out.println("Done — '$DEVRIG_MCP_SERVER_NAME' is registered for ${agent.displayName}.")
    out.println("  Command ${agent.displayName} will run: $renderedCommand")
    out.println("  Verify with: ${agent.binary} mcp list")
    return 0
}

private fun emitAgentOutput(result: AiAgentCliResult, err: PrintStream) {
    if (result.output.isNotBlank()) {
        err.print(result.output)
        if (!result.output.endsWith("\n")) err.println()
    }
}

