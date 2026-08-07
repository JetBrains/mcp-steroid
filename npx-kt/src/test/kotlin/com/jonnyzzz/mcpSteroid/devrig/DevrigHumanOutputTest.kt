/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class DevrigHumanOutputTest {
    @Test
    fun `human headliner uses terminal color while JSON stays ANSI free`() {
        val terminal = Terminal(ansiLevel = AnsiLevel.ANSI16)
        val human = invocation(json = false, terminal).renderHeadliner("devrig v1\n")
        val json = invocation(json = true, terminal).renderHeadliner("devrig v1\n")

        assertTrue(human.contains("\u001B["), human)
        assertFalse(json.contains("\u001B["), json)
        assertTrue(json.startsWith("devrig v1"), json)
    }

    private fun invocation(json: Boolean, terminal: Terminal) = DevrigCliInvocation(
        commandPath = "devrig backend",
        debug = false,
        json = json,
        mode = DevrigCliMode.BACKEND,
        terminal = terminal,
        action = { 0 },
    )
}
