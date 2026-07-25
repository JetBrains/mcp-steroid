/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import javax.xml.parsers.DocumentBuilderFactory

data class SurefireClassResult(
    val className: String,
    val testsRun: Int,
    val failures: Int,
    val errors: Int,
    val skipped: Int,
) {
    val passed: Boolean get() = testsRun > 0 && failures == 0 && errors == 0
}

data class ArenaVerificationResult(
    /** One entry per expected FAIL_TO_PASS class; classes with no surefire XML get testsRun=0. */
    val perClass: List<SurefireClassResult>,
    /** True when any file touched by the test patch changed after the agent ran. */
    val testsTampered: Boolean,
    val verificationDurationMs: Long,
) {
    val classesPassed: Int get() = perClass.count { it.passed }
    val classesTotal: Int get() = perClass.size
    val failToPassRate: Double get() = if (perClass.isEmpty()) 0.0 else classesPassed.toDouble() / perClass.size
}

/**
 * Parse one surefire `TEST-*.xml` report. Only the `<testsuite>` root attributes are needed —
 * per-testcase details stay in the container for manual inspection.
 */
fun parseSurefireXml(xmlText: String): SurefireClassResult {
    val doc = DocumentBuilderFactory.newInstance()
        .apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        .newDocumentBuilder()
        .parse(xmlText.trim().byteInputStream())
    val suite = doc.documentElement
    require(suite.tagName == "testsuite") { "Not a surefire report root: <${suite.tagName}>" }
    fun attr(name: String) = suite.getAttribute(name).ifBlank { "0" }.toInt()
    return SurefireClassResult(
        className = suite.getAttribute("name"),
        testsRun = attr("tests"),
        failures = attr("failures"),
        errors = attr("errors"),
        skipped = attr("skipped"),
    )
}

private val DIFF_FILE_HEADER = Regex("""^diff --git a/(\S+) b/\S+$""", RegexOption.MULTILINE)

/** All file paths a unified diff touches ("a/" side is enough — arena patches never rename). */
fun extractPatchFilePaths(patch: String): Set<String> =
    DIFF_FILE_HEADER.findAll(patch).map { it.groupValues[1] }.toSet()

/**
 * Fails loud if any [paths] would break out of the single-quoted shell interpolation used to build the
 * hashing/verification scripts (`'$projectDir/$it'`). Paths come from an external dataset's unified diff,
 * so this is an injection guard, not a formatting nicety — reject rather than attempt to escape.
 */
fun requireSafeShellPaths(paths: Collection<String>) {
    val bad = paths.filter { '\'' in it || '\n' in it }
    require(bad.isEmpty()) { "Unsupported character in test-patch path(s), refusing to shell-interpolate: $bad" }
}

private val FQCN_REGEX = Regex("""^[A-Za-z0-9_.]+$""")

/** Fails loud if any [fqcns] contain characters outside a Java fully-qualified class name. */
fun requireSafeFqcns(fqcns: Collection<String>) {
    val bad = fqcns.filterNot { FQCN_REGEX.matches(it) }
    require(bad.isEmpty()) { "Unsupported character in FAIL_TO_PASS class name(s), refusing to shell-interpolate: $bad" }
}

/**
 * Runs the harness-side, objective FAIL_TO_PASS verification for one arena run: hashes the test-patch
 * files before the agent runs, then re-runs all FAIL_TO_PASS classes in one Maven invocation afterward
 * and grades them from the surefire XML — independent of whatever the agent claimed in its transcript.
 */
class ArenaVerifier(
    private val container: ContainerDriver,
    private val projectDir: String,
) {
    private fun bash(script: String, timeoutSeconds: Long, description: String): ProcessResult =
        container.startProcessInContainer {
            this.args("bash", "-lc", script).timeoutSeconds(timeoutSeconds).description(description)
        }.awaitForProcessFinish()

    /** sha256 of every test-patch file, taken BEFORE the agent runs. Path → hash; missing files map to "ABSENT". */
    fun snapshotTestFiles(testPatch: String): Map<String, String> =
        hashTestFiles(extractPatchFilePaths(testPatch))

    private fun hashTestFiles(paths: Set<String>): Map<String, String> {
        if (paths.isEmpty()) return emptyMap()
        requireSafeShellPaths(paths)
        val list = paths.joinToString(" ") { "'$projectDir/$it'" }
        val out = bash(
            "for f in $list; do if [ -f \"${'$'}f\" ]; then sha256sum \"${'$'}f\"; else echo \"ABSENT  ${'$'}f\"; fi; done",
            timeoutSeconds = 60,
            description = "Hash arena test-patch files",
        )
        return out.stdout.lineSequence().filter { it.isNotBlank() }.associate { line ->
            val (hash, file) = line.trim().split(Regex("\\s+"), limit = 2)
            file.removePrefix("$projectDir/") to hash
        }
    }

    /**
     * Run all [failToPass] classes in ONE Maven invocation and grade from surefire XML.
     * Maven exit code is ignored (failures are data, not errors); a class with no XML
     * (compile failure / not run) grades as testsRun=0 → not passed.
     */
    fun verify(
        failToPass: List<String>,
        projectJdkVersion: String,
        testPatch: String,
        preAgentSnapshot: Map<String, String>?,
    ): ArenaVerificationResult {
        val startMs = System.currentTimeMillis()

        // A null snapshot means the pre-agent hashing itself failed (infra hiccup, logged by the
        // caller); tamper detection needs a baseline to diff against, so it's skipped rather than
        // treated as tampering.
        val tampered = preAgentSnapshot?.let { hashTestFiles(extractPatchFilePaths(testPatch)) != it } ?: false

        requireSafeFqcns(failToPass)
        val testFilter = failToPass.map { it.substringAfterLast('.') }.distinct().joinToString(",")
        val javaHome = bash(
            "ls -d /usr/lib/jvm/temurin-$projectJdkVersion-jdk-* 2>/dev/null | head -1",
            timeoutSeconds = 20,
            description = "Resolve verification JAVA_HOME",
        ).stdout.trim()
        check(javaHome.isNotBlank()) { "No temurin-$projectJdkVersion JDK in the arena container" }

        bash("rm -rf '$projectDir/target/surefire-reports'", 30, "Clean stale surefire reports")
        val mvn = bash(
            // pipefail: without it the logged exit code is tail's (always 0), not Maven's.
            "set -o pipefail && cd '$projectDir' && JAVA_HOME='$javaHome' ./mvnw test " +
                "-Dtest='$testFilter' -Dsurefire.failIfNoSpecifiedTests=false -Dspotless.check.skip=true " +
                "2>&1 | tail -100",
            timeoutSeconds = 1_200,
            description = "Arena verification: run FAIL_TO_PASS classes",
        )
        println("[ARENA-VERIFY] Maven exit=${mvn.exitCode}; tail:\n${mvn.stdout}")

        val perClass = failToPass.map { fqcn ->
            val xml = bash(
                "cat '$projectDir/target/surefire-reports/TEST-$fqcn.xml' 2>/dev/null",
                timeoutSeconds = 30,
                description = "Read surefire report for $fqcn",
            ).stdout
            if (xml.isBlank()) {
                SurefireClassResult(className = fqcn, testsRun = 0, failures = 0, errors = 0, skipped = 0)
            } else {
                parseSurefireXml(xml)
            }
        }

        return ArenaVerificationResult(
            perClass = perClass,
            testsTampered = tampered,
            verificationDurationMs = System.currentTimeMillis() - startMs,
        )
    }
}
