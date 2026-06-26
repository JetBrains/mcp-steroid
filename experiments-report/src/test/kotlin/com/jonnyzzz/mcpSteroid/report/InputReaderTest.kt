package com.jonnyzzz.mcpSteroid.report

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class InputReaderTest {
    private fun fixtureText(name: String): String =
        requireNotNull(javaClass.classLoader.getResourceAsStream("fixtures/$name")) { "missing $name" }
            .bufferedReader().readText()

    private fun place(dir: Path, rel: String, content: String) {
        val f = dir.resolve(rel).toFile()
        f.parentFile.mkdirs()
        f.writeText(content)
    }

    @Test
    fun `reads build logs and summary jsons from a directory tree and merges by run`(@TempDir dir: Path) {
        place(dir, "builds/build-123/log.txt", fixtureText("arena-log-petclinic27-claude.txt"))
        place(dir, "builds/build-123/summaries/run-mcp.json", fixtureText("dpaia-arena-run-petclinic27-claude-mcp.json"))

        val runs = InputReader.read(dir.toFile())

        assertEquals(2, runs.size, "with + without, merged across log and json")
        val withMcp = runs.single { it.mode == McpMode.WITH }
        assertEquals("claude", withMcp.agent)
        assertEquals("dpaia__spring__petclinic-27", withMcp.scenario)
        assertEquals(3.21, withMcp.costUsd)
        assertNotNull(runs.single { it.mode == McpMode.WITHOUT })
    }

    @Test
    fun `enriches a run with ndjson metrics matched by mode within a build folder`(@TempDir dir: Path) {
        place(dir, "builds/petclinic27-claude/log.txt", fixtureText("arena-log-petclinic27-claude.txt"))
        place(dir, "builds/petclinic27-claude/runs/mcp/agent.ndjson", fixtureText("agent-claude-ndjson-sample.ndjson"))

        val runs = InputReader.read(dir.toFile())
        val withMcp = runs.single { it.mode == McpMode.WITH }
        // scenario+agent still come from the (authoritative, self-identifying) log
        assertEquals("dpaia__spring__petclinic-27", withMcp.scenario)
        assertEquals("claude", withMcp.agent)
        // model / version / token budget / tool calls come from the ndjson
        assertEquals("claude-opus-4-6", withMcp.model)
        assertEquals("2.1.119", withMcp.agentVersion)
        assertEquals(200_000L, withMcp.contextWindow)
        assertEquals(2, withMcp.toolCalls["Read"])
        // the without-mcp run (log only, no ndjson) carries no model
        assertEquals(null, runs.single { it.mode == McpMode.WITHOUT }.model)
    }

    @Test
    fun `ignores files that are not arena logs or summaries`(@TempDir dir: Path) {
        place(dir, "random.txt", "nothing to see here")
        place(dir, "notes.md", "# hello")
        assertEquals(0, InputReader.read(dir.toFile()).size)
    }
}
