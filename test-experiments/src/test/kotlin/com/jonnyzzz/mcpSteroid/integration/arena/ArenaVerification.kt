/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
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
    /**
     * True when a file **defining a FAIL_TO_PASS class** changed after the agent ran — the agent
     * rewrote the oracle it was graded against, so its grade means nothing.
     *
     * Deliberately narrower than "any test-patch file changed": the prompt itself instructs the agent
     * to update the collateral test classes its production change breaks, and migration scenarios
     * (JPA→R2DBC) cannot pass without doing so. Those edits are reported separately in
     * [collateralTestFilesEdited] as data, never as cheating.
     */
    val failToPassTampered: Boolean,
    /** Test-patch files the agent edited that define no FAIL_TO_PASS class — informational. */
    val collateralTestFilesEdited: List<String>,
    /** Test classes that passed in the pre-agent baseline and fail afterward; empty when no baseline. */
    val regressions: List<String>,
    /** False when no pre-agent baseline suite was available, making [regressions] unknown rather than empty. */
    val baselineAvailable: Boolean,
    /** True when either whole-suite run was cut short, making [regressions] a lower bound — see [FullSuiteSnapshot.timedOut]. */
    val regressionScanTruncated: Boolean = false,
    val verificationDurationMs: Long,
) {
    val classesPassed: Int get() = perClass.count { it.passed }
    val classesTotal: Int get() = perClass.size
    val failToPassRate: Double get() = if (perClass.isEmpty()) 0.0 else classesPassed.toDouble() / perClass.size

    /**
     * The harness's own verdict on the run, to compare the agent's claim against.
     *
     * Every FAIL_TO_PASS class passes and nothing that used to pass broke. The regression half is what
     * makes an honest refusal distinguishable from a wrong claim: 149 of the 154 dataset cases carry an
     * EMPTY `PASS_TO_PASS` list, so the only regression evidence available is the measured
     * baseline-vs-after comparison. Without it a suite that was already red before the agent ran (an
     * unrelated module's pre-existing failures, or tests needing a Docker socket the container lacks)
     * pushed agents into refusing the success marker for a task they had in fact completed.
     */
    val objectiveSuccess: Boolean get() = classesTotal > 0 && failToPassRate == 1.0 && regressions.isEmpty()
}

/** Per-class results of one whole-suite run, used as the before/after regression baseline. */
data class FullSuiteSnapshot(
    val perClass: List<SurefireClassResult>,
    val mavenExitCode: Int,
    /**
     * True when Maven was killed on the harness timeout, so this snapshot covers only the modules that
     * finished. A truncated snapshot can only UNDER-report regressions (a class it never saw is treated
     * as unknown, never as broken) — which is the safe direction, but "0 regressions" measured against a
     * truncated baseline is not evidence of none, and must not be published as if it were.
     */
    val timedOut: Boolean = false,
) {
    val passing: Set<String> get() = perClass.filter { it.passed }.map { it.className }.toSet()
    val failing: Set<String> get() = perClass.filterNot { it.passed }.map { it.className }.toSet()

    /**
     * True when this snapshot can serve as a regression baseline at all.
     *
     * A run that reported no classes AND failed is not "a project with nothing to break" — it is a
     * build that never got to the tests. Treating the two alike publishes "0 regressions" for a
     * scenario where regressions were never observable; an empty project that builds cleanly (exit 0)
     * really does have nothing to regress and stays usable.
     */
    val usableAsBaseline: Boolean get() = perClass.isNotEmpty() || mavenExitCode == 0
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

private val JAVAC_RELEASE_UNSUPPORTED = Regex("""release version (\d+) not supported""")

/**
 * The Java release the build demands when javac rejected the one it was given, else null.
 *
 * The baseline suite picks its JDK from the case's configured version, which describes the *agent's*
 * environment and need not match what the pristine repository compiles with: `dpaia__empty__maven__
 * springboot3-*` is configured for JDK 21 while its pom asks for release 24, so the baseline died with
 * `release version 24 not supported` and reported zero classes — no regression could be detected for
 * those scenarios. The number in that message is exactly the JDK to retry on.
 */
fun requiredJavaReleaseFromError(mavenOutput: String): String? =
    JAVAC_RELEASE_UNSUPPORTED.find(mavenOutput)?.groupValues?.get(1)

private val DIFF_FILE_HEADER = Regex("""^diff --git a/(\S+) b/\S+$""", RegexOption.MULTILINE)

/** All file paths a unified diff touches ("a/" side is enough — arena patches never rename). */
fun extractPatchFilePaths(patch: String): Set<String> =
    DIFF_FILE_HEADER.findAll(patch).map { it.groupValues[1] }.toSet()

/**
 * The test-patch paths that DEFINE one of the [failToPass] classes — the files an agent must not touch,
 * because they are the oracle its grade is read from.
 *
 * Matched by file leaf against each entry's simple name, the same way [moduleDirectoryForClass] does.
 * A FAIL_TO_PASS entry whose file the patch never adds contributes nothing.
 */
fun failToPassFilePaths(testPatch: String, failToPass: List<String>): Set<String> {
    val leaves = failToPass.map { parseFailToPassEntry(it).simpleName }.toSet()
    return extractPatchFilePaths(testPatch).filterTo(LinkedHashSet()) { path ->
        val leaf = path.substringAfterLast('/')
        leaves.any { leaf == "$it.java" || leaf == "$it.kt" }
    }
}

/** Paths whose hash differs between [before] and [after]; a file appearing or vanishing counts. */
fun changedPaths(before: Map<String, String>, after: Map<String, String>): List<String> =
    (before.keys + after.keys).sorted().filter { before[it] != after[it] }

private val SUREFIRE_ATTR = Regex("""(\w+)="([^"]*)"""")

/**
 * Parse the whole-suite report index: one line per surefire XML, `<path>\t<testsuite …>` opening tag.
 *
 * Reading only the opening tag keeps a 40-module reactor's worth of reports to a few hundred KB and
 * avoids building a DOM per file — the whole-suite snapshot needs the per-class totals, nothing else.
 * Lines without a parsable `<testsuite>` tag (a truncated or in-progress report) are skipped.
 */
fun parseSurefireSuiteIndex(indexText: String): List<SurefireClassResult> =
    indexText.lineSequence().mapNotNull { line ->
        val tag = line.substringAfter('\t', missingDelimiterValue = "")
        if (!tag.contains("<testsuite")) return@mapNotNull null
        val attrs = SUREFIRE_ATTR.findAll(tag).associate { it.groupValues[1] to it.groupValues[2] }
        val name = attrs["name"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        fun num(key: String) = attrs[key]?.toIntOrNull() ?: 0
        SurefireClassResult(
            className = name,
            testsRun = num("tests"),
            failures = num("failures"),
            errors = num("errors"),
            skipped = num("skipped"),
        )
    }.toList()

/**
 * Classes that passed [before] the agent and no longer pass [after] it, excluding [excluded].
 *
 * A class absent from the baseline is NOT a regression: its module may simply never have built, and
 * calling that an agent-caused break would recreate the false-zero problem the loud verification check
 * exists to prevent. FAIL_TO_PASS classes are excluded because they are expected to change state — they
 * are graded on their own.
 */
fun regressedClasses(
    before: FullSuiteSnapshot,
    after: FullSuiteSnapshot,
    excluded: Set<String> = emptySet(),
): List<String> {
    val stillFine = after.passing
    return before.passing.asSequence()
        .filter { it !in excluded && it !in stillFine }
        .filter { it in after.failing }
        .sorted()
        .toList()
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

    /**
     * Run the WHOLE test suite in [dir] and index every surefire report.
     *
     * `-fae` plus `maven.test.failure.ignore` are what make the snapshot complete: without them Maven
     * stops at the first module whose tests fail, so every later module would look "absent" rather than
     * "green", and a pre-existing failure in an early module would hide the whole reactor.
     */
    fun fullSuiteSnapshot(
        projectJdkVersion: String,
        label: String,
        dir: String = projectDir,
        retried: Boolean = false,
    ): FullSuiteSnapshot {
        val javaHome = resolveJavaHome(projectJdkVersion)
        val mavenCommand = resolveMavenCommand(dir)
        cleanSurefireReports(dir)
        val mvn = bash(
            "set -o pipefail; cd '$dir' && JAVA_HOME='$javaHome' $mavenCommand test " +
                "-fae -Dmaven.test.failure.ignore=true -Dsurefire.failIfNoSpecifiedTests=false " +
                "-Dspotless.check.skip=true 2>&1 | tail -200",
            timeoutSeconds = 2_400,
            description = "Arena $label full-suite snapshot",
        )
        val perClass = parseSurefireSuiteIndex(readSurefireIndex(dir))
        // A harness timeout does not throw — it returns exit=-1 with this marker on stderr. Left
        // undetected it would look like a completed suite that simply reported fewer classes.
        val timedOut = mvn.stderr.contains("Terminated by timeout")
        println(
            "[ARENA-VERIFY] $label full suite: exit=${mvn.exitCode}, ${perClass.size} classes reported, " +
                "${perClass.count { it.passed }} passing" + if (timedOut) " — TRUNCATED BY TIMEOUT" else ""
        )
        if (timedOut) {
            System.err.println(
                "[ARENA-VERIFY] $label full suite hit the harness timeout, so it covers only the modules " +
                    "that finished. Regression counts derived from it are a lower bound, not a clean zero."
            )
        }
        if (perClass.isEmpty()) {
            System.err.println(
                "[ARENA-VERIFY] $label full suite produced NO surefire reports at all (Maven " +
                    "exit=${mvn.exitCode}). Tail:\n${mvn.stdout.takeLast(4_000)}"
            )
            // The configured JDK describes the AGENT's environment; the pristine repository may demand a
            // newer one. Retrying on the release javac named is the difference between a real baseline
            // and none at all — measured on the springboot3 scenarios, configured for 21 against a pom
            // asking for release 24. Retried once, and only when nothing ran, so a genuinely empty
            // project still costs one suite run.
            val required = requiredJavaReleaseFromError(mvn.stdout)
            if (required != null && required != projectJdkVersion && !retried) {
                println(
                    "[ARENA-VERIFY] $label full suite needs Java $required, not the case's configured " +
                        "$projectJdkVersion — retrying the baseline on Java $required"
                )
                return fullSuiteSnapshot(required, label, dir, retried = true)
            }
        }
        return FullSuiteSnapshot(
            perClass = perClass,
            mavenExitCode = mvn.exitCode ?: -1,
            timedOut = timedOut,
        )
    }

    /**
     * The regression baseline: the whole suite as it stood at [baseCommit], BEFORE the dataset's test
     * patch was applied.
     *
     * It cannot be taken in the agent's own directory. The applied test patch is the task: its tests
     * call production code that does not exist yet, so `test-compile` fails there and the suite reports
     * zero classes — measured on `dpaia__spring__petclinic-27`, where the patched tree yields
     * `exit=1, 0 classes` and no regression could ever be detected. The pre-patch tree is also the right
     * oracle on the merits: "did the agent break something" means something that worked in the original
     * repository.
     *
     * Run in a throwaway `git worktree` so the agent's directory keeps its own cold `target/` and is
     * never left in a half-reverted state if this fails. The worktree is removed either way.
     */
    fun baselineSnapshotAtBaseCommit(baseCommit: String, projectJdkVersion: String): FullSuiteSnapshot {
        val worktree = "/tmp/arena-baseline-worktree"
        bash(
            "git -C '$projectDir' worktree remove --force '$worktree' 2>/dev/null; rm -rf '$worktree'; " +
                "git -C '$projectDir' worktree add --detach '$worktree' '$baseCommit'",
            timeoutSeconds = 300,
            description = "Create pre-patch baseline worktree at $baseCommit",
        ).assertExitCode(0, "create baseline worktree at $baseCommit")
        try {
            return fullSuiteSnapshot(projectJdkVersion, label = "pre-agent baseline", dir = worktree)
        } finally {
            bash(
                "git -C '$projectDir' worktree remove --force '$worktree' 2>/dev/null; rm -rf '$worktree'; true",
                timeoutSeconds = 120,
                description = "Remove baseline worktree",
            )
        }
    }

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
    private fun resolveMavenCommand(dir: String = projectDir): String {
        val candidates = listOf(
            "/opt/idea/plugins/maven-plugin/lib/maven3/bin/mvn",
            "/opt/idea/plugins/maven/lib/maven3/bin/mvn",
        )
        val probe = buildString {
            append("if [ -x '$dir/mvnw' ]; then echo './mvnw'; ")
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
            "Arena verification cannot run Maven for $dir: no executable ./mvnw wrapper, no `mvn` " +
                "on PATH, and no bundled Maven at ${candidates.joinToString(" or ")}. Every FAIL_TO_PASS " +
                "class would grade 0 and the run would look like an agent failure instead of a missing " +
                "build tool."
        }
        return resolved
    }

    /** The temurin JDK the case is configured for; blank is fatal — a wrong JDK grades everything 0. */
    private fun resolveJavaHome(projectJdkVersion: String): String {
        val javaHome = bash(
            "ls -d /usr/lib/jvm/temurin-$projectJdkVersion-jdk-* 2>/dev/null | head -1",
            timeoutSeconds = 20,
            description = "Resolve verification JAVA_HOME",
        ).stdout.trim()
        check(javaHome.isNotBlank()) { "No temurin-$projectJdkVersion JDK in the arena container" }
        return javaHome
    }

    /**
     * Delete every surefire report in the tree, so a later read cannot pick up a stale one.
     *
     * Multi-module repos keep reports per module (`<module>/target/surefire-reports`), hence the walk
     * instead of assuming a single root `target/`.
     */
    private fun cleanSurefireReports(dir: String = projectDir) {
        bash(
            "find '$dir' -type d -path '*/target/surefire-reports' -exec rm -rf {} + 2>/dev/null; true",
            timeoutSeconds = 60,
            description = "Clean stale surefire reports",
        )
    }

    /** One `<path>\t<testsuite …>` line per surefire report in the tree — see [parseSurefireSuiteIndex]. */
    private fun readSurefireIndex(dir: String = projectDir): String = bash(
        "find '$dir' -type f -path '*/target/surefire-reports/TEST-*.xml' 2>/dev/null | " +
            "while IFS= read -r f; do printf '%s\\t' \"${'$'}f\"; " +
            "tr '\\n' ' ' < \"${'$'}f\" | grep -o '<testsuite[^>]*>' | head -1; printf '\\n'; done",
        timeoutSeconds = 120,
        description = "Index surefire reports",
    ).stdout

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
        baseline: FullSuiteSnapshot? = null,
    ): ArenaVerificationResult {
        val startMs = System.currentTimeMillis()

        val oraclePaths = failToPassFilePaths(testPatch, failToPass)
        val changed = changedPaths(preAgentSnapshot, hashTestFiles(extractPatchFilePaths(testPatch)))
        val tampered = changed.any { it in oraclePaths }
        val collateral = changed.filterNot { it in oraclePaths }
        if (changed.isNotEmpty()) {
            println(
                "[ARENA-VERIFY] test-patch files edited by the agent: " +
                    changed.joinToString { if (it in oraclePaths) "$it (FAIL_TO_PASS ORACLE)" else it }
            )
        }

        val testFilter = surefireTestFilter(failToPass)
        val javaHome = resolveJavaHome(projectJdkVersion)
        val mavenCommand = resolveMavenCommand()

        cleanSurefireReports()
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

        // Regression half of the verdict: re-run the whole suite and compare with the pre-agent baseline.
        // Runs AFTER the targeted grading above so its `clean` cannot wipe the FAIL_TO_PASS reports the
        // grade is read from, and so a broken whole-suite run can never cost us the FAIL_TO_PASS numbers.
        val usableBaseline = baseline?.takeIf { it.usableAsBaseline }
        if (baseline != null && usableBaseline == null) {
            System.err.println(
                "[ARENA-VERIFY] the pre-agent baseline never ran the tests (Maven " +
                    "exit=${baseline.mavenExitCode}, 0 classes), so regressions are UNKNOWN for this run " +
                    "— not zero. Reported as null rather than a measured clean result."
            )
        }
        var truncated = usableBaseline?.timedOut == true
        val regressions = if (usableBaseline == null) emptyList() else {
            val after = fullSuiteSnapshot(projectJdkVersion, label = "post-agent")
            truncated = truncated || after.timedOut
            val ftpClassNames = failToPass.map { parseFailToPassEntry(it).fqcn }.toSet()
            regressedClasses(usableBaseline, after, excluded = ftpClassNames).also {
                if (it.isEmpty()) println("[ARENA-VERIFY] no regressions vs baseline")
                else println("[ARENA-VERIFY] REGRESSIONS vs baseline (${it.size}): ${it.joinToString()}")
            }
        }

        return ArenaVerificationResult(
            perClass = perClass,
            failToPassTampered = tampered,
            collateralTestFilesEdited = collateral,
            regressions = regressions,
            baselineAvailable = usableBaseline != null,
            regressionScanTruncated = truncated,
            verificationDurationMs = System.currentTimeMillis() - startMs,
        )
    }
}
