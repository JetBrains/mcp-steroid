/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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

    // ── "tests never ran" must not masquerade as "agent scored 0" ──────────────────────────────
    //
    // train-ticket-31 is a 43-module reactor. `ts-common` — a shared module the FAIL_TO_PASS tests
    // do not live in — failed to compile, so Maven SKIPPED everything downstream including
    // ts-payment-service. No surefire reports, every class graded 0, reported as `verified 0/2` and
    // `claim matches reality: false` while the agent's own run was green. A zero that means "the
    // harness never executed the tests" has to be loud, because it is indistinguishable in a report
    // from a zero that means "the agent's fix does not work".

    private val trainTicketReactorFailure = """
        [INFO] Reactor Summary:
        [INFO] ts-service-cluster 0.1.0 ........................... SUCCESS [  1.000 s]
        [INFO] ts-common 0.1.0 .................................... FAILURE [  1.948 s]
        [INFO] ts-payment-service 1.0 ............................. SKIPPED
        [INFO] BUILD FAILURE
        [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.8.1:compile
         (default-compile) on project ts-common: Fatal error compiling: java.lang.NoSuchFieldError -> [Help 1]
        [ERROR] After correcting the problems, you can resume the build with the command
        [ERROR]   mvn <args> -rf :ts-common
    """.trimIndent()

    @Test
    fun `the maven project that broke the build is identified`() {
        assertEquals("ts-common", mavenFailedProject(trainTicketReactorFailure))
    }

    @Test
    fun `a clean build reports no failing project`() {
        assertNull(mavenFailedProject("[INFO] BUILD SUCCESS\n[INFO] Total time: 12 s\n"))
    }

    @Test
    fun `the failing project is read from the goal line when no resume hint is present`() {
        val output = "[ERROR] Failed to execute goal surefire:test (default-test) on project ts-payment-service: boom"
        assertEquals("ts-payment-service", mavenFailedProject(output))
    }

    @Test
    fun `the module owning a FAIL_TO_PASS class comes from the test patch path`() {
        val patch = """
            diff --git a/ts-payment-service/src/test/java/com/trainticket/controller/PaymentControllerTest.java b/ts-payment-service/src/test/java/com/trainticket/controller/PaymentControllerTest.java
            --- a/ts-payment-service/src/test/java/com/trainticket/controller/PaymentControllerTest.java
            +++ b/ts-payment-service/src/test/java/com/trainticket/controller/PaymentControllerTest.java
        """.trimIndent()
        assertEquals(
            "ts-payment-service",
            moduleDirectoryForClass(patch, "com.trainticket.controller.PaymentControllerTest"),
        )
    }

    @Test
    fun `a single-module project reports the root as the owning module`() {
        val patch = """
            diff --git a/src/test/java/org/springframework/samples/petclinic/owner/OwnerControllerTests.java b/src/test/java/org/springframework/samples/petclinic/owner/OwnerControllerTests.java
            --- a/src/test/java/org/springframework/samples/petclinic/owner/OwnerControllerTests.java
            +++ b/src/test/java/org/springframework/samples/petclinic/owner/OwnerControllerTests.java
        """.trimIndent()
        assertEquals(
            "",
            moduleDirectoryForClass(patch, "org.springframework.samples.petclinic.owner.OwnerControllerTests"),
        )
    }

    @Test
    fun `a class the test patch never touches has no known module`() {
        assertNull(moduleDirectoryForClass("diff --git a/src/test/java/A.java b/src/test/java/A.java", "com.other.BTest"))
    }

    @Test
    fun `an unrelated module breaking the build is an infrastructure failure`() {
        assertTrue(
            verificationNeverRanTests(
                anyReportFound = false,
                ftpModuleDirectory = "ts-payment-service",
                failedMavenProject = "ts-common",
            ),
        )
    }

    @Test
    fun `the FAIL_TO_PASS module breaking its own build is a legitimate zero`() {
        // The agent broke the module it was editing — that IS the measurement, not a harness fault.
        assertFalse(
            verificationNeverRanTests(
                anyReportFound = false,
                ftpModuleDirectory = "ts-payment-service",
                failedMavenProject = "ts-payment-service",
            ),
        )
    }

    @Test
    fun `reports present means the tests ran, whatever else failed`() {
        assertFalse(
            verificationNeverRanTests(
                anyReportFound = true,
                ftpModuleDirectory = "ts-payment-service",
                failedMavenProject = "ts-common",
            ),
        )
    }

    @Test
    fun `a single-module project never blames the harness for its own compile failure`() {
        // The root project's directory is "" and can never equal an artifactId, so a naive comparison
        // would call every genuine petclinic compile failure an infrastructure fault.
        assertFalse(
            verificationNeverRanTests(
                anyReportFound = false,
                ftpModuleDirectory = "",
                failedMavenProject = "spring-petclinic",
            ),
        )
    }

    @Test
    fun `an unknown owning module cannot prove infrastructure fault`() {
        // Without knowing which module owns the tests, blaming the harness would be a guess.
        assertFalse(
            verificationNeverRanTests(
                anyReportFound = false,
                ftpModuleDirectory = null,
                failedMavenProject = "ts-common",
            ),
        )
    }

    @Test
    fun `verification result aggregates rates`() {
        val result = verificationOf(
            perClass = listOf(
                SurefireClassResult("A", 5, 0, 0, 0),
                SurefireClassResult("B", 3, 1, 0, 0),
                SurefireClassResult("C", 0, 0, 0, 0),
            ),
        )
        assertEquals(1, result.classesPassed)
        assertEquals(3, result.classesTotal)
        assertEquals(1.0 / 3.0, result.failToPassRate, 1e-9)
    }

    // ── FAIL_TO_PASS oracle files vs collateral test files ───────────────────

    /** The petclinic-71 shape: a 13-file test patch of which only 4 files define FAIL_TO_PASS classes. */
    private val petclinic71Patch = listOf(
        "src/test/java/org/springframework/samples/petclinic/service/ClinicServiceTests.java",
        "src/test/java/org/springframework/samples/petclinic/owner/OwnerControllerTests.java",
        "src/test/java/org/springframework/samples/petclinic/owner/R2dbcOwnerRepositoryTests.java",
        "src/test/java/org/springframework/samples/petclinic/vet/R2dbcVetRepositoryTests.java",
    ).joinToString("\n") { "diff --git a/$it b/$it" }

    private val petclinic71FailToPass = listOf(
        "org.springframework.samples.petclinic.owner.R2dbcOwnerRepositoryTests",
        "org.springframework.samples.petclinic.vet.R2dbcVetRepositoryTests",
    )

    @Test
    fun `only the files defining FAIL_TO_PASS classes count as the oracle`() {
        val oracle = failToPassFilePaths(petclinic71Patch, petclinic71FailToPass)
        assertEquals(
            setOf(
                "src/test/java/org/springframework/samples/petclinic/owner/R2dbcOwnerRepositoryTests.java",
                "src/test/java/org/springframework/samples/petclinic/vet/R2dbcVetRepositoryTests.java",
            ),
            oracle,
        )
    }

    @Test
    fun `a method-scoped FAIL_TO_PASS entry still resolves to its file`() {
        val patch = "diff --git a/src/test/java/com/example/FooTest.java b/src/test/java/com/example/FooTest.java"
        assertEquals(
            setOf("src/test/java/com/example/FooTest.java"),
            failToPassFilePaths(patch, listOf("com.example.FooTest#someMethod")),
        )
    }

    @Test
    fun `editing collateral test files is not tampering with the oracle`() {
        // The prompt itself tells the agent to update test classes its production change breaks, and the
        // JPA→R2DBC scenarios cannot pass without doing so. Round 4 flagged all six petclinic-71 arms as
        // tampered on exactly these edits while every FAIL_TO_PASS file stayed untouched.
        val before = mapOf(
            "src/test/java/org/springframework/samples/petclinic/service/ClinicServiceTests.java" to "aaa",
            "src/test/java/org/springframework/samples/petclinic/owner/R2dbcOwnerRepositoryTests.java" to "bbb",
        )
        val after = before + mapOf(
            "src/test/java/org/springframework/samples/petclinic/service/ClinicServiceTests.java" to "CHANGED",
        )
        val changed = changedPaths(before, after)
        val oracle = failToPassFilePaths(petclinic71Patch, petclinic71FailToPass)
        assertFalse(changed.any { it in oracle })
        assertEquals(
            listOf("src/test/java/org/springframework/samples/petclinic/service/ClinicServiceTests.java"),
            changed.filterNot { it in oracle },
        )
    }

    @Test
    fun `rewriting a FAIL_TO_PASS file is tampering`() {
        val path = "src/test/java/org/springframework/samples/petclinic/vet/R2dbcVetRepositoryTests.java"
        val changed = changedPaths(mapOf(path to "aaa"), mapOf(path to "bbb"))
        assertTrue(changed.any { it in failToPassFilePaths(petclinic71Patch, petclinic71FailToPass) })
    }

    @Test
    fun `a deleted test file counts as changed`() {
        assertEquals(listOf("A.java"), changedPaths(mapOf("A.java" to "aaa"), mapOf("A.java" to "ABSENT")))
    }

    // ── Whole-suite index parsing ────────────────────────────────────────────

    @Test
    fun `parses the whole-suite report index`() {
        val index = """
            /p/target/surefire-reports/TEST-com.example.GreenTest.xml	<testsuite xmlns:xsi="x" name="com.example.GreenTest" time="0.4" tests="7" errors="0" skipped="0" failures="0">
            /p/m2/target/surefire-reports/TEST-com.example.RedTest.xml	<testsuite name="com.example.RedTest" tests="4" errors="1" skipped="0" failures="2">
        """.trimIndent()
        val parsed = parseSurefireSuiteIndex(index)
        assertEquals(2, parsed.size)
        assertEquals("com.example.GreenTest", parsed[0].className)
        assertEquals(7, parsed[0].testsRun)
        assertTrue(parsed[0].passed)
        assertEquals(1, parsed[1].errors)
        assertEquals(2, parsed[1].failures)
        assertFalse(parsed[1].passed)
    }

    @Test
    fun `index lines without a parsable testsuite tag are skipped`() {
        val index = "/p/target/surefire-reports/TEST-Truncated.xml\t<?xml version=\"1.0\"?>\n\n"
        assertTrue(parseSurefireSuiteIndex(index).isEmpty())
    }

    // ── Regressions vs the pre-agent baseline ────────────────────────────────

    private fun snapshot(vararg classes: Pair<String, Boolean>) = FullSuiteSnapshot(
        perClass = classes.map { (name, ok) ->
            if (ok) SurefireClassResult(name, 3, 0, 0, 0) else SurefireClassResult(name, 3, 1, 0, 0)
        },
        mavenExitCode = 0,
    )

    @Test
    fun `a class that used to pass and now fails is a regression`() {
        val regressed = regressedClasses(
            before = snapshot("A" to true, "B" to true),
            after = snapshot("A" to true, "B" to false),
        )
        assertEquals(listOf("B"), regressed)
    }

    @Test
    fun `a test already failing before the agent ran is not a regression`() {
        // The train-ticket-1 shape: 17 pre-existing ts-contacts-service Mockito errors in a module the
        // agent never touched. Charging those to the agent is what made it refuse a success marker for a
        // task it had actually completed.
        val regressed = regressedClasses(
            before = snapshot("Fixed" to true, "AlreadyRed" to false),
            after = snapshot("Fixed" to true, "AlreadyRed" to false),
        )
        assertTrue(regressed.isEmpty())
    }

    @Test
    fun `a class absent from the baseline is not a regression`() {
        // Its module may simply never have built at baseline; guessing would recreate the false-zero bug.
        val regressed = regressedClasses(
            before = snapshot("A" to true),
            after = snapshot("A" to true, "NeverSeenBefore" to false),
        )
        assertTrue(regressed.isEmpty())
    }

    @Test
    fun `FAIL_TO_PASS classes are excluded from regressions`() {
        val regressed = regressedClasses(
            before = snapshot("Ftp" to true),
            after = snapshot("Ftp" to false),
            excluded = setOf("Ftp"),
        )
        assertTrue(regressed.isEmpty())
    }

    @Test
    fun `a class that vanishes after the agent ran is not reported as a regression`() {
        // Absent is "unknown", not "broken" — only a report that says it failed counts.
        assertTrue(regressedClasses(before = snapshot("A" to true), after = snapshot()).isEmpty())
    }

    // ── Baseline usability ───────────────────────────────────────────────────

    @Test
    fun `javac names the release the pristine repository needs`() {
        // Verbatim from build 1025034600 — Maven keeps the whole goal failure on ONE line.
        val tail = "[INFO] BUILD FAILURE\n" +
            "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.14.0:compile " +
            "(default-compile) on project empty-spring-boot3: Fatal error compiling: error: release " +
            "version 24 not supported -> [Help 1]\n"
        assertEquals("24", requiredJavaReleaseFromError(tail))
    }

    @Test
    fun `a suite that merely failed its tests names no required release`() {
        assertNull(requiredJavaReleaseFromError("[ERROR] Tests run: 58, Failures: 0, Errors: 2"))
    }

    @Test
    fun `a suite that ran nothing and failed cannot serve as a baseline`() {
        // The springboot3 shape: `release version 24 not supported`, exit 1, no reports. Calling that
        // "nothing to regress" would publish a measured-looking zero for a scenario where regressions
        // were never observable.
        assertFalse(FullSuiteSnapshot(perClass = emptyList(), mavenExitCode = 1).usableAsBaseline)
    }

    @Test
    fun `an empty project that builds cleanly is still a valid baseline`() {
        assertTrue(FullSuiteSnapshot(perClass = emptyList(), mavenExitCode = 0).usableAsBaseline)
    }

    @Test
    fun `a snapshot with reports is a valid baseline whatever Maven exited with`() {
        // -fae plus maven.test.failure.ignore normally give exit 0, but a late module failure can still
        // set a non-zero code while every earlier module reported honestly.
        assertTrue(
            FullSuiteSnapshot(
                perClass = listOf(SurefireClassResult("A", 3, 0, 0, 0)),
                mavenExitCode = 1,
            ).usableAsBaseline,
        )
    }

    // ── objectiveSuccess ─────────────────────────────────────────────────────

    @Test
    fun `objective success needs every FAIL_TO_PASS class green and no regression`() {
        assertTrue(verificationOf(listOf(SurefireClassResult("A", 2, 0, 0, 0))).objectiveSuccess)
        assertFalse(verificationOf(listOf(SurefireClassResult("A", 2, 1, 0, 0))).objectiveSuccess)
        assertFalse(
            verificationOf(listOf(SurefireClassResult("A", 2, 0, 0, 0)), regressions = listOf("Other"))
                .objectiveSuccess,
        )
    }

    @Test
    fun `objective success is false when no FAIL_TO_PASS class was graded at all`() {
        assertFalse(verificationOf(emptyList()).objectiveSuccess)
    }

    private fun verificationOf(
        perClass: List<SurefireClassResult>,
        regressions: List<String> = emptyList(),
    ) = ArenaVerificationResult(
        perClass = perClass,
        failToPassTampered = false,
        collateralTestFilesEdited = emptyList(),
        regressions = regressions,
        baselineAvailable = true,
        verificationDurationMs = 1L,
    )
}
