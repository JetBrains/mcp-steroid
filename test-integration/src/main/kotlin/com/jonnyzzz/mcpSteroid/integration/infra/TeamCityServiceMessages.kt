/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import java.io.File

/**
 * Emits TeamCity service messages for integrating Docker-based IDE tests with the
 * TC build agent. See https://www.jetbrains.com/help/teamcity/service-messages.html.
 *
 * The [publishRunDirArtifact] helper turns each test-scoped run directory into a
 * TC build artifact so humans can download the full session (session-info.txt, agent
 * NDJSON/decoded logs, screenshots, IDE logs, video) from the TC UI after the build.
 *
 * Emission is unconditional — running the message outside TC is harmless (TC agents
 * recognise these messages, local runs just see a log line). We deliberately avoid
 * gating on `TEAMCITY_VERSION` so output is identical everywhere, which makes local
 * debugging of the generated spec trivial.
 */
object TeamCityServiceMessages {

    /**
     * Escapes special characters per the TC service-messages spec. Single quotes,
     * pipes and square brackets must be prefixed with `|`; newlines become `|n` / `|r`.
     */
    fun escape(value: String): String = buildString(value.length + 8) {
        for (ch in value) {
            when (ch) {
                '\'' -> append("|'")
                '|' -> append("||")
                '[' -> append("|[")
                ']' -> append("|]")
                '\n' -> append("|n")
                '\r' -> append("|r")
                else -> append(ch)
            }
        }
    }

    /**
     * Publish [runDir] as a single `test/<dirName>.zip` build artifact.
     *
     * TC interprets `<src> => <dst>.zip` in a `publishArtifacts` spec as "zip the source
     * directory and upload it under the given archive name". Emitted once per session
     * right after the run directory is created; TC queues the spec and performs the
     * actual zip + upload at the end of the build, so partial content from crashed
     * runs is still captured.
     *
     * Artifact layout on TC:
     *   test/run-20260415-123456-arena-dpaia-claude-mcp.zip
     *
     * Pattern intentionally uses the run-dir basename as the archive name so a single
     * build that spins up multiple sessions (e.g. a bucket config running claude+mcp
     * and claude+none in one test) produces one zip per session, unambiguously named.
     */
    fun publishRunDirArtifact(runDir: File) {
        val archiveName = "${runDir.name}.zip"
        val spec = "${runDir.absolutePath} => test/$archiveName"
        println("##teamcity[publishArtifacts '${escape(spec)}']")
    }
}
