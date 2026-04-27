/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DpaiaConfigTest {

    @Test
    fun `default projectJdkVersion is 21`() {
        val config = DpaiaCuratedCases.CaseConfig()
        assertEquals("21", config.projectJdkVersion)
    }

    @Test
    fun `microshop cases use JDK 24`() {
        val microshop18 = DpaiaCuratedCases.CASE_CONFIGS["dpaia__spring__boot__microshop-18"]
        assertEquals("24", microshop18?.projectJdkVersion)

        val microshop2 = DpaiaCuratedCases.CASE_CONFIGS["dpaia__spring__boot__microshop-2"]
        assertEquals("24", microshop2?.projectJdkVersion)

        val microshop1 = DpaiaCuratedCases.CASE_CONFIGS["dpaia__spring__boot__microshop-1"]
        assertEquals("24", microshop1?.projectJdkVersion)
    }

    @Test
    fun `petclinic cases default to JDK 21`() {
        val petclinicCases = DpaiaCuratedCases.CASE_CONFIGS
            .filterKeys { it.contains("petclinic") }
        assertTrue(petclinicCases.isNotEmpty(), "Expected curated petclinic cases")
        assertEquals(
            List(petclinicCases.size) { "21" },
            petclinicCases.values.map { it.projectJdkVersion },
        )
    }
}
