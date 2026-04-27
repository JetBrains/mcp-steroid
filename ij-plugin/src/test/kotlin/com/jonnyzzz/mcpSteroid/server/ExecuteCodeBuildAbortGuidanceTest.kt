/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeGradlePromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeMavenPromptArticle
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class ExecuteCodeBuildAbortGuidanceTest {
    private val claudeFetchResourceTool = "mcp__mcp-steroid__steroid_fetch_resource"

    private val tempDirs = mutableListOf<Path>()

    @After
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
        assertTrue("should make the recovery mandatory: $guidance", guidance!!.contains("REQUIRED ACTION"))
        assertTrue("should name Claude's fetch tool: $guidance", guidance.contains(claudeFetchResourceTool))
        assertTrue("should identify that tool as the next call: $guidance", guidance.contains("NEXT TOOL CALL"))
        assertTrue("should include Gradle resource URI: $guidance", guidance.contains(ExecuteCodeGradlePromptArticle().uri))
        assertFalse("should not include Maven URI for a Gradle project: $guidance", guidance.contains(ExecuteCodeMavenPromptArticle().uri))
        assertTrue("should tell the agent not to jump straight to Bash: $guidance", guidance.contains("before using Bash"))
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
        assertTrue("should include Maven resource URI: $guidance", guidance!!.contains(ExecuteCodeMavenPromptArticle().uri))
        assertFalse("should not include Gradle URI for a Maven project: $guidance", guidance.contains(ExecuteCodeGradlePromptArticle().uri))
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
        assertTrue("should include Gradle resource URI: $guidance", guidance!!.contains(ExecuteCodeGradlePromptArticle().uri))
        assertTrue("should include Maven resource URI: $guidance", guidance.contains(ExecuteCodeMavenPromptArticle().uri))
        assertTrue("should ask the agent to choose the matching resource: $guidance", guidance.contains("the matching resource"))
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
        assertTrue("should include Gradle resource URI: $guidance", guidance!!.contains(ExecuteCodeGradlePromptArticle().uri))
        assertTrue("should include Maven resource URI: $guidance", guidance.contains(ExecuteCodeMavenPromptArticle().uri))
    }

    @Test
    fun `missing base path falls back to both build resources`() {
        val guidance = ExecuteCodeBuildAbortGuidance.guidanceFor(
            outputText = "Compile errors: false, aborted: true",
            projectBasePath = null,
        )

        assertNotNull(guidance)
        assertTrue("should include Gradle resource URI: $guidance", guidance!!.contains(ExecuteCodeGradlePromptArticle().uri))
        assertTrue("should include Maven resource URI: $guidance", guidance.contains(ExecuteCodeMavenPromptArticle().uri))
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

        assertFalse("result should stay non-error", updated.isError)
        assertTrue("original text should remain: $text", text.contains("execution_id: test"))
        assertTrue("guidance should be appended: $text", text.contains(ExecuteCodeGradlePromptArticle().uri))
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

        assertFalse("guidance should not be glued to the aborted flag: $text", text.contains("trueREQUIRED ACTION"))
        assertTrue("guidance should start on a separate line: $text", text.contains("true\nREQUIRED ACTION"))
        assertTrue("guidance should name Claude's fetch tool: $text", text.contains(claudeFetchResourceTool))
    }

    private fun tempProjectRoot(): Path {
        return createTempDirectory("execute-code-build-abort-guidance-").also {
            tempDirs.add(it)
        }
    }
}
