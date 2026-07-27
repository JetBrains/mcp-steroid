/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.server.ExecCodeDescriptionVariant
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The `steroid_execute_code` tool description ships in two variants (full and slim) and the arena arms
 * pick one per container through `MCP_STEROID_EXEC_CODE_DESCRIPTION`. That switch is only worth
 * anything if the environment variable actually reaches the IDE JVM that answers `tools/list` — the
 * [ExecCodeDescriptionVariant] unit tests cannot see the container plumbing in between.
 *
 * This is the cheap end-to-end check for that plumbing: no agent, no API spend, empty project. The
 * containers run one at a time, each torn down before the next starts.
 */
class ExecCodeDescriptionVariantIntegrationTest {

    @Test
    @Timeout(value = 40, unit = TimeUnit.MINUTES)
    fun `each container serves the execute-code description its environment selects`() {
        val served = ExecCodeDescriptionVariant.entries.associateWith { variant ->
            serveExecCodeDescription(variant).also {
                println("[exec-desc] ${variant.wire}: ${it.length} chars")
            }
        }

        served.forEach { (variant, description) ->
            check(description.contains(variant.marker)) {
                "The ${variant.wire} container must serve the ${variant.wire} description, but its " +
                    "marker '${variant.marker}' is absent from the ${description.length}-char text " +
                    "served over tools/list:\n${description.take(1000)}"
            }

            val foreignMarkers = ExecCodeDescriptionVariant.entries
                .filter { it != variant && description.contains(it.marker) }
            check(foreignMarkers.isEmpty()) {
                "The ${variant.wire} description also carries the markers of " +
                    "${foreignMarkers.map { it.wire }} — the variants are no longer distinguishable, " +
                    "so an arena arm can no longer prove which one it measured."
            }
        }

        val full = served.getValue(ExecCodeDescriptionVariant.FULL)
        val slim = served.getValue(ExecCodeDescriptionVariant.SLIM)
        check(slim.length < full.length) {
            "The slim router must serve less tool-definition context than the full description " +
                "(slim=${slim.length}, full=${full.length})."
        }
    }

    /**
     * Starts an IDE container with [variant] selected through the environment and returns the
     * `steroid_execute_code` description it advertises over `tools/list`.
     */
    private fun serveExecCodeDescription(variant: ExecCodeDescriptionVariant): String {
        val lifetime = CloseableStackHost("exec-code-description-${variant.wire}")
        try {
            val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "exec-code-description-${variant.wire}",
                // The description is assembled from the prompt corpus, not from project content —
                // EmptyProject keeps startup fast.
                project = IntelliJProject.EmptyProject,
                extraEnv = mapOf(ExecCodeDescriptionVariant.ENV_VAR to variant.wire),
            ))
            return session.mcpSteroid.mcpToolDescription("steroid_execute_code")
        } finally {
            lifetime.closeAllStacks()
        }
    }
}
