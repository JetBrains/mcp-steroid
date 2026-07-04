/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Locks the #92 invariant of the project-id hash. The in-IDE plugin's `projectNameFor(project)` is
 * `"<name>-<base36FixedWidth("project", project.basePath, project.name)>"`; this guards the hash part
 * (the disambiguating component) so two SAME-NAMED projects in DIFFERENT directories — e.g. a checkout
 * and its git worktree — never collapse to the same `project_name`.
 */
class ProjectNameHashTest {
    private fun hash(path: String?, name: String) = base36FixedWidth("project", path, name)

    @Test
    fun `same name in different directories yields different hashes (the #92 core)`() {
        val a = hash("/work/a/dupproj", "dupproj")
        val b = hash("/work/b/dupproj", "dupproj")
        assertNotEquals(a, b, "same-named projects in different folders must hash differently")
    }

    @Test
    fun `the hash is deterministic for the same path and name`() {
        assertEquals(hash("/work/a/dupproj", "dupproj"), hash("/work/a/dupproj", "dupproj"))
    }

    @Test
    fun `different names in the same directory differ`() {
        assertNotEquals(hash("/work/x", "alpha"), hash("/work/x", "beta"))
    }

    @Test
    fun `the hash is fixed-width lowercase base36`() {
        val h = hash("/work/a/dupproj", "dupproj")
        assertEquals(8, h.length, "hash must be 8 chars")
        assertTrue(h.all { it in "0123456789abcdefghijklmnopqrstuvwxyz" }, "hash must be lowercase base36: $h")
    }

    @Test
    fun `a null base path is tolerated and stays distinct from an empty path neighbour`() {
        // base36FixedWidth treats a null arg and "" identically (empty bytes + separator), so a
        // project with no basePath hashes the same as one with "" — documented, deterministic behavior.
        assertEquals(hash(null, "dupproj"), hash("", "dupproj"))
        assertNotEquals(hash(null, "dupproj"), hash("/work/a/dupproj", "dupproj"))
    }
}
