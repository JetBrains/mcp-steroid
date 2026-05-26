/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

class IdeUnpackerSecurityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `tar rejects prefix-bypass path outside unpack dir`() {
        val archive = tmp.newFile("prefix.tar.gz")
        createTarGz(archive) {
            addTarFile("../foo-evil/x")
        }
        val unpackDir = tmp.newFolder("foo")
        val outside = File(tmp.root, "foo-evil/x")

        val ex = expectIllegalArgument { unpackTarGz(archive, unpackDir) }

        assertTrue("expected escape message, got: ${ex.message}", ex.message!!.contains("escapes target directory"))
        assertFalse("archive entry must not be created outside unpack dir", outside.exists())
    }

    @Test
    fun `tar rejects absolute entry path`() {
        val archive = tmp.newFile("absolute.tar.gz")
        createTarGz(archive) {
            addTarFile("/etc/passwd")
        }
        val passwd = Path.of("/etc/passwd")
        val before = fileSnapshot(passwd)

        val ex = expectIllegalArgument { unpackTarGz(archive, tmp.newFolder("out")) }

        assertTrue("expected escape message, got: ${ex.message}", ex.message!!.contains("escapes target directory"))
        assertEquals("absolute path target must not be touched", before, fileSnapshot(passwd))
    }

    @Test
    fun `tar rejects relative path that escapes unpack dir`() {
        val archive = tmp.newFile("relative.tar.gz")
        val entryName = "../../../../tmp/ide-unpacker-${UUID.randomUUID()}/foo"
        createTarGz(archive) {
            addTarFile(entryName)
        }
        val unpackDir = tmp.newFolder("out")
        val outside = File(unpackDir, entryName).canonicalFile
        assertFalse("test precondition: outside file must not exist", outside.exists())

        val ex = expectIllegalArgument { unpackTarGz(archive, unpackDir) }

        assertTrue("expected escape message, got: ${ex.message}", ex.message!!.contains("escapes target directory"))
        assertFalse("archive entry must not be created outside unpack dir", outside.exists())
    }

    @Test
    fun `tar rejects symlink whose location is inside but target escapes`() {
        val archive = tmp.newFile("symlink-target-escape.tar.gz")
        createTarGz(archive) {
            addTarSymlink("legit/path", "../../../../tmp/secret")
        }
        val unpackDir = tmp.newFolder("out")
        val link = File(unpackDir, "legit/path")

        val ex = expectIllegalArgument { unpackTarGz(archive, unpackDir) }

        assertTrue("expected symlink escape message, got: ${ex.message}", ex.message!!.contains("Archive symlink escapes target directory"))
        assertFalse("escaping symlink must not be created", Files.isSymbolicLink(link.toPath()))
    }

    @Test
    fun `tar preserves relative symlink whose target stays inside unpack dir`() {
        val archive = tmp.newFile("symlink-target-ok.tar.gz")
        val linkName = "../jbr/bin/java"
        createTarGz(archive) {
            addTarSymlink("bin/idea", linkName)
        }
        val unpackDir = tmp.newFolder("out")

        unpackTarGz(archive, unpackDir)

        val link = File(unpackDir, "bin/idea").toPath()
        assertTrue("expected extracted symlink at $link", Files.isSymbolicLink(link))
        assertEquals("symlink text must be preserved verbatim", linkName, Files.readSymbolicLink(link).toString())
    }

    @Test
    fun `tar rejects absolute symlink target`() {
        val archive = tmp.newFile("absolute-symlink-target.tar.gz")
        createTarGz(archive) {
            addTarSymlink("a", "/etc/passwd")
        }
        val unpackDir = tmp.newFolder("out")
        val link = File(unpackDir, "a")

        val ex = expectIllegalArgument { unpackTarGz(archive, unpackDir) }

        assertTrue("expected symlink escape message, got: ${ex.message}", ex.message!!.contains("Archive symlink escapes target directory"))
        assertFalse("escaping symlink must not be created", Files.isSymbolicLink(link.toPath()))
    }

    @Test
    fun `tar extracts normal directories files executable bit and relative symlink`() {
        val archive = tmp.newFile("happy.tar.gz")
        val launcher = "launcher"
        createTarGz(archive) {
            addTarDirectory("bin")
            addTarDirectory("jbr/bin")
            addTarFile("README.txt", "hello")
            addTarFile("jbr/bin/java", launcher, mode = 0b111_101_101)
            addTarSymlink("bin/idea", "../jbr/bin/java")
        }
        val unpackDir = tmp.newFolder("out")

        unpackTarGz(archive, unpackDir)

        assertTrue(File(unpackDir, "bin").isDirectory)
        assertEquals("hello", File(unpackDir, "README.txt").readText())
        val java = File(unpackDir, "jbr/bin/java")
        assertEquals(launcher, java.readText())
        assertTrue("executable bit must be preserved on $java", java.canExecute())
        val idea = File(unpackDir, "bin/idea").toPath()
        assertTrue("expected symlink at $idea", Files.isSymbolicLink(idea))
        assertEquals("../jbr/bin/java", Files.readSymbolicLink(idea).toString())
    }

    @Test
    fun `zip rejects prefix-bypass path outside unpack dir`() {
        val archive = tmp.newFile("prefix.zip")
        createZip(archive) {
            addZipFile("../foo-evil/x")
        }
        val unpackDir = tmp.newFolder("foo")
        val outside = File(tmp.root, "foo-evil/x")

        val ex = expectIllegalArgument { unpackZip(archive, unpackDir) }

        assertTrue("expected escape message, got: ${ex.message}", ex.message!!.contains("escapes target directory"))
        assertFalse("archive entry must not be created outside unpack dir", outside.exists())
    }

    @Test
    fun `zip extracts normal directories and files`() {
        val archive = tmp.newFile("happy.zip")
        createZip(archive) {
            addZipDirectory("bin")
            addZipFile("bin/idea.bat", "@echo off")
            addZipFile("README.txt", "hello")
        }
        val unpackDir = tmp.newFolder("out")

        unpackZip(archive, unpackDir)

        assertTrue(File(unpackDir, "bin").isDirectory)
        assertEquals("@echo off", File(unpackDir, "bin/idea.bat").readText())
        assertEquals("hello", File(unpackDir, "README.txt").readText())
    }

    private fun createTarGz(file: File, block: TarArchiveOutputStream.() -> Unit) {
        FileOutputStream(file).use { fileStream ->
            GzipCompressorOutputStream(fileStream).use { gzip ->
                TarArchiveOutputStream(gzip).use { tar ->
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    tar.block()
                }
            }
        }
    }

    private fun TarArchiveOutputStream.addTarDirectory(name: String) {
        val entry = TarArchiveEntry(name.trimEnd('/') + "/")
        putArchiveEntry(entry)
        closeArchiveEntry()
    }

    private fun TarArchiveOutputStream.addTarFile(
        name: String,
        content: String = "",
        mode: Int = 0b110_100_100,
    ) {
        val bytes = content.toByteArray()
        val entry = if (name.startsWith("/")) TarArchiveEntry(name, true) else TarArchiveEntry(name)
        entry.size = bytes.size.toLong()
        entry.mode = mode
        putArchiveEntry(entry)
        write(bytes)
        closeArchiveEntry()
    }

    private fun TarArchiveOutputStream.addTarSymlink(name: String, linkName: String) {
        val entry = TarArchiveEntry(name, TarConstants.LF_SYMLINK)
        entry.linkName = linkName
        entry.size = 0
        putArchiveEntry(entry)
        closeArchiveEntry()
    }

    private fun createZip(file: File, block: ZipArchiveOutputStream.() -> Unit) {
        ZipArchiveOutputStream(file).use { zip ->
            zip.block()
        }
    }

    private fun ZipArchiveOutputStream.addZipDirectory(name: String) {
        val entry = ZipArchiveEntry(name.trimEnd('/') + "/")
        putArchiveEntry(entry)
        closeArchiveEntry()
    }

    private fun ZipArchiveOutputStream.addZipFile(
        name: String,
        content: String = "",
        mode: Int = 0b110_100_100,
    ) {
        val bytes = content.toByteArray()
        val entry = ZipArchiveEntry(name)
        entry.size = bytes.size.toLong()
        entry.unixMode = mode
        putArchiveEntry(entry)
        write(bytes)
        closeArchiveEntry()
    }

    private fun fileSnapshot(path: Path): FileSnapshot {
        if (!Files.exists(path)) return FileSnapshot(missing = true)
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
        return FileSnapshot(
            missing = false,
            fileKey = attributes.fileKey()?.toString(),
            size = attributes.size(),
            lastModifiedMillis = attributes.lastModifiedTime().toMillis(),
        )
    }

    private fun expectIllegalArgument(block: () -> Unit): IllegalArgumentException {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return e
        }
        fail("Expected IllegalArgumentException; none thrown")
        @Suppress("UNREACHABLE_CODE")
        throw AssertionError()
    }

    private data class FileSnapshot(
        val missing: Boolean,
        val fileKey: String? = null,
        val size: Long = -1,
        val lastModifiedMillis: Long = -1,
    )
}
