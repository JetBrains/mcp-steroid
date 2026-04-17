/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.testHelper.ProjectHomeDirectory
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * Enforces that `mcp-steroid://` resource URIs in ij-plugin Kotlin code are constructed
 * from generated prompt article classes (e.g. `TestSkillPromptArticle().uri`) rather than
 * hardcoded as string literals. This ensures URIs stay in sync with the prompt file structure
 * and are caught at compile time if a resource is renamed or removed.
 *
 * If a file legitimately needs the URI protocol prefix (e.g. in a comment, KDoc, or protocol
 * handling code that doesn't reference a specific resource), add it to [ALLOWED_FILES] below.
 */
class NoHardcodedMcpSteroidUriUsageTest : BasePlatformTestCase() {

    companion object {
        /**
         * Files allowed to contain `mcp-steroid://` literals.
         * Each entry is a path relative to the project root.
         * Add files here ONLY when the URI is protocol-level (not a specific resource reference).
         */
        private val ALLOWED_FILES = setOf(
            // McpResourceRegistry uses the protocol prefix for URI matching/registration
            "ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/mcp/McpResourceRegistry.kt",
        )
    }

    fun testNoHardcodedMcpSteroidUriInKotlinSources() {
        val projectHome = ProjectHomeDirectory.requireProjectHomeDirectory()
        // Match specific resource URIs like "mcp-steroid://prompt/skill" but NOT the bare
        // protocol prefix "mcp-steroid://". Mentioning the protocol is fine; hardcoding
        // a specific resource path is what we ban (use generated article classes instead).
        val forbiddenPattern = Regex("""mcp-steroid://\w""")
        // Scope: production Kotlin sources only — ij-plugin + prompts + prompt-generator.
        // Test sources are excluded (tests may legitimately hardcode URIs for assertions).
        // Prompt .md files are excluded (they ARE the resource content).
        val sourceRoots = listOf(
            "ij-plugin/src/main/kotlin",
            "prompts/src/main/kotlin",
            "prompt-generator/src/main/kotlin",
        ).map(projectHome::resolve)

        // Exclude this file itself so it can use the literal without self-evasion tricks.
        val selfPath = projectHome
            .resolve("ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/NoHardcodedMcpSteroidUriUsageTest.kt")
            .normalize()

        val allowedPaths = ALLOWED_FILES.map { projectHome.resolve(it).normalize() }.toSet()

        val matches = mutableListOf<String>()
        for (root in sourceRoots) {
            if (!Files.isDirectory(root)) continue
            for (file in collectKotlinFiles(root)) {
                val normalized = file.normalize()
                if (normalized == selfPath) continue
                if (normalized in allowedPaths) continue
                val lines = Files.readAllLines(file)
                lines.forEachIndexed { index, line ->
                    if (forbiddenPattern.containsMatchIn(line)) {
                        matches.add("${projectHome.relativize(file)}:${index + 1}")
                    }
                }
            }
        }

        assertTrue(
            "Hardcoded MCP resource URI literals found in Kotlin code:\n${matches.joinToString("\n")}",
            matches.isEmpty()
        )
    }

    private fun collectKotlinFiles(root: Path): List<Path> {
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
