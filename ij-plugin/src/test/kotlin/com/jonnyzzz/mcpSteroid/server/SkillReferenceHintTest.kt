/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.jonnyzzz.mcpSteroid.execution.executionSuggestionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class SkillReferenceHintTest {

    private val projectFixture = projectFixture()

    @Test
    fun hintForProtectedApplicationConfigurationConstructor() {
        val compilerError = """
            input.kt:39:22: error: cannot access 'constructor(p0: String!, p1: Project, p2: ConfigurationFactory): ApplicationConfiguration': it is protected in 'com.intellij.execution.application.ApplicationConfiguration'.
        """.trimIndent()

        val hint = projectFixture.get().executionSuggestionService.computeHint(compilerError)
        assertTrue(
            hint.contains("RunManager.createConfiguration"),
            "Hint should recommend modern run configuration creation APIs:\n$hint",
        )
        assertTrue(
            hint.contains("ApplicationConfiguration"),
            "Hint should direct agents away from direct ApplicationConfiguration constructor usage:\n$hint",
        )
    }

    @Test
    fun hintForPsiFileVirtualFileMismatch() {
        val compilerError = """
            input.kt:45:55: error: argument type mismatch: actual type is 'PsiFile', but 'VirtualFile' was expected.
            input.kt:40:29: error: unresolved reference 'path'.
            input.kt:71:31: error: unresolved reference 'url'.
        """.trimIndent()

        val hint = projectFixture.get().executionSuggestionService.computeHint(compilerError)
        assertTrue(
            hint.contains("FilenameIndex.getVirtualFilesByName"),
            "Hint should suggest a VirtualFile-oriented search/read pattern:\n$hint",
        )
        assertTrue(
            hint.contains("psiFile.virtualFile"),
            "Hint should explain how to convert PsiFile to VirtualFile safely:\n$hint",
        )
    }

    @Test
    fun hintForDeprecatedFindFilesHelper() {
        val compilerError = """
            input.kt:24:17: error: unresolved reference 'findFiles'.
            input.kt:27:45: error: unresolved reference 'contentsToByteArray'.
        """.trimIndent()

        val hint = projectFixture.get().executionSuggestionService.computeHint(compilerError)
        assertTrue(
            hint.contains("findProjectFiles"),
            "Hint should point to currently supported file discovery helpers:\n$hint",
        )
        assertTrue(
            hint.contains("VfsUtilCore"),
            "Hint should suggest VirtualFile loading pattern when helper is unavailable:\n$hint",
        )
    }

    @Test
    fun hintForLegacyExecuteSuspendLabel() {
        val compilerError = """
            input.kt:28:15: error: unresolved label.
                    return@executeSuspend
                          ^^^^^^^^^^^^^^^
        """.trimIndent()

        val hint = projectFixture.get().executionSuggestionService.computeHint(compilerError)
        assertTrue(
            hint.contains("return@executeSteroidCode or return@executeSuspend"),
            "Hint should mention both legacy execute wrapper labels:\n$hint",
        )
        assertTrue(
            hint.contains("Use plain return"),
            "Hint should recommend plain return in script body context:\n$hint",
        )
    }

    @Test
    fun emptyOutputHintFiresOnSuccessfulSilentScript() {
        val service = projectFixture.get().executionSuggestionService
        val suggestions = service.generateSuggestions(
            isFailed = false,
            errorMessages = emptyList(),
            userOutputCount = 0,
        )
        assertEquals(
            1, suggestions.size,
            "Successful but silent script must yield exactly one hint:\n$suggestions",
        )
        val hint = suggestions.single()
        assertTrue(
            hint.contains("println(value)"),
            "Hint must call out the missing println(...) explicitly:\n$hint",
        )
        assertTrue(
            hint.contains("printJson(value)"),
            "Hint must mention printJson for structured data:\n$hint",
        )
        assertTrue(
            hint.contains("NOT auto-printed"),
            "Hint must explain that the last expression is not auto-printed:\n$hint",
        )
    }

    @Test
    fun emptyOutputHintStaysQuietWhenScriptPrinted() {
        val service = projectFixture.get().executionSuggestionService
        val suggestions = service.generateSuggestions(
            isFailed = false,
            errorMessages = emptyList(),
            userOutputCount = 3,
        )
        assertTrue(
            suggestions.isEmpty(),
            "Successful script with output must produce no suggestions:\n$suggestions",
        )
    }

    @Test
    fun emptyOutputHintIsNotEmittedOnFailure() {
        val service = projectFixture.get().executionSuggestionService
        // Failed execution that ALSO printed nothing must surface the failure-side hint,
        // never the empty-output one (the failure is the actionable signal).
        val suggestions = service.generateSuggestions(
            isFailed = true,
            errorMessages = listOf("Read access is allowed from inside read-action only"),
            userOutputCount = 0,
        )
        assertEquals(
            1, suggestions.size,
            "Failure path must produce exactly the failure hint:\n$suggestions",
        )
        assertFalse(
            suggestions.single().contains("NOT auto-printed"),
            "Failure path must not surface the empty-output hint:\n${suggestions.single()}",
        )
        assertTrue(
            suggestions.single().contains("readAction"),
            "Failure hint must still steer toward readAction:\n${suggestions.single()}",
        )
    }

    @Test
    fun readActionHintEnumeratesCommonWrapTargets() {
        val hint = projectFixture.get().executionSuggestionService.computeHint(
            "Read access is allowed from inside read-action only (see Application.runReadAction())"
        )
        for (api in listOf(
            "FilenameIndex.*",
            "PsiManager.findFile(vf)",
            "psiFile.text",
            "vf.children",
            "FileDocumentManager.getDocument(vf)",
            "ProjectRootManager.contentRoots",
            "ChangeListManager.allChanges",
        )) {
            assertTrue(
                hint.contains(api),
                "Read-action hint must enumerate $api as a wrap target:\n$hint",
            )
        }
    }

    @Test
    fun readActionHintLinksToThreadingArticle() {
        val hint = projectFixture.get().executionSuggestionService.computeHint(
            "Read access is allowed from inside read-action only"
        )
        assertTrue(
            hint.contains("mcp-steroid://skill/coding-with-intellij-threading"),
            "Read-action hint must surface the coding-with-intellij-threading article URI:\n$hint",
        )
        assertTrue(
            hint.contains("EVERY script") || hint.contains("every script"),
            "Read-action hint must remind that the wrap is required on EVERY script:\n$hint",
        )
    }

    @Test
    fun generateSuggestionsBackwardCompatWhenUserOutputCountUnknown() {
        val service = projectFixture.get().executionSuggestionService
        // The default -1 must keep the old behavior: success + no error => no hint.
        val suggestions = service.generateSuggestions(
            isFailed = false,
            errorMessages = emptyList(),
        )
        assertTrue(
            suggestions.isEmpty(),
            "Default userOutputCount must NOT trigger the empty-output hint:\n$suggestions",
        )
    }

    @Test
    fun hintForIndexNotReadyException() {
        val error = """
            com.intellij.openapi.project.IndexNotReadyException: Please change caller according to com.intellij.openapi.project.IndexNotReadyException documentation
        """.trimIndent()

        val hint = projectFixture.get().executionSuggestionService.computeHint(error)
        assertTrue(
            hint.contains("smartReadAction"),
            "Hint should recommend smartReadAction for indexed PSI work:\n$hint",
        )
        assertTrue(
            hint.contains("Observation.awaitConfiguration"),
            "Hint should recommend Observation.awaitConfiguration after project configuration:\n$hint",
        )
        assertTrue(
            hint.contains("point-in-time"),
            "Hint should explain that waitForSmartMode is point-in-time only:\n$hint",
        )
    }
}
