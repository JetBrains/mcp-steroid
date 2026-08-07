/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure-JVM coverage for [compileProjectScript] — no container involved.
 *
 * The two modes are not interchangeable: the strict one guards fixture projects that must build, the
 * lenient one lets a DPAIA arena scenario warm its caches on a tree that cannot build yet. Getting the
 * flag backwards is invisible until a live Docker run burns an agent session, which is exactly how the
 * Round 6 smoke builds failed.
 */
class CompileProjectScriptTest {

    @Test
    fun `a strict compile fails the run on build errors`() {
        val script = compileProjectScript(requireCleanCompile = true)
        assertTrue(script.contains("""check(!build.hasErrors())"""), script)
    }

    @Test
    fun `a warm-up compile reports build errors instead of failing`() {
        // The arena's starting state: the dataset test patch calls production code the agent has yet to
        // write, so buildAllModules reports errors by construction.
        val script = compileProjectScript(requireCleanCompile = false)
        assertFalse(script.contains("""check(!build.hasErrors())"""), script)
        assertTrue(script.contains("build errors present"), script)
    }

    @Test
    fun `an aborted build fails in both modes`() {
        // Aborted means the build runner never started — nothing was warmed, so the container is
        // misconfigured whatever the project was expected to compile to.
        for (strict in listOf(true, false)) {
            val script = compileProjectScript(requireCleanCompile = strict)
            assertTrue(script.contains("""check(!build.isAborted())"""), "strict=$strict: $script")
        }
    }

    @Test
    fun `both modes still build every module`() {
        for (strict in listOf(true, false)) {
            val script = compileProjectScript(requireCleanCompile = strict)
            assertTrue(script.contains("buildAllModules()"), "strict=$strict: $script")
        }
    }
}
