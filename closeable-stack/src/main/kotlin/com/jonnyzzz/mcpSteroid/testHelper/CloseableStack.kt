/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

interface CloseableStack {
    fun registerCleanupAction(cleanupAction: () -> Unit)
    fun nestedStack(name: String): CloseableStackDriver

    companion object
}

interface CloseableStackDriver : CloseableStack {
    fun closeAllStacks()
}

class CloseableStackHost(val name: String = "root") : CloseableStack, CloseableStackDriver {
    private val cleanupActions = mutableListOf<() -> Unit>()

    override fun registerCleanupAction(cleanupAction: () -> Unit) {
        cleanupActions += cleanupAction
    }

    override fun nestedStack(name: String): CloseableStackDriver {
        val child = CloseableStackHost(name)
        registerCleanupAction {
            // We assume the next cleanup does nothing, if ever.
            child.closeAllStacks()
        }
        return child
    }

    override fun closeAllStacks() {
        val errors = mutableListOf<Throwable>()
        while (cleanupActions.isNotEmpty()) {
            val copy = cleanupActions.toMutableList().reversed()
            cleanupActions.clear()
            for (it in copy) {
                try {
                    it()
                } catch (t: Throwable) {
                    println("Error during cleanup: ${t.message}")
                    println(t.stackTraceToString())
                    errors.add(t)
                }
            }
        }

        if (errors.isNotEmpty()) {
            throw Error("Error during cleanup").apply {
                errors.forEach {
                    addSuppressed(it)
                }
            }
        }
    }
}

fun runWithCloseableStack(action: (CloseableStack) -> Unit) {
    val stack = CloseableStackHost("root")
    try {
        action(stack)
    } finally {
        stack.closeAllStacks()
    }
}
