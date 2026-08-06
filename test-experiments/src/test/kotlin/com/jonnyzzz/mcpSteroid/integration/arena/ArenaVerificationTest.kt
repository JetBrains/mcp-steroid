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

    // ── Method-level FAIL_TO_PASS entries (petclinic-36 graded 2/4 on every run) ──────────────
    //
    // Surefire writes ONE report per CLASS. A dataset entry of the form `fqcn#method` was used
    // verbatim as the report filename, so `TEST-…ValidatorTests#shouldValidate….xml` never existed
    // and both method-level entries graded testsRun=0 — a permanent 2/4 that looked like the agent
    // failing half the task.

    private val validatorXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <testsuite name="org.springframework.samples.petclinic.model.ValidatorTests"
                   time="0.5" tests="3" errors="0" skipped="0" failures="1">
          <testcase name="shouldValidateWhenEmailFormatIsValid"
                    classname="org.springframework.samples.petclinic.model.ValidatorTests" time="0.1"/>
          <testcase name="shouldNotValidateWhenEmailFormatIsInvalid"
                    classname="org.springframework.samples.petclinic.model.ValidatorTests" time="0.1">
            <failure message="expected the email to be rejected" type="java.lang.AssertionError">stack</failure>
          </testcase>
          <testcase name="shouldRejectBlankNames[1]"
                    classname="org.springframework.samples.petclinic.model.ValidatorTests" time="0.1"/>
        </testsuite>
    """.trimIndent()

    @Test
    fun `a passing method-level entry grades from its own testcase`() {
        val r = parseSurefireXml(validatorXml, methodName = "shouldValidateWhenEmailFormatIsValid")
        assertEquals(1, r.testsRun)
        assertEquals(0, r.failures)
        assertEquals(0, r.errors)
        assertTrue(r.passed)
    }

    @Test
    fun `a failing method-level entry does not inherit the suite verdict`() {
        val r = parseSurefireXml(validatorXml, methodName = "shouldNotValidateWhenEmailFormatIsInvalid")
        assertEquals(1, r.testsRun)
        assertEquals(1, r.failures)
        assertFalse(r.passed)
    }

    @Test
    fun `a method absent from the report grades as not run`() {
        val r = parseSurefireXml(validatorXml, methodName = "methodThatWasNeverExecuted")
        assertEquals(0, r.testsRun)
        assertFalse(r.passed)
    }

    @Test
    fun `a parameterized method matches its bracketed testcase names`() {
        val r = parseSurefireXml(validatorXml, methodName = "shouldRejectBlankNames")
        assertEquals(1, r.testsRun)
        assertTrue(r.passed)
    }

    @Test
    fun `class-level grading still reads the suite attributes`() {
        val r = parseSurefireXml(validatorXml)
        assertEquals(3, r.testsRun)
        assertEquals(1, r.failures)
        assertFalse(r.passed)
    }

    // ── Surefire -Dtest= filter construction ──────────────────────────────────────────────────

    @Test
    fun `a class-only entry filters by simple class name`() {
        assertEquals("OwnerControllerTests", surefireTestFilter(listOf("a.b.OwnerControllerTests")))
    }

    @Test
    fun `two methods of one class collapse into surefire's plus syntax`() {
        // `Class#m1,Class#m2` makes surefire honour only the last selector for that class;
        // `Class#m1+m2` is the documented shape for several methods of one class.
        assertEquals(
            "ValidatorTests#shouldValidateWhenEmailFormatIsValid+shouldNotValidateWhenEmailFormatIsInvalid",
            surefireTestFilter(
                listOf(
                    "org.springframework.samples.petclinic.model.ValidatorTests#shouldValidateWhenEmailFormatIsValid",
                    "org.springframework.samples.petclinic.model.ValidatorTests#shouldNotValidateWhenEmailFormatIsInvalid",
                ),
            ),
        )
    }

    @Test
    fun `distinct classes stay comma-separated in input order`() {
        assertEquals(
            "ValidatorTests#onlyThis,OwnerControllerTests",
            surefireTestFilter(listOf("a.b.ValidatorTests#onlyThis", "a.b.OwnerControllerTests")),
        )
    }

    @Test
    fun `a whole-class entry wins over method entries for the same class`() {
        // Running the entire class already covers every method selector for it.
        assertEquals(
            "ValidatorTests",
            surefireTestFilter(listOf("a.b.ValidatorTests#one", "a.b.ValidatorTests")),
        )
    }

    @Test
    fun `an entry splits into its class and optional method`() {
        assertEquals(FailToPassSelector("a.b.C", "m"), parseFailToPassEntry("a.b.C#m"))
        assertEquals(FailToPassSelector("a.b.C", null), parseFailToPassEntry("a.b.C"))
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
