/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Pins [repairTrimmedUnifiedDiff] against the two real defect shapes the dpaia.dev dataset
 * serialization produces by stripping trailing whitespace (jonnyzzz/mcp-steroid#447 — observed as
 * `git apply: error: corrupt patch at line N` in the Petclinic36 and JhipsterApp3 arena setups):
 * blank context lines trimmed to empty, and whole trailing-context lines dropped from a hunk.
 */
class DpaiaPatchRepairTest {

    @Test
    fun `a clean patch passes through byte-identical`() {
        val patch = """
            diff --git a/A.java b/A.java
            index 1111111..2222222 100644
            --- a/A.java
            +++ b/A.java
            @@ -1,3 +1,4 @@
             one
            +added
             two
             three
        """.trimIndent() + "\n"

        assertEquals(patch, repairTrimmedUnifiedDiff(patch))
    }

    @Test
    fun `a blank context line trimmed to empty gets its leading space back`() {
        val trimmed = "@@ -1,3 +1,4 @@\n one\n\n+added\n three\n"
        val repaired = "@@ -1,3 +1,4 @@\n one\n \n+added\n three\n"

        assertEquals(repaired, repairTrimmedUnifiedDiff(trimmed))
    }

    @Test
    fun `trailing context dropped from the last hunk shrinks the header counts`() {
        // The dataset shape behind "corrupt patch at line N": the header promises one more
        // context line on each side than the body carries, because the serialization dropped it.
        val trimmed = "@@ -57,4 +58,5 @@\n ctx1\n ctx2\n+added\n ctx3\n"
        val repaired = "@@ -57,3 +58,4 @@\n ctx1\n ctx2\n+added\n ctx3\n"

        assertEquals(repaired, repairTrimmedUnifiedDiff(trimmed))
    }

    @Test
    fun `an empty line between two file sections is never absorbed as hunk content`() {
        // The header counts drive the parse: once -1/+1 is satisfied by "old"/"new", the bare
        // empty line before the next `diff --git` is a section boundary, not a blank context line.
        val patch = "@@ -1 +1 @@\n-old\n+new\n\ndiff --git a/B.java b/B.java\n--- a/B.java\n+++ b/B.java\n@@ -1 +1 @@\n-x\n+y\n"

        assertEquals(patch, repairTrimmedUnifiedDiff(patch))
    }

    @Test
    fun `both defects combined repair to a strictly applicable patch`() {
        val trimmed = "@@ -10,6 +10,7 @@ header\n ctx1\n ctx2\n\n-removed\n+added1\n+added2\n ctx3\n"
        val repaired = "@@ -10,5 +10,6 @@ header\n ctx1\n ctx2\n \n-removed\n+added1\n+added2\n ctx3\n"

        assertEquals(repaired, repairTrimmedUnifiedDiff(trimmed))
    }

    @Test
    fun `an asymmetric truncation fails loudly instead of guessing content`() {
        // Missing a '+' line cannot be reconstructed — repairing must never invent the change.
        val truncated = "@@ -1,1 +1,3 @@\n ctx\n+added\n"

        assertThrows(IllegalStateException::class.java) { repairTrimmedUnifiedDiff(truncated) }
    }

    @Test
    fun `a blank patch is returned unchanged`() {
        assertEquals("", repairTrimmedUnifiedDiff(""))
        assertEquals("\n", repairTrimmedUnifiedDiff("\n"))
    }
}
