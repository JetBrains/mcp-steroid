/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ocr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class DemoTestByJonnyzzz {

    @Test
    fun leaderboardSortsByScoreDescending() {
        assumeTrue(
            java.lang.Boolean.getBoolean(DEMO_PROPERTY),
            "Enable demo test with -Dmcp.demo.by.jonnyzzz=true"
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
