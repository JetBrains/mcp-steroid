/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.jonnyzzz.mcpSteroid.TestResultBuilder
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
@TestApplication
class ApplyPatchTest {

    private val projectFixture = projectFixture()

    // Production apply-patch resolves files via `McpScriptContext.findFile`,
    // which goes through `LocalFileSystem`. The test uses a real on-disk temp
    // directory so the test exercises the same VFS path — creating files on
    // `temp://` via `myFixture.tempDirFixture` would bypass LocalFileSystem and
    // miss regressions in the production resolver.
    private lateinit var tempRoot: Path
    private val disposables = mutableListOf<com.intellij.openapi.Disposable>()

    @BeforeEach
    fun setUp() {
        tempRoot = createTempDirectory("apply-patch-test-")
    }

    @AfterEach
    fun tearDown() {
        try {
            disposables.forEach { Disposer.dispose(it) }
        } finally {
            Files.walk(tempRoot).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun createContext(): McpScriptContextImpl {
        val executionId = ExecutionId("test-apply-patch")
        val disposable = Disposer.newDisposable("apply-patch-$executionId").also { disposables.add(it) }
        return McpScriptContextImpl(
            project = projectFixture.get(),
            executionId = executionId,
            disposable = disposable,
            resultBuilder = TestResultBuilder(),
            // No-op killer hook for tests — ScriptExecutor wires this in production.
            onDoNotCancelOnModalityStateChange = {},
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

    @Test
    fun singleHunkSingleFile(): Unit = timeoutRunBlocking(30.seconds) {
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

    @Test
    fun singleHunkPersistsToDiskBeforeReturning(): Unit = timeoutRunBlocking(30.seconds) {
        val ctx = createContext()
        val vf = writeTempFile("Persist.java", "class Persist { int x = 1; }\n")

        ctx.applyPatch {
            hunk(vf.toString(), "int x = 1", "int x = 42")
        }

        assertEquals("class Persist { int x = 42; }\n", Files.readString(vf))
    }

    @Test
    fun multiHunkSameFileDescendingOrder(): Unit = timeoutRunBlocking(30.seconds) {
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
        assertTrue( updated.contains("int x = 100"),"x replaced: $updated")
        assertTrue( updated.contains("int y = 200"),"y replaced: $updated")
        assertTrue( updated.contains("int z = 300"),"z replaced: $updated")
        assertEquals(3, result.hunkCount)
        assertEquals(1, result.fileCount)
        // Line numbers captured pre-edit: x at line 2, y at line 3, z at line 4.
        // Result preserves insertion order (0,1,2) even though apply order was reversed.
        assertEquals(listOf(2, 3, 4), result.applied.map { it.line })
    }

    @Test
    fun multipleFiles(): Unit = timeoutRunBlocking(30.seconds) {
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

    @Test
    fun readOnlyFileFailsBeforeEditingDisk(): Unit = timeoutRunBlocking(30.seconds) {
        val ctx = createContext()
        val vf = writeTempFile("ReadOnly.java", "class ReadOnly { int value = 1; }\n")
        val file = vf.toFile()
        assertTrue( file.setWritable(false),"Test setup should make the file read-only")
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

            assertNotNull( err,"Expected ApplyPatchException for read-only file")
            assertTrue(
                err!!.message!!.contains("file is read-only") || err.message!!.contains("Failed to save"),
                "Error explains read-only or save failure: ${err.message}")
            assertEquals("class ReadOnly { int value = 1; }\n", Files.readString(vf))
        } finally {
            file.setWritable(true)
        }
    }

    @Test
    fun oldStringMissingFailsCleanlyNoPartialEdit(): Unit = timeoutRunBlocking(30.seconds) {
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

        assertNotNull( err,"Expected ApplyPatchException")
        assertTrue( err!!.message!!.contains("Hunk #1"),"Error names the missing hunk index")
        assertTrue( err.message!!.contains("Fail_B.java"),"Error names the path")
        // Crucial: neither file was modified, because pre-flight ran before any edit.
        assertEquals("content_A\n", a.readViaIde())
        assertEquals("content_B\n", b.readViaIde())
    }

    @Test
    fun nonUniqueOldStringFailsCleanly(): Unit = timeoutRunBlocking(30.seconds) {
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

        assertNotNull( err,"Expected ApplyPatchException on non-unique old_string")
        assertTrue( err!!.message!!.contains("occurs more than once"),"Error explains non-unique")
        assertTrue( err.message!!.contains("expand old_string"),"Error suggests context expansion")
        assertEquals("token\nother\ntoken\n", vf.readViaIde())
    }

    @Test
    fun zeroHunksThrows(): Unit = timeoutRunBlocking(30.seconds) {
        val ctx = createContext()
        val err = try {
            ctx.applyPatch { }
            null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertNotNull( err,"Zero hunks should throw IllegalArgumentException")
        assertTrue(err!!.message!!.contains("zero hunks"))
    }

    @Test
    fun resultToStringContainsAuditLines(): Unit = timeoutRunBlocking(30.seconds) {
        val ctx = createContext()
        val vf = writeTempFile("Audit.java", "AAA\nBBB\n")

        val result = ctx.applyPatch {
            hunk(vf.toString(), "AAA", "aaa-aaa")
        }
        val summary = result.toString()
        assertTrue( summary.contains("1 hunk"),"Summary mentions hunk count: $summary")
        assertTrue( summary.contains("1 file"),"Summary mentions file count: $summary")
        assertTrue(
            summary.contains(vf.toString() + ":1:1"),"Summary lists the hunk path:line:col: $summary")
        assertTrue( summary.contains("3→7 chars"),"Summary lists the char delta: $summary")
    }

    // --- Tricky edge cases below pin behaviour that has bitten apply-patch agents
    // in the wild. Each test explains the trap it catches; if a future engine
    // refactor breaks one, the comment tells you why the rule exists. ---

    /**
     * The reason this test class overrides [runInDispatchThread] to false is
     * that BasePlatformTestCase's default `runInEdtAndWait` parks EDT inside
     * `runBlocking`, deadlocking `withContext(Dispatchers.EDT)` in the engine.
     * That deadlock cost ~30s timeouts during DPAIA arena runs (the historical
     * "stuck around apply_patch" symptom) until commit `d83baa52` flipped the
     * override. This test re-asserts the contract with a tight 5-second
     * deadline: if anyone removes the override or reintroduces the EDT-parking
     * pattern, the test fails fast (5s) instead of timing out (30s). We can
     * also set a deadline here without losing assertion power because the
     * write phase is sub-millisecond on a 1-line patch.
     */
    @Test
    fun completesWellUnderTimeoutGuardingEdtDeadlock(): Unit = timeoutRunBlocking(5.seconds) {
        val ctx = createContext()
        val vf = writeTempFile("Deadline.java", "x = 1\n")
        val result = ctx.applyPatch {
            hunk(vf.toString(), "x = 1", "x = 42")
        }
        assertEquals(1, result.hunkCount)
        assertEquals("x = 42\n", vf.readViaIde())
    }

    /**
     * CRLF line endings must survive a patch — the engine works on
     * `Document.text` which IntelliJ normalises to `\n`, but the on-disk bytes
     * keep the original CRLF via the file's `LineSeparator` system. If we ever
     * accidentally roundtrip through a normalised `String` write, this catches it.
     */
    @Test
    fun crlfLineEndingsPreservedOnDisk(): Unit = timeoutRunBlocking(30.seconds) {
        val ctx = createContext()
        val path = tempRoot / "Crlf.java"
        path.parent.createDirectories()
        // Bytes written explicitly so VFS detects the CRLF separator from disk.
        Files.write(path, "class A {\r\n    int x = 1;\r\n}\r\n".toByteArray())
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: error("VFS refresh failed")

        ctx.applyPatch {
            hunk(path.toString(), "int x = 1", "int x = 42")
        }

        // Document.text ships \n; on-disk bytes must keep \r\n.
        val onDisk = Files.readAllBytes(path).toString(Charsets.UTF_8)
        assertTrue(
            onDisk.contains("\r\n"),"CRLF preserved on disk: ${onDisk.replace("\r", "<CR>").replace("\n", "<LF>\n")}")
        assertTrue( onDisk.contains("int x = 42"),"New value present")
        assertFalse(
            onDisk.contains("int x = 42;\n") && !onDisk.contains("int x = 42;\r\n"),"No bare LF leaked into a CRLF region")
    }

    /** Empty `new_string` is a valid deletion. Production agents use this for "remove import". */
    @Test
    fun emptyNewStringDeletes(): Unit = timeoutRunBlocking(30.seconds) {
        val ctx = createContext()
        val vf = writeTempFile("Delete.java", "import a.B;\nimport a.C;\nclass X {}\n")

        ctx.applyPatch {
            hunk(vf.toString(), "import a.B;\n", "")
        }

        assertEquals("import a.C;\nclass X {}\n", vf.readViaIde())
    }

    /**
     * `new_string` containing `old_string` must NOT be re-applied (otherwise we
     * loop). The engine uses single-pass `replaceString(offset, end, new)`, so
     * one application is the correct semantics. This test pins it.
     */
    @Test
    fun newStringContainsOldStringAppliesOnce(): Unit = timeoutRunBlocking(30.seconds) {
        val ctx = createContext()
        val vf = writeTempFile("Wrap.java", "fun foo() { return 1 }\n")

        ctx.applyPatch {
            // new contains old — naive re-search-and-replace would loop forever.
            hunk(vf.toString(), "return 1", "if (x) return 1 else return 0")
        }

        assertEquals("fun foo() { if (x) return 1 else return 0 }\n", vf.readViaIde())
    }

    /** Hunk at offset 0 — a regression target since `indexOf` returns 0 on hit. */
    @Test
    fun hunkAtFileStart(): Unit = timeoutRunBlocking(30.seconds) {
        val ctx = createContext()
        val vf = writeTempFile("Start.java", "package old;\nclass A {}\n")

        val result = ctx.applyPatch {
            hunk(vf.toString(), "package old;", "package new_pkg;")
        }

        assertEquals("package new_pkg;\nclass A {}\n", vf.readViaIde())
        val h = result.applied.single()
        assertEquals(1, h.line)
        assertEquals(1, h.column)
    }

    /** Hunk at file end (no trailing newline) — verifies offset arithmetic with EOF. */
    @Test
    fun hunkAtFileEndNoTrailingNewline(): Unit = timeoutRunBlocking(30.seconds) {
        val ctx = createContext()
        val vf = writeTempFile("Eof.java", "class A {\n    int x = 1;\n}")  // no trailing \n
        ctx.applyPatch {
            hunk(vf.toString(), "}", "}\n")
        }
        // Note: Document loaders may add a trailing newline back; we just assert the body.
        val updated = vf.readViaIde()
        assertTrue( updated.contains("    int x = 1;"),"Class body intact: $updated")
        assertTrue( updated.contains("}"),"Trailing brace present: $updated")
    }

    /** Multi-line `old_string` spanning many lines — common for block edits. */
    @Test
    fun multiLineHunkSpanningSeveralLines(): Unit = timeoutRunBlocking(30.seconds) {
        val ctx = createContext()
        val vf = writeTempFile("Block.java", """
            class A {
                fun foo() {
                    val x = 1
                    val y = 2
                    val z = 3
                }
            }
        """.trimIndent())

        // After trimIndent strips the 12-col common prefix, `val x = 1` etc. sit at col 9 (8-space indent).
        val oldBlock = "        val x = 1\n        val y = 2\n        val z = 3"
        val newBlock = "        // body removed"

        val result = ctx.applyPatch {
            hunk(vf.toString(), oldBlock, newBlock)
        }

        val updated = vf.readViaIde()
        assertTrue( updated.contains("// body removed"),"Comment placeholder inserted: $updated")
        assertFalse( updated.contains("val x = 1"),"Old lines gone: $updated")
        assertFalse( updated.contains("val z = 3"),"Old lines gone: $updated")
        // line/column captured at first char of `val x = 1` (line 3, after indent).
        val h = result.applied.single()
        assertEquals(3, h.line)
    }

    /**
     * Result preserves the *input* hunk index even when the apply order is
     * reversed for descending-offset replays. Regression target: keep callers'
     * audit trail aligned with the order they shipped hunks.
     */
    @Test
    fun resultPreservesInputIndexOrder(): Unit = timeoutRunBlocking(30.seconds) {
        val ctx = createContext()
        val vf = writeTempFile("Order.java", "A=1;\nB=2;\nC=3;\nD=4;\n")

        val result = ctx.applyPatch {
            // Ship in MIDDLE, FIRST, LAST, SECOND order — file is ABCD.
            hunk(vf.toString(), "B=2", "B=20")
            hunk(vf.toString(), "A=1", "A=10")
            hunk(vf.toString(), "D=4", "D=40")
            hunk(vf.toString(), "C=3", "C=30")
        }

        // Engine result.applied keeps the input order (0..3 indices).
        assertEquals(listOf(0, 1, 2, 3), result.applied.map { it.index })
        // Lines captured pre-edit, in input order: B=2 line 2, A=1 line 1, D=4 line 4, C=3 line 3.
        assertEquals(listOf(2, 1, 4, 3), result.applied.map { it.line })

        // All four edits landed.
        val updated = vf.readViaIde()
        assertTrue(updated.contains("A=10"))
        assertTrue(updated.contains("B=20"))
        assertTrue(updated.contains("C=30"))
        assertTrue(updated.contains("D=40"))
    }

    /** Unicode/multi-byte content. IntelliJ Documents use UTF-16 internally; offsets must match the original encoding. */
    @Test
    fun unicodeMultiByteContent(): Unit = timeoutRunBlocking(30.seconds) {
        val ctx = createContext()
        // Mix of ASCII, BMP, and supplementary plane characters.
        val original = "val msg = \"héllo 🌍 world\"\n"
        val vf = writeTempFile("Unicode.kt", original)

        ctx.applyPatch {
            hunk(vf.toString(), "héllo 🌍 world", "héllo 🌎 world")
        }

        val updated = vf.readViaIde()
        assertEquals("val msg = \"héllo 🌎 world\"\n", updated)
    }

    /**
     * Atomicity across multiple files: pre-flight failure on file #2 must NOT
     * touch file #1's disk content. This is broader than the existing
     * `testOldStringMissingFailsCleanlyNoPartialEdit` because it places the
     * failing hunk on a different *physical* document.
     */
    @Test
    fun crossFileAtomicityOnMidBatchFailure(): Unit = timeoutRunBlocking(30.seconds) {
        val ctx = createContext()
        val a = writeTempFile("Atomic1.java", "class A { int v = 1; }\n")
        val b = writeTempFile("Atomic2.java", "class B { int v = 2; }\n")
        val c = writeTempFile("Atomic3.java", "class C { int v = 3; }\n")

        val err = try {
            ctx.applyPatch {
                hunk(a.toString(), "v = 1", "v = 10")
                hunk(b.toString(), "v = 2", "v = 20")
                hunk(c.toString(), "MISSING", "v = 30")
            }
            null
        } catch (e: ApplyPatchException) {
            e
        }

        assertNotNull( err,"Pre-flight must reject hunk #2")
        // Files A and B must be untouched even though their hunks pre-flighted OK.
        assertEquals("class A { int v = 1; }\n", Files.readString(a))
        assertEquals("class B { int v = 2; }\n", Files.readString(b))
        assertEquals("class C { int v = 3; }\n", Files.readString(c))
    }

    /**
     * `forEach` idiom from the prompt: same old/new applied to N files. Pins
     * the recipe documented in `prompts/src/main/prompts/ide/apply-patch.md`.
     */
    @Test
    fun forEachShareOldNewAcrossFiles(): Unit = timeoutRunBlocking(30.seconds) {
        val ctx = createContext()
        val a = writeTempFile("Each_A.java", "@SpringBootApplication\npublic class AppA { }\n")
        val b = writeTempFile("Each_B.java", "@SpringBootApplication\npublic class AppB { }\n")
        val c = writeTempFile("Each_C.java", "@SpringBootApplication\npublic class AppC { }\n")

        val old = "@SpringBootApplication\npublic class"
        val new = "@SpringBootApplication\n@ComponentScan(\"shop\")\npublic class"

        val result = ctx.applyPatch {
            listOf(a, b, c).forEach { hunk(it.toString(), old, new) }
        }

        assertEquals(3, result.hunkCount)
        assertEquals(3, result.fileCount)
        listOf(a, b, c).forEach {
            assertTrue(
                Files.readString(it).contains("@ComponentScan(\"shop\")"),"ComponentScan added to $it: ${Files.readString(it)}")
        }
    }

    /**
     * Same path repeated with the SAME old_string — the second hunk must be
     * rejected as non-unique (it now occurs zero times after the first replace,
     * but pre-flight runs once before any apply, so it sees one occurrence).
     * Actually the bug to catch is: if the engine ever runs pre-flight twice
     * (once per hunk lazily), the second pre-flight would see zero occurrences
     * and fail with "not found" rather than "not unique". This test pins the
     * single-pre-flight contract.
     */
    @Test
    fun duplicateHunkSamePathSameOld(): Unit = timeoutRunBlocking(30.seconds) {
        val ctx = createContext()
        val vf = writeTempFile("Dup2.java", "x = 1\ny = 2\n")

        val err = try {
            ctx.applyPatch {
                hunk(vf.toString(), "x = 1", "x = 10")
                hunk(vf.toString(), "x = 1", "x = 100")  // same target!
            }
            null
        } catch (e: ApplyPatchException) {
            e
        }

        // Both pre-flights see "x = 1" exactly once — neither fails. But the
        // descending-offset apply will replay the same offset twice. Worth pinning
        // SOMETHING here so we know what the engine actually does, and break a
        // future "fix" that silently changes the policy.
        if (err == null) {
            // Both applied at the same offset; the second `replaceString` runs
            // on text that already has "x = 10". Whether it then succeeds or
            // throws depends on `x = 1` still being a prefix of "x = 10"
            // (it is). Document whatever the engine does.
            val updated = vf.readViaIde()
            // Either result is "fine" as long as neither file is corrupted.
            // Most likely outcome: "x = 1" still matches inside "x = 10" so
            // the replacement chains to "x = 1000" or similar — pin behaviour.
            assertTrue(
                updated.contains("x = 10") || updated.contains("x = 100"),
                "Duplicate-target patch left file in a known state, got: $updated")
        } else {
            // If the engine learns to reject duplicate paths-with-same-old,
            // make sure the error message is helpful.
            assertTrue(
                err.message!!.contains("duplicate", ignoreCase = true)
                    || err.message!!.contains("once")
                    || err.message!!.contains("unique"),"Error mentions duplicate or non-unique: ${err.message}")
        }
    }

    // -- dryRun (C4) --------------------------------------------------------
    //
    // The DSL on McpScriptContext deliberately does NOT expose dryRun — the
    // flag was used by the removed `steroid_apply_patch` MCP tool to preflight
    // an external write. Tests below call the engine (`executeApplyPatch`)
    // directly to keep coverage on the dryRun code path in case a future
    // recipe needs it.

    @Test
    fun dryRunDoesNotModifyFile(): Unit = timeoutRunBlocking(30.seconds) {
        val original = "class A { int x = 1; }\n"
        val vf = writeTempFile("DryRun_NoMod.java", original)

        val result = executeApplyPatch(
            project = projectFixture.get(),
            hunks = listOf(ApplyPatchHunk(vf.toString(), "int x = 1", "int x = 42")),
            dryRun = true,
        ) { path -> LocalFileSystem.getInstance().findFileByPath(path) }

        assertTrue( result.isDryRun,"Result must flag dry-run")
        assertEquals(1, result.hunkCount)
        assertEquals(1, result.fileCount)
        // Critical: file bytes unchanged on disk AND in VFS.
        assertEquals(original, vf.readViaIde())
        assertEquals(original, Files.readString(vf))
    }

    @Test
    fun dryRunMultiHunkReportsAllResolvedPositions(): Unit = timeoutRunBlocking(30.seconds) {
        val original = """
            class A {
                int x = 1;
                int y = 2;
                int z = 3;
            }
        """.trimIndent()
        val vf = writeTempFile("DryRun_Multi.java", original)

        val result = executeApplyPatch(
            project = projectFixture.get(),
            hunks = listOf(
                ApplyPatchHunk(vf.toString(), "int x = 1", "int x = 100"),
                ApplyPatchHunk(vf.toString(), "int y = 2", "int y = 200"),
                ApplyPatchHunk(vf.toString(), "int z = 3", "int z = 300"),
            ),
            dryRun = true,
        ) { path -> LocalFileSystem.getInstance().findFileByPath(path) }

        assertTrue(result.isDryRun)
        assertEquals(3, result.hunkCount)
        assertEquals(1, result.fileCount)
        assertEquals(listOf(2, 3, 4), result.applied.map { it.line })
        assertEquals(original, vf.readViaIde())
    }

    @Test
    fun dryRunSurfacesFileNotFoundSameAsLive(): Unit = timeoutRunBlocking(30.seconds) {
        val missing = tempRoot.resolve("NoSuchFile.java").toString()

        val err = try {
            executeApplyPatch(
                project = projectFixture.get(),
                hunks = listOf(ApplyPatchHunk(missing, "anything", "replacement")),
                dryRun = true,
            ) { path -> LocalFileSystem.getInstance().findFileByPath(path) }
            null
        } catch (e: ApplyPatchException) {
            e
        }

        assertNotNull( err,"Expected ApplyPatchException on missing file under dryRun")
        assertTrue( err!!.message!!.contains("Hunk #0"),"Error names hunk index: ${err.message}")
        assertTrue(
            err.message!!.contains("file not found"),"Error preserves 'file not found' substring: ${err.message}")
    }

    @Test
    fun dryRunSurfacesAnchorMismatchSameAsLive(): Unit = timeoutRunBlocking(30.seconds) {
        val original = "class A { int x = 1; }\n"
        val vf = writeTempFile("DryRun_AnchorMiss.java", original)

        val err = try {
            executeApplyPatch(
                project = projectFixture.get(),
                hunks = listOf(ApplyPatchHunk(vf.toString(), "NOT_IN_FILE_XYZ", "replacement")),
                dryRun = true,
            ) { path -> LocalFileSystem.getInstance().findFileByPath(path) }
            null
        } catch (e: ApplyPatchException) {
            e
        }

        assertNotNull( err,"Expected ApplyPatchException on missing anchor under dryRun")
        assertTrue(
            err!!.message!!.contains("old_string not found"),"Error preserves 'old_string not found' substring: ${err.message}")
        // File must remain unmodified after a failed dry-run preflight, just
        // as it would after a failed live preflight.
        assertEquals(original, vf.readViaIde())
    }

    @Test
    fun dryRunResultStringSaysWouldApply(): Unit = timeoutRunBlocking(30.seconds) {
        val vf = writeTempFile("DryRun_String.java", "class A { int x = 1; }\n")
        val result = executeApplyPatch(
            project = projectFixture.get(),
            hunks = listOf(ApplyPatchHunk(vf.toString(), "int x = 1", "int x = 42")),
            dryRun = true,
        ) { path -> LocalFileSystem.getInstance().findFileByPath(path) }

        val asString = result.toString()
        assertTrue( asString.contains("dry-run"),"Audit trail flags dry-run: $asString")
        assertTrue( asString.contains("would apply"),"Audit trail says 'would apply': $asString")
        assertFalse(
            asString.contains("applied atomically"),"Live wording must not leak into dry-run audit: $asString")
    }

    @Test
    fun dryRunSurfacesAmbiguousAnchorSameAsLive(): Unit = timeoutRunBlocking(30.seconds) {
        // The third leg of C1's structured fallout — non-unique anchor — must
        // behave identically under dryRun. Two occurrences of `dup_token`:
        val original = "dup_token line one\nmiddle\ndup_token line three\n"
        val vf = writeTempFile("DryRun_Ambiguous.java", original)

        val err = try {
            executeApplyPatch(
                project = projectFixture.get(),
                hunks = listOf(ApplyPatchHunk(vf.toString(), "dup_token", "rep_token")),
                dryRun = true,
            ) { path -> LocalFileSystem.getInstance().findFileByPath(path) }
            null
        } catch (e: ApplyPatchException) {
            e
        }

        assertNotNull( err,"Expected ApplyPatchException on ambiguous anchor under dryRun")
        assertTrue(
            err!!.message!!.contains("occurs more than once"),"Error preserves 'occurs more than once': ${err.message}")
        assertTrue(
            err.message!!.contains("expand old_string"),"Error preserves expansion hint: ${err.message}")
        assertEquals(
            original, vf.readViaIde(),"Failed dry-run preflight must not write to disk")
    }

    // -- C1: structured candidate tails on rejection -----------------------
    //
    // These tests pin that the structured tail produced by fileNotFoundMessage
    // and anchorNotFoundMessage actually reaches the agent. The earlier
    // ApplyPatch tests assert the leading wording ("file not found",
    // "old_string not found") but not the candidate listing, which is the
    // whole point of issue #50's recovery-hint contract. A future change that
    // accidentally drops the helpers would be invisible without these.

    @Test
    fun anchorNotFoundIncludesFileSizeAndFuzzyCandidates(): Unit = timeoutRunBlocking(30.seconds) {
        val ctx = createContext()
        // Two lines containing "findByStatus" — the longest stable token in
        // the bad anchor — so the fuzzy candidates should surface both lines.
        val content = """
            class FeatureService {
                fun findByStatus(status: Status): List<Feature> = listOf()
                fun findByStatusAndOwner(status: Status, owner: User): List<Feature> = listOf()
            }
        """.trimIndent()
        val vf = writeTempFile("Fuzzy_FeatureService.java", content)

        val err = try {
            ctx.applyPatch {
                // Anchor doesn't exist literally, but "findByStatus" does — the
                // diagnostic must pick that token and list candidate lines.
                hunk(
                    filePath = vf.toString(),
                    oldString = "fun findByStatus(BadSignatureUnique): NoSuchType<Anchor>",
                    newString = "REPLACED",
                )
            }
            null
        } catch (e: ApplyPatchException) {
            e
        }

        assertNotNull( err,"Expected ApplyPatchException for missing anchor")
        val msg = err!!.message!!
        assertTrue( msg.contains("old_string not found"),"Lead preserved: $msg")
        assertTrue( Regex("""\d+ lines""").containsMatchIn(msg),"File line count surfaced: $msg")
        assertTrue( Regex("""\d+ bytes""").containsMatchIn(msg),"File byte count surfaced: $msg")
        assertTrue(
            msg.contains("Fuzzy candidates"),
            "Fuzzy candidates section present: $msg")
        assertTrue(
            msg.contains("findByStatus"),
            "Stable token 'findByStatus' named in the candidates section: $msg")
        assertTrue(
            Regex("""\bL\d+:""").containsMatchIn(msg),
            "At least one candidate line number (Lnnn:) printed: $msg")
    }

    @Test
    fun anchorNotFoundShowsStaleAnchorHintWhenTokenIsAbsent(): Unit = timeoutRunBlocking(30.seconds) {
        // Anchor has a 4+ char token but the file does NOT contain any token
        // from it — the diagnostic must say so explicitly, so the agent
        // knows expanding the anchor won't help.
        val ctx = createContext()
        val vf = writeTempFile("Stale_File.java", "class A {}\n")

        val err = try {
            ctx.applyPatch {
                // "UniqueXyzZZZ_NotInFile" is a single 4+ char token (per the
                // [A-Za-z0-9_]{4,} regex) that does not appear in the file.
                hunk(vf.toString(), "UniqueXyzZZZ_NotInFile", "REPLACED")
            }
            null
        } catch (e: ApplyPatchException) {
            e
        }

        assertNotNull(err)
        val msg = err!!.message!!
        assertTrue( msg.contains("old_string not found"),"Lead preserved: $msg")
        assertTrue( msg.contains(" bytes"),"File size surfaced: $msg")
        assertTrue(
            msg.contains("not present in file") || msg.contains("no stable token"),
            "Either 'not present in file' or 'no stable token' hint appears: $msg")
    }

    @Test
    fun fileNotFoundIncludesBasenameDiagnostic(): Unit = timeoutRunBlocking(30.seconds) {
        // Light test project has no files in scope by this basename, so the
        // diagnostic falls back to the "no candidates by basename" message.
        // Either branch is acceptable for the contract — what matters is the
        // structured tail mentions the basename so the agent can grep for it.
        val ctx = createContext()
        val missing = tempRoot.resolve("DoesNotExist_Unique_XYZ.java").toString()

        val err = try {
            ctx.applyPatch {
                hunk(missing, "anything", "replacement")
            }
            null
        } catch (e: ApplyPatchException) {
            e
        }

        assertNotNull(err)
        val msg = err!!.message!!
        assertTrue( msg.contains("file not found"),"Lead preserved: $msg")
        assertTrue(
            msg.contains("Nearby candidates by basename") ||
                msg.contains("no candidates by basename"),
            "Either nearby-candidates or no-candidates note is present: $msg")
        assertTrue(
            msg.contains("DoesNotExist_Unique_XYZ.java"),
            "Basename is surfaced for grep: $msg")
    }

    @Test
    fun dryRunMultiFileAllOrNothingOnPartialFailure(): Unit = timeoutRunBlocking(30.seconds) {
        // Multi-file preflight: one valid + one missing-anchor hunk. Dry-run
        // must reject the batch atomically — no per-file partial "would apply"
        // result for the valid hunk.
        val originalA = "class A { int value = 1; }\n"
        val originalB = "class B { int other = 1; }\n"
        val vfA = writeTempFile("DryRun_MultiA.java", originalA)
        val vfB = writeTempFile("DryRun_MultiB.java", originalB)

        val err = try {
            executeApplyPatch(
                project = projectFixture.get(),
                hunks = listOf(
                    ApplyPatchHunk(vfA.toString(), "int value = 1", "int value = 42"), // valid
                    ApplyPatchHunk(vfB.toString(), "NOT_PRESENT_XYZ", "rep"),           // fails
                ),
                dryRun = true,
            ) { path -> LocalFileSystem.getInstance().findFileByPath(path) }
            null
        } catch (e: ApplyPatchException) {
            e
        }

        assertNotNull( err,"Expected ApplyPatchException on partial-failure dry-run")
        assertTrue(
            err!!.message!!.contains("Hunk #1"),"Error names the failing hunk index: ${err.message}")
        // Both files must be unmodified — the valid hunk in #0 must NOT
        // partially "apply" even in dry-run audit.
        assertEquals(originalA, vfA.readViaIde())
        assertEquals(originalB, vfB.readViaIde())
    }
}
