/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * `devrig install config` (issue #398): print the MANUAL configuration recipe — the stdio `mcpServers`
 * JSON snippet pointing at the stable launcher, plus the per-agent `mcp add` command lines — for MCP
 * clients devrig cannot configure automatically.
 */
class InstallConfigCommandTest {
    // Fixed home; windows = false picks the launcher NAME/invocation shape, but Path.toString() still
    // uses the running JVM's separator — on Windows launcherPath contains backslashes, which are not
    // valid unescaped inside a JSON string (same convention as InstallCommandTest).
    private val home = HomePaths(Path.of("/home/user/.mcp-steroid"))
    private val launcherPath = DevrigUserLauncher.path(home, windows = false).toString()
    private val launcherPathJsonEscaped = launcherPath.replace("\\", "\\\\")
    private val mcpCommand = DevrigUserLauncher.invocation(home, listOf("mcp"), windows = false)

    @Test
    fun `config prints the stdio mcpServers JSON pointing at the stable launcher`() {
        val text = renderInstallConfig(mcpCommand)

        assertContains(text, "\"mcpServers\"")
        assertContains(text, "\"mcp-steroid\"")
        assertContains(text, "\"command\": \"$launcherPathJsonEscaped\"")
        assertContains(text, "\"mcp\"")
    }

    @Test
    fun `config lists the per-agent mcp add commands for the same stdio launch command`() {
        val text = renderInstallConfig(mcpCommand)

        assertContains(text, "claude mcp add --scope user mcp-steroid -- $launcherPath mcp")
        assertContains(text, "codex mcp add mcp-steroid -- $launcherPath mcp")
        assertContains(text, "gemini mcp add --type stdio --scope user --trust mcp-steroid $launcherPath mcp")
        // The automatic path stays advertised next to the manual one.
        assertContains(text, "devrig install claude|codex|gemini")
    }

    @Test
    fun `config JSON is one structured document with tokenized agent commands`() {
        val root = Json.parseToJsonElement(renderInstallConfigJson(mcpCommand)).jsonObject

        assertEquals("mcp-steroid", root["serverName"]!!.jsonPrimitive.content)
        assertEquals(launcherPath, root["mcpServers"]!!.jsonObject["mcp-steroid"]!!.jsonObject["command"]!!.jsonPrimitive.content)
        assertEquals("claude", root["agentCommands"]!!.jsonObject["claude"]!!.jsonArray.first().jsonPrimitive.content)
    }

    @Test
    fun `config JSON escapes a Windows launcher path`() {
        // The Windows invocation shape (cmd.exe /d /c "<quoted .cmd path> mcp") carries backslashes and
        // embedded quotes — the rendered JSON must escape them (issue #398: no hand-concatenated JSON).
        val windowsCommand = StdioMcpCommand(
            command = "cmd.exe",
            args = listOf("/d", "/c", "\"C:\\Users\\First Last\\.mcp-steroid\\bin\\devrig.cmd\" mcp"),
        )
        val text = renderInstallConfig(windowsCommand)

        assertContains(text, "\"command\": \"cmd.exe\"")
        assertContains(text, "C:\\\\Users\\\\First Last\\\\.mcp-steroid\\\\bin\\\\devrig.cmd")
    }
}
