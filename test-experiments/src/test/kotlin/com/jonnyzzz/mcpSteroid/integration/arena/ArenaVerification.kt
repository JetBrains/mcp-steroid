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

/** One FAIL_TO_PASS dataset entry: a class, plus the single method when the entry names one. */
data class FailToPassSelector(val fqcn: String, val method: String?) {
    /** Surefire's simple-name selector for this entry, e.g. `ValidatorTests` or `ValidatorTests#ok`. */
    val simpleName: String get() = fqcn.substringAfterLast('.')
}

/** Split `org.example.FooTest#someMethod` into its class and method halves. */
fun parseFailToPassEntry(entry: String): FailToPassSelector =
    FailToPassSelector(
        fqcn = entry.substringBefore('#').trim(),
        method = entry.substringAfter('#', missingDelimiterValue = "").trim().ifBlank { null },
    )

/**
 * Build the `-Dtest=` value covering every [failToPass] entry.
 *
 * Methods of the same class are joined with surefire's `Class#m1+m2` syntax: passing
 * `Class#m1,Class#m2` makes surefire honour only the last selector for that class, silently skipping
 * the earlier methods. A whole-class entry supersedes any method entry for the same class.
 */
fun surefireTestFilter(failToPass: List<String>): String {
    val methodsByClass = LinkedHashMap<String, MutableList<String>?>()
    for (entry in failToPass) {
        val selector = parseFailToPassEntry(entry)
        val existing = methodsByClass[selector.simpleName]
        when {
            // `null` marks "run the whole class" and is never downgraded back to a method list.
            selector.method == null -> methodsByClass[selector.simpleName] = null
            selector.simpleName !in methodsByClass -> methodsByClass[selector.simpleName] = mutableListOf(selector.method)
            existing != null -> existing.add(selector.method)
        }
    }
    return methodsByClass.entries.joinToString(",") { (simpleName, methods) ->
        if (methods.isNullOrEmpty()) simpleName else "$simpleName#${methods.joinToString("+")}"
    }
}

/**
 * Parse one surefire `TEST-*.xml` report.
 *
 * With [methodName] null the `<testsuite>` root attributes are the verdict. With [methodName] set,
 * only that method's `<testcase>` counts — surefire writes ONE report per class, so a dataset entry
 * naming a single method must be graded from inside the suite rather than from the suite total (a
 * class whose OTHER tests fail would otherwise fail an entry that actually passed). Parameterized
 * and templated tests report as `method[1]` / `method(String)`, so those prefixes match too.
 */
fun parseSurefireXml(xmlText: String, methodName: String? = null): SurefireClassResult {
    val doc = DocumentBuilderFactory.newInstance()
        .apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        .newDocumentBuilder()
        .parse(xmlText.trim().byteInputStream())
    val suite = doc.documentElement
    require(suite.tagName == "testsuite") { "Not a surefire report root: <${suite.tagName}>" }
    val className = suite.getAttribute("name")

    if (methodName == null) {
        fun attr(name: String) = suite.getAttribute(name).ifBlank { "0" }.toInt()
        return SurefireClassResult(
            className = className,
            testsRun = attr("tests"),
            failures = attr("failures"),
            errors = attr("errors"),
            skipped = attr("skipped"),
        )
    }

    val cases = suite.getElementsByTagName("testcase")
    var testsRun = 0
    var failures = 0
    var errors = 0
    var skipped = 0
    for (i in 0 until cases.length) {
        val case = cases.item(i) as? org.w3c.dom.Element ?: continue
        val name = case.getAttribute("name")
        val matches = name == methodName ||
            name.startsWith("$methodName[") ||
            name.startsWith("$methodName(")
        if (!matches) continue
        testsRun++
        failures += case.getElementsByTagName("failure").length
        errors += case.getElementsByTagName("error").length
        skipped += case.getElementsByTagName("skipped").length
    }
    return SurefireClassResult(className, testsRun, failures, errors, skipped)
}

private val MAVEN_RESUME_HINT = Regex("""-rf\s+:(\S+)""")
private val MAVEN_FAILED_GOAL = Regex("""Failed to execute goal .*? on project (\S+?):""")

/**
 * The Maven project (artifactId) whose failure stopped the reactor, or null when nothing failed.
 *
 * Maven names it twice: in the `mvn <args> -rf :<artifactId>` resume hint and in the
 * `Failed to execute goal … on project <artifactId>:` line. The resume hint is preferred because it
 * survives multi-line wrapping of the goal line.
 */
fun mavenFailedProject(output: String): String? =
    MAVEN_RESUME_HINT.find(output)?.groupValues?.get(1)
        ?: MAVEN_FAILED_GOAL.find(output)?.groupValues?.get(1)

/**
 * The module directory owning [fqcn], derived from the test patch that introduced its file, or null
 * when the patch never touches that class. Returns "" for a single-module project (path starts at
 * `src/`), which is the reactor root.
 */
fun moduleDirectoryForClass(testPatch: String, fqcn: String): String? {
    val fileName = fqcn.substringAfterLast('.').substringBefore('#')
    val path = extractPatchFilePaths(testPatch).firstOrNull { candidate ->
        val leaf = candidate.substringAfterLast('/')
        leaf == "$fileName.java" || leaf == "$fileName.kt"
    } ?: return null
    val srcIndex = path.indexOf("src/")
    if (srcIndex <= 0) return ""
    return path.substring(0, srcIndex).trimEnd('/')
}

/**
 * True when the graded zero means "the harness never executed the tests" rather than "the fix does
 * not work" — i.e. no surefire report was produced for any FAIL_TO_PASS class AND the module that
 * stopped the reactor is a DIFFERENT module from the one owning those tests.
 *
 * The distinction is the whole point: a module the agent never touched can break (a shared library
 * failing on a too-new JDK, say) and skip the test module entirely. That zero is a harness fault and
 * must fail the run loudly. If the owning module itself failed to build, the agent broke what it was
 * editing and the zero is a real measurement. With the owning module unknown, blaming the harness
 * would be a guess, so it is treated as a real measurement too.
 */
fun verificationNeverRanTests(
    anyReportFound: Boolean,
    ftpModuleDirectory: String?,
    failedMavenProject: String?,
): Boolean {
    if (anyReportFound) return false
    if (ftpModuleDirectory == null || failedMavenProject == null) return false
    // Single-module project: the tests live in the root project, so whatever failed IS their module.
    // Its directory is "" and can never be matched against an artifactId, so comparing would report
    // every genuine compile failure as an infrastructure fault.
    if (ftpModuleDirectory.isEmpty()) return false
    val owningModule = ftpModuleDirectory.substringAfterLast('/')
    return owningModule != failedMavenProject
}

private val DIFF_FILE_HEADER = Regex("""^diff --git a/(\S+) b/\S+$""", RegexOption.MULTILINE)

/** All file paths a unified diff touches ("a/" side is enough — arena patches never rename). */
fun extractPatchFilePaths(patch: String): Set<String> =
    DIFF_FILE_HEADER.findAll(patch).map { it.groupValues[1] }.toSet()

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

    /**
     * The Maven entry point to grade with: the project's own `./mvnw` wrapper when it ships one, else
     * `mvn` from PATH, else the IDE's bundled Maven.
     *
     * Not every dataset repo has a wrapper — `dpaia__train__ticket-31` is a 42-module aggregator driven
     * by a Makefile. Hardcoding `./mvnw` made verification die on `./mvnw: No such file or directory`,
     * produce no surefire reports at all, and grade every FAIL_TO_PASS class 0 — reported as
     * `verified 0/2` / `claim matches reality: false` on three consecutive runs of BOTH arms, which
     * reads as an agent regression rather than a missing file.
     *
     * The bundled-Maven plugin directory is `maven-plugin` on current IDE builds and `maven` on older
     * ones, so both are probed. Failing loudly when none resolves is the point: no Maven means the run
     * measured nothing, and a silent 0 is indistinguishable from a real agent failure.
     */
    private fun resolveMavenCommand(): String {
        val candidates = listOf(
            "/opt/idea/plugins/maven-plugin/lib/maven3/bin/mvn",
            "/opt/idea/plugins/maven/lib/maven3/bin/mvn",
        )
        val probe = buildString {
            append("if [ -x '$projectDir/mvnw' ]; then echo './mvnw'; ")
            append("elif command -v mvn >/dev/null 2>&1; then echo 'mvn'; ")
            candidates.forEach { append("elif [ -x '$it' ]; then echo '$it'; ") }
            append("fi")
        }
        val resolved = bash(
            probe,
            timeoutSeconds = 20,
            description = "Resolve arena verification Maven command",
        ).stdout.trim()
        check(resolved.isNotBlank()) {
            "Arena verification cannot run Maven for $projectDir: no executable ./mvnw wrapper, no `mvn` " +
                "on PATH, and no bundled Maven at ${candidates.joinToString(" or ")}. Every FAIL_TO_PASS " +
                "class would grade 0 and the run would look like an agent failure instead of a missing " +
                "build tool."
        }
        return resolved
    }

    /** Contents of `TEST-<fqcn>.xml` from anywhere in the tree, or blank when the class produced none. */
    private fun readSurefireReport(fqcn: String): String = bash(
        "f=\$(find '$projectDir' -type f -path '*/target/surefire-reports/TEST-$fqcn.xml' 2>/dev/null | head -1); " +
            "if [ -n \"\$f\" ]; then cat \"\$f\"; fi",
        timeoutSeconds = 60,
        description = "Read surefire report for $fqcn",
    ).stdout

    private fun hashTestFiles(paths: Set<String>): Map<String, String> {
        if (paths.isEmpty()) return emptyMap()
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
        preAgentSnapshot: Map<String, String>,
    ): ArenaVerificationResult {
        val startMs = System.currentTimeMillis()

        val tampered = hashTestFiles(extractPatchFilePaths(testPatch)) != preAgentSnapshot

        val testFilter = surefireTestFilter(failToPass)
        val javaHome = bash(
            "ls -d /usr/lib/jvm/temurin-$projectJdkVersion-jdk-* 2>/dev/null | head -1",
            timeoutSeconds = 20,
            description = "Resolve verification JAVA_HOME",
        ).stdout.trim()
        check(javaHome.isNotBlank()) { "No temurin-$projectJdkVersion JDK in the arena container" }
        val mavenCommand = resolveMavenCommand()

        // Multi-module repos keep reports per module (`<module>/target/surefire-reports`), so both the
        // clean and the read below walk the tree instead of assuming a single root `target/`.
        bash(
            "find '$projectDir' -type d -path '*/target/surefire-reports' -exec rm -rf {} + 2>/dev/null; true",
            timeoutSeconds = 60,
            description = "Clean stale surefire reports",
        )
        // `set -o pipefail` keeps Maven's exit code instead of `tail`'s — without it a Maven that never
        // started was logged as `exit=0`, indistinguishable from a clean run whose tests simply failed.
        val mvn = bash(
            "set -o pipefail; cd '$projectDir' && JAVA_HOME='$javaHome' $mavenCommand test " +
                "-Dtest='$testFilter' -Dsurefire.failIfNoSpecifiedTests=false -Dspotless.check.skip=true " +
                // 200, not 100: a 43-module reactor summary plus the [ERROR] block must fit, because
                // the `-rf :<artifactId>` line inside it is what identifies the module that failed.
                "2>&1 | tail -200",
            timeoutSeconds = 1_200,
            description = "Arena verification: run FAIL_TO_PASS classes",
        )
        println("[ARENA-VERIFY] $mavenCommand exit=${mvn.exitCode}; filter=$testFilter; tail:\n${mvn.stdout}")

        val perClass = failToPass.map { entry ->
            val selector = parseFailToPassEntry(entry)
            val xml = readSurefireReport(selector.fqcn)
            if (xml.isBlank()) {
                SurefireClassResult(className = entry, testsRun = 0, failures = 0, errors = 0, skipped = 0)
            } else {
                parseSurefireXml(xml, selector.method).copy(className = entry)
            }
        }

        // A zero because the tests never ran is a harness fault, not a measurement — see
        // [verificationNeverRanTests]. Reported as data it is indistinguishable from a failed fix.
        val failedProject = mavenFailedProject(mvn.stdout)
        val ftpModule = failToPass.firstNotNullOfOrNull { entry ->
            moduleDirectoryForClass(testPatch, parseFailToPassEntry(entry).fqcn)
        }
        check(
            !verificationNeverRanTests(
                anyReportFound = perClass.any { it.testsRun > 0 },
                ftpModuleDirectory = ftpModule,
                failedMavenProject = failedProject,
            ),
        ) {
            "Arena verification never executed the FAIL_TO_PASS tests: Maven stopped on project " +
                "'$failedProject', which is not the module owning them ('${ftpModule.orEmpty()}'), so " +
                "every class graded 0 without running. That is a harness/dataset fault, not an agent " +
                "result — grading it would put a false zero in the comparison. Maven exit=${mvn.exitCode}."
        }

        return ArenaVerificationResult(
            perClass = perClass,
            testsTampered = tampered,
            verificationDurationMs = System.currentTimeMillis() - startMs,
        )
    }
}
