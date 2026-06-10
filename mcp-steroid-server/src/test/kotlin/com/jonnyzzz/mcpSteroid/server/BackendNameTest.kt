/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackendNameTest {
    @Test
    fun `base62Sha256 is deterministic`() {
        assertEquals(base62Sha256("pid:1234"), base62Sha256("pid:1234"))
    }

    @Test
    fun `base62Sha256 differs for different inputs`() {
        assertNotEquals(base62Sha256("pid:1234"), base62Sha256("pid:1235"))
        assertNotEquals(base62Sha256("pid:1234"), base62Sha256("port:1234"))
    }

    @Test
    fun `base62Sha256 is alphanumeric and at least 8 chars`() {
        val hash = base62Sha256("managed:some-managed-id")
        assertTrue(hash.length >= 8, "expected length>=8 but was ${hash.length}: $hash")
        assertTrue(hash.all { it.isLetterOrDigit() }, "expected alphanumeric but was: $hash")
        // base62 alphabet excludes URL-unsafe '-'/'_'.
        assertTrue(hash.none { it == '-' || it == '_' }, "must not contain '-'/'_': $hash")
    }

    @Test
    fun `take(8) yields a stable short handle`() {
        val full = base62Sha256("pid:42")
        assertEquals(full.take(8), base62Sha256("pid:42").take(8))
        assertEquals(8, full.take(8).length)
    }

    @Test
    fun `base62FixedWidth pads to the requested width`() {
        // A single low byte renders to far fewer than 8 base62 digits; fixed width zero-pads.
        val hash = base62FixedWidth(byteArrayOf(1), 8)
        assertEquals(8, hash.length)
        assertTrue(hash.all { it.isLetterOrDigit() }, hash)
    }
}
