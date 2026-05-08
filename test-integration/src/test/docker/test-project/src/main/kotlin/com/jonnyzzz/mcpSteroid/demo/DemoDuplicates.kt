/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.demo

/**
 * Fixture for FindDuplicatesPromptTest (issue #33).
 *
 * The two methods below have byte-identical bodies — the IDE's bundled `DuplicatedCode`
 * inspection MUST flag them as a clone cluster. The integration test instructs Claude
 * to enumerate every cluster via the typed `DuplicateProblemDescriptor.getTextClone()`
 * getter (no private-field reflection) and asserts this cluster appears in the output.
 *
 * If you change either body, update the other one to keep them identical, or the
 * test will start failing.
 */
class DemoDuplicates {

    fun calculateInvoiceTotal(items: List<Pair<Int, Double>>): Double {
        var total = 0.0
        var count = 0
        for ((quantity, price) in items) {
            val line = quantity * price
            total += line
            count += quantity
            println("[invoice] quantity=$quantity price=$price line=$line total=$total count=$count")
        }
        println("[invoice] done — total=$total count=$count")
        return total
    }

    fun calculateOrderTotal(items: List<Pair<Int, Double>>): Double {
        var total = 0.0
        var count = 0
        for ((quantity, price) in items) {
            val line = quantity * price
            total += line
            count += quantity
            println("[invoice] quantity=$quantity price=$price line=$line total=$total count=$count")
        }
        println("[invoice] done — total=$total count=$count")
        return total
    }
}
