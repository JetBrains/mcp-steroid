/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure tests for [isProjectNotFound] — the predicate `mcpExecuteCode` uses to recognize the
 * Gradle-import rename race (#412): the project opens under its folder name (`project-home`), the
 * first Gradle sync renames it to `rootProject.name` (`demo-project`), and a `project_name` routing
 * key resolved before the rename stops routing. The server then answers with
 * `ProjectScopedToolHandler.resolveProject`'s error, which this predicate mirrors (the same
 * documented tool-result coupling as [INDEXING_IN_PROGRESS_MARKER]).
 */
class ProjectRenameRetryTest {

    @Test
    fun `detects the not-found answer for exactly the key that was sent`() {
        // The literal failure observed on TC (PrintCsvPrintToonPromptTest.initializationError).
        val text = """
            execution_id: eid_x-integration-test
            ERROR: Project not found: "project-home". Available project_name values: demo-project-ki3y1pmc
        """.trimIndent()
        assertTrue(isProjectNotFound(text, "project-home"))
    }

    @Test
    fun `a not-found answer for a DIFFERENT key is not a match`() {
        // The retry must key on the name it actually sent — never on someone else's failure text.
        val text = """ERROR: Project not found: "other-project". Available project_name values: demo-project-ki3y1pmc"""
        assertFalse(isProjectNotFound(text, "project-home"))
    }

    @Test
    fun `script output mentioning the phrase without the quoted key is not a match`() {
        // A script's own println must never masquerade as the routing failure.
        assertFalse(isProjectNotFound("Project not found: project-home (no quotes, script chatter)", "project-home"))
        assertFalse(isProjectNotFound("done", "project-home"))
        assertFalse(isProjectNotFound("", "project-home"))
    }

    @Test
    fun `a normal successful result is not a routing failure`() {
        assertFalse(isProjectNotFound("execution_id: eid_x\nJDKs registered\ndone", "project-home"))
    }
}
