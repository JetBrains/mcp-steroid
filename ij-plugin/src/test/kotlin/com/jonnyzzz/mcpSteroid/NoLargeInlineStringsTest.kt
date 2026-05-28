/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.testHelper.ProjectHomeDirectory
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * Lint test: production Kotlin code must not contain large inline strings.
 *
 * Rules enforced (scans ij-plugin/src/main/kotlin only):
 * 1. No triple-quoted string (""") spanning > 20 lines in a single literal.
 * 2. No sequence of >= 15 consecutive lines each containing appendLine( or .appendLine(.
 *
 * Both patterns indicate that documentation/content should be moved to resource files
 * (ij-plugin/src/main/prompts/ -> codegen -> MCP resource URIs).
 */
class NoLargeInlineStringsTest : BasePlatformTestCase() {

    fun testNoLargeTripleQuotedStrings() {
        val projectHome = ProjectHomeDirectory.requireProjectHomeDirectory()
        val sourceRoot = projectHome.resolve("ij-plugin/src/main/kotlin")
        val selfPath = projectHome
            .resolve("ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/NoLargeInlineStringsTest.kt")
            .normalize()

        val violations = mutableListOf<String>()
        for (file in collectKotlinFiles(sourceRoot)) {
            if (file.normalize() == selfPath) continue
            checkTripleQuotedStrings(file, projectHome, violations)
        }

        assertTrue(
            "Triple-quoted strings spanning > 20 lines found in production code.\n" +
                "Move content to src/main/prompts/ resource files instead.\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    fun testNoLargeAppendLineSequences() {
        val projectHome = ProjectHomeDirectory.requireProjectHomeDirectory()
        val sourceRoot = projectHome.resolve("ij-plugin/src/main/kotlin")
        val selfPath = projectHome
            .resolve("ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/NoLargeInlineStringsTest.kt")
            .normalize()

        val violations = mutableListOf<String>()
        for (file in collectKotlinFiles(sourceRoot)) {
            if (file.normalize() == selfPath) continue
            checkAppendLineSequences(file, projectHome, violations)
        }

        assertTrue(
            "Sequences of >= 15 consecutive appendLine() calls found in production code.\n" +
                "Move content to src/main/prompts/ resource files instead.\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    /** Report violations where a triple-quoted string spans > 20 lines. */
    private fun checkTripleQuotedStrings(file: Path, projectHome: Path, violations: MutableList<String>) {
        val lines = Files.readAllLines(file)
        var inTripleQuote = false
        var startLine = -1
        var lineCount = 0

        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (!inTripleQuote) {
                // Count occurrences of """ on this line
                var pos = 0
                while (true) {
                    val found = line.indexOf("\"\"\"", pos)
                    if (found == -1) break
                    inTripleQuote = !inTripleQuote
                    if (inTripleQuote) {
                        startLine = index + 1
                        lineCount = 0
                    } else {
                        // Closed on same line — single-line triple-quoted string, reset
                        startLine = -1
                        lineCount = 0
                    }
                    pos = found + 3
                }
            } else {
                lineCount++
                val closePos = line.indexOf("\"\"\"")
                if (closePos != -1) {
                    inTripleQuote = false
                    if (lineCount > 20) {
                        violations.add(
                            "${projectHome.relativize(file)}:$startLine — triple-quoted string spans $lineCount lines (limit: 20)"
                        )
                    }
                    // Check if another triple-quote opens on the same line after the close
                    val afterClose = line.indexOf("\"\"\"", closePos + 3)
                    if (afterClose != -1) {
                        inTripleQuote = true
                        startLine = index + 1
                        lineCount = 0
                    }
                }
            }
        }
    }

    /** Report violations where >= 15 consecutive lines each contain appendLine(. */
    private fun checkAppendLineSequences(file: Path, projectHome: Path, violations: MutableList<String>) {
        val lines = Files.readAllLines(file)
        var runStart = -1
        var runLength = 0

        fun flush(endLineIndex: Int) {
            if (runLength >= 15) {
                violations.add(
                    "${projectHome.relativize(file)}:${runStart + 1} — $runLength consecutive appendLine() calls (limit: 14)"
                )
            }
            runStart = -1
            runLength = 0
        }

        lines.forEachIndexed { index, line ->
            if (line.contains("appendLine(")) {
                if (runStart == -1) runStart = index
                runLength++
            } else {
                flush(index)
            }
        }
        flush(lines.size)
    }

    private fun collectKotlinFiles(root: Path): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).use { paths ->
            paths
                .filter { it.isKotlinFile() }
                .collect(Collectors.toList())
        }
    }

    private fun Path.isKotlinFile(): Boolean {
        if (!Files.isRegularFile(this)) return false
        val fileName = fileName.toString()
        return fileName.endsWith(".kt") || fileName.endsWith(".kts")
    }
}
