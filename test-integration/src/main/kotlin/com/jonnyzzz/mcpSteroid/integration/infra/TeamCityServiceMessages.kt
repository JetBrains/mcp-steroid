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
     * Publish [runDir] as a set of TC build artifacts.
     *
     * Emits three independent `publishArtifacts` service messages, using
     * the recursive-glob form `<pattern>/<star><star> => <dest>` from the
     * "Artifact paths" syntax documented at
     * <https://www.jetbrains.com/help/teamcity/configuring-general-settings.html#Build+Options>
     * (the literal two-star-slash is avoided in this KDoc because the
     * Kotlin comment parser treats `<star><star>/` as end-of-comment —
     * see the source of this method for the actual emitted strings):
     *
     *  1. Video recording(s) published standalone under `<runName>/video/`
     *     so humans can click-preview them directly in the TC build UI
     *     without downloading the full zip.
     *  2. Screenshots published standalone under `<runName>/screenshot/`,
     *     same rationale as video.
     *  3. Everything (session-info.txt, IDE logs, agent NDJSON, decoded
     *     logs, video, screenshots) archived into a single `<runName>.zip`
     *     for bulk offline download.
     *
     * The recursive-glob form is important: a plain `<dir> => <zip>`
     * spec is interpreted as a literal path, and on an empty-at-emission
     * directory TC logs `Artifacts path '…' not found` and moves on.
     * Using a glob makes TC resolve matching files at publish time.
     *
     * Emission site also matters: this is called from a lifetime cleanup
     * action (see intelliJ-factory.kt), NOT at container creation, so
     * the runDir is fully populated by the time TC processes the messages.
     */
    fun publishRunDirArtifact(runDir: File) {
        val runName = runDir.name
        val base = runDir.absolutePath
        // Video — standalone, one file per run under <runName>/video/
        println("##teamcity[publishArtifacts '${escape("$base/video/** => $runName/video/")}']")
        // Screenshots — standalone, one folder per run under <runName>/screenshot/
        println("##teamcity[publishArtifacts '${escape("$base/screenshot/** => $runName/screenshot/")}']")
        // Everything zipped for bulk download; the video + screenshots are
        // included in the zip too (duplicates the standalone copies) so the
        // zip stays a self-contained offline record of the whole session.
        println("##teamcity[publishArtifacts '${escape("$base/** => $runName.zip")}']")
    }
}
