/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.mcp.Prompt
import com.jonnyzzz.mcpSteroid.mcp.Resource
import com.jonnyzzz.mcpSteroid.mcp.ServerCapabilities
import com.jonnyzzz.mcpSteroid.mcp.ServerInfo
import com.jonnyzzz.mcpSteroid.mcp.Tool
import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import com.jonnyzzz.mcpSteroid.devrig.HomePaths
import com.jonnyzzz.mcpSteroid.devrig.DevrigServices
import com.jonnyzzz.mcpSteroid.server.McpSteroidTools
import com.jonnyzzz.mcpSteroid.server.PromptsContextHandler
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.io.TempDir

class DevrigDescriptorParityTest {
    @Test
    fun `devrig descriptors match direct IDE supported surface`(
        @TempDir tempDir: Path,
    ) {
        val direct = directIdeServer(PromptsContext(productCode = "IU", baselineVersion = 261))
        val devrig = devrigServer(tempDir)

        assertEquals(
            direct.toolRegistry.listTools().sortedBy(Tool::name),
            devrig.toolRegistry.listTools().sortedBy(Tool::name),
        )

        assertDescriptorSubset(
            direct = direct.resourceRegistry.listResources().associateBy(Resource::uri),
            devrig = devrig.resourceRegistry.listResources().associateBy(Resource::uri),
            label = "resources",
        )
        assertDescriptorSubset(
            direct = direct.promptRegistry.listPrompts().associateBy(Prompt::name),
            devrig = devrig.promptRegistry.listPrompts().associateBy(Prompt::name),
            label = "prompts",
        )
    }

    private fun directIdeServer(context: PromptsContext): McpServerCore =
        newServer().also { server ->
            DirectDescriptorTools(context).registerAll(server)
        }

    private fun devrigServer(tempDir: Path): McpServerCore {
        val lifetime = CloseableStackHost()
        return try {
            newServer().also { server ->
                StubMcpSteroidTools(
                    DevrigServices(
                        lifetime = lifetime,
                        homePaths = HomePaths(tempDir.resolve("devrig-home")).also { it.mkdirsAll() },
                        mcpStdin = ByteArrayInputStream(ByteArray(0)),
                        mcpStdout = PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8),
                    )
                ).registerAll(server)
            }
        } finally {
            lifetime.closeAllStacks()
        }
    }

    private fun newServer(): McpServerCore =
        McpServerCore(
            serverInfo = ServerInfo(name = "descriptor-parity", version = "0"),
            capabilities = ServerCapabilities(),
        )

    private fun <T> assertDescriptorSubset(
        direct: Map<String, T>,
        devrig: Map<String, T>,
        label: String,
    ) {
        val missing = direct.keys - devrig.keys
        assertEquals(emptySet(), missing, "devrig is missing direct IDE $label: $missing")
        for ((key, directDescriptor) in direct) {
            assertEquals(
                directDescriptor,
                assertNotNull(devrig[key], "devrig is missing direct IDE $label descriptor: $key"),
                "descriptor mismatch for $label $key",
            )
        }
    }

    private class DirectDescriptorTools(
        private val context: PromptsContext,
    ) : McpSteroidTools() {
        val promptsContextHandler = object : PromptsContextHandler {
            override fun buildPromptsContext(projectName: String?): PromptsContext = context
        }

        override fun <T> handler(type: Class<T>): T {
            if (type == PromptsContextHandler::class.java) {
                return type.cast(promptsContextHandler)
            }
            error("handler ${type.name} must not be resolved while listing descriptors")
        }
    }
}
