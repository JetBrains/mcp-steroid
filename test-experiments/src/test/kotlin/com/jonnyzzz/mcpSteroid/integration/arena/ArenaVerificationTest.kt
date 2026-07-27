/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

class ArenaVerificationTest {

    private val passingXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <testsuite xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" version="3.0"
                   name="com.sivalabs.ft.features.domain.ReleaseStatusTransitionValidatorTest"
                   time="0.102" tests="25" errors="0" skipped="0" failures="0">
          <testcase name="testValidTransition_DraftToPlanned" classname="com.sivalabs.ft.features.domain.ReleaseStatusTransitionValidatorTest" time="0.01"/>
        </testsuite>
    """.trimIndent()

    private val failingXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <testsuite name="com.sivalabs.ft.features.api.controllers.ReleaseControllerTests"
                   time="12.4" tests="8" errors="2" skipped="1" failures="3">
          <testcase name="t" classname="com.sivalabs.ft.features.api.controllers.ReleaseControllerTests" time="0.1">
            <failure message="expected 400 but was 200" type="java.lang.AssertionError">stack</failure>
          </testcase>
        </testsuite>
    """.trimIndent()

    @Test
    fun `parses passing surefire suite`() {
        val r = parseSurefireXml(passingXml)
        assertEquals("com.sivalabs.ft.features.domain.ReleaseStatusTransitionValidatorTest", r.className)
        assertEquals(25, r.testsRun)
        assertEquals(0, r.failures)
        assertEquals(0, r.errors)
        assertEquals(0, r.skipped)
        assertTrue(r.passed)
    }

    @Test
    fun `parses failing surefire suite`() {
        val r = parseSurefireXml(failingXml)
        assertEquals(8, r.testsRun)
        assertEquals(3, r.failures)
        assertEquals(2, r.errors)
        assertEquals(1, r.skipped)
        assertFalse(r.passed)
    }

    @Test
    fun `class with zero executed tests is not passed`() {
        assertFalse(SurefireClassResult("X", 0, 0, 0, 0).passed)
    }

    @Test
    fun `extracts file paths from a unified diff`() {
        val patch = """
            diff --git a/src/test/java/com/example/FooTest.java b/src/test/java/com/example/FooTest.java
            new file mode 100644
            --- /dev/null
            +++ b/src/test/java/com/example/FooTest.java
            @@ -0,0 +1,3 @@
            +class FooTest {
            +}
            diff --git a/src/test/resources/test-data.sql b/src/test/resources/test-data.sql
            --- a/src/test/resources/test-data.sql
            +++ b/src/test/resources/test-data.sql
        """.trimIndent()
        assertEquals(
            setOf("src/test/java/com/example/FooTest.java", "src/test/resources/test-data.sql"),
            extractPatchFilePaths(patch),
        )
    }

    @Test
    fun `verification result aggregates rates`() {
        val result = ArenaVerificationResult(
            perClass = listOf(
                SurefireClassResult("A", 5, 0, 0, 0),
                SurefireClassResult("B", 3, 1, 0, 0),
                SurefireClassResult("C", 0, 0, 0, 0),
            ),
            testsTampered = false,
            verificationDurationMs = 1L,
        )
        assertEquals(1, result.classesPassed)
        assertEquals(3, result.classesTotal)
        assertEquals(1.0 / 3.0, result.failToPassRate, 1e-9)
    }

    @Test
    fun `accepts ordinary test-patch paths`() {
        requireSafeShellPaths(listOf("src/test/java/com/example/FooTest.java", "src/test/resources/test-data.sql"))
    }

    @Test
    fun `rejects a path containing a single quote`() {
        assertThrows(IllegalArgumentException::class.java) {
            requireSafeShellPaths(listOf("src/test/java/com/example/Foo'; rm -rf /; echo 'Test.java"))
        }
    }

    @Test
    fun `rejects a path containing a newline`() {
        assertThrows(IllegalArgumentException::class.java) {
            requireSafeShellPaths(listOf("src/test/java/Foo.java\nrm -rf /"))
        }
    }

    @Test
    fun `accepts ordinary fully-qualified class names`() {
        requireSafeFqcns(listOf("com.example.FooTest", "com.example.bar.BazTest"))
    }

    @Test
    fun `rejects a class name with shell metacharacters`() {
        assertThrows(IllegalArgumentException::class.java) {
            requireSafeFqcns(listOf("com.example.FooTest;rm -rf /"))
        }
    }

    @TempDir
    lateinit var tempDir: File

    private fun bash(script: String): String {
        val out = File.createTempFile("bash-out", ".txt")
        val err = File.createTempFile("bash-err", ".txt")
        try {
            val process = ProcessBuilder("bash", "-c", script)
                .redirectOutput(out).redirectError(err).start()
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "bash script timed out: $script")
            assertEquals(0, process.exitValue(), "bash script failed: ${err.readText()}")
            return out.readText()
        } finally {
            out.delete()
            err.delete()
        }
    }

    private fun writeReport(module: String, fqcn: String, tests: Int): File {
        val file = File(tempDir, "$module/target/surefire-reports/TEST-$fqcn.xml")
        file.parentFile.mkdirs()
        file.writeText("""<testsuite name="$fqcn" tests="$tests" errors="0" skipped="0" failures="0"/>""")
        return file
    }

    @Test
    fun `report lookup finds a report in a nested reactor module`() {
        val fqcn = "com.example.FooTest"
        writeReport("services/order-service", fqcn, tests = 4)

        val xml = bash(surefireReportLookupScript(tempDir.absolutePath, fqcn))

        assertEquals(4, parseSurefireXml(xml).testsRun)
    }

    @Test
    fun `report lookup prints nothing when no module produced a report`() {
        writeReport("services/order-service", "com.example.OtherTest", tests = 1)

        assertEquals("", bash(surefireReportLookupScript(tempDir.absolutePath, "com.example.FooTest")).trim())
    }

    @Test
    fun `report lookup prefers the most recently written module report`() {
        val fqcn = "com.example.FooTest"
        val old = writeReport("services/a", fqcn, tests = 1)
        val fresh = writeReport("services/b", fqcn, tests = 2)
        old.setLastModified(fresh.lastModified() - 60_000)

        assertEquals(2, parseSurefireXml(bash(surefireReportLookupScript(tempDir.absolutePath, fqcn))).testsRun)
    }

    @Test
    fun `clean removes every module's surefire reports and nothing else`() {
        val a = writeReport("services/a", "com.example.FooTest", tests = 1)
        val b = writeReport("services/b", "com.example.BarTest", tests = 1)
        val root = writeReport(".", "com.example.RootTest", tests = 1)
        val classFile = File(tempDir, "services/a/target/classes/Foo.class").apply {
            parentFile.mkdirs()
            writeText("keep me")
        }

        bash(surefireCleanScript(tempDir.absolutePath))

        assertFalse(a.exists())
        assertFalse(b.exists())
        assertFalse(root.exists())
        assertTrue(classFile.exists(), "Clean must only remove surefire-reports directories")
    }
}
