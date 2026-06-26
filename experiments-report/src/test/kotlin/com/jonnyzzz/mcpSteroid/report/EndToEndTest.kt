package com.jonnyzzz.mcpSteroid.report

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertTrue

class EndToEndTest {
    private fun fixtureText(name: String): String =
        requireNotNull(javaClass.classLoader.getResourceAsStream("fixtures/$name")) { "missing $name" }
            .bufferedReader().readText()

    private fun place(dir: Path, rel: String, content: String) {
        val f = dir.resolve(rel).toFile()
        f.parentFile.mkdirs()
        f.writeText(content)
    }

    @Test
    fun `builds a report and renders dashboard html from a collected input dir`(@TempDir dir: Path) {
        place(dir, "builds/petclinic27-claude/log.txt", fixtureText("arena-log-petclinic27-claude.txt"))

        val report = buildReport(dir.toFile(), "Test Dashboard", "2026-06-26T00:00:00Z")
        assertTrue(report.comparisons.isNotEmpty(), "found at least one comparison")
        val pet = report.comparisons.single { it.scenario == "dpaia__spring__petclinic-27" && it.agent == "claude" }
        // both claimed a fix => neutral, but the MCP run was faster (521s vs 711s)
        assertTrue(pet.verdict == Verdict.NEUTRAL)
        assertTrue(pet.durationDeltaMs == -190_000L)

        val html = HtmlRenderer.render(report)
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.contains("dpaia__spring__petclinic-27"))
        assertTrue(html.contains("IDE preparation"))
    }
}
