/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
}
