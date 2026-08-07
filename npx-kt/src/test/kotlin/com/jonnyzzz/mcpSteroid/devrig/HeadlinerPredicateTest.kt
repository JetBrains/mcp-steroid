/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins banner behavior through the real unified command tree, including generated MCP tool commands. */
class HeadlinerPredicateTest {

    @Test
    fun `tool-running lifecycle commands print the headliner only in human mode`() {
        val commands = listOf(
            arrayOf("backend") to arrayOf("backend", "--json"),
            arrayOf("backend", "download") to arrayOf("backend", "download", "--json"),
            arrayOf("backend", "start") to arrayOf("backend", "start", "--json"),
            arrayOf("backend", "stop") to arrayOf("backend", "stop", "--json"),
            arrayOf("backend", "provision") to arrayOf("backend", "provision", "--json"),
            arrayOf("install", "claude") to null,
            arrayOf("install", "devrig") to null,
            arrayOf("install", "plugin") to null,
        )

        for ((humanArgs, jsonArgs) in commands) {
            val human = parseDevrigCommand(humanArgs)
            assertTrue(human.printsHeadliner, "expected ${humanArgs.toList()} to print the headliner")
            if (jsonArgs != null) {
                val json = parseDevrigCommand(jsonArgs)
                assertFalse(json.printsHeadliner, "expected ${jsonArgs.toList()} to suppress the headliner")
            }
        }
    }

    @Test
    fun `MCP informational and generated tool commands never print the headliner`() {
        val commands = listOf(
            arrayOf("mcp"),
            arrayOf("install"),
            arrayOf("install", "--json"),
            arrayOf("install", "config"),
            arrayOf("install", "config", "--json"),
            arrayOf("--help"),
            arrayOf("version"),
            arrayOf("version", "--json"),
            arrayOf("list_windows"),
            arrayOf("list_windows", "--json"),
            arrayOf("list_projects"),
            arrayOf("list_projects", "--json"),
            arrayOf("project"),
            arrayOf("project", "--json"),
        )

        for (args in commands) {
            assertFalse(parseDevrigCommand(args).printsHeadliner, "expected ${args.toList()} to stay banner-free")
        }
    }
}
