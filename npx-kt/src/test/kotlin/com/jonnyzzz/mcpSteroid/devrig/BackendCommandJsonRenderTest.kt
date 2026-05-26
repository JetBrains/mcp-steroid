/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.McpSteroidServerInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIdeByPort
import com.jonnyzzz.mcpSteroid.server.ProjectInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path

/** Pins the schema of `devrig backend --json`. */
class BackendCommandJsonRenderTest {

    private val parser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun render(rows: List<BackendRow>): JsonObject {
        val buf = ByteArrayOutputStream()
        renderBackendJson(rows, PrintStream(buf, true, Charsets.UTF_8))
        val text = buf.toString(Charsets.UTF_8)
        return parser.parseToJsonElement(text).jsonObject
    }

    private fun backendIds(root: JsonObject): List<String> =
        root["backends"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    private fun jsonStrings(element: JsonElement): List<String> = when (element) {
        is JsonObject -> element.values.flatMap(::jsonStrings)
        is JsonArray -> element.flatMap(::jsonStrings)
        is JsonPrimitive -> element.contentOrNull?.let { listOf(it) }.orEmpty()
    }

    private fun captureBackendCommandLogs(action: () -> JsonObject): Pair<JsonObject, List<ILoggingEvent>> {
        val logger = LoggerFactory.getLogger("com.jonnyzzz.mcpSteroid.devrig.BackendCommand") as Logger
        val appender = ListAppender<ILoggingEvent>().apply {
            context = logger.loggerContext
            start()
        }
        logger.addAppender(appender)
        try {
            return action() to appender.list.toList()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    private fun markerIde(
        name: String = "IntelliJ IDEA",
        version: String = "2025.3.3",
        pid: Long = 1234L,
        build: String = "IU-253.21581.142",
        mcpUrl: String = "http://localhost:6315/mcp",
        token: String = "",
    ): DiscoveredIde {
        val ideInfo = IdeInfo(name = name, version = version, build = build)
        val pluginInfo = PluginInfo(id = "com.jonnyzzz.mcp-steroid", name = "MCP Steroid", version = "0.0.0-test")
        val marker = PidMarker(
            schema = PidMarker.SCHEMA_VERSION,
            pid = pid,
            mcpSteroidServer = McpSteroidServerInfo(
                mcpUrl = mcpUrl,
                port = 0,
                headers = mapOf("Authorization" to "Bearer $token"),
            ),
            ide = ideInfo,
            plugin = pluginInfo,
            createdAt = "1970-01-01T00:00:00Z",
            intellijWebServer = null,
            intellijMcpServer = null,
        )
        return DiscoveredIde(pid = pid, mcpUrl = mcpUrl, markerPath = "/tmp/$pid.mcp-steroid", marker = marker)
    }

    private fun portIde(
        port: Int = 63342,
        productFullName: String? = "IntelliJ IDEA Ultimate",
        productName: String? = "IDEA",
        buildNumber: String? = "IU-253.21581.142",
        baselineVersion: Int? = 253,
        edition: String? = "IU",
    ) = DiscoveredIdeByPort(
        port = port,
        baseUrl = "http://127.0.0.1:$port",
        productName = productName,
        productFullName = productFullName,
        edition = edition,
        baselineVersion = baselineVersion,
        buildNumber = buildNumber,
    )

    private fun managedInfo(
        id: String = "idea-community-2025.2.6.2",
        state: ManagedBackendState = ManagedBackendState.INSTALLED,
        runningPid: Long? = null,
    ) = ManagedBackendInfo(
        id = id,
        productKey = id.substringBeforeLast('-'),
        productCode = "IC",
        version = id.substringAfterLast('-'),
        buildNumber = "IC-252.1",
        installPath = Path.of("/managed/$id"),
        cachePath = Path.of("/caches/$id"),
        runningPid = runningPid,
        state = state,
    )

    // ----------------------------- top-level shape -------------------------

    @Test
    fun `output is valid JSON with exactly tool backends and projects at the top level`() {
        val root = render(emptyList())
        assertEquals(setOf("tool", "backends", "projects"), root.keys)
        val tool = root["tool"]!!.jsonObject
        assertEquals("devrig", tool["name"]?.jsonPrimitive?.contentOrNull)
        assertNotNull(tool["version"]?.jsonPrimitive?.contentOrNull,
            "tool.version must be non-null so scripts can correlate against changelog")
    }

    @Test
    fun `empty list produces empty backends and projects arrays`() {
        val root = render(emptyList())
        assertEquals(0, root["backends"]!!.jsonArray.size,
            "empty discovery must serialise as [] so consumers can always iterate")
        assertEquals(0, root["projects"]!!.jsonArray.size,
            "no reachable marker backends means no projects")
    }

    @Test
    fun `every backend entry carries the intellij type discriminator`() {
        val rows = listOf(
            BackendRow.FromMarker(markerIde(pid = 1L), emptyList()),
            BackendRow.FromPort(portIde(port = 63342)),
            BackendRow.FromMarker(markerIde(pid = 2L), null, errorMessage = "x"),
        )
        val backends = render(rows)["backends"]!!.jsonArray
        assertEquals(rows.size, backends.size)
        assertTrue(backends.all { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "intellij" },
            "every current backend is an IntelliJ-family backend: $backends")
    }

    // -------------------------- marker variant -----------------------------

    @Test
    fun `marker row serialises common fields plus pid mcpUrl and flat projects`() {
        val row = BackendRow.FromMarker(
            ide = markerIde(pid = 1234, mcpUrl = "http://localhost:6315/mcp"),
            projects = listOf(ProjectInfo("my-app", "/Users/x/my-app")),
        )
        val root = render(listOf(row))
        val backend = root["backends"]!!.jsonArray.single().jsonObject

        assertEquals("pid-1234", backend["id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("intellij", backend["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("marker", backend["source"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, backend["pluginInstalled"]?.jsonPrimitive?.boolean)
        assertEquals(true, backend["reachable"]?.jsonPrimitive?.boolean)
        assertEquals(backendDisplayName(row), backend["displayName"]?.jsonPrimitive?.contentOrNull)
        assertEquals(backendLocatorLabel(row), backend["locator"]?.jsonPrimitive?.contentOrNull)
        assertEquals("IntelliJ IDEA", backend["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("2025.3.3", backend["version"]?.jsonPrimitive?.contentOrNull)
        assertEquals("IU-253.21581.142", backend["build"]?.jsonPrimitive?.contentOrNull)
        assertEquals("IU-253.21581.142", backend["buildNumber"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1234L, backend["pid"]?.jsonPrimitive?.long)
        assertEquals("http://localhost:6315/mcp", backend["mcpUrl"]?.jsonPrimitive?.contentOrNull)
        val plugin = backend["plugin"]!!.jsonObject
        assertEquals(true, plugin["installed"]?.jsonPrimitive?.boolean)
        assertEquals("com.jonnyzzz.mcp-steroid", plugin["id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("MCP Steroid", plugin["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("0.0.0-test", plugin["version"]?.jsonPrimitive?.contentOrNull)
        assertEquals(0, backend["actions"]!!.jsonArray.size)
        assertNull(backend["error"], "reachable marker rows must not carry an error: $backend")
        assertNull(backend["projects"], "projects are top-level now, not nested in backends: $backend")

        val projects = root["projects"]!!.jsonArray
        assertEquals(1, projects.size)
        val p = projects.single().jsonObject
        assertEquals("pid-1234", p["backend"]?.jsonPrimitive?.contentOrNull)
        assertEquals("my-app", p["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("/Users/x/my-app", p["path"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `marker row with empty projects list is reachable and contributes no project rows`() {
        val row = BackendRow.FromMarker(markerIde(), projects = emptyList())
        val root = render(listOf(row))
        val backend = root["backends"]!!.jsonArray.single().jsonObject
        assertEquals(true, backend["reachable"]?.jsonPrimitive?.boolean)
        assertNull(backend["error"], "empty projects is a successful snapshot, not an error")
        assertEquals(0, root["projects"]!!.jsonArray.size)
    }

    @Test
    fun `unreachable marker row serialises reachable=false error and no project references`() {
        val row = BackendRow.FromMarker(markerIde(), projects = null, errorMessage = "connect refused")
        val root = render(listOf(row))
        val backend = root["backends"]!!.jsonArray.single().jsonObject
        val backendId = backend["id"]!!.jsonPrimitive.content
        assertEquals("marker", backend["source"]?.jsonPrimitive?.contentOrNull)
        assertEquals(false, backend["reachable"]?.jsonPrimitive?.boolean)
        assertEquals("connect refused", backend["error"]?.jsonPrimitive?.contentOrNull)
        assertNull(backend["projects"], "unreachable state is reachable=false + error, not projects=null")
        val projectRefs = root["projects"]!!.jsonArray.map { it.jsonObject["backend"]!!.jsonPrimitive.content }
        assertTrue(backendId !in projectRefs,
            "unreachable marker backend must not contribute projects; refs=$projectRefs backend=$backend")
    }

    @Test
    fun `unreachable marker row with null errorMessage gets a non-null fallback error string`() {
        val row = BackendRow.FromMarker(markerIde(), projects = null, errorMessage = null)
        val backend = render(listOf(row))["backends"]!!.jsonArray.single().jsonObject
        val error = backend["error"]?.jsonPrimitive?.contentOrNull
        assertNotNull(error, "error must always be a string when reachable=false")
        assertTrue(error!!.isNotBlank(), "fallback error must be non-empty")
    }

    // --------------------------- port variant ------------------------------

    @Test
    fun `port row serialises common fields plus port baseUrl about-fields and no marker payload`() {
        val row = BackendRow.FromPort(portIde(port = 63342))
        val backend = render(listOf(row))["backends"]!!.jsonArray.single().jsonObject

        assertEquals("port-63342", backend["id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("intellij", backend["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("port", backend["source"]?.jsonPrimitive?.contentOrNull)
        assertEquals(false, backend["pluginInstalled"]?.jsonPrimitive?.boolean)
        assertEquals(true, backend["reachable"]?.jsonPrimitive?.boolean)
        assertEquals(backendDisplayName(row), backend["displayName"]?.jsonPrimitive?.contentOrNull)
        assertEquals(backendLocatorLabel(row), backend["locator"]?.jsonPrimitive?.contentOrNull)
        assertEquals(63342, backend["port"]?.jsonPrimitive?.int)
        assertEquals("http://127.0.0.1:63342", backend["baseUrl"]?.jsonPrimitive?.contentOrNull)
        assertEquals("IDEA", backend["productName"]?.jsonPrimitive?.contentOrNull)
        assertEquals("IntelliJ IDEA Ultimate", backend["productFullName"]?.jsonPrimitive?.contentOrNull)
        assertEquals("IU", backend["edition"]?.jsonPrimitive?.contentOrNull)
        assertEquals(253, backend["baselineVersion"]?.jsonPrimitive?.int)
        assertEquals("IU-253.21581.142", backend["buildNumber"]?.jsonPrimitive?.contentOrNull)
        assertEquals(false, backend["plugin"]!!.jsonObject["installed"]?.jsonPrimitive?.boolean)
        val action = backend["actions"]!!.jsonArray.single().jsonObject
        assertEquals("provision", action["id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Install MCP Steroid plugin", action["label"]?.jsonPrimitive?.contentOrNull)
        assertEquals("devrig backend provision port-63342", action["command"]?.jsonPrimitive?.contentOrNull)
        assertNull(backend["pid"], "port row must not carry a pid: $backend")
        assertNull(backend["projects"], "port row must not carry projects: $backend")
        assertNull(backend["mcpUrl"], "port row must not carry mcpUrl: $backend")
        assertNull(backend["name"], "port row must not carry marker-only name: $backend")
        assertNull(backend["version"], "port row must not carry marker-only version: $backend")
        assertNull(backend["build"], "port row must not carry marker-only build: $backend")
    }

    @Test
    fun `port row omits about-fields that came back as null from api-about`() {
        val row = BackendRow.FromPort(portIde(productFullName = null, edition = null, baselineVersion = null))
        val backend = render(listOf(row))["backends"]!!.jsonArray.single().jsonObject
        assertNull(backend["productFullName"], "absent fields must NOT serialise as null: $backend")
        assertNull(backend["edition"], "absent fields must NOT serialise as null: $backend")
        assertNull(backend["baselineVersion"], "absent fields must NOT serialise as null: $backend")
        assertEquals("IDEA", backend["productName"]?.jsonPrimitive?.contentOrNull)
    }

    // ---------------------------- mixed list -------------------------------

    @Test
    fun `mixed list keeps input order and assigns backend ids from natural identifiers`() {
        val rows = listOf(
            BackendRow.FromMarker(markerIde(pid = 1L), emptyList()),
            BackendRow.FromPort(portIde(port = 63342)),
            BackendRow.FromMarker(markerIde(pid = 2L), null, errorMessage = "x"),
        )
        val backends = render(rows)["backends"]!!.jsonArray
        assertEquals(3, backends.size)
        assertEquals(listOf("pid-1", "port-63342", "pid-2"),
            backends.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals("marker", backends[0].jsonObject["source"]?.jsonPrimitive?.contentOrNull)
        assertEquals("port", backends[1].jsonObject["source"]?.jsonPrimitive?.contentOrNull)
        assertEquals("marker", backends[2].jsonObject["source"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `top-level projects reference valid backend ids`() {
        val rows = listOf(
            BackendRow.FromMarker(markerIde(pid = 1L), listOf(ProjectInfo("a", "/a"), ProjectInfo("b", "/b"))),
            BackendRow.FromPort(portIde(port = 63342)),
            BackendRow.FromMarker(markerIde(pid = 2L), listOf(ProjectInfo("c", "/c"))),
        )
        val root = render(rows)
        val ids = root["backends"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
        val refs = root["projects"]!!.jsonArray.map { it.jsonObject["backend"]!!.jsonPrimitive.content }
        assertEquals(listOf("pid-1", "pid-1", "pid-2"), refs)
        assertTrue(refs.all { it in ids }, "every projects[].backend must resolve to a backend id; ids=$ids refs=$refs")
    }

    @Test
    fun `backend ids use natural identifier for marker port and managed rows`() {
        val rows = listOf(
            BackendRow.FromMarker(markerIde(pid = 4242L), listOf(ProjectInfo("known", "/known"))),
            BackendRow.FromPort(portIde(port = 65432)),
            BackendRow.FromManaged(managedInfo(id = "idea-community-2025.2.6.2")),
        )

        val root = render(rows)

        assertEquals(listOf("pid-4242", "port-65432", "idea-community-2025.2.6.2"), backendIds(root))
        val projects = root["projects"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("pid-4242"), projects.map { it["backend"]!!.jsonPrimitive.content })
    }

    @Test
    fun `json document contains no synthetic backend prefix strings`() {
        val root = render(
            listOf(
                BackendRow.FromMarker(markerIde(pid = 4242L), listOf(ProjectInfo("known", "/known"))),
                BackendRow.FromPort(portIde(port = 65432)),
                BackendRow.FromManaged(managedInfo(id = "idea-community-2025.2.6.2")),
            ),
        )

        val syntheticValues = jsonStrings(root).filter { it.startsWith("backend-") }
        assertEquals(emptyList<String>(), syntheticValues)
    }

    @Test
    fun `backend ids stay stable when discovery rows are reordered`() {
        val marker = BackendRow.FromMarker(markerIde(pid = 200L), emptyList())
        val port = BackendRow.FromPort(portIde(port = 65432))
        val managed = BackendRow.FromManaged(managedInfo(id = "idea-community-2025.2.6.2"))
        val expected = mapOf(
            "marker" to "pid-200",
            "port" to "port-65432",
            "managed" to "idea-community-2025.2.6.2",
        )

        fun idsBySource(rows: List<BackendRow>) = render(rows)["backends"]!!.jsonArray.associate { element ->
            val backend = element.jsonObject
            backend["source"]!!.jsonPrimitive.content to backend["id"]!!.jsonPrimitive.content
        }

        assertEquals(expected, idsBySource(listOf(marker, port, managed)))
        assertEquals(expected, idsBySource(listOf(managed, marker, port)))
    }

    @Test
    fun `duplicate stable ids log warning and keep first row`() {
        val first = BackendRow.FromMarker(markerIde(pid = 777L), listOf(ProjectInfo("first", "/first")))
        val second = BackendRow.FromMarker(
            markerIde(pid = 777L, mcpUrl = "http://localhost:7778/mcp"),
            listOf(ProjectInfo("second", "/second")),
        )

        val (root, events) = captureBackendCommandLogs { render(listOf(first, second)) }

        val ids = backendIds(root)
        assertEquals(1, ids.count { it == "pid-777" })
        assertEquals(listOf("pid-777"), ids)
        val projects = root["projects"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("first"), projects.map { it["name"]!!.jsonPrimitive.content })
        assertEquals(listOf("pid-777"), projects.map { it["backend"]!!.jsonPrimitive.content })

        val warnMessages = events.filter { it.level == Level.WARN }.map { it.formattedMessage }
        assertTrue(
            warnMessages.any { it.contains("Duplicate backend ids in backend --json output") && it.contains("pid-777") },
            "expected a WARN mentioning duplicate pid-777; got $warnMessages",
        )
    }

    @Test
    fun `output has no banner -- pure JSON on stdout for safe piping`() {
        val buf = ByteArrayOutputStream()
        renderBackendJson(emptyList(), PrintStream(buf, true, Charsets.UTF_8))
        val text = buf.toString(Charsets.UTF_8).trim()
        assertTrue(text.startsWith("{"),
            "output must start with '{' (the JSON document); got first 60 chars: '${text.take(60)}'")
        assertTrue(text.endsWith("}"),
            "output must end with '}' — no trailing banner allowed; got last 60 chars: '${text.takeLast(60)}'")
    }
}
