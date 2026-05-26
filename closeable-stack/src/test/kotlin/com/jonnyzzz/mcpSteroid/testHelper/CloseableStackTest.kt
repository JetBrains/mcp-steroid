/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class CloseableStackTest {
    @Test
    fun `runs cleanup actions in reverse registration order`() {
        val events = mutableListOf<String>()
        val stack = CloseableStackHost()

        stack.registerCleanupAction { events += "first" }
        stack.registerCleanupAction { events += "second" }

        stack.closeAllStacks()

        assertEquals(listOf("second", "first"), events)
    }

    @Test
    fun `runs cleanup actions registered during cleanup`() {
        val events = mutableListOf<String>()
        val stack = CloseableStackHost()

        stack.registerCleanupAction {
            events += "first"
            stack.registerCleanupAction { events += "late" }
        }

        stack.closeAllStacks()

        assertEquals(listOf("first", "late"), events)
    }

    @Test
    fun `nested stack closes at its registration position`() {
        val events = mutableListOf<String>()
        val stack = CloseableStackHost()

        stack.registerCleanupAction { events += "parent-first" }
        val nested = stack.nestedStack("nested")
        nested.registerCleanupAction { events += "nested-first" }
        nested.registerCleanupAction { events += "nested-second" }
        stack.registerCleanupAction { events += "parent-second" }

        stack.closeAllStacks()

        assertEquals(
            listOf("parent-second", "nested-second", "nested-first", "parent-first"),
            events,
        )
    }

    @Test
    fun `runWithCloseableStack closes stack when action fails`() {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("boom")

        val thrown = assertFailsWith<IllegalStateException> {
            runWithCloseableStack { stack ->
                stack.registerCleanupAction { events += "cleanup" }
                throw failure
            }
        }

        assertSame(failure, thrown)
        assertEquals(listOf("cleanup"), events)
    }

    @Test
    fun `closeAllStacks aggregates cleanup failures after running all actions`() {
        val events = mutableListOf<String>()
        val firstFailure = IllegalStateException("first")
        val secondFailure = IllegalArgumentException("second")
        val stack = CloseableStackHost()

        stack.registerCleanupAction {
            events += "first"
            throw firstFailure
        }
        stack.registerCleanupAction {
            events += "second"
            throw secondFailure
        }

        val thrown = assertFailsWith<Error> {
            stack.closeAllStacks()
        }

        assertEquals(listOf("second", "first"), events)
        assertEquals("Error during cleanup", thrown.message)
        assertEquals(listOf(secondFailure, firstFailure), thrown.suppressed.toList())
    }
}
