/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.server.backendNameForMarker
import com.jonnyzzz.mcpSteroid.server.base36FixedWidth
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/** R3.3: one uniform `backend_name` scheme `<productCodeLower>-<hash8>` for every source. */
class BackendIdentityTest {
    @Test
    fun `the same pid yields the same id and different pids differ even with the same product`() {
        val a = backendNameForMarker(pid = 1L, build = "IU-261.1")
        val aAgain = backendNameForMarker(pid = 1L, build = "IU-261.1")
        val b = backendNameForMarker(pid = 2L, build = "IU-261.1")
        assertEquals(a, aAgain)
        assertNotEquals(a, b)
        assertTrue(a.startsWith("iu-"))
    }

    @Test
    fun `missing product code falls back to the ide- prefix`() {
        assertTrue(backendNameForMarker(pid = 7L, build = null).startsWith("ide-"))
        assertTrue(backendNameForMarker(pid = 7L, build = "").startsWith("ide-"))
        // A build with no product-code prefix (port /api/about can return "253.x") also falls back.
        assertTrue(backendNameForPort(port = 63342, build = "253.21581.142").startsWith("ide-"))
    }

    @Test
    fun `backendNameForPort is deterministic and keyed by port`() {
        val a = backendNameForPort(port = 65432, build = "IC-253.1")
        val aAgain = backendNameForPort(port = 65432, build = "IC-253.1")
        val b = backendNameForPort(port = 65433, build = "IC-253.1")
        assertEquals(a, aAgain)
        assertNotEquals(a, b)
        assertTrue(a.startsWith("ic-"))
        // Deterministic formula
        assertEquals("ic-" + base36FixedWidth("port:65432", "IC-253.1").take(8), a)
    }

    @Test
    fun `startableBackendName is deterministic and keyed by normalized ideHome`() {
        fun installed(home: String) = InstalledBackend(
            id = "idea-community-2025.3.0",
            ide = IdeInfo(name = "IntelliJ IDEA Community", version = "2025.3", build = "IC-253.1"),
            ideHome = home,
            launcher = Path.of(home, "bin", "idea.sh"),
        )
        val home = "/opt/idea/2025.3"
        val a = startableBackendName(installed(home))
        val aAgain = startableBackendName(installed(home))
        // Same home → same id
        assertEquals(a, aAgain)
        // Different home → different id
        val b = startableBackendName(installed("/opt/idea/2025.2"))
        assertNotEquals(a, b)
        assertTrue(a.startsWith("ic-"), "startable id should start with ic- but was: $a")
        // Normalised home must produce the same id regardless of trailing slash
        val withSlash = startableBackendName(installed("$home/"))
        // normalizeHome strips trailing slash via toAbsolutePath().normalize()
        assertEquals(a, withSlash,
            "startableBackendName must be stable under path normalisation (trailing slash)")
    }
}
