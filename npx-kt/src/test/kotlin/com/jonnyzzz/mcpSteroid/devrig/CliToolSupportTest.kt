/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.EmbeddedResource
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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
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
        ToolCallResult(content = listOf(imageItem(bytes, mimeType)))

    private fun imageItem(bytes: ByteArray, mimeType: String = "image/png") =
        ContentItem.Image(data = Base64.getEncoder().encodeToString(bytes), mimeType = mimeType)

    private fun firstTextOf(content: kotlinx.serialization.json.JsonElement): String =
        (content as JsonArray)[0].jsonObject.getValue("text").jsonPrimitive.content

    private fun countOccurrences(haystack: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(haystack).count()

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

    // ------------------------------ contentDataJson: text vs json payload ------------------------------
    // These pin the --json envelope's payload shape: a tool's own JSON payload (e.g. list_windows'
    // {"windows":[...]}) must be reachable in ONE parse, not double-encoded as an escaped string under
    // "text". Every assertion below reads a whole document (never a substring) per the brief.

    @Test
    fun `an object payload is unpacked under json, not double-encoded under text`() {
        val result = textResult("""{"windows":[{"windowId":"w1"}]}""")

        val item = (result.contentDataJson().getValue("content") as JsonArray)[0].jsonObject
        assertEquals("text", item.getValue("type").jsonPrimitive.content)
        assertTrue("text" !in item, "must carry exactly one payload key, not also text: $item")
        val windowId = item.getValue("json").jsonObject.getValue("windows")
            .jsonArray[0].jsonObject.getValue("windowId").jsonPrimitive.content
        assertEquals("w1", windowId)
    }

    @Test
    fun `an array payload is unpacked under json`() {
        val result = textResult("""[1,2,3]""")

        val item = (result.contentDataJson().getValue("content") as JsonArray)[0].jsonObject
        assertTrue("text" !in item, "must carry exactly one payload key, not also text: $item")
        assertEquals(listOf(1, 2, 3), item.getValue("json").jsonArray.map { it.jsonPrimitive.int })
    }

    @Test
    fun `a bare scalar payload stays under text, never json`() {
        for (scalar in listOf("123", "\"hi\"", "true")) {
            val item = (textResult(scalar).contentDataJson().getValue("content") as JsonArray)[0].jsonObject
            assertEquals(scalar, item.getValue("text").jsonPrimitive.content, "scalar payload: $scalar")
            assertTrue("json" !in item, "a bare scalar must not be unpacked as json: $scalar -> $item")
        }
    }

    @Test
    fun `prose that fails to parse as json stays under text, unchanged`() {
        val prose = "hello, this is not json {"
        val item = (textResult(prose).contentDataJson().getValue("content") as JsonArray)[0].jsonObject

        assertEquals(prose, item.getValue("text").jsonPrimitive.content)
        assertTrue("json" !in item)
    }

    @Test
    fun `an execute_code-shaped transcript with an embedded json object stays under text`() {
        // Pins the load-bearing assumption behind this whole task: execute_code's payload is a
        // transcript (execution_id / [PRE] / script output / [POST]), never bare JSON, no matter what
        // the script printed. If transcript wrapping is ever dropped, this must fail loudly instead of
        // silently flipping execute_code's envelope shape to json.
        val transcript = "execution_id: eid_1\n[PRE] sync documents\n{\"a\":1}\n[POST] sync documents"
        val item = (textResult(transcript).contentDataJson().getValue("content") as JsonArray)[0].jsonObject

        assertEquals(transcript, item.getValue("text").jsonPrimitive.content)
        assertTrue("json" !in item, "an embedded JSON object mid-transcript must not flip the shape: $item")
    }

    @Test
    fun `a pathologically deep payload overflows the parser stack but stays under text, never failing the command`() {
        // The reviewer proved this is reachable: kotlinx.serialization's recursive-descent JsonTreeReader
        // throws StackOverflowError (an Error, not an Exception) around ~10,000 nesting levels; no
        // Exception-typed catch — however broad — can see it. 50,000 levels gives comfortable margin
        // without making the test slow: building and scanning the string is microseconds, the parse
        // attempt fails fast once the stack overflows. The claim under test is the contract in
        // parseAsJsonContainer's KDoc: this is a rendering choice, so it must degrade to "not json", not
        // propagate an exception and fail the whole --json command.
        val depth = 50_000
        val deeplyNested = "[".repeat(depth) + "]".repeat(depth)

        val item = (textResult(deeplyNested).contentDataJson().getValue("content") as JsonArray)[0].jsonObject

        assertEquals(deeplyNested, item.getValue("text").jsonPrimitive.content)
        assertTrue("json" !in item, "a StackOverflowError while parsing must not be treated as json: $item")
    }

    @Test
    fun `a resource content item has no top-level text key and is never mistaken for an unpackable payload`() {
        // ContentItem.Resource's own `text` (if any) is nested under `resource`, never top-level, so
        // unpackJsonPayload's `item["text"]` lookup must not find it. Pinned directly rather than left to
        // inspection, so a future schema change that hoists `text` to the top level fails this test
        // instead of silently starting to unpack resource payloads.
        val resource = EmbeddedResource(uri = "mcp-steroid://some/resource", mimeType = "text/markdown", text = "# hi, this could look like json {}")
        val result = ToolCallResult(content = listOf(ContentItem.Resource(resource)))

        val item = (result.contentDataJson().getValue("content") as JsonArray)[0].jsonObject

        assertEquals("resource", item.getValue("type").jsonPrimitive.content)
        assertTrue("text" !in item, "a Resource item's own text is nested under resource, not top-level: $item")
        assertTrue("json" !in item, "a Resource item must never be treated as an unpackable text payload: $item")
    }

    @Test
    fun `a multi-item result unpacks json and text independently per item, images untouched`() {
        val result = ToolCallResult(
            content = listOf(
                ContentItem.Text("""{"a":1}"""),
                ContentItem.Text("plain prose"),
                imageItem(byteArrayOf(1, 2, 3)),
            ),
        )

        val content = result.contentDataJson().getValue("content") as JsonArray
        assertEquals(3, content.size)
        val first = content[0].jsonObject
        assertTrue("json" in first && "text" !in first, "expected json-shaped: $first")
        assertEquals(1, first.getValue("json").jsonObject.getValue("a").jsonPrimitive.int)
        val second = content[1].jsonObject
        assertTrue("text" in second && "json" !in second, "expected text-shaped: $second")
        assertEquals("image", content[2].jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `the envelope frame tool command isError data is unchanged when a payload unpacks as json`() {
        val out = CapturedStream()
        val exit = Presentation.Json().render(textResult("""{"windows":[]}"""), "list_windows", out.stream)

        assertEquals(CliExit.OK, exit)
        val envelope = parseJson.parseToJsonElement(out.text()).jsonObject
        assertEquals(setOf("tool", "command", "isError", "data"), envelope.keys)
        assertEquals("devrig", envelope.getValue("tool").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("list_windows", envelope.getValue("command").jsonPrimitive.content)
        assertEquals(false, envelope.getValue("isError").jsonPrimitive.boolean)
    }

    @Test
    fun `console rendering of a json-shaped payload is untouched, printing the raw text verbatim`() {
        val out = CapturedStream()
        val payload = """{"windows":[{"windowId":"w1"}]}"""

        val exit = Presentation.Console { tempDir }.render(textResult(payload), "list_windows", out.stream)

        assertEquals(CliExit.OK, exit)
        assertEquals(payload + "\n", out.text())
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
    fun `console render does not throw on undecodable base64, prints a marker, and exits DATA_ERROR`() {
        val out = CapturedStream()
        val err = CapturedStream()
        val badResult = ToolCallResult(content = listOf(ContentItem.Image(data = "!!! not base64 !!!", mimeType = "image/png")))

        val exit = Presentation.Console { tempDir }.render(badResult, "take_screenshot", out.stream, err.stream)

        // Coherent with the --out path below: an undecodable image is DATA_ERROR whether or not
        // --out was passed, never a bare CliExit.OK that hides a corrupt payload.
        assertEquals(CliExit.DATA_ERROR, exit)
        assertTrue(out.text().contains("undecodable"))
        assertTrue(err.text().contains("not valid base64"))
        assertTrue(tempDir.listDirectoryEntries().isEmpty(), "no file should be written for undecodable data")
    }

    @Test
    fun `console render maps a filesystem write failure while saving an image to IO_ERROR`() {
        // imageDir() resolves to a plain file, not a directory: Files.createTempFile(dir, ...) fails
        // with NotDirectoryException (an IOException), the ordinary-render equivalent of the --out
        // write-failure case below. A filesystem failure must never surface as a plain USAGE exit
        // through the last-resort handler in Main.kt.
        val notADirectory = tempDir.resolve("not-a-directory")
        Files.write(notADirectory, byteArrayOf(0))
        val out = CapturedStream()
        val err = CapturedStream()

        val exit = Presentation.Console { notADirectory }.render(imageResult(), "take_screenshot", out.stream, err.stream)

        assertEquals(CliExit.IO_ERROR, exit)
        assertTrue(err.text().contains("failed to write"), err.text())
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
    fun `--out writes the decoded image bytes, reports savedOut, and strips its base64 from the json envelope`() {
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
        // Asking for a file must not also put the image's bytes on stdout: the written image is the
        // only content item, so it disappears from the envelope entirely.
        val content = envelope.getValue("data").jsonObject.getValue("content") as JsonArray
        assertTrue(content.isEmpty(), "expected the written image to be removed from content: $content")
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
    fun `--out with two images writes only the first, and the second still rides along in the json envelope`() {
        // Reachable via execute_code: a script's own logImage plus a dialog-failure screenshot yield
        // two images in one result. Only the one actually written to --out may disappear.
        val outPath = tempDir.resolve("ok.png")
        val out = CapturedStream()
        val firstBytes = byteArrayOf(1, 1, 1)
        val secondBytes = byteArrayOf(2, 2, 2)
        val result = ToolCallResult(content = listOf(imageItem(firstBytes), imageItem(secondBytes)))

        val exit = renderWithOut(Presentation.Json(), result, "execute_code", outPath, out.stream)

        assertEquals(CliExit.OK, exit)
        assertTrue(firstBytes.contentEquals(Files.readAllBytes(outPath)), "the FIRST image must be the one written to --out")
        val envelope = parseJson.parseToJsonElement(out.text()).jsonObject
        val content = envelope.getValue("data").jsonObject.getValue("content") as JsonArray
        assertEquals(1, content.size, "the first image is removed; the second must survive: $content")
        assertEquals(Base64.getEncoder().encodeToString(secondBytes), content[0].jsonObject.getValue("data").jsonPrimitive.content)
    }

    @Test
    fun `--out with two images on the console writes only the first to --out and still saves the second under tmpDir`() {
        val outPath = tempDir.resolve("ok.png")
        val out = CapturedStream()
        val firstBytes = byteArrayOf(1, 1, 1)
        val secondBytes = byteArrayOf(2, 2, 2)
        val result = ToolCallResult(content = listOf(imageItem(firstBytes), imageItem(secondBytes)))

        val exit = renderWithOut(Presentation.Console { tempDir }, result, "execute_code", outPath, out.stream)

        assertEquals(CliExit.OK, exit)
        assertTrue(firstBytes.contentEquals(Files.readAllBytes(outPath)))
        // The second image must not be silently dropped: it still goes through the normal render path
        // and lands under tmpDir, exactly as it would with no --out at all.
        val savedUnderTmp = tempDir.listDirectoryEntries().filterNot { it == outPath }
        assertEquals(1, savedUnderTmp.size, "expected exactly one extra file for the second image: $savedUnderTmp")
        assertTrue(secondBytes.contentEquals(Files.readAllBytes(savedUnderTmp.single())))
    }

    @Test
    fun `--out with no image on an otherwise-successful result is a DATA_ERROR under --json`() {
        val outPath = tempDir.resolve("x.png")
        val out = CapturedStream()
        val exit = renderWithOut(
            Presentation.Json(),
            textResult("all good, no dialog appeared", isError = false),
            "execute_code",
            outPath,
            out.stream,
        )

        // The user explicitly asked for an image and the tool succeeded without producing one: that unmet
        // expectation is a DATA_ERROR, never a bare CliExit.OK that hides the missing file.
        assertEquals(CliExit.DATA_ERROR, exit)
        assertTrue(!Files.exists(outPath), "nothing should be written when there is no image")
        val envelope = parseJson.parseToJsonElement(out.text()).jsonObject
        assertEquals(true, envelope.getValue("isError").jsonPrimitive.boolean)
        assertTrue(firstTextOf(envelope.getValue("data").jsonObject.getValue("content")).contains("no image"))
    }

    @Test
    fun `--out with no image but a failed tool result surfaces the tool's own error, not a --out miss`() {
        // A14 (D7): when the tool itself FAILED and returned no image, its error is what the user needs.
        // Masking it with "result carries no image" hides the message; --out must step aside and let the
        // tool's own error render verbatim, at TOOL_ERROR rather than a --out DATA_ERROR.
        val outPath = tempDir.resolve("x.png")
        val out = CapturedStream()
        val exit = renderWithOut(
            Presentation.Json(),
            textResult("compilation failed: unresolved reference foo", isError = true),
            "execute_code",
            outPath,
            out.stream,
        )

        assertEquals(CliExit.TOOL_ERROR, exit)
        assertTrue(!Files.exists(outPath), "nothing should be written when there is no image")
        val envelope = parseJson.parseToJsonElement(out.text()).jsonObject
        assertEquals(true, envelope.getValue("isError").jsonPrimitive.boolean)
        val text = firstTextOf(envelope.getValue("data").jsonObject.getValue("content"))
        assertTrue("unresolved reference foo" in text, "the tool's own error must survive --out; got: $text")
        assertTrue("no image" !in text, "the --out miss must not mask the tool error; got: $text")
    }

    @Test
    fun `--out normalizes the saved path, collapsing dot-dot segments to a single absolute form`() {
        // A14 (D12): the parent used for mkdir, the file written, and the reported savedOut are all one
        // normalized path — a caller passing `sub/../ok.png` gets `ok.png`, never a savedOut carrying `..`.
        val outPath = tempDir.resolve("sub/../ok.png")
        val out = CapturedStream()
        val bytes = byteArrayOf(4, 5, 6)

        val exit = renderWithOut(Presentation.Json(), imageResult(bytes), "take_screenshot", outPath, out.stream)

        assertEquals(CliExit.OK, exit)
        val normalized = tempDir.resolve("ok.png").toAbsolutePath()
        val savedOut = parseJson.parseToJsonElement(out.text()).jsonObject
            .getValue("data").jsonObject.getValue("savedOut").jsonPrimitive.content
        assertEquals(normalized.toString(), savedOut)
        assertTrue(".." !in savedOut, "savedOut must be normalized: $savedOut")
        assertTrue(bytes.contentEquals(Files.readAllBytes(normalized)), "the normalized target is the file actually written")
        // The un-normalized `sub` segment must not be materialized as a spurious directory.
        assertTrue(!Files.exists(tempDir.resolve("sub")), "no stray directory from the un-normalized parent")
    }

    @Test
    fun `--out replaces an existing file atomically, leaving only the new bytes`() {
        val outPath = tempDir.resolve("existing.png")
        Files.write(outPath, byteArrayOf(0, 0, 0, 0))
        val out = CapturedStream()
        val newBytes = byteArrayOf(7, 8, 9)

        val exit = renderWithOut(Presentation.Json(), imageResult(newBytes), "take_screenshot", outPath, out.stream)

        assertEquals(CliExit.OK, exit)
        assertTrue(newBytes.contentEquals(Files.readAllBytes(outPath)), "the atomic write must replace the existing file")
        // The staging temp file must not survive a successful write.
        assertEquals(listOf(outPath), tempDir.listDirectoryEntries())
    }

    @Test
    fun `--out with no image in the result is a DATA_ERROR on the console, with the diagnostic printed exactly once`() {
        val outPath = tempDir.resolve("x.png")
        val out = CapturedStream()
        val err = CapturedStream()

        val exit = renderWithOut(Presentation.Console { tempDir }, textResult("all good"), "execute_code", outPath, out.stream, err.stream)

        assertEquals(CliExit.DATA_ERROR, exit)
        assertTrue(!Files.exists(outPath))
        assertEquals(1, countOccurrences(err.text(), "no image"), "expected exactly one diagnostic, got: ${err.text()}")
    }

    @Test
    fun `--out with an undecodable image payload is a DATA_ERROR whose message reaches the caller exactly once`() {
        val outPath = tempDir.resolve("x.png")
        val badResult = ToolCallResult(content = listOf(ContentItem.Image(data = "!!! not base64 !!!", mimeType = "image/png")))

        val jsonOut = CapturedStream()
        val jsonExit = renderWithOut(Presentation.Json(), badResult, "take_screenshot", outPath, jsonOut.stream)
        assertEquals(CliExit.DATA_ERROR, jsonExit)
        assertTrue(!Files.exists(outPath))
        val envelope = parseJson.parseToJsonElement(jsonOut.text()).jsonObject
        assertTrue(firstTextOf(envelope.getValue("data").jsonObject.getValue("content")).contains("not valid base64"))

        val consoleOut = CapturedStream()
        val consoleErr = CapturedStream()
        val consoleExit = renderWithOut(Presentation.Console { tempDir }, badResult, "take_screenshot", outPath, consoleOut.stream, consoleErr.stream)
        assertEquals(CliExit.DATA_ERROR, consoleExit)
        assertEquals(1, countOccurrences(consoleErr.text(), "not valid base64"), "expected exactly one diagnostic, got: ${consoleErr.text()}")
    }

    @Test
    fun `--out to an unwritable path is an IO_ERROR whose message reaches the caller exactly once`() {
        // tempDir itself is a directory: the atomic move that finishes the write cannot land a file onto an
        // existing directory (rename fails EISDIR), a genuine write IOException distinct from the
        // undecodable-payload DATA_ERROR case above.
        val jsonOut = CapturedStream()
        val jsonExit = renderWithOut(Presentation.Json(), imageResult(), "take_screenshot", tempDir, jsonOut.stream)
        assertEquals(CliExit.IO_ERROR, jsonExit)
        val envelope = parseJson.parseToJsonElement(jsonOut.text()).jsonObject
        assertTrue(firstTextOf(envelope.getValue("data").jsonObject.getValue("content")).contains("failed to write"))

        val consoleOut = CapturedStream()
        val consoleErr = CapturedStream()
        val consoleExit = renderWithOut(Presentation.Console { tempDir }, imageResult(), "take_screenshot", tempDir, consoleOut.stream, consoleErr.stream)
        assertEquals(CliExit.IO_ERROR, consoleExit)
        assertEquals(1, countOccurrences(consoleErr.text(), "failed to write"), "expected exactly one diagnostic, got: ${consoleErr.text()}")
    }

    // ------------------------------ steroid_ → devrig CLI translation ------------------------------

    @Test
    fun `the CLI name translation rewrites every steroid_ tool mention to its devrig command`() {
        assertEquals("devrig list_projects", steroidToolNamesToDevrigCli("steroid_list_projects"))
        assertEquals("devrig execute_code", steroidToolNamesToDevrigCli("steroid_execute_code"))
        // Underscores INSIDE the tool name survive; only the prefix is replaced.
        assertEquals(
            "run devrig list_projects to refresh",
            steroidToolNamesToDevrigCli("run steroid_list_projects to refresh"),
        )
        // Two mentions in one string are both translated.
        assertEquals(
            "devrig open_project then devrig take_screenshot",
            steroidToolNamesToDevrigCli("steroid_open_project then steroid_take_screenshot"),
        )
    }

    @Test
    fun `the CLI name translation leaves the mcp-steroid resource scheme and base64 payloads untouched`() {
        // `mcp-steroid://` has no underscore after `steroid`, so the resource scheme is never mangled.
        val uri = "mcp-steroid://skill/design-philosophy"
        assertEquals(uri, steroidToolNamesToDevrigCli(uri))
        // Base64 cannot contain `_`, so an image payload can never match — assert a representative sample is inert.
        val b64 = Base64.getEncoder().encodeToString("steroid".toByteArray())
        assertEquals(b64, steroidToolNamesToDevrigCli(b64))
    }

    @Test
    fun `a tool result that names a steroid_ tool renders as devrig under --json, never leaking the MCP name`() {
        val out = CapturedStream()
        val result = textResult("project_name is gone — call steroid_list_projects to refresh", isError = true)

        Presentation.Json().render(result, "open_project", out.stream)

        val text = out.text()
        assertTrue("steroid_" !in text, "no MCP tool name may reach CLI output: $text")
        assertTrue("devrig list_projects" in text, "the MCP name must render as its devrig command: $text")
    }

    @Test
    fun `a tool result that names a steroid_ tool renders as devrig on the console, never leaking the MCP name`() {
        val out = CapturedStream()
        val err = CapturedStream()
        val result = textResult("no candidates — start an IDE or call steroid_list_projects.", isError = true)

        Presentation.Console { tempDir }.render(result, "open_project", out.stream, err.stream)

        // An error result prints to stderr; assert on the stream that actually carried it.
        val text = err.text()
        assertTrue("steroid_" !in text, "no MCP tool name may reach CLI output: $text")
        assertTrue("devrig list_projects" in text, "the MCP name must render as its devrig command: $text")
    }

    @Test
    fun `a CLI-level error message naming a steroid_ tool is translated in both presentations`() {
        val message = "unknown project_name — run steroid_list_projects to refresh"

        val jsonOut = CapturedStream()
        Presentation.Json().renderError("open_project", message, CliExit.USAGE, jsonOut.stream)
        assertTrue("steroid_" !in jsonOut.text(), "json renderError leaked an MCP name: ${jsonOut.text()}")
        assertTrue("devrig list_projects" in jsonOut.text())

        val consoleErr = CapturedStream()
        Presentation.Console { tempDir }.renderError("open_project", message, CliExit.USAGE, CapturedStream().stream, consoleErr.stream)
        assertTrue("steroid_" !in consoleErr.text(), "console renderError leaked an MCP name: ${consoleErr.text()}")
        assertTrue("devrig list_projects" in consoleErr.text())
    }
}
