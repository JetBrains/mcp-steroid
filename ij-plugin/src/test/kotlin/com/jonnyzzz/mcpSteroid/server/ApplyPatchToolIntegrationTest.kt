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
