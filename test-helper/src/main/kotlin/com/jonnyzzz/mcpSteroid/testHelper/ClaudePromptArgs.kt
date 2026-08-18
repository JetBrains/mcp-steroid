/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

/**
 * Builds the non-interactive Claude CLI argument list for one prompt run.
 *
 * Extracted out of `DockerClaudeSession.runPrompt` as a PURE function on purpose: the order of these
 * arguments is what silently breaks a run (a `--settings` landing after `-p` becomes part of the
 * prompt; a prompt starting with `--` gets parsed as a flag unless it stays last), yet exercising it
 * through the session needs an API key, a container, and minutes of agent time. As a plain
 * `List<String>` builder it is covered by fast unit assertions instead.
 *
 * Both [mcpConfigFile] and [settingsFile] are container-local FILE PATHS, never inline JSON — see the
 * Windows quote-stripping note in `DockerClaudeSession.runPrompt` for why JSON cannot be passed inline.
 *
 * @param model the `--model` value
 * @param mcpConfigFile path of the MCP config file, or `null` to run with no MCP servers at all
 * @param settingsFile path of a Claude Code settings file (e.g. carrying a `PostToolUse` hook),
 *   or `null` to leave the CLI on its default settings resolution
 * @param prompt the prompt, always emitted last after `-p`
 */
fun claudeRunPromptArgs(
    model: String,
    mcpConfigFile: String?,
    settingsFile: String?,
    prompt: String,
): List<String> = buildList {
    add("--permission-mode")
    add("bypassPermissions")
    add("--model")
    add(model)
    add("--tools")
    add("default")
    add("--input-format")
    add("text")
    add("--output-format")
    add("stream-json")
    add("--verbose")
    mcpConfigFile?.let { configFile ->
        add("--mcp-config")
        add(configFile)
        add("--strict-mcp-config")
    }
    settingsFile?.let { file ->
        add("--settings")
        add(file)
    }
    add("-p")
    add(prompt)
}
