/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeGradlePromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeMavenPromptArticle
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class ExecuteCodeBuildAbortGuidanceTest {
    private val claudeFetchResourceTool = "mcp__mcp-steroid__steroid_fetch_resource"

    private val tempDirs = mutableListOf<Path>()

    @AfterEach
    fun tearDown() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun `gradle aborted build result points at gradle resource before bash fallback`() {
        val root = tempProjectRoot()
        root.resolve("settings.gradle.kts").writeText("""rootProject.name = "sample"""")

        val guidance = ExecuteCodeBuildAbortGuidance.guidanceFor(
            outputText = "Build errors: false, aborted: true",
            projectBasePath = root,
        )

        assertNotNull(guidance)
        assertTrue(guidance!!.contains("REQUIRED ACTION"), "should make the recovery mandatory: $guidance")
        assertTrue(guidance.contains(claudeFetchResourceTool), "should name Claude's fetch tool: $guidance")
        assertTrue(guidance.contains("NEXT TOOL CALL"), "should identify that tool as the next call: $guidance")
        assertTrue(guidance.contains(ExecuteCodeGradlePromptArticle().uri), "should include Gradle resource URI: $guidance")
        assertFalse(guidance.contains(ExecuteCodeMavenPromptArticle().uri), "should not include Maven URI for a Gradle project: $guidance")
        assertTrue(guidance.contains("before using Bash"), "should tell the agent not to jump straight to Bash: $guidance")
    }

    @Test
    fun `maven aborted compile result points at maven resource`() {
        val root = tempProjectRoot()
        root.resolve("pom.xml").writeText("<project />")

        val guidance = ExecuteCodeBuildAbortGuidance.guidanceFor(
            outputText = "Compile errors: false, aborted: true",
            projectBasePath = root,
        )

        assertNotNull(guidance)
        assertTrue(guidance!!.contains(ExecuteCodeMavenPromptArticle().uri), "should include Maven resource URI: $guidance")
        assertFalse(guidance.contains(ExecuteCodeGradlePromptArticle().uri), "should not include Gradle URI for a Maven project: $guidance")
    }

    @Test
    fun `successful build result does not add guidance`() {
        val root = tempProjectRoot()
        root.resolve("settings.gradle").writeText("""rootProject.name = "sample"""")

        val guidance = ExecuteCodeBuildAbortGuidance.guidanceFor(
            outputText = "Build errors: false, aborted: false",
            projectBasePath = root,
        )

        assertNull(guidance)
    }

    @Test
    fun `compiler-error build result does not add guidance`() {
        val root = tempProjectRoot()
        root.resolve("settings.gradle").writeText("""rootProject.name = "sample"""")

        val guidance = ExecuteCodeBuildAbortGuidance.guidanceFor(
            outputText = "Build errors: true, aborted: true",
            projectBasePath = root,
        )

        assertNull(guidance)
    }

    @Test
    fun `unknown project root falls back to both build resources`() {
        val root = tempProjectRoot()

        val guidance = ExecuteCodeBuildAbortGuidance.guidanceFor(
            outputText = "build errors: false, aborted: true",
            projectBasePath = root,
        )

        assertNotNull(guidance)
        assertTrue(guidance!!.contains(ExecuteCodeGradlePromptArticle().uri), "should include Gradle resource URI: $guidance")
        assertTrue(guidance.contains(ExecuteCodeMavenPromptArticle().uri), "should include Maven resource URI: $guidance")
        assertTrue(guidance.contains("the matching resource"), "should ask the agent to choose the matching resource: $guidance")
    }

    @Test
    fun `mixed maven and gradle root includes both build resources`() {
        val root = tempProjectRoot()
        root.resolve("pom.xml").writeText("<project />")
        root.resolve("build.gradle.kts").writeText("plugins { java }")

        val guidance = ExecuteCodeBuildAbortGuidance.guidanceFor(
            outputText = "Build errors: false, aborted: true",
            projectBasePath = root,
        )

        assertNotNull(guidance)
        assertTrue(guidance!!.contains(ExecuteCodeGradlePromptArticle().uri), "should include Gradle resource URI: $guidance")
        assertTrue(guidance.contains(ExecuteCodeMavenPromptArticle().uri), "should include Maven resource URI: $guidance")
    }

    @Test
    fun `missing base path falls back to both build resources`() {
        val guidance = ExecuteCodeBuildAbortGuidance.guidanceFor(
            outputText = "Compile errors: false, aborted: true",
            projectBasePath = null,
        )

        assertNotNull(guidance)
        assertTrue(guidance!!.contains(ExecuteCodeGradlePromptArticle().uri), "should include Gradle resource URI: $guidance")
        assertTrue(guidance.contains(ExecuteCodeMavenPromptArticle().uri), "should include Maven resource URI: $guidance")
    }

    @Test
    fun `appended guidance preserves original tool result`() {
        val root = tempProjectRoot()
        root.resolve("build.gradle.kts").writeText("plugins { java }")
        val original = ToolCallResult(
            content = listOf(ContentItem.Text("execution_id: test\nBuild errors: false, aborted: true")),
            isError = false,
        )

        val updated = ExecuteCodeBuildAbortGuidance.appendTo(original, root)
        val text = updated.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }

        assertFalse(updated.isError, "result should stay non-error")
        assertTrue(text.contains("execution_id: test"), "original text should remain: $text")
        assertTrue(text.contains(ExecuteCodeGradlePromptArticle().uri), "guidance should be appended: $text")
    }

    @Test
    fun `appended guidance starts on its own line`() {
        val root = tempProjectRoot()
        root.resolve("build.gradle.kts").writeText("plugins { java }")
        val original = ToolCallResult(
            content = listOf(ContentItem.Text("Build errors: false, aborted: true")),
            isError = false,
        )

        val updated = ExecuteCodeBuildAbortGuidance.appendTo(original, root)
        val text = updated.content.filterIsInstance<ContentItem.Text>().joinToString("") { it.text }

        assertFalse(text.contains("trueREQUIRED ACTION"), "guidance should not be glued to the aborted flag: $text")
        assertTrue(text.contains("true\nREQUIRED ACTION"), "guidance should start on a separate line: $text")
        assertTrue(text.contains(claudeFetchResourceTool), "guidance should name Claude's fetch tool: $text")
    }

    private fun tempProjectRoot(): Path {
        return createTempDirectory("execute-code-build-abort-guidance-").also {
            tempDirs.add(it)
        }
    }
}
