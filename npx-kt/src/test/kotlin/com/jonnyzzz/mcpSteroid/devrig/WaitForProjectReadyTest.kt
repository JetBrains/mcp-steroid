/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.mcp.McpJson
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

/**
 * `win`'s field names are NOT a guess: they are `ListedWindow`'s (`mcp-steroid-server`'s
 * `ListWindowsTool.kt`) serialized shape verbatim. `McpJson` sets no naming strategy, so a property
 * serializes under its declared name unless annotated — `projectPath` alone carries
 * `@SerialName("project_path")` (#381), while the three readiness flags carry no annotation and stay
 * camelCase.
 */
class WaitForProjectReadyTest {
    private fun windows(vararg w: String) = McpJson.parseToJsonElement(
        """{"windows":[${w.joinToString(",")}]}"""
    ).jsonObject

    private fun win(path: String, init: Boolean, indexing: Boolean, modal: Boolean) =
        """{"project_path":"$path","projectInitialized":$init,"indexingInProgress":$indexing,""" +
            """"modalDialogShowing":$modal}"""

    @Test
    fun `ready when initialized, not indexing, no modal`() {
        assertTrue(isProjectReady(windows(win("/p", true, false, false)), "/p"))
    }

    @Test
    fun `not ready while indexing`() {
        assertFalse(isProjectReady(windows(win("/p", true, true, false)), "/p"))
    }

    @Test
    fun `not ready with a modal`() {
        assertFalse(isProjectReady(windows(win("/p", true, false, true)), "/p"))
    }

    @Test
    fun `not ready when the project window is absent`() {
        assertFalse(isProjectReady(windows(win("/other", true, false, false)), "/p"))
    }

    @Test
    fun `awaitProjectReady returns true once ready before timeout`() = runTest {
        var t = 0L
        var calls = 0
        val ok = awaitProjectReady(
            pollListWindows = { calls++; windows(win("/p", true, calls < 3, false)) },
            projectPath = "/p", timeoutMs = 300_000, intervalMs = 1000,
            now = { t }, sleep = { t += it },
        )
        assertTrue(ok)
        assertTrue(calls >= 3)
    }

    @Test
    fun `awaitProjectReady returns false at timeout`() = runTest {
        var t = 0L
        val ok = awaitProjectReady(
            pollListWindows = { windows(win("/p", true, true, false)) },
            projectPath = "/p", timeoutMs = 5_000, intervalMs = 1000,
            now = { t }, sleep = { t += it },
        )
        assertFalse(ok)
    }
}
