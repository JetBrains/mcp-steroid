/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins the shape of [McpSteroidIdeTargets] — the single source of truth for
 * which IntelliJ majors the plugin build, the Plugin Verifier, and the
 * in-repo IDE downloader cover. Failures here mean someone edited the matrix
 * in a way that breaks the per-major contract documented in
 * `docs/262-EAP-PLAN.md`.
 */
class McpSteroidIdeTargetsTest {

    @Test
    fun `build target is 261 stable`() {
        assertEquals(IdeTarget(major = "261", version = "2026.1"), McpSteroidIdeTargets.buildTarget)
    }

    @Test
    fun `verifier covers exactly 261 and 262`() {
        assertEquals(
            "verifierTargets must enumerate every major the plugin claims to support; " +
                "missing or extra entries indicate a silent EAP slide.",
            setOf("261", "262"),
            McpSteroidIdeTargets.verifierTargets.map { it.major }.toSet(),
        )
    }

    @Test
    fun `262 entry uses the named per-major EAP snapshot tag`() {
        val target = McpSteroidIdeTargets.verifierTargets.singleOrNull { it.major == "262" }
        assertNotNull("262 verifier entry must exist", target)
        assertEquals(
            "262 must use the per-major EAP-SNAPSHOT spelling so a future 263 EAP " +
                "cut cannot silently take its place.",
            "262-EAP-SNAPSHOT",
            target!!.version,
        )
    }

    @Test
    fun `no rolling LATEST-style tags anywhere`() {
        for (target in McpSteroidIdeTargets.verifierTargets + McpSteroidIdeTargets.buildTarget) {
            assertFalse(
                "Rolling cross-major tags like LATEST-EAP-SNAPSHOT would silently slide " +
                    "into a major we have not tested. Got '${target.version}' for major ${target.major}.",
                target.version.contains("LATEST"),
            )
        }
    }

    @Test
    fun `252 and 253 are not present`() {
        for (target in McpSteroidIdeTargets.verifierTargets) {
            assertFalse(
                "Major ${target.major} is deprecated; remove the verifier entry.",
                target.major == "252" || target.major == "253",
            )
        }
    }

    @Test
    fun `validateTargets rejects LATEST-EAP-SNAPSHOT`() {
        try {
            validateTargets(
                buildTarget = IdeTarget("261", "2026.1"),
                verifierTargets = listOf(
                    IdeTarget("261", "2026.1"),
                    IdeTarget("262", "LATEST-EAP-SNAPSHOT"),
                ),
            )
            fail("validateTargets should have rejected LATEST-EAP-SNAPSHOT")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("LATEST"))
        }
    }

    @Test
    fun `validateTargets rejects EAP tag with wrong major prefix`() {
        try {
            validateTargets(
                buildTarget = IdeTarget("261", "2026.1"),
                verifierTargets = listOf(
                    IdeTarget("261", "2026.1"),
                    // Wrong: '263-EAP-SNAPSHOT' under the '262' major.
                    IdeTarget("262", "263-EAP-SNAPSHOT"),
                ),
            )
            fail("validateTargets should have rejected a per-major mismatch")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("per-major-scoped"))
        }
    }

    @Test
    fun `validateTargets rejects deprecated 253 major`() {
        try {
            validateTargets(
                buildTarget = IdeTarget("261", "2026.1"),
                verifierTargets = listOf(
                    IdeTarget("253", "2025.3"),
                    IdeTarget("261", "2026.1"),
                ),
            )
            fail("validateTargets should have rejected 253")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("deprecated"))
        }
    }

    @Test
    fun `validateTargets requires buildTarget to match the first verifier entry`() {
        try {
            validateTargets(
                buildTarget = IdeTarget("261", "2026.1"),
                verifierTargets = listOf(
                    IdeTarget("262", "262-EAP-SNAPSHOT"),
                    IdeTarget("261", "2026.1"),
                ),
            )
            fail("validateTargets should have rejected build/first-verifier mismatch")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("first verifierTargets entry"))
        }
    }

    @Test
    fun `allTargets dedups buildTarget against verifier`() {
        // buildTarget IS the first verifier entry → no duplicate.
        assertEquals(
            McpSteroidIdeTargets.verifierTargets,
            McpSteroidIdeTargets.allTargets,
        )
    }
}
