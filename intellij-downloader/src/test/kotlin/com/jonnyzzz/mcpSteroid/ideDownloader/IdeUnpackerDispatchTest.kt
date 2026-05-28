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
    fun `EXE unpack refuses on non-windows hosts`() {
        if (resolveHostOs() == HostOs.WINDOWS) {
            // On Windows this path would actually call the bundled 7z.exe; skip this test there.
            return
        }
        val archive = tmp.newFile("idea.exe").apply { writeBytes(byteArrayOf(0)) }
        val fakeSevenZip = tmp.newFile("7z.exe").apply { writeBytes(byteArrayOf(0)) }.toPath()
        val ex = expectError { unpackExeWith7z(archive, tmp.newFolder("out"), fakeSevenZip) }
        assertTrue("expected windows-host error, got: ${ex.message}",
            ex.message!!.contains("requires a Windows host"))
    }

    @Test
    fun `dispatcher requires sevenZipBinary or SevenZipLocator fallback for exe archives`() {
        // On non-Windows hosts SevenZipLocator returns null (the bundled
        // 7z.exe is Windows-only), so this `.exe` dispatch path errors out
        // with a clear "cannot resolve a 7z.exe" message. The require(WINDOWS)
        // assertion in unpackExeWith7z is hit BEFORE this on Windows hosts
        // because the dispatcher walks past the host check first.
        if (resolveHostOs() == HostOs.WINDOWS) return
        val archive = tmp.newFile("idea.exe").apply { writeBytes(byteArrayOf(0)) }
        val ex = expectError { unpackIdeArchive(archive, tmp.newFolder("out")) }
        assertTrue("expected missing-binary error, got: ${ex.message}",
            ex.message!!.contains("cannot resolve a 7z.exe"))
    }

    private inline fun expectError(block: () -> Unit): Throwable {
        try { block() } catch (e: Throwable) { return e }
        fail("Expected an exception; none thrown")
        @Suppress("UNREACHABLE_CODE") throw AssertionError()
    }
}
