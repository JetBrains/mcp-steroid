/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import kotlinx.serialization.json.*
import java.io.File
import java.net.URI

/**
 * Represents a single test case from the dpaia.dev arena dataset.
 *
 * Each entry describes a bug-fix scenario with:
 * - A repository to clone
 * - A base commit to check out
 * - A problem statement describing what needs to be fixed
 * - Expected patches (production + test)
 * - Test names that should transition from FAIL to PASS
 * - Test names that should remain PASS
 */
data class DpaiaTestCase(
    /** Unique identifier, e.g. "dpaia__Stirling__PDF-1" */
    val instanceId: String,

    /** Issue numbers as a list of strings */
    val issueNumbers: List<String>,

    /** Tags describing the test category, e.g. ["Validation", "Security"] */
    val tags: List<String>,

    /** Repository path with .git suffix, e.g. "dpaia/Stirling-PDF.git" */
    val repo: String,

    /** Unified diff of production code changes (the expected fix) */
    val patch: String,

    /** Unified diff of test code additions */
    val testPatch: String,

    /** Tests that should transition from failing to passing after the fix */
    val failToPass: List<String>,

    /** Tests that should remain passing after the fix */
    val passToPass: List<String>,

    /** ISO 8601 creation timestamp */
    val createdAt: String,

    /** Git commit SHA to check out as the starting point */
    val baseCommit: String,

    /** Detailed description of what needs to be fixed */
    val problemStatement: String,

    /** Version identifier */
    val version: String,

    /** Whether the project uses Maven */
    val isMaven: Boolean,

    /** Build system: "gradle" or "maven" */
    val buildSystem: String,

    /** Optional test runner arguments */
    val testArgs: String,
) {
    /** GitHub clone URL for the repository */
    val cloneUrl: String
        get() = "https://github.com/$repo"

    /** Repository name without .git suffix */
    val repoName: String
        get() = repo.removeSuffix(".git").substringAfterLast("/")
}

/**
 * Loader for the dpaia.dev arena dataset JSON files.
 */
object DpaiaDatasetLoader {

    /**
     * Parse a dataset JSON file into a list of test cases.
     */
    fun load(jsonFile: File): List<DpaiaTestCase> {
        require(jsonFile.isFile) { "Dataset file not found: $jsonFile" }
        val jsonText = jsonFile.readText()
        return parseJson(jsonText)
    }

    /**
     * Download and parse a dataset from a URL.
     */
    fun loadFromUrl(url: String): List<DpaiaTestCase> {
        val jsonText = URI(url).toURL().readText()
        return parseJson(jsonText)
    }

    /**
     * Parse dataset JSON text into test cases.
     */
    fun parseJson(jsonText: String): List<DpaiaTestCase> {
        val jsonArray = Json.parseToJsonElement(jsonText).jsonArray
        return jsonArray.map { parseTestCase(it.jsonObject) }
    }

    /**
     * Find a test case by instance ID.
     */
    fun findById(cases: List<DpaiaTestCase>, instanceId: String): DpaiaTestCase {
        return cases.find { it.instanceId == instanceId }
            ?: error("Test case not found: $instanceId. Available: ${cases.map { it.instanceId }}")
    }

    /**
     * Filter test cases by tag.
     */
    fun filterByTag(cases: List<DpaiaTestCase>, tag: String): List<DpaiaTestCase> {
        return cases.filter { tag in it.tags }
    }

    /**
     * Filter test cases by build system.
     */
    fun filterByBuildSystem(cases: List<DpaiaTestCase>, buildSystem: String): List<DpaiaTestCase> {
        return cases.filter { it.buildSystem.equals(buildSystem, ignoreCase = true) }
    }

    private fun parseTestCase(obj: JsonObject): DpaiaTestCase {
        return DpaiaTestCase(
            instanceId = obj.string("instance_id"),
            issueNumbers = obj.stringList("issue_numbers"),
            tags = obj.stringList("tags"),
            repo = obj.string("repo"),
            patch = repairTrimmedUnifiedDiff(obj.string("patch")),
            testPatch = repairTrimmedUnifiedDiff(obj.string("test_patch")),
            failToPass = obj.stringList("FAIL_TO_PASS"),
            passToPass = obj.stringList("PASS_TO_PASS"),
            createdAt = obj.string("created_at"),
            baseCommit = obj.string("base_commit"),
            problemStatement = obj.string("problem_statement"),
            version = obj.string("version"),
            isMaven = obj.string("is_maven").equals("true", ignoreCase = true),
            buildSystem = obj.string("build_system").lowercase(),
            testArgs = obj.stringOrEmpty("test_args"),
        )
    }

    private fun JsonObject.string(key: String): String =
        get(key)?.jsonPrimitive?.content ?: error("Missing field: $key")

    private fun JsonObject.stringOrEmpty(key: String): String =
        get(key)?.jsonPrimitive?.contentOrNull ?: ""

    /**
     * Some fields in the dataset are JSON arrays encoded as strings,
     * e.g. `"[\"test1\", \"test2\"]"`. This parses them into a list.
     */
    private fun JsonObject.stringList(key: String): List<String> {
        val raw = string(key)
        if (raw.isBlank() || raw == "[]") return emptyList()
        return try {
            Json.parseToJsonElement(raw).jsonArray.map { it.jsonPrimitive.content }
        } catch (_: Exception) {
            // If parsing fails, treat the whole string as a single element
            listOf(raw)
        }
    }
}

/**
 * Repairs unified-diff text whose trailing whitespace was stripped by the dataset serialization
 * (jonnyzzz/mcp-steroid#447 — 11 of 304 patches in the live dataset are affected; `git apply`
 * rejects them with "corrupt patch at line N"):
 *
 *  - a blank CONTEXT line inside a hunk was trimmed from `" "` to `""` — restore the leading space;
 *  - context lines at the very END of a hunk were dropped entirely — shrink the hunk header's
 *    line counts to the surviving body (trailing context only anchors a hunk, it carries no change,
 *    so a shorter-context hunk applies to exactly the same states).
 *
 * The declared header counts drive the parse, so a bare `""` BETWEEN two file sections is never
 * mistaken for hunk content. Any other malformation fails loudly: repair must never guess content.
 */
fun repairTrimmedUnifiedDiff(patch: String): String {
    if (patch.isBlank()) return patch
    val hadTrailingNewline = patch.endsWith("\n")
    val lines = (if (hadTrailingNewline) patch.dropLast(1) else patch).split("\n")
    val hunkHeader = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@(.*)$""")
    val out = ArrayList<String>(lines.size)
    var i = 0
    while (i < lines.size) {
        val header = hunkHeader.matchEntire(lines[i])
        if (header == null) {
            out.add(lines[i])
            i++
            continue
        }
        val (oldStart, oldCount, newStart, newCount, tail) = header.destructured
        val oldN = oldCount.ifEmpty { "1" }.toInt()
        val newN = newCount.ifEmpty { "1" }.toInt()
        val headerIndex = out.size
        out.add(lines[i])
        i++
        var oldLeft = oldN
        var newLeft = newN
        while ((oldLeft > 0 || newLeft > 0) && i < lines.size) {
            var body = lines[i]
            if (body.startsWith("@@") || body.startsWith("diff --git")) break
            if (body.isEmpty()) body = " "
            when {
                body.startsWith(" ") -> { oldLeft--; newLeft-- }
                body.startsWith("-") -> oldLeft--
                body.startsWith("+") -> newLeft--
                body.startsWith("\\") -> Unit // "\ No newline at end of file"
                else -> error("Cannot repair patch: unparseable hunk line: $body")
            }
            out.add(body)
            i++
        }
        if (oldLeft > 0 || newLeft > 0) {
            check(oldLeft == newLeft) {
                "Cannot repair patch: hunk truncated asymmetrically (old missing $oldLeft, new missing $newLeft)"
            }
            out[headerIndex] = "@@ -$oldStart,${oldN - oldLeft} +$newStart,${newN - newLeft} @@$tail"
        }
    }
    return out.joinToString("\n") + if (hadTrailingNewline) "\n" else ""
}
