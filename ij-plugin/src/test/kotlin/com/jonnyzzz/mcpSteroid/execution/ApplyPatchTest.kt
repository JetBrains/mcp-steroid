/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.TestResultBuilder
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

/**
 * Semantics of the `applyPatch { hunk(...) }` DSL exposed on [McpScriptContext].
 *
 * These tests pin the contract that the apply-patch recipe prompt
 * (`prompts/src/main/prompts/ide/apply-patch.md`) documents for agents. If the
 * DSL's behaviour ever drifts — atomicity, descending-offset ordering,
 * pre-flight validation — a failure here catches it before the recipe
 * sends agents off in the wrong direction.
 *
 * **Threading note.** By default `UsefulTestCase.runBare` wraps each test in
 * `EdtTestUtil.runInEdtAndWait`, which makes `timeoutRunBlocking` park the EDT
 * inside `runBlocking`. The apply-patch engine dispatches its write phase with
 * `withContext(Dispatchers.EDT)`, which would then be unable to run because
 * EDT is parked — a classic deadlock. We opt out by overriding
 * [runInDispatchThread] to `false`, so each test runs on the JUnit worker
 * thread; `timeoutRunBlocking` on that thread leaves EDT free to pump the
 * `Dispatchers.EDT` queue for the write action.
 */
class ApplyPatchTest : BasePlatformTestCase() {

    // See the class KDoc above — the apply-patch engine requires a live EDT
    // dispatcher during its `withContext(Dispatchers.EDT)` phase; running the
    // test itself on EDT would deadlock `timeoutRunBlocking` against that.
    override fun runInDispatchThread(): Boolean = false

    // Production apply-patch resolves files via `McpScriptContext.findFile`,
    // which goes through `LocalFileSystem`. The test uses a real on-disk temp
    // directory so the test exercises the same VFS path — creating files on
    // `temp://` via `myFixture.tempDirFixture` would bypass LocalFileSystem and
    // miss regressions in the production resolver.
    private lateinit var tempRoot: Path

    override fun setUp() {
        super.setUp()
        tempRoot = createTempDirectory("apply-patch-test-")
    }

    override fun tearDown() {
        try {
            Files.walk(tempRoot).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        } finally {
            super.tearDown()
        }
    }

    private fun createContext(): McpScriptContextImpl {
        val executionId = ExecutionId("test-apply-patch")
        val disposable = Disposer.newDisposable(testRootDisposable, "apply-patch-$executionId")
        val modalityMonitor = ModalityStateMonitor(project, executionId, disposable)
        return McpScriptContextImpl(
            project = project,
            params = buildJsonObject { },
            executionId = executionId,
            disposable = disposable,
            resultBuilder = TestResultBuilder(),
            modalityMonitor = modalityMonitor,
        )
    }

    private fun writeTempFile(name: String, content: String): Path {
        val path = tempRoot / name
        path.parent.createDirectories()
        path.writeText(content)
        // Refresh VFS so LocalFileSystem.findFileByPath picks up the new file.
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
            ?: error("VFS refresh did not surface $path")
        return path
    }

    private suspend fun Path.readViaIde(): String {
        val vf = LocalFileSystem.getInstance().findFileByNioFile(this)
            ?: error("VFS cannot resolve $this")
        return String(vf.contentsToByteArray(), vf.charset)
    }

    fun testSingleHunkSingleFile(): Unit = timeoutRunBlocking(30.seconds) {
        val ctx = createContext()
        val vf = writeTempFile("A.java", "class A { int x = 1; }\n")

        val result = ctx.applyPatch {
            hunk(vf.toString(), "int x = 1", "int x = 42")
        }

        assertEquals("class A { int x = 42; }\n", vf.readViaIde())
        assertEquals(1, result.hunkCount)
        assertEquals(1, result.fileCount)
        val h = result.applied.single()
        assertEquals(1, h.line)
        // `int x = 1` starts at col 11 (1-based) in `class A { int x = 1; }`
        assertEquals(11, h.column)
        assertEquals(9, h.oldLen)  // "int x = 1".length
        assertEquals(10, h.newLen) // "int x = 42".length
    }

    fun testSingleHunkPersistsToDiskBeforeReturning(): Unit = timeoutRunBlocking(30.seconds) {
        val ctx = createContext()
        val vf = writeTempFile("Persist.java", "class Persist { int x = 1; }\n")

        ctx.applyPatch {
            hunk(vf.toString(), "int x = 1", "int x = 42")
        }

        assertEquals("class Persist { int x = 42; }\n", Files.readString(vf))
    }

    fun testMultiHunkSameFileDescendingOrder(): Unit = timeoutRunBlocking(30.seconds) {
        // Multi-hunk in the same file. If the DSL applied in file order (top-down),
        // the first replacement would shift the second's offset and the second hunk
        // would miss its target. Descending offset order is the only correct policy.
        val ctx = createContext()
        val content = """
            class A {
                int x = 1;
                int y = 2;
                int z = 3;
            }
        """.trimIndent()
        val vf = writeTempFile("B.java", content)

        val result = ctx.applyPatch {
            hunk(vf.toString(), "int x = 1", "int x = 100")
            hunk(vf.toString(), "int y = 2", "int y = 200")
            hunk(vf.toString(), "int z = 3", "int z = 300")
        }

        val updated = vf.readViaIde()
        assertTrue("x replaced: $updated", updated.contains("int x = 100"))
        assertTrue("y replaced: $updated", updated.contains("int y = 200"))
        assertTrue("z replaced: $updated", updated.contains("int z = 300"))
        assertEquals(3, result.hunkCount)
        assertEquals(1, result.fileCount)
        // Line numbers captured pre-edit: x at line 2, y at line 3, z at line 4.
        // Result preserves insertion order (0,1,2) even though apply order was reversed.
        assertEquals(listOf(2, 3, 4), result.applied.map { it.line })
    }

    fun testMultipleFiles(): Unit = timeoutRunBlocking(30.seconds) {
        val ctx = createContext()
        val a = writeTempFile("Multi_A.java", "int count = 1;\n")
        val b = writeTempFile("Multi_B.java", "int count = 2;\n")

        val result = ctx.applyPatch {
            hunk(a.toString(), "count = 1", "count = 100")
            hunk(b.toString(), "count = 2", "count = 200")
        }

        assertEquals("int count = 100;\n", a.readViaIde())
        assertEquals("int count = 200;\n", b.readViaIde())
        assertEquals(2, result.hunkCount)
        assertEquals(2, result.fileCount)
    }

    fun testReadOnlyFileFailsBeforeEditingDisk(): Unit = timeoutRunBlocking(30.seconds) {
        val ctx = createContext()
        val vf = writeTempFile("ReadOnly.java", "class ReadOnly { int value = 1; }\n")
        val file = vf.toFile()
        assertTrue("Test setup should make the file read-only", file.setWritable(false))
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(vf)

        try {
            val err = try {
                ctx.applyPatch {
                    hunk(vf.toString(), "value = 1", "value = 100")
                }
                null
            } catch (e: ApplyPatchException) {
                e
            }

            assertNotNull("Expected ApplyPatchException for read-only file", err)
            assertTrue(
                "Error explains read-only or save failure: ${err!!.message}",
                err.message!!.contains("file is read-only") || err.message!!.contains("Failed to save"),
            )
            assertEquals("class ReadOnly { int value = 1; }\n", Files.readString(vf))
        } finally {
            file.setWritable(true)
        }
    }

    fun testOldStringMissingFailsCleanlyNoPartialEdit(): Unit = timeoutRunBlocking(30.seconds) {
        val ctx = createContext()
        val a = writeTempFile("Fail_A.java", "content_A\n")
        val b = writeTempFile("Fail_B.java", "content_B\n")

        val err = try {
            ctx.applyPatch {
                hunk(a.toString(), "content_A", "REPLACED_A")
                hunk(b.toString(), "THIS_DOES_NOT_EXIST", "REPLACED_B")
            }
            null
        } catch (e: ApplyPatchException) {
            e
        }

        assertNotNull("Expected ApplyPatchException", err)
        assertTrue("Error names the missing hunk index", err!!.message!!.contains("Hunk #1"))
        assertTrue("Error names the path", err.message!!.contains("Fail_B.java"))
        // Crucial: neither file was modified, because pre-flight ran before any edit.
        assertEquals("content_A\n", a.readViaIde())
        assertEquals("content_B\n", b.readViaIde())
    }

    fun testNonUniqueOldStringFailsCleanly(): Unit = timeoutRunBlocking(30.seconds) {
        val ctx = createContext()
        val vf = writeTempFile("Dup.java", "token\nother\ntoken\n")

        val err = try {
            ctx.applyPatch {
                hunk(vf.toString(), "token", "WINNER")
            }
            null
        } catch (e: ApplyPatchException) {
            e
        }

        assertNotNull("Expected ApplyPatchException on non-unique old_string", err)
        assertTrue("Error explains non-unique", err!!.message!!.contains("occurs more than once"))
        assertTrue("Error suggests context expansion", err.message!!.contains("expand old_string"))
        assertEquals("token\nother\ntoken\n", vf.readViaIde())
    }

    fun testZeroHunksThrows(): Unit = timeoutRunBlocking(30.seconds) {
        val ctx = createContext()
        val err = try {
            ctx.applyPatch { }
            null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertNotNull("Zero hunks should throw IllegalArgumentException", err)
        assertTrue(err!!.message!!.contains("zero hunks"))
    }

    fun testResultToStringContainsAuditLines(): Unit = timeoutRunBlocking(30.seconds) {
        val ctx = createContext()
        val vf = writeTempFile("Audit.java", "AAA\nBBB\n")

        val result = ctx.applyPatch {
            hunk(vf.toString(), "AAA", "aaa-aaa")
        }
        val summary = result.toString()
        assertTrue("Summary mentions hunk count: $summary", summary.contains("1 hunk"))
        assertTrue("Summary mentions file count: $summary", summary.contains("1 file"))
        assertTrue("Summary lists the hunk path:line:col: $summary",
            summary.contains(vf.toString() + ":1:1"))
        assertTrue("Summary lists the char delta: $summary", summary.contains("3→7 chars"))
    }
}
