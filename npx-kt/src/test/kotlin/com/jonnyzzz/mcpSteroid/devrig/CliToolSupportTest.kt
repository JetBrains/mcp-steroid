/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir

class CliToolSupportTest {

    @TempDir
    lateinit var tempDir: Path

    private val parseJson = Json { ignoreUnknownKeys = true }

    private class CapturedStream {
        val buffer = ByteArrayOutputStream()
        val stream = PrintStream(buffer, true, Charsets.UTF_8)
        fun text(): String = buffer.toString(Charsets.UTF_8)
    }

    private fun textResult(text: String, isError: Boolean = false) =
        ToolCallResult(content = listOf(ContentItem.Text(text)), isError = isError)

    private fun imageResult(bytes: ByteArray = byteArrayOf(1, 2, 3, 4), mimeType: String = "image/png") =
        ToolCallResult(content = listOf(ContentItem.Image(data = Base64.getEncoder().encodeToString(bytes), mimeType = mimeType)))

    private fun firstTextOf(content: kotlinx.serialization.json.JsonElement): String =
        (content as JsonArray)[0].jsonObject.getValue("text").jsonPrimitive.content

    // ------------------------------ presentationFor / stderrProgressReporter ------------------------------

    @Test
    fun `presentationFor selects Json when json is true and Console otherwise`() {
        assertTrue(presentationFor(json = true) { tempDir } is Presentation.Json)
        assertTrue(presentationFor(json = false) { tempDir } is Presentation.Console)
    }

    @Test
    fun `stderrProgressReporter writes each reported message as its own line`() {
        val err = CapturedStream()

        stderrProgressReporter(err.stream).report("indexing…")
        stderrProgressReporter(err.stream).report("done")

        assertEquals("indexing…\ndone\n", err.text())
    }

    // ------------------------------ envelope shape ------------------------------

    @Test
    fun `json envelope for a successful result carries tool, command and isError false`() {
        val out = CapturedStream()
        val exit = Presentation.Json().render(textResult("hello"), "list_projects", out.stream)

        assertEquals(CliExit.OK, exit)
        val envelope = parseJson.parseToJsonElement(out.text()).jsonObject
        assertEquals("devrig", envelope.getValue("tool").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("list_projects", envelope.getValue("command").jsonPrimitive.content)
        assertEquals(false, envelope.getValue("isError").jsonPrimitive.boolean)
        assertEquals("hello", firstTextOf(envelope.getValue("data").jsonObject.getValue("content")))
    }

    @Test
    fun `json envelope for a tool-level error result carries isError true and TOOL_ERROR exit`() {
        val out = CapturedStream()
        val exit = Presentation.Json().render(textResult("boom", isError = true), "execute_code", out.stream)

        assertEquals(CliExit.TOOL_ERROR, exit)
        val envelope = parseJson.parseToJsonElement(out.text()).jsonObject
        assertEquals(true, envelope.getValue("isError").jsonPrimitive.boolean)
    }

    @Test
    fun `json renderError wraps the message as text content with the given exit and isError true`() {
        val out = CapturedStream()
        val exit = Presentation.Json().renderError("open_project", "no such project", CliExit.USAGE, out.stream)

        assertEquals(CliExit.USAGE, exit)
        val envelope = parseJson.parseToJsonElement(out.text()).jsonObject
        assertEquals(true, envelope.getValue("isError").jsonPrimitive.boolean)
        assertEquals("no such project", firstTextOf(envelope.getValue("data").jsonObject.getValue("content")))
    }

    @Test
    fun `console renderError prints the message to stderr and returns the given exit verbatim`() {
        val out = CapturedStream()
        val err = CapturedStream()
        val exit = Presentation.Console { tempDir }.renderError("open_project", "no such project", CliExit.UNAVAILABLE, out.stream, err.stream)

        assertEquals(CliExit.UNAVAILABLE, exit)
        assertTrue(err.text().contains("no such project"))
        assertEquals("", out.text())
    }

    // ------------------------------ console image rendering ------------------------------

    @Test
    fun `console render decodes an image into tmpDir and prints its absolute path`() {
        val out = CapturedStream()
        val bytes = byteArrayOf(9, 9, 9)

        val exit = Presentation.Console { tempDir }.render(imageResult(bytes), "take_screenshot", out.stream)

        assertEquals(CliExit.OK, exit)
        val printed = out.text().trim().removePrefix("Saved image: ")
        val savedFile = Path.of(printed)
        assertEquals(savedFile.toAbsolutePath(), savedFile, "path printed to console must be absolute: $printed")
        assertTrue(Files.exists(savedFile), "expected an image file at $savedFile")
        assertEquals(tempDir, savedFile.parent)
        assertTrue(bytes.contentEquals(Files.readAllBytes(savedFile)))
    }

    @Test
    fun `console render does not throw on undecodable base64 and prints a marker instead`() {
        val out = CapturedStream()
        val err = CapturedStream()
        val badResult = ToolCallResult(content = listOf(ContentItem.Image(data = "!!! not base64 !!!", mimeType = "image/png")))

        val exit = Presentation.Console { tempDir }.render(badResult, "take_screenshot", out.stream, err.stream)

        assertEquals(CliExit.OK, exit)
        assertTrue(out.text().contains("undecodable"))
        assertTrue(err.text().contains("not valid base64"))
        assertTrue(tempDir.listDirectoryEntries().isEmpty(), "no file should be written for undecodable data")
    }

    // ------------------------------ --out ------------------------------

    @Test
    fun `--out with no path passed delegates to the plain render`() {
        val out = CapturedStream()
        val exit = renderWithOut(Presentation.Json(), textResult("hi"), "list_windows", outPath = null, out = out.stream)

        assertEquals(CliExit.OK, exit)
        assertTrue(out.text().contains("\"hi\""))
    }

    @Test
    fun `--out writes the decoded image bytes to the given path and the json envelope reports savedOut`() {
        val outPath = tempDir.resolve("shots/ok.png")
        val out = CapturedStream()
        val bytes = byteArrayOf(5, 6, 7)

        val exit = renderWithOut(Presentation.Json(), imageResult(bytes), "take_screenshot", outPath, out.stream)

        assertEquals(CliExit.OK, exit)
        assertTrue(bytes.contentEquals(Files.readAllBytes(outPath)))
        val envelope = parseJson.parseToJsonElement(out.text()).jsonObject
        assertEquals(false, envelope.getValue("isError").jsonPrimitive.boolean)
        assertEquals(
            outPath.toAbsolutePath().toString(),
            envelope.getValue("data").jsonObject.getValue("savedOut").jsonPrimitive.content,
        )
    }

    @Test
    fun `--out on the console prints a Saved --out note and writes nothing else under tmpDir`() {
        val outPath = tempDir.resolve("ok.png")
        val out = CapturedStream()

        val exit = renderWithOut(Presentation.Console { tempDir }, imageResult(), "take_screenshot", outPath, out.stream)

        assertEquals(CliExit.OK, exit)
        assertTrue(out.text().contains("Saved --out: ${outPath.toAbsolutePath()}"))
        // Only the --out target itself should exist under tempDir — no extra Files.createTempFile
        // copy from the ordinary image-rendering path (that path was intentionally bypassed).
        assertEquals(listOf(outPath), tempDir.listDirectoryEntries())
    }

    @Test
    fun `--out with no image in the result warns on stderr and preserves the tool result's own exit code`() {
        val outPath = tempDir.resolve("x.png")
        val out = CapturedStream()
        val err = CapturedStream()

        val exit = renderWithOut(Presentation.Json(), textResult("all good, no dialog appeared"), "execute_code", outPath, out.stream, err.stream)

        assertEquals(CliExit.OK, exit, "a --out miss must not fail an otherwise-successful call")
        assertTrue(err.text().contains("no image"), err.text())
        assertTrue(!Files.exists(outPath), "nothing should be written when there is no image")
        assertTrue(out.text().contains("all good"), "the underlying result must still be rendered")
    }

    @Test
    fun `--out with no image preserves a tool-level error's own TOOL_ERROR exit code`() {
        val outPath = tempDir.resolve("x.png")
        val out = CapturedStream()
        val err = CapturedStream()

        val exit = renderWithOut(Presentation.Json(), textResult("compile failed", isError = true), "execute_code", outPath, out.stream, err.stream)

        assertEquals(CliExit.TOOL_ERROR, exit)
        assertTrue(err.text().contains("no image"))
    }

    @Test
    fun `--out with an undecodable image payload is a DATA_ERROR`() {
        val outPath = tempDir.resolve("x.png")
        val out = CapturedStream()
        val err = CapturedStream()
        val badResult = ToolCallResult(content = listOf(ContentItem.Image(data = "!!! not base64 !!!", mimeType = "image/png")))

        val exit = renderWithOut(Presentation.Json(), badResult, "take_screenshot", outPath, out.stream, err.stream)

        assertEquals(CliExit.DATA_ERROR, exit)
        assertTrue(!Files.exists(outPath))
        assertTrue(err.text().contains("not valid base64"))
    }

    @Test
    fun `--out to an unwritable path is an IO_ERROR`() {
        // tempDir itself is a directory: Files.write onto it fails with IOException ("Is a directory"),
        // a genuine write failure distinct from the undecodable-payload DATA_ERROR case above.
        val out = CapturedStream()
        val err = CapturedStream()

        val exit = renderWithOut(Presentation.Json(), imageResult(), "take_screenshot", tempDir, out.stream, err.stream)

        assertEquals(CliExit.IO_ERROR, exit)
        assertTrue(err.text().contains("failed to write"), err.text())
    }
}
