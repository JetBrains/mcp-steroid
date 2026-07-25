/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

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
