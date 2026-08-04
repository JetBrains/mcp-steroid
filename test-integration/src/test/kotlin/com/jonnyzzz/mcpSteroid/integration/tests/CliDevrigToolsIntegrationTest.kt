/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.DevrigSteroidDriver
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.docker.mapGuestPathToHostPath
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Docker smoke test for the schema-driven `devrig` CLI (#284) against a REAL, already-running IDE —
 * exercising the three CLI tools that genuinely need one, not a fake/stub bridge:
 *
 *  - `list_windows --json`: the running project's window must be visible through the CLI's own JSON
 *    envelope, with `windows[0]` reachable from the SAME parse as the envelope's `tool`/`command`/
 *    `isError` fields — a `jq` consumer parses stdout exactly once.
 *  - `open_project --wait`: the CLI must poll `steroid_list_windows` itself and only return once the
 *    project reports ready (no modal, indexing settled), not merely once the open call was accepted.
 *  - `take_screenshot --out=<path>`: the CLI must write a real PNG to disk, not merely report success.
 *
 * Every assertion below is on the WHOLE parsed envelope/document, never a substring: three Phase-B
 * defects in an earlier draft of this suite survived substring assertions.
 *
 * Opt-in like every other Docker test in this module: `tasks.test` in `build.gradle.kts` already
 * refuses to run outside an explicit `:test-integration:` (or `ciIntegrationTests`) invocation — the
 * same blanket, Gradle-task-level gate `DevrigRealIdeBridgeIntegrationTest` and
 * `CLionMcpExecutionIntegrationTest` rely on. This class needs no extra gate beyond that: unlike
 * `testManagedBackendsTart` (which additionally requires the `tart` binary on an Apple-Silicon host),
 * a live IDE container is the ordinary precondition for anything under `tests/`, not a special one.
 *
 * ```
 * ./gradlew :test-integration:test --tests '*CliDevrigToolsIntegrationTest*'
 * ```
 */
class CliDevrigToolsIntegrationTest {

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `list_windows --json exposes the whole envelope with windows first entry reachable in one parse`() {
        val result = devrig("list_windows", "--json").assertExitCode(0) {
            "devrig list_windows --json\nstdout=$stdout\nstderr=$stderr"
        }

        // ONE parse for the whole assertion: a second document, a banner line, or a leaked progress
        // line on stdout would break `devrig list_windows --json | jq` the same way it would break this.
        val envelope = json.parseToJsonElement(result.stdout).jsonObject
        assertEquals("devrig", envelope.getValue("tool").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("list_windows", envelope.getValue("command").jsonPrimitive.content)
        assertEquals("false", envelope.getValue("isError").jsonPrimitive.content)

        val payload = envelope.getValue("data").jsonObject.getValue("content").jsonArray
            .single().jsonObject.getValue("json").jsonObject
        val windows = payload.getValue("windows").jsonArray
        assertTrue(windows.isNotEmpty(), "expected at least one open window in the envelope; envelope=$envelope")

        val firstWindow = windows[0].jsonObject
        assertEquals(
            guestProjectDir,
            firstWindow["project_path"]?.jsonPrimitive?.content,
            "windows[0] must be the already-open project; envelope=$envelope",
        )
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `open_project --wait returns only after the project reports ready`() {
        val result = devrig(
            "open_project",
            "--project_path=$guestProjectDir",
            "--task_id=cli-open-project-wait",
            "--reason=verify devrig open_project --wait polls list_windows until ready against a real IDE",
            "--wait",
            "--json",
            timeoutSeconds = 360,
        ).assertExitCode(0) { "devrig open_project --wait\nstdout=$stdout\nstderr=$stderr" }

        val envelope = json.parseToJsonElement(result.stdout).jsonObject
        assertEquals("devrig", envelope.getValue("tool").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("open_project", envelope.getValue("command").jsonPrimitive.content)
        assertEquals("false", envelope.getValue("isError").jsonPrimitive.content)

        // "--wait" only returns 0 once the CLI's own poll of steroid_list_windows observes readiness —
        // assert that readiness directly, rather than trusting the exit code alone.
        val listWindows = devrig("list_windows", "--json").assertExitCode(0) {
            "devrig list_windows --json (post open_project --wait)\nstdout=$stdout\nstderr=$stderr"
        }
        val windows = json.parseToJsonElement(listWindows.stdout).jsonObject
            .getValue("data").jsonObject.getValue("content").jsonArray
            .single().jsonObject.getValue("json").jsonObject.getValue("windows").jsonArray
        val readyWindow = windows.map { it.jsonObject }.singleOrNull { it["project_path"]?.jsonPrimitive?.content == guestProjectDir }
            ?: error("expected exactly one window for $guestProjectDir; windows=$windows")
        assertEquals("true", readyWindow["projectInitialized"]?.jsonPrimitive?.content, "windows=$windows")
        assertEquals("false", (readyWindow["indexingInProgress"]?.jsonPrimitive?.content ?: "false"), "windows=$windows")
        assertEquals("false", (readyWindow["modalDialogShowing"]?.jsonPrimitive?.content ?: "false"), "windows=$windows")
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `take_screenshot --out writes a real PNG to disk`() {
        val projectName = waitForCliProjectName()
        val guestOut = "/mcp-run-dir/cli-take-screenshot.png"

        devrig(
            "take_screenshot",
            "--project_name=$projectName",
            "--task_id=cli-take-screenshot",
            "--reason=verify devrig take_screenshot --out writes a real PNG against a real IDE",
            "--out=$guestOut",
            "--json",
        ).assertExitCode(0) { "devrig take_screenshot --out\nstdout=$stdout\nstderr=$stderr" }

        val hostFile = session.scope.mapGuestPathToHostPath(guestOut)
        assertTrue(hostFile.isFile && hostFile.length() > 0, "expected a non-empty PNG at ${hostFile.absolutePath}")

        val header = hostFile.inputStream().use { it.readNBytes(PNG_MAGIC.size) }
        assertTrue(
            header.contentEquals(PNG_MAGIC),
            "expected PNG magic bytes at ${hostFile.absolutePath}; got ${header.joinToString(",")}",
        )
    }

    // ------------------------------------------------------------------------------------------------

    private val guestProjectDir: String get() = session.intellijDriver.getGuestProjectDir()

    private fun devrig(vararg args: String, timeoutSeconds: Long = 180): ProcessResult =
        session.scope.startProcessInContainer {
            args(listOf(launcher) + args)
                .timeoutSeconds(timeoutSeconds)
                .description("devrig ${args.joinToString(" ")}")
        }.awaitForProcessFinish()

    /**
     * `take_screenshot` addresses a project by `project_name` (the routing key), not the filesystem
     * path used to open it — so this polls `list_projects --json` (the CLI's own routing list) rather
     * than assuming the two coincide, mirroring [DevrigRealIdeBridgeIntegrationTest.waitForProjectName].
     */
    private fun waitForCliProjectName(): String {
        repeat(80) {
            val result = devrig("list_projects", "--json")
            if (result.exitCode == 0) {
                val projects = json.parseToJsonElement(result.stdout).jsonObject
                    .getValue("data").jsonObject.getValue("content").jsonArray
                    .single().jsonObject.getValue("json").jsonObject.getValue("projects").jsonArray
                val first = projects.firstOrNull()?.jsonObject?.get("project_name")?.jsonPrimitive?.content
                if (first != null) return first
            }
            Thread.sleep(250)
        }
        error("Timed out waiting for `devrig list_projects` to discover the running IDE\n${session.diagnosticsSummary()}")
    }

    companion object {
        private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val json = Json { ignoreUnknownKeys = true }
        private val lifetime by lazy { CloseableStackHost(CliDevrigToolsIntegrationTest::class.java.simpleName) }
        private val session by lazy {
            IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "devrig CLI tools against a live IDE",
                aiMode = AiMode.NONE,
            )).waitForProjectReady()
        }
        private val launcher: String by lazy {
            DevrigSteroidDriver.deploy(session.scope, session.mcpSteroid).devrigCommand.command
        }

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            session.toString()
            check(launcher.isNotBlank()) { "devrig launcher path must not be blank" }
        }

        @AfterAll
        @JvmStatic
        fun cleanup() {
            lifetime.closeAllStacks()
        }
    }
}
