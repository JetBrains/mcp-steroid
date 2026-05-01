/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Lint-style guards for incremental-build correctness in subproject build scripts.
 *
 * These tests prevent two regressions caught in code review of the
 * "improvements-for-long-tests" branch:
 *  - npmBuild can go UP-TO-DATE while dist/index.js is stale because the
 *    lockfile / TS config aren't declared as inputs.
 *  - ocr-tesseract reuses old .traineddata files when tessdataVersion is bumped
 *    because the onlyIf predicate keys on file name, not the version URL.
 */
class BuildScriptIncrementalInputsTest {

    private val repoRoot: File = run {
        var dir = File(".").canonicalFile
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        dir
    }

    @Test
    fun `npmBuild declares package-lock_json as input`() {
        val script = readScript("npx/build.gradle.kts")
        assertScriptContains(
            script,
            Regex("""inputs\.file\(\s*projectDir\.resolve\(\s*"package-lock\.json"\s*\)\s*\)"""),
            "npmBuild must declare package-lock.json as input — otherwise a lockfile change " +
                "leaves the task UP-TO-DATE while dist/index.js is stale."
        )
    }

    @Test
    fun `npmBuild declares tsconfig_json as input`() {
        val script = readScript("npx/build.gradle.kts")
        assertScriptContains(
            script,
            Regex("""inputs\.file\(\s*projectDir\.resolve\(\s*"tsconfig\.json"\s*\)\s*\)"""),
            "npmBuild must declare tsconfig.json as input — otherwise a TS config change " +
                "leaves the task UP-TO-DATE while dist/index.js is stale."
        )
    }

    @Test
    fun `ocr-tesseract download directory is scoped by tessdataVersion`() {
        val script = readScript("ocr-tesseract/build.gradle.kts")
        assertScriptContains(
            script,
            Regex("""tessdata-download/\$\{?tessdataVersion\}?"""),
            "tessdata download directory must include tessdataVersion — otherwise bumping " +
                "tessdataVersion silently reuses old .traineddata files until `clean`."
        )
    }

    private fun readScript(relativePath: String): String {
        val file = File(repoRoot, relativePath)
        assertTrue(file.isFile, "expected build script at $file")
        return file.readText()
    }

    private fun assertScriptContains(script: String, pattern: Regex, message: String) {
        if (!pattern.containsMatchIn(script)) {
            fail("$message\n\nExpected regex: $pattern")
        }
    }
}
