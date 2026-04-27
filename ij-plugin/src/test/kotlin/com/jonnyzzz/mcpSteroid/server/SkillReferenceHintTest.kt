/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.execution.executionSuggestionService

class SkillReferenceHintTest : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    fun testHintForProtectedApplicationConfigurationConstructor() {
        val compilerError = """
            input.kt:39:22: error: cannot access 'constructor(p0: String!, p1: Project, p2: ConfigurationFactory): ApplicationConfiguration': it is protected in 'com.intellij.execution.application.ApplicationConfiguration'.
        """.trimIndent()

        val hint = project.executionSuggestionService.computeHint(compilerError)
        assertTrue(
            "Hint should recommend modern run configuration creation APIs:\n$hint",
            hint.contains("RunManager.createConfiguration")
        )
        assertTrue(
            "Hint should direct agents away from direct ApplicationConfiguration constructor usage:\n$hint",
            hint.contains("ApplicationConfiguration")
        )
    }

    fun testHintForPsiFileVirtualFileMismatch() {
        val compilerError = """
            input.kt:45:55: error: argument type mismatch: actual type is 'PsiFile', but 'VirtualFile' was expected.
            input.kt:40:29: error: unresolved reference 'path'.
            input.kt:71:31: error: unresolved reference 'url'.
        """.trimIndent()

        val hint = project.executionSuggestionService.computeHint(compilerError)
        assertTrue(
            "Hint should suggest a VirtualFile-oriented search/read pattern:\n$hint",
            hint.contains("FilenameIndex.getVirtualFilesByName")
        )
        assertTrue(
            "Hint should explain how to convert PsiFile to VirtualFile safely:\n$hint",
            hint.contains("psiFile.virtualFile")
        )
    }

    fun testHintForDeprecatedFindFilesHelper() {
        val compilerError = """
            input.kt:24:17: error: unresolved reference 'findFiles'.
            input.kt:27:45: error: unresolved reference 'contentsToByteArray'.
        """.trimIndent()

        val hint = project.executionSuggestionService.computeHint(compilerError)
        assertTrue(
            "Hint should point to currently supported file discovery helpers:\n$hint",
            hint.contains("findProjectFiles")
        )
        assertTrue(
            "Hint should suggest VirtualFile loading pattern when helper is unavailable:\n$hint",
            hint.contains("VfsUtilCore")
        )
    }

    fun testHintForLegacyExecuteSuspendLabel() {
        val compilerError = """
            input.kt:28:15: error: unresolved label.
                    return@executeSuspend
                          ^^^^^^^^^^^^^^^
        """.trimIndent()

        val hint = project.executionSuggestionService.computeHint(compilerError)
        assertTrue(
            "Hint should mention both legacy execute wrapper labels:\n$hint",
            hint.contains("return@executeSteroidCode or return@executeSuspend")
        )
        assertTrue(
            "Hint should recommend plain return in script body context:\n$hint",
            hint.contains("Use plain return")
        )
    }

    fun testHintForIndexNotReadyException() {
        val error = """
            com.intellij.openapi.project.IndexNotReadyException: Please change caller according to com.intellij.openapi.project.IndexNotReadyException documentation
        """.trimIndent()

        val hint = project.executionSuggestionService.computeHint(error)
        assertTrue(
            "Hint should recommend smartReadAction for indexed PSI work:\n$hint",
            hint.contains("smartReadAction")
        )
        assertTrue(
            "Hint should recommend Observation.awaitConfiguration after project configuration:\n$hint",
            hint.contains("Observation.awaitConfiguration")
        )
        assertTrue(
            "Hint should explain that waitForSmartMode is point-in-time only:\n$hint",
            hint.contains("point-in-time")
        )
    }
}
