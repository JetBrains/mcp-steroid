/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins launcher self-healing through each real lifecycle branch and the generated tool branch. */
class LauncherSelfHealPredicateTest {

    @Test
    fun `lifecycle and informational commands self-heal the launcher`() {
        val commands = listOf(
            arrayOf("mcp"),
            arrayOf("backend"),
            arrayOf("backend", "download"),
            arrayOf("backend", "start"),
            arrayOf("backend", "stop"),
            arrayOf("backend", "provision"),
            arrayOf("project"),
            arrayOf("install"),
            arrayOf("install", "claude"),
            arrayOf("install", "devrig"),
            arrayOf("install", "config"),
            arrayOf("install", "plugin"),
            arrayOf("--help"),
            arrayOf("version"),
            arrayOf("--bogus"),
        )

        for (args in commands) {
            assertTrue(
                parseDevrigCommand(args).selfHealsLauncherOnStart,
                "expected ${args.toList()} to self-heal the launcher",
            )
        }
    }

    @Test
    fun `generated MCP tool facades never mutate launcher state`() {
        for (args in listOf(arrayOf("list_windows"), arrayOf("list_windows", "--json"))) {
            val invocation = parseDevrigCommand(args)
            assertTrue(invocation.generatedTool != null, "expected a generated tool for ${args.toList()}")
            assertFalse(invocation.selfHealsLauncherOnStart, "generated tool ${args.toList()} must stay stateless")
        }
    }
}
