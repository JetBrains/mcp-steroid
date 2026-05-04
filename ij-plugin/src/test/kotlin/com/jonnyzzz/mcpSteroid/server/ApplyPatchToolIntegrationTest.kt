/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.setServerPortProperties
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.InitializeResult
import com.jonnyzzz.mcpSteroid.mcp.JsonRpcResponse
import com.jonnyzzz.mcpSteroid.mcp.MCP_PROTOCOL_VERSION
import com.jonnyzzz.mcpSteroid.mcp.McpHttpTransport
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

class ApplyPatchToolIntegrationTest : BasePlatformTestCase() {
    private lateinit var client: HttpClient

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        setServerPortProperties()
        super.setUp()
        client = HttpClient(CIO) {
            expectSuccess = false
            install(io.ktor.client.plugins.HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 10_000
            }
        }
    }

    override fun tearDown() {
        try {
            client.close()
        } finally {
            super.tearDown()
        }
    }

    fun testSingleHunkPersistsFileBeforeReturning(): Unit = timeoutRunBlocking(30.seconds) {
        withTempDir { dir ->
            val file = writeProjectFile(dir, "PatchTarget.java", "class PatchTarget { int value = 1; }\n")

            val result = callApplyPatchTool(
                hunks = listOf(
                    hunk(file, "int value = 1", "int value = 42"),
                ),
            )

            assertFalse("steroid_apply_patch should succeed, got: ${result.output}", result.isError)
            assertTrue("Audit should include one hunk: ${result.output}", result.output.contains("1 hunk"))
            assertEquals("class PatchTarget { int value = 42; }\n", Files.readString(file))
        }
    }

    fun testMultiHunkSameFilePersistsInDescendingOffsetOrder(): Unit = timeoutRunBlocking(30.seconds) {
        withTempDir { dir ->
            val file = writeProjectFile(
                dir,
                "Multi.java",
                """
                    class Multi {
                        int first = 1;
                        int second = 2;
                        int third = 3;
                    }
                """.trimIndent(),
            )

            val result = callApplyPatchTool(
                hunks = listOf(
                    hunk(file, "int first = 1", "int first = 100"),
                    hunk(file, "int second = 2", "int second = 200"),
                    hunk(file, "int third = 3", "int third = 300"),
                ),
            )

            assertFalse("steroid_apply_patch should succeed, got: ${result.output}", result.isError)
            assertEquals(
                """
                    class Multi {
                        int first = 100;
                        int second = 200;
                        int third = 300;
                    }
                """.trimIndent(),
                Files.readString(file),
            )
        }
    }

    fun testMultipleFilesPersistBeforeReturning(): Unit = timeoutRunBlocking(30.seconds) {
        withTempDir { dir ->
            val a = writeProjectFile(dir, "A.java", "class A { int value = 1; }\n")
            val b = writeProjectFile(dir, "nested/B.java", "class B { int value = 2; }\n")

            val result = callApplyPatchTool(
                hunks = listOf(
                    hunk(a, "value = 1", "value = 100"),
                    hunk(b, "value = 2", "value = 200"),
                ),
            )

            assertFalse("steroid_apply_patch should succeed, got: ${result.output}", result.isError)
            assertTrue("Audit should include two files: ${result.output}", result.output.contains("2 file(s)"))
            assertEquals("class A { int value = 100; }\n", Files.readString(a))
            assertEquals("class B { int value = 200; }\n", Files.readString(b))
        }
    }

    fun testMissingOldStringFailsWithoutPartialDiskEdit(): Unit = timeoutRunBlocking(30.seconds) {
        withTempDir { dir ->
            val a = writeProjectFile(dir, "AtomicA.java", "class AtomicA { int value = 1; }\n")
            val b = writeProjectFile(dir, "AtomicB.java", "class AtomicB { int value = 2; }\n")

            val result = callApplyPatchTool(
                hunks = listOf(
                    hunk(a, "value = 1", "value = 100"),
                    hunk(b, "missing = 2", "value = 200"),
                ),
            )

            assertTrue("steroid_apply_patch should fail, got: ${result.output}", result.isError)
            assertTrue("Error should name missing old_string: ${result.output}", result.output.contains("old_string not found"))
            assertEquals("class AtomicA { int value = 1; }\n", Files.readString(a))
            assertEquals("class AtomicB { int value = 2; }\n", Files.readString(b))
        }
    }

    fun testNonUniqueOldStringFailsWithoutDiskEdit(): Unit = timeoutRunBlocking(30.seconds) {
        withTempDir { dir ->
            val file = writeProjectFile(dir, "Duplicate.java", "token\nmiddle\ntoken\n")

            val result = callApplyPatchTool(
                hunks = listOf(
                    hunk(file, "token", "updated"),
                ),
            )

            assertTrue("steroid_apply_patch should fail, got: ${result.output}", result.isError)
            assertTrue("Error should explain non-unique old_string: ${result.output}", result.output.contains("occurs more than once"))
            assertEquals("token\nmiddle\ntoken\n", Files.readString(file))
        }
    }

    fun testMissingFileFailsWithoutPartialDiskEdit(): Unit = timeoutRunBlocking(30.seconds) {
        withTempDir { dir ->
            val existing = writeProjectFile(dir, "Existing.java", "class Existing { int value = 1; }\n")
            val missing = dir / "Missing.java"

            val result = callApplyPatchTool(
                hunks = listOf(
                    hunk(existing, "value = 1", "value = 100"),
                    hunk(missing, "value = 2", "value = 200"),
                ),
            )

            assertTrue("steroid_apply_patch should fail, got: ${result.output}", result.isError)
            assertTrue("Error should name missing file: ${result.output}", result.output.contains("file not found"))
            assertEquals("class Existing { int value = 1; }\n", Files.readString(existing))
            assertFalse("Missing file must not be created", Files.exists(missing))
        }
    }

    fun testReadOnlyFileFailsWithoutDiskEdit(): Unit = timeoutRunBlocking(30.seconds) {
        withTempDir { dir ->
            val file = writeProjectFile(dir, "ReadOnly.java", "class ReadOnly { int value = 1; }\n")
            val ioFile = file.toFile()
            assertTrue("Test setup should make the file read-only", ioFile.setWritable(false))
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)

            try {
                val result = callApplyPatchTool(
                    hunks = listOf(
                        hunk(file, "value = 1", "value = 100"),
                    ),
                )

                assertTrue("steroid_apply_patch should fail, got: ${result.output}", result.isError)
                assertTrue(
                    "Error should explain read-only file or save failure: ${result.output}",
                    result.output.contains("file is read-only") || result.output.contains("Failed to save"),
                )
                assertEquals("class ReadOnly { int value = 1; }\n", Files.readString(file))
            } finally {
                ioFile.setWritable(true)
            }
        }
    }

    fun testEmptyHunksFailsFast(): Unit = timeoutRunBlocking(30.seconds) {
        val result = callApplyPatchTool(hunks = emptyList())

        assertTrue("steroid_apply_patch should fail, got: ${result.output}", result.isError)
        assertTrue("Error should name empty hunks: ${result.output}", result.output.contains("hunks array is empty"))
    }

    // --- Tricky edge cases through the real MCP HTTP transport ---

    /**
     * Some MCP clients pass complex parameters as serialised JSON strings
     * instead of native arrays/objects (Claude Code's tool-call envelope does
     * this for nested structures). Today the handler does
     * `args["hunks"]?.jsonArray` which throws `IllegalArgumentException` on a
     * `JsonPrimitive`, leaking a 500-style stacktrace through the MCP layer
     * instead of returning a clean tool-error. Pin the contract: a
     * string-encoded `hunks` MUST come back as a tool-error (`isError=true`)
     * with a clear message — not a JsonPrimitive cast crash.
     *
     * This is also a regression guard for the live bug observed when calling
     * the tool from Claude Code on 2026-05-04: the cast threw and the tool
     * appeared to "hang" until the client timed out.
     */
    fun testStringEncodedHunksWithNonArrayPrimitiveReturnsCleanError(): Unit = timeoutRunBlocking(30.seconds) {
        // Send hunks as a JSON number (not an array, not a string-encoded array)
        // — exercises the broadest "wrong shape" branch without going near VFS.
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()
        val sessionId = startSession(server)

        val response = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", "apply-patch-bad-shape")
                    put("method", "tools/call")
                    putJsonObject("params") {
                        put("name", "steroid_apply_patch")
                        putJsonObject("arguments") {
                            put("project_name", project.name)
                            put("task_id", "bad-shape")
                            put("reason", "Regression: hunks shipped as a number")
                            put("hunks", 42)
                        }
                    }
                }.toString()
            )
        }

        val bodyText = response.bodyAsText()
        assertEquals("Body: $bodyText", HttpStatusCode.OK, response.status)
        val rpc = McpJson.decodeFromString<JsonRpcResponse>(bodyText)
        assertNull("Handler must not leak as JSON-RPC error: ${rpc.error}", rpc.error)
        val toolResult = McpJson.decodeFromJsonElement<ToolCallResult>(rpc.result!!)
        assertTrue("Result.isError must be true on bad hunks shape", toolResult.isError)
        val msg = toolResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
        assertTrue(
            "Error must mention array-shape: $msg",
            msg.contains("hunks", ignoreCase = true) && msg.contains("array", ignoreCase = true),
        )
    }

    /**
     * Some MCP clients pass the `hunks` parameter as a serialised JSON string
     * instead of a real array (Claude Code's tool-call envelope sometimes does
     * this for nested structures). Today the handler decodes the string back
     * into an array; if it ever stops doing so, this test catches it.
     *
     * Live reproducer (2026-05-04): the original `?.jsonArray` cast threw
     * `IllegalArgumentException("Element class kotlinx.serialization.json.JsonLiteral is not a JsonArray")`
     * which leaked through `McpHttpTransport.handlePost`'s catch as a 500.
     */
    fun testStringEncodedHunksAreDecodedAndApplied(): Unit = timeoutRunBlocking(30.seconds) {
        withTempDir { dir ->
            val file = writeProjectFile(dir, "Stringy.java", "class Stringy { int v = 1; }\n")

            val server = SteroidsMcpServer.getInstance()
            server.startServerIfNeeded()
            val sessionId = startSession(server)

            // hunks as a serialised JSON-string of a real array — the bug from the wild.
            val stringEncoded = buildJsonObject {
                putJsonArray("wrap") {
                    addJsonObject {
                        put("file_path", file.toString())
                        put("old_string", "v = 1")
                        put("new_string", "v = 99")
                    }
                }
            }["wrap"]!!.toString()

            val response = client.post(server.mcpUrl) {
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                header(McpHttpTransport.SESSION_HEADER, sessionId)
                setBody(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", "apply-patch-string-hunks")
                        put("method", "tools/call")
                        putJsonObject("params") {
                            put("name", "steroid_apply_patch")
                            putJsonObject("arguments") {
                                put("project_name", project.name)
                                put("task_id", "string-encoded")
                                put("reason", "Regression: hunks shipped as JSON string")
                                put("hunks", stringEncoded)
                            }
                        }
                    }.toString()
                )
            }

            val bodyText = response.bodyAsText()
            assertEquals("Body: $bodyText", HttpStatusCode.OK, response.status)
            val rpc = McpJson.decodeFromString<JsonRpcResponse>(bodyText)
            assertNull("Handler must not leak as JSON-RPC error: ${rpc.error}", rpc.error)
            val toolResult = McpJson.decodeFromJsonElement<ToolCallResult>(rpc.result!!)
            val msg = toolResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
            assertFalse("String-encoded hunks must succeed (msg: $msg)", toolResult.isError)
            // Disk reflects the patch.
            assertEquals("class Stringy { int v = 99; }\n", Files.readString(file))
        }
    }

    /**
     * Latency guard for the full HTTP path. The original "stuck" symptom is
     * an EDT deadlock in `ApplyPatch.kt` — but it can also surface as a slow
     * client roundtrip. A 1-hunk patch through HTTP must complete in well
     * under Claude Code's 60s tool-call timeout, even on a cold sandbox.
     */
    fun testToolCallCompletesQuickly(): Unit = timeoutRunBlocking(15.seconds) {
        withTempDir { dir ->
            val file = writeProjectFile(dir, "Quick.java", "class Quick { int v = 1; }\n")
            val started = System.currentTimeMillis()
            val result = callApplyPatchTool(
                hunks = listOf(hunk(file, "v = 1", "v = 42")),
            )
            val elapsed = System.currentTimeMillis() - started
            assertFalse("steroid_apply_patch should succeed: ${result.output}", result.isError)
            // Generous bound: tests sometimes share a JVM with other Docker-y stuff.
            assertTrue("Apply-patch HTTP roundtrip too slow: ${elapsed}ms", elapsed < 10_000)
        }
    }

    /**
     * Empty `new_string` deletion through the HTTP boundary. The JSON envelope
     * must preserve empty strings (some serialisers strip them by default),
     * and the engine must accept the deletion semantics.
     */
    fun testEmptyNewStringDeletesThroughTool(): Unit = timeoutRunBlocking(30.seconds) {
        withTempDir { dir ->
            val file = writeProjectFile(dir, "ToolDelete.java", "import a.B;\nimport a.C;\nclass X {}\n")

            val result = callApplyPatchTool(
                hunks = listOf(hunk(file, "import a.B;\n", "")),
            )

            assertFalse("Apply-patch should succeed, got: ${result.output}", result.isError)
            assertEquals("import a.C;\nclass X {}\n", Files.readString(file))
        }
    }

    /**
     * CRLF line-ending preservation through the HTTP boundary. Mirrors the
     * unit-level CRLF test but exercises JSON-string roundtripping of `\r\n`.
     */
    fun testCrlfLineEndingsPreservedThroughTool(): Unit = timeoutRunBlocking(30.seconds) {
        withTempDir { dir ->
            val file = dir / "ToolCrlf.java"
            file.parent.createDirectories()
            Files.write(file, "class A {\r\n    int x = 1;\r\n}\r\n".toByteArray())
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)

            val result = callApplyPatchTool(
                hunks = listOf(hunk(file, "int x = 1", "int x = 42")),
            )

            assertFalse("Apply-patch should succeed, got: ${result.output}", result.isError)
            val onDisk = Files.readAllBytes(file).toString(Charsets.UTF_8)
            assertTrue("CRLF preserved on disk", onDisk.contains("\r\n"))
            assertTrue("New value present", onDisk.contains("int x = 42"))
        }
    }

    /**
     * Missing `project_name` should return a clean tool-error, not crash.
     * Pinning the validation contract for malformed envelopes from clients.
     */
    fun testMissingProjectNameReturnsCleanError(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()
        val sessionId = startSession(server)

        val response = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", "no-project")
                    put("method", "tools/call")
                    putJsonObject("params") {
                        put("name", "steroid_apply_patch")
                        putJsonObject("arguments") {
                            // project_name missing on purpose
                            put("task_id", "no-project")
                            put("reason", "validation regression guard")
                            putJsonArray("hunks") {
                                addJsonObject {
                                    put("file_path", "/tmp/never")
                                    put("old_string", "a")
                                    put("new_string", "b")
                                }
                            }
                        }
                    }
                }.toString()
            )
        }

        val rpc = McpJson.decodeFromString<JsonRpcResponse>(response.bodyAsText())
        assertNull("Validation failure must surface as tool-result, not JSON-RPC error", rpc.error)
        val toolResult = McpJson.decodeFromJsonElement<ToolCallResult>(rpc.result!!)
        assertTrue("Missing project_name must be tool-error", toolResult.isError)
        val msg = toolResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
        assertTrue("Error names project_name: $msg", msg.contains("project_name"))
    }

    /**
     * Multi-byte (Unicode) content roundtrips through JSON envelope correctly.
     * Catches naive byte-vs-codepoint offset mistakes and JSON encoding drift.
     */
    fun testUnicodeContentThroughTool(): Unit = timeoutRunBlocking(30.seconds) {
        withTempDir { dir ->
            val file = writeProjectFile(dir, "ToolUnicode.kt", "val msg = \"héllo 🌍 world\"\n")

            val result = callApplyPatchTool(
                hunks = listOf(hunk(file, "héllo 🌍 world", "héllo 🌎 world")),
            )

            assertFalse("Apply-patch should succeed, got: ${result.output}", result.isError)
            assertEquals("val msg = \"héllo 🌎 world\"\n", Files.readString(file))
        }
    }

    private suspend fun callApplyPatchTool(hunks: List<JsonObject>): ApplyPatchCallResult {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()
        val sessionId = startSession(server)

        val response = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", "apply-patch")
                    put("method", "tools/call")
                    putJsonObject("params") {
                        put("name", "steroid_apply_patch")
                        putJsonObject("arguments") {
                            put("project_name", project.name)
                            put("task_id", "apply-patch-tool-test")
                            put("reason", "Verify apply patch tool behavior")
                            putJsonArray("hunks") {
                                hunks.forEach { add(it) }
                            }
                        }
                    }
                }.toString()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val rpc = McpJson.decodeFromString<JsonRpcResponse>(response.bodyAsText())
        assertNull("steroid_apply_patch should return result payload", rpc.error)
        val toolResult = McpJson.decodeFromJsonElement<ToolCallResult>(rpc.result!!)
        return ApplyPatchCallResult(
            isError = toolResult.isError,
            output = toolResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text },
        )
    }

    private suspend fun startSession(server: SteroidsMcpServer): String {
        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildInitializeRequest())
        }

        assertEquals(HttpStatusCode.OK, initResponse.status)
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Server must issue MCP session id", sessionId)

        val initRpc = McpJson.decodeFromString<JsonRpcResponse>(initResponse.bodyAsText())
        assertNull("Initialize should not return error", initRpc.error)
        val initResult = McpJson.decodeFromJsonElement<InitializeResult>(initRpc.result!!)
        assertEquals(MCP_PROTOCOL_VERSION, initResult.protocolVersion)

        return sessionId!!
    }

    private fun buildInitializeRequest() = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", "init")
        put("method", "initialize")
        putJsonObject("params") {
            put("protocolVersion", MCP_PROTOCOL_VERSION)
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") {
                put("name", "apply-patch-tool-test-client")
                put("version", "1.0.0")
            }
        }
    }.toString()

    private fun hunk(file: Path, oldString: String, newString: String) = buildJsonObject {
        put("file_path", file.toString())
        put("old_string", oldString)
        put("new_string", newString)
    }

    private fun writeProjectFile(dir: Path, relativePath: String, content: String): Path {
        val file = dir.resolve(relativePath)
        file.parent.createDirectories()
        file.writeText(content)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)
            ?: error("VFS refresh did not surface $file")
        return file
    }

    private suspend inline fun withTempDir(action: suspend (Path) -> Unit) {
        val dir = Files.createTempDirectory("mcp-apply-patch-tool-")
        try {
            action(dir)
        } finally {
            Files.walk(dir).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() }
            }
        }
    }

    private data class ApplyPatchCallResult(val isError: Boolean, val output: String)
}
