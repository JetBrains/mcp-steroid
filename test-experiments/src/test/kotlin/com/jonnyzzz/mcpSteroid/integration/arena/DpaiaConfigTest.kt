/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
    fun `service-125 overlay resource resolves and adds its class to FAIL_TO_PASS`() {
        val config = requireNotNull(DpaiaCuratedCases.CASE_CONFIGS["dpaia__feature__service-125"])
        val resourcePath = requireNotNull(config.overlayTestPatch) { "service-125 must declare its overlay" }
        assertNotNull(
            javaClass.classLoader.getResource(resourcePath),
            "overlay patch must be on the test classpath",
        )
        assertEquals(
            listOf("com.sivalabs.ft.features.config.ReleaseApiSecuritySliceTest"),
            config.overlayFailToPass,
        )
    }

    @Test
    fun `an overlay adds its patch and classes on top of the dataset case`() {
        val base = datasetCase("dpaia__feature__service-125", "dpaia/feature-service.git")
        val config = requireNotNull(DpaiaCuratedCases.CASE_CONFIGS[base.instanceId])

        val augmented = DpaiaCuratedCases.applyOverlay(base, config) { path ->
            assertEquals(config.overlayTestPatch, path)
            "overlay-test-patch"
        }

        assertEquals("dpaia__feature__service-125x", augmented.instanceId)
        assertEquals("dataset-test-patch\noverlay-test-patch", augmented.testPatch)
        assertEquals(
            listOf("com.example.DatasetTest", "com.sivalabs.ft.features.config.ReleaseApiSecuritySliceTest"),
            augmented.failToPass,
        )
    }

    @Test
    fun `a case without an overlay is returned unchanged`() {
        val base = datasetCase("dpaia__spring__petclinic-36", "dpaia/spring-petclinic.git")
        val config = requireNotNull(DpaiaCuratedCases.CASE_CONFIGS[base.instanceId])

        val same = DpaiaCuratedCases.applyOverlay(base, config) { error("must not read any resource") }

        assertEquals(base, same)
    }

    private fun datasetCase(instanceId: String, repo: String) = DpaiaTestCase(
        instanceId = instanceId,
        issueNumbers = emptyList(),
        tags = emptyList(),
        repo = repo,
        patch = "",
        testPatch = "dataset-test-patch",
        failToPass = listOf("com.example.DatasetTest"),
        passToPass = emptyList(),
        createdAt = "2026-01-01T00:00:00Z",
        baseCommit = "0".repeat(40),
        problemStatement = "",
        version = "1",
        isMaven = true,
        buildSystem = "maven",
        testArgs = "",
    )

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
