/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

/**
 * Builds the non-interactive Claude CLI argument list for one prompt run.
 *
 * Extracted out of `DockerClaudeSession.runPrompt` as a PURE function on purpose: the order of these
 * arguments is what silently breaks a run (a `--settings` landing after `-p` becomes part of the
 * prompt), yet exercising it through the session needs an API key, a container, and minutes of agent
 * time. As a plain `List<String>` builder it is covered by fast unit assertions instead.
 *
 * The prompt itself is NOT here: `-p` is emitted as the bare print flag and the prompt reaches the CLI
 * on stdin. A single argument cannot exceed `MAX_ARG_STRLEN`, which is 128 KiB on Linux regardless of
 * how much total argv room the kernel allows, and the whole in-container command line travels as ONE
 * argument to `docker exec`. A repair turn carries the compiler output plus the full text of every
 * failing file, so it crosses that limit on exactly the runs whose implementation went worst — the
 * exec fails with `E2BIG` ("Argument list too long") after the agent has already done its work, which
 * loses the run and biases whatever it fed. Stdin has no such ceiling, and it also removes the older
 * hazard of a prompt that begins with `--` being parsed as a flag.
 *
 * Both [mcpConfigFile] and [settingsFile] are container-local FILE PATHS, never inline JSON — see the
 * Windows quote-stripping note in `DockerClaudeSession.runPrompt` for why JSON cannot be passed inline.
 *
 * @param model the `--model` value
 * @param mcpConfigFile path of the MCP config file, or `null` to run with no MCP servers at all
 * @param settingsFile path of a Claude Code settings file (e.g. carrying a `PostToolUse` hook),
 *   or `null` to leave the CLI on its default settings resolution
 */
fun claudeRunPromptArgs(
    model: String,
    mcpConfigFile: String?,
    settingsFile: String?,
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
    // Bare: the prompt arrives on stdin, so `-p` here is only the print flag. It stays LAST so a
    // later argument cannot be appended behind it and read as the prompt.
    add("-p")
}
