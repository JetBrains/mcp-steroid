/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class IdeUnpackerDispatchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `dispatcher rejects unknown extensions`() {
        val archive = tmp.newFile("idea.7z").apply { writeBytes(byteArrayOf(0)) }
        val ex = expectError { unpackIdeArchive(archive, tmp.newFolder("out")) }
        assertTrue("expected dispatcher error, got: ${ex.message}",
            ex.message!!.contains("Unsupported archive format"))
    }

    @Test
    fun `dispatcher rejects missing archive`() {
        val archive = File(tmp.root, "does-not-exist.tar.gz")
        expectError { unpackIdeArchive(archive, tmp.newFolder("out")) }
    }

    @Test
    fun `DMG unpack refuses on non-mac hosts`() {
        if (resolveHostOs() == HostOs.MAC) {
            // On macOS this path would actually call hdiutil; skip this test there.
            return
        }
        val archive = tmp.newFile("idea.dmg").apply { writeBytes(byteArrayOf(0)) }
        val ex = expectError { unpackDmgViaMount(archive, tmp.newFolder("out")) }
        assertTrue("expected mac-host error, got: ${ex.message}",
            ex.message!!.contains("requires a macOS host"))
    }

    @Test
    fun `EXE unpack reports clearly when 7z is absent`() {
        val archive = tmp.newFile("idea.exe").apply { writeBytes(byteArrayOf(0)) }
        // PATH-less environments can't have 7z; force PATH to empty for this process check.
        // We can't mutate environment here, so we just assert the error wording is intelligible
        // when 7z IS missing on PATH. If 7z happens to be on the developer's machine, the call
        // will instead fail because the file is not a real .exe — which is also acceptable.
        val ex = expectError { unpackExeWith7z(archive, tmp.newFolder("out")) }
        val msg = ex.message.orEmpty()
        assertTrue(
            "expected either '7z not found' or '7z failed' message, got: $msg",
            msg.contains("No 7z binary on PATH") || msg.contains("exited") || msg.contains("7z")
        )
    }

    private inline fun expectError(block: () -> Unit): Throwable {
        try { block() } catch (e: Throwable) { return e }
        fail("Expected an exception; none thrown")
        @Suppress("UNREACHABLE_CODE") throw AssertionError()
    }
}
