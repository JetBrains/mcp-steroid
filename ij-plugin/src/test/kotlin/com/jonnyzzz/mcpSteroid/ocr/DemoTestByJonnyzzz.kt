/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ocr

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class DemoTestByJonnyzzz {

    @Test
    fun leaderboardSortsByScoreDescending() {
        assumeTrue(
            "Enable demo test with -Dmcp.demo.by.jonnyzzz=true",
            java.lang.Boolean.getBoolean(DEMO_PROPERTY)
        )

        val players = mutableListOf(
            Player("Ada", 120),
            Player("Linus", 450),
            Player("Grace", 300)
        )

        val sorted = leaderboard(players).map { it.name }

        assertEquals(listOf("Linus", "Grace", "Ada"), sorted)
    }

    private companion object {
        const val DEMO_PROPERTY = "mcp.demo.by.jonnyzzz"
    }
}
