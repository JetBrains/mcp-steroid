/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path

class BackendCommandActionJsonTest {
    private val originalErr = System.err

    @AfterEach
    fun restoreErr() {
        System.setErr(originalErr)
    }

    @Test
    fun `download action json success has the stable schema`(@TempDir tempDir: Path) {
        val homePaths = HomePaths(tempDir)
        val backendService = FakeBackendService(
            downloadResult = downloadResult(homePaths, "idea-community-2025.3.3"),
        )

        val (exit, root, stderr) = runJsonAction {
            runBackendDownloadCommand(
                out = it,
                homePaths = homePaths,
                command = downloadCommand("idea-community"),
                backendService = backendService,
            )
        }

        assertEquals(0, exit)
        assertEquals("", stderr)
        assertEquals(setOf("tool", "action", "id", "productKey", "version", "installPath", "cachePath", "vmoptionsPath", "downloadDurationMs"), root.keys)
        assertEquals("download", root["action"]!!.jsonPrimitive.content)
        assertEquals("idea-community-2025.3.3", root["id"]!!.jsonPrimitive.content)
        assertEquals("idea-community", root["productKey"]!!.jsonPrimitive.content)
        assertEquals("2025.3.3", root["version"]!!.jsonPrimitive.content)
        assertEquals(homePaths.backendDir("idea-community-2025.3.3").toString(), root["installPath"]!!.jsonPrimitive.content)
        assertEquals(homePaths.cacheDir("idea-community-2025.3.3").toString(), root["cachePath"]!!.jsonPrimitive.content)
        assertEquals(homePaths.backendDir("idea-community-2025.3.3").resolve("idea-IIC.vmoptions").toString(), root["vmoptionsPath"]!!.jsonPrimitive.content)
        assertTrue(root["downloadDurationMs"]!!.jsonPrimitive.long >= 0L)
    }

    @Test
    fun `start action json success has the stable schema`(@TempDir tempDir: Path) {
        val homePaths = HomePaths(tempDir)
        backendFixture(homePaths, "idea-community-2025.3.3", productKey = "idea-community", productCode = "IC", version = "2025.3.3")
        val backendService = FakeBackendService(
            startResult = StartResult(
                id = "idea-community-2025.3.3",
                pid = 12345L,
                ideaLogPath = homePaths.cacheDir("idea-community-2025.3.3").resolve("logs/managed.log"),
                configPath = homePaths.cacheDir("idea-community-2025.3.3").resolve("config"),
            ),
        )

        val (exit, root, stderr) = runJsonAction {
            runBackendStartCommand(
                out = it,
                homePaths = homePaths,
                command = startCommand("idea-community-2025.3.3"),
                backendService = backendService,
            )
        }

        assertEquals(0, exit)
        assertEquals("", stderr)
        assertEquals(setOf("tool", "action", "id", "pid", "logPath", "configPath", "vmoptionsPath"), root.keys)
        assertEquals("start", root["action"]!!.jsonPrimitive.content)
        assertEquals("idea-community-2025.3.3", root["id"]!!.jsonPrimitive.content)
        assertEquals(12345L, root["pid"]!!.jsonPrimitive.long)
        assertEquals(homePaths.cacheDir("idea-community-2025.3.3").resolve("logs/managed.log").toString(), root["logPath"]!!.jsonPrimitive.content)
        assertEquals(homePaths.cacheDir("idea-community-2025.3.3").resolve("config").toString(), root["configPath"]!!.jsonPrimitive.content)
        assertEquals(homePaths.backendDir("idea-community-2025.3.3").resolve("bundle-idea-community-2025.3.3.vmoptions").toString(), root["vmoptionsPath"]!!.jsonPrimitive.content)
    }

    @Test
    fun `stop action json success has the stable schema`(@TempDir tempDir: Path) {
        val homePaths = HomePaths(tempDir)
        val backendService = FakeBackendService(
            stopResult = StopResult(id = "idea-community-2025.3.3", pid = 12345L, outcome = "stopped"),
        )

        val (exit, root, stderr) = runJsonAction {
            runBackendStopCommand(
                out = it,
                homePaths = homePaths,
                command = stopCommand("idea-community-2025.3.3"),
                backendService = backendService,
            )
        }

        assertEquals(0, exit)
        assertEquals("", stderr)
        assertEquals(setOf("tool", "action", "id", "stoppedPid", "outcome", "graceful", "durationMs"), root.keys)
        assertEquals("stop", root["action"]!!.jsonPrimitive.content)
        assertEquals("idea-community-2025.3.3", root["id"]!!.jsonPrimitive.content)
        assertEquals(12345L, root["stoppedPid"]!!.jsonPrimitive.long)
        assertEquals("stopped", root["outcome"]!!.jsonPrimitive.content)
        assertEquals(true, root["graceful"]!!.jsonPrimitive.boolean)
        assertTrue(root["durationMs"]!!.jsonPrimitive.long >= 0L)
    }

    @Test
    fun `stop action json marks forced kill as not graceful`(@TempDir tempDir: Path) {
        val backendService = FakeBackendService(
            stopResult = StopResult(id = "idea-community-2025.3.3", pid = 12345L, outcome = "killed"),
        )

        val (exit, root, stderr) = runJsonAction {
            runBackendStopCommand(
                out = it,
                homePaths = HomePaths(tempDir),
                command = stopCommand("idea-community-2025.3.3"),
                backendService = backendService,
            )
        }

        assertEquals(0, exit)
        assertEquals("", stderr)
        assertEquals(false, root["graceful"]!!.jsonPrimitive.boolean)
        assertEquals("killed", root["outcome"]!!.jsonPrimitive.content)
    }

    @Test
    fun `stop action json exposes stale pid outcome and message`(@TempDir tempDir: Path) {
        val backendService = FakeBackendService(
            stopResult = StopResult(
                id = "idea-community-2025.3.3",
                pid = null,
                outcome = "stale",
                message = "pid 12345 is no longer the managed backend",
            ),
        )

        val (exit, root, stderr) = runJsonAction {
            runBackendStopCommand(
                out = it,
                homePaths = HomePaths(tempDir),
                command = stopCommand("idea-community-2025.3.3"),
                backendService = backendService,
            )
        }

        assertEquals(0, exit)
        assertEquals("", stderr)
        assertEquals(setOf("tool", "action", "id", "stoppedPid", "outcome", "graceful", "message", "durationMs"), root.keys)
        assertEquals(null, root["stoppedPid"]!!.jsonPrimitive.contentOrNull)
        assertEquals("stale", root["outcome"]!!.jsonPrimitive.content)
        assertEquals("pid 12345 is no longer the managed backend", root["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `json action errors are JSON on stdout with empty stderr`(@TempDir tempDir: Path) {
        val homePaths = HomePaths(tempDir)
        val failing = FakeBackendService(error = IllegalStateException("no plugin found"))

        val download = runJsonAction {
            runBackendDownloadCommand(
                out = it,
                homePaths = homePaths,
                command = downloadCommand("idea-community"),
                backendService = failing,
            )
        }
        val start = runJsonAction {
            runBackendStartCommand(
                out = it,
                homePaths = homePaths,
                command = startCommand("idea-community-2025.3.3"),
                backendService = failing,
            )
        }
        val stop = runJsonAction {
            runBackendStopCommand(
                out = it,
                homePaths = homePaths,
                command = stopCommand("idea-community-2025.3.3"),
                backendService = failing,
            )
        }

        for ((expectedAction, result) in listOf("download" to download, "start" to start, "stop" to stop)) {
            assertEquals(64, result.exitCode)
            assertEquals("", result.stderr, expectedAction)
            assertEquals(setOf("tool", "action", "id", "error", "exitCode"), result.json.keys)
            assertEquals(expectedAction, result.json["action"]!!.jsonPrimitive.content)
            assertEquals("no plugin found", result.json["error"]!!.jsonPrimitive.content)
            assertEquals(64, result.json["exitCode"]!!.jsonPrimitive.int)
            assertNotNull(result.json["id"]!!.jsonPrimitive.contentOrNull)
        }
    }

    private fun runJsonAction(action: (PrintStream) -> Int): JsonActionResult {
        val outBuf = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        System.setErr(PrintStream(errBuf, true, Charsets.UTF_8))
        val exit = action(PrintStream(outBuf, true, Charsets.UTF_8))
        val stdout = outBuf.toString(Charsets.UTF_8)
        return JsonActionResult(
            exitCode = exit,
            json = Json.parseToJsonElement(stdout).jsonObject,
            stderr = errBuf.toString(Charsets.UTF_8),
        )
    }

    private fun downloadCommand(id: String): DevrigCommand.DevrigCommandBackendDownload =
        DevrigCommand.DevrigCommandBackendDownload(id = id, json = true)

    private fun startCommand(id: String): DevrigCommand.DevrigCommandBackendStart =
        DevrigCommand.DevrigCommandBackendStart(id = id, json = true)

    private fun stopCommand(id: String): DevrigCommand.DevrigCommandBackendStop =
        DevrigCommand.DevrigCommandBackendStop(id = id, json = true)

    private fun downloadResult(homePaths: HomePaths, id: String) = DownloadResult(
        id = id,
        descriptor = BackendDescriptor(
            id = id,
            productKey = "idea-community",
            productCode = "IC",
            version = "2025.3.3",
            buildNumber = "IIC-253.1",
            bundleDirName = "idea-IIC",
            launcherPath = "bin/idea.sh",
            downloadedAt = "2026-05-14T21:00:00Z",
        ),
        backendDir = homePaths.backendDir(id),
        vmOptionsPath = homePaths.backendDir(id).resolve("idea-IIC.vmoptions"),
    )

    private data class JsonActionResult(
        val exitCode: Int,
        val json: kotlinx.serialization.json.JsonObject,
        val stderr: String,
    )

    private class FakeBackendService(
        private val downloadResult: DownloadResult? = null,
        private val startResult: StartResult? = null,
        private val stopResult: StopResult? = null,
        private val error: Exception? = null,
    ) : ManagedBackendService {
        override suspend fun download(id: BackendId): DownloadResult {
            error?.let { throw it }
            return downloadResult ?: throw IllegalStateException("missing fake download result")
        }

        override suspend fun start(id: BackendId): StartResult {
            error?.let { throw it }
            return startResult ?: throw IllegalStateException("missing fake start result")
        }

        override suspend fun stop(id: BackendId): StopResult {
            error?.let { throw it }
            return stopResult ?: throw IllegalStateException("missing fake stop result")
        }
    }
}
