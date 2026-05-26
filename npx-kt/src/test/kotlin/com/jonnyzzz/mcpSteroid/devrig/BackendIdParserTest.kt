/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BackendIdParserTest {

    @Test
    fun `accepts bare product key without version`() {
        val id = parseBackendId("idea-community")

        assertEquals(IdeProduct.IntelliJIdeaCommunity, id.product)
        assertEquals(null, id.version)
    }

    @Test
    fun `accepts colon separated version`() {
        val id = parseBackendId("pycharm-community:2025.3.4")

        assertEquals(IdeProduct.PyCharmCommunity, id.product)
        assertEquals("2025.3.4", id.version)
    }

    @Test
    fun `accepts dash separated version even when product key contains dashes`() {
        val id = parseBackendId("android-studio-2025.3.4.7")

        assertEquals(IdeProduct.AndroidStudio, id.product)
        assertEquals("2025.3.4.7", id.version)
    }

    @Test
    fun `accepts all managed product keys`() {
        val products = listOf(
            "idea-community" to IdeProduct.IntelliJIdeaCommunity,
            "pycharm-community" to IdeProduct.PyCharmCommunity,
            "android-studio" to IdeProduct.AndroidStudio,
            "idea-ultimate" to IdeProduct.IntelliJIdea,
            "pycharm-pro" to IdeProduct.PyCharm,
            "goland" to IdeProduct.GoLand,
            "webstorm" to IdeProduct.WebStorm,
            "rider" to IdeProduct.Rider,
            "clion" to IdeProduct.CLion,
        )

        for ((raw, product) in products) {
            assertEquals(product, parseBackendId(raw).product, raw)
            assertEquals(product, parseBackendId("$raw:1.2.3").product, raw)
            assertEquals("1.2.3", parseBackendId("$raw-1.2.3").version, raw)
        }
    }

    @Test
    fun `version override replaces positional version`() {
        val id = parseBackendId("idea-community-2025.1").withVersionOverride("2025.3.3")

        assertEquals("2025.3.3", id.version)
    }

    @Test
    fun `version comparator handles dotted update numbers numerically`() {
        assertEquals(1, compareBackendVersions("2025.2.6.2", "2025.2.6.1").coerceIn(-1, 1))
        assertEquals(1, compareBackendVersions("2025.2.6.10", "2025.2.6.2").coerceIn(-1, 1))
        assertEquals(-1, compareBackendVersions("2025.2.6.2", "2025.2.6.10").coerceIn(-1, 1))
        assertEquals(0, compareBackendVersions("2025.2.6.02", "2025.2.6.2"))
    }

    @Test
    fun `rejects unknown product keys and aliases`() {
        assertFailsWith<IllegalArgumentException> { parseBackendId("intellij") }
        assertFailsWith<IllegalArgumentException> { parseBackendId("unknown") }
        assertFailsWith<IllegalArgumentException> { parseBackendId("IDEA-COMMUNITY") }
    }

    @Test
    fun `rejects malformed versions`() {
        assertFailsWith<IllegalArgumentException> { parseBackendId("idea-community:") }
        assertFailsWith<IllegalArgumentException> { parseBackendId("idea-community-") }
        assertFailsWith<IllegalArgumentException> { parseBackendId("idea-community:2025/3") }
        assertFailsWith<IllegalArgumentException> { parseBackendId("idea-community:1:2") }
    }
}
