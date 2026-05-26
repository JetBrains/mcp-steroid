/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Network-free coverage of [LocalIdeProvisioner]'s pure helpers. End-to-end
 * download/unpack lives behind [LiveNetwork] and is not part of the default
 * `:intellij-downloader:test` run.
 */
class LocalIdeProvisionerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // --- inferChannel ---

    @Test fun `inferChannel detects EAP snapshot tags`() {
        assertEquals(IdeChannel.EAP, inferChannel("262-EAP-SNAPSHOT"))
        assertEquals(IdeChannel.EAP, inferChannel("261-EAP-SNAPSHOT"))
    }

    @Test fun `inferChannel detects bare per-major snapshots`() {
        assertEquals(IdeChannel.EAP, inferChannel("262-SNAPSHOT"))
    }

    @Test fun `inferChannel detects EAP keyword anywhere in tag`() {
        assertEquals(IdeChannel.EAP, inferChannel("2026.2-EAP1"))
    }

    @Test fun `inferChannel treats stable version strings as stable`() {
        assertEquals(IdeChannel.STABLE, inferChannel("2026.1"))
        assertEquals(IdeChannel.STABLE, inferChannel("2026.1.2"))
        assertEquals(IdeChannel.STABLE, inferChannel("262.6228.19"))
    }

    // --- ideRootFolderName ---

    @Test fun `folder name combines build + os + arch`() {
        assertEquals(
            "IU-261.24374.151-mac-aarch64",
            ideRootFolderName("261.24374.151", HostOs.MAC, HostArchitecture.ARM64),
        )
        assertEquals(
            "IU-261.24374.151-linux-x86_64",
            ideRootFolderName("261.24374.151", HostOs.LINUX, HostArchitecture.X86_64),
        )
        assertEquals(
            "IU-262.6228.19-windows-aarch64",
            ideRootFolderName("262.6228.19", HostOs.WINDOWS, HostArchitecture.ARM64),
        )
    }

    @Test fun `folder name is stable across multiple invocations`() {
        val first = ideRootFolderName("261.24374.151", HostOs.LINUX, HostArchitecture.X86_64)
        val second = ideRootFolderName("261.24374.151", HostOs.LINUX, HostArchitecture.X86_64)
        assertEquals(first, second)
    }

    // --- findIdeRoot ---

    @Test fun `findIdeRoot detects direct Linux-style layout`() {
        // unpackDir/product-info.json
        File(tmp.root, "product-info.json").writeText("{}")
        assertEquals(tmp.root.canonicalFile, findIdeRoot(tmp.root).canonicalFile)
    }

    @Test fun `findIdeRoot descends into Linux idea-IU directory`() {
        // unpackDir/idea-IU-261.x.y/product-info.json
        val inner = File(tmp.root, "idea-IU-261.24374.151").also { it.mkdirs() }
        File(inner, "product-info.json").writeText("{}")
        assertEquals(inner.canonicalFile, findIdeRoot(tmp.root).canonicalFile)
    }

    @Test fun `findIdeRoot descends into macOS app Contents`() {
        // unpackDir/<X>.app/Contents/Resources/product-info.json
        val app = File(tmp.root, "IntelliJ IDEA.app").also { it.mkdirs() }
        val contents = File(app, "Contents").also { it.mkdirs() }
        File(contents, "Resources").mkdirs()
        File(contents, "Resources/product-info.json").writeText("{}")
        assertEquals(contents.canonicalFile, findIdeRoot(tmp.root).canonicalFile)
    }

    @Test fun `findIdeRoot fails clearly when no product-info found`() {
        // Empty dir — no IDE files.
        try {
            findIdeRoot(tmp.root)
            fail("Should have raised on missing product-info.json")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Error message must mention product-info.json. Got: ${e.message}",
                e.message!!.contains("product-info.json"),
            )
        }
    }

    @Test fun `findIdeRoot requires existing directory`() {
        val missing = File(tmp.root, "missing")
        try {
            findIdeRoot(missing)
            fail("Should have raised on missing directory")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
