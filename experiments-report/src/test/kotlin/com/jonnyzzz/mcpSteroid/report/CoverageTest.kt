package com.jonnyzzz.mcpSteroid.report

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverageTest {
    private fun fixtureText(name: String): String =
        requireNotNull(javaClass.classLoader.getResourceAsStream("fixtures/$name")) { "missing $name" }
            .bufferedReader().readText()

    private fun place(dir: Path, rel: String, content: String) {
        val f = dir.resolve(rel).toFile(); f.parentFile.mkdirs(); f.writeText(content)
    }

    @Test
    fun `collects build metas including a failed build that produced no parseable runs`(@TempDir dir: Path) {
        // a healthy build with a log...
        place(dir, "builds/petclinic27-claude__1/log.txt", fixtureText("arena-log-petclinic27-claude.txt"))
        place(dir, "builds/petclinic27-claude__1/meta.json",
            """{"buildConfigId":"X_Petclinic27_Claude","buildId":1,"scenario":"Petclinic27","agent":"claude","status":"SUCCESS"}""")
        // ...and a FAILED build that emitted no [ARENA] data (failed during IDE prep)
        place(dir, "builds/feature125-claude__2/log.txt", "infra exploded before the agent ran")
        place(dir, "builds/feature125-claude__2/meta.json",
            """{"buildConfigId":"X_FeatureService125_Claude","buildId":2,"scenario":"FeatureService125","agent":"claude","status":"FAILURE"}""")

        val report = buildReport(dir.toFile(), "Coverage", "2026-06-27T00:00:00Z")

        assertEquals(2, report.collectedBuilds.size, "both builds are reported as collected")
        val failed = report.collectedBuilds.single { it.status == "FAILURE" }
        assertEquals("FeatureService125", failed.scenario)

        // the healthy build yields runs; the failed one yields none
        assertTrue(report.allRuns.any { it.scenario == "dpaia__spring__petclinic-27" })
        assertTrue(report.allRuns.none { it.scenario == "FeatureService125" })

        // and the rendered dashboard discloses the gap
        val html = HtmlRenderer.render(report)
        assertTrue(html.contains("Coverage"))
        assertTrue(html.contains("FeatureService125"))
    }
}
