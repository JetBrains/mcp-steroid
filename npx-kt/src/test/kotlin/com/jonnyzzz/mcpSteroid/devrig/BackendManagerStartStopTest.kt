/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.McpSteroidServerInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PidMarkerJson
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackendManagerStartStopTest {

    @Test
    fun `start writes pid file and stop terminates gracefully`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, launcherBody = gracefulLauncher())
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            ideUserHome = tempDir.resolve("user-home"),
        )

        val started = manager.start(parseBackendId("idea-community-2025.3.3"))

        assertTrue(started.pid > 0)
        assertEquals("${started.pid}\n", Files.readString(homePaths.pidFile("idea-community-2025.3.3")))
        assertTrue(ProcessHandle.of(started.pid).orElseThrow().isAlive)
        val marker = PidMarker.markerDirectory(tempDir.resolve("user-home"))
            .resolve(PidMarker.markerFileNameFor(started.pid))
        Files.createDirectories(marker.parent)
        Files.writeString(marker, "marker")

        val stopped = manager.stop(parseBackendId("idea-community-2025.3.3"))

        assertEquals("stopped", stopped.outcome)
        assertFalse(homePaths.pidFile("idea-community-2025.3.3").exists())
        assertFalse(marker.exists())
        assertFalse(ProcessHandle.of(started.pid).map { it.isAlive }.orElse(false))
    }

    @Test
    fun `stop force kills a process that does not exit before the grace period`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, launcherBody = sleepyLauncher())
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            ideUserHome = tempDir.resolve("user-home"),
            stopGracePeriodMillis = 0L,
        )

        val started = manager.start(parseBackendId("idea-community-2025.3.3"))
        assertTrue(ProcessHandle.of(started.pid).orElseThrow().isAlive)
        val stopped = manager.stop(parseBackendId("idea-community-2025.3.3"))

        assertEquals("killed", stopped.outcome)
        assertFalse(homePaths.pidFile("idea-community-2025.3.3").exists())
        assertFalse(ProcessHandle.of(started.pid).map { it.isAlive }.orElse(false))
    }

    @Test
    fun `stop treats a missing pid file as successful not running`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val manager = BackendManager(HomePaths(tempDir.resolve("home")), downloader = StaticDownloader)

        val stopped = manager.stop(parseBackendId("idea-community-2025.3.3"))

        assertEquals("not running", stopped.outcome)
    }

    @Test
    fun `stop deletes stale pid file without signalling unrelated process`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, launcherBody = gracefulLauncher())
        val unrelated = startUnrelatedSleeper()
        try {
            Files.createDirectories(homePaths.stateDir)
            Files.writeString(homePaths.pidFile("idea-community-2025.3.3"), "${unrelated.pid()}\n")
            val manager = BackendManager(
                homePaths = homePaths,
                downloader = StaticDownloader,
                ideUserHome = tempDir.resolve("user-home"),
            )

            val stopped = manager.stop(parseBackendId("idea-community-2025.3.3"))

            assertEquals("stale", stopped.outcome)
            assertNull(stopped.pid)
            assertEquals("pid ${unrelated.pid()} is no longer the managed backend", stopped.message)
            assertFalse(homePaths.pidFile("idea-community-2025.3.3").exists())
            assertTrue(unrelated.isAlive, "unrelated process must not be signalled")
        } finally {
            stopProcess(unrelated)
        }
    }

    @Test
    fun `stop accepts matching pid marker for process outside backend directory`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, launcherBody = gracefulLauncher())
        val process = startUnrelatedSleeper()
        val userHome = tempDir.resolve("user-home")
        try {
            val markersDir = PidMarker.markerDirectory(userHome)
            Files.createDirectories(homePaths.stateDir)
            Files.createDirectories(markersDir)
            Files.writeString(homePaths.pidFile("idea-community-2025.3.3"), "${process.pid()}\n")
            Files.writeString(
                markersDir.resolve(PidMarker.markerFileNameFor(process.pid())),
                PidMarkerJson.encode(
                    PidMarker(
                        schema = PidMarker.SCHEMA_VERSION,
                        pid = process.pid(),
                        mcpSteroidServer = McpSteroidServerInfo(
                            mcpUrl = "http://localhost:63342/mcp",
                            port = 63342,
                            headers = emptyMap(),
                        ),
                        ide = IdeInfo(name = "IntelliJ IDEA Community", version = "2025.3.3", build = "IC-253.1"),
                        plugin = PluginInfo(id = "com.jonnyzzz.mcpSteroid", name = "MCP Steroid", version = "1.0.0"),
                        createdAt = "2026-05-14T21:00:00Z",
                        intellijWebServer = null,
                        intellijMcpServer = null,
                    ),
                ),
            )
            val manager = BackendManager(
                homePaths = homePaths,
                downloader = StaticDownloader,
                ideUserHome = userHome,
            )

            val stopped = manager.stop(parseBackendId("idea-community-2025.3.3"))

            assertEquals("stopped", stopped.outcome)
            assertEquals(process.pid(), stopped.pid)
            assertFalse(ProcessHandle.of(process.pid()).map { it.isAlive }.orElse(false))
        } finally {
            stopProcess(process)
        }
    }

    @Test
    fun `start product-only prefers highest locally installed backend without resolving releases`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, launcherBody = gracefulLauncher(), id = "idea-community-2025.2.6.1")
        installStubBackend(homePaths, launcherBody = gracefulLauncher(), id = "idea-community-2025.2.6.2")
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = ThrowingDownloader,
            ideUserHome = tempDir.resolve("user-home"),
        )

        val started = manager.start(parseBackendId("idea-community"))
        try {
            assertEquals("idea-community-2025.2.6.2", started.id)
            assertTrue(ProcessHandle.of(started.pid).orElseThrow().isAlive)
        } finally {
            manager.stop(parseBackendId("idea-community-2025.2.6.2"))
        }
    }

    @Test
    fun `stop product-only prefers highest locally installed backend without resolving releases`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, launcherBody = gracefulLauncher(), id = "idea-community-2025.2.6.1")
        installStubBackend(homePaths, launcherBody = gracefulLauncher(), id = "idea-community-2025.2.6.2")
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = ThrowingDownloader,
            ideUserHome = tempDir.resolve("user-home"),
        )
        val started = manager.start(parseBackendId("idea-community-2025.2.6.2"))
        assertTrue(ProcessHandle.of(started.pid).orElseThrow().isAlive)

        val stopped = manager.stop(parseBackendId("idea-community"))

        assertEquals("idea-community-2025.2.6.2", stopped.id)
        assertEquals("stopped", stopped.outcome)
        assertFalse(ProcessHandle.of(started.pid).map { it.isAlive }.orElse(false))
    }

    @Test
    fun `start captures launcher stdout and stderr to managed log`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, launcherBody = noisyLauncher())
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            ideUserHome = tempDir.resolve("user-home"),
        )

        val started = manager.start(parseBackendId("idea-community-2025.3.3"))
        try {
            assertEquals(homePaths.cacheDir("idea-community-2025.3.3").resolve("logs/managed.log"), started.ideaLogPath)
            withTimeout(5_000) {
                while (true) {
                    if (started.ideaLogPath.exists()) {
                        val text = Files.readString(started.ideaLogPath)
                        if ("managed stdout" in text && "managed stderr" in text) break
                    }
                    delay(100)
                }
            }
        } finally {
            manager.stop(parseBackendId("idea-community-2025.3.3"))
        }
    }

    @Test
    fun `start seeds first-run startup config before launching IDE`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, launcherBody = gracefulLauncher())
        val userHome = tempDir.resolve("user-home")
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            ideUserHome = userHome,
        )

        val started = manager.start(parseBackendId("idea-community-2025.3.3"))
        try {
            val configDir = homePaths.cacheDir("idea-community-2025.3.3").resolve("config")
            assertTrue(
                Files.readString(configDir.resolve("options/other.xml"))
                    .contains("experimental.ui.onboarding.proposed.version"),
            )
            assertEquals(
                "switched.from.classic.to.islands\nfalse\n",
                Files.readString(configDir.resolve("early-access-registry.txt")),
            )
            assertTrue(
                Files.readString(configDir.resolve("options/AIOnboardingPromoWindowAdvisor.xml"))
                    .contains("""<option name="wasShown" value="true" />"""),
            )
            assertTrue(
                Files.readString(userHome.resolve(".java/.userPrefs/jetbrains/privacy_policy/prefs.xml"))
                    .contains("""<entry key="euacommunity_accepted_version" value="999.999"/>"""),
            )
            assertTrue(
                Files.readString(userHome.resolve(".config/JetBrains/consentOptions/accepted"))
                    .startsWith("rsch.send.usage.stat:1.1:0:"),
            )
        } finally {
            manager.stop(parseBackendId("idea-community-2025.3.3"))
        }
        assertFalse(ProcessHandle.of(started.pid).map { it.isAlive }.orElse(false))
    }

    private fun installStubBackend(
        homePaths: HomePaths,
        launcherBody: String,
        id: String = "idea-community-2025.3.3",
    ) {
        val backendDir = homePaths.backendDir(id)
        val bundleDir = backendDir.resolve("idea-IC-253.1")
        val launcher = bundleDir.resolve("bin/idea.sh")
        Files.createDirectories(launcher.parent)
        Files.writeString(launcher, launcherBody)
        launcher.toFile().setExecutable(true)
        writeDescriptor(
            descriptorPath(backendDir),
            BackendDescriptor(
                id = id,
                productKey = "idea-community",
                productCode = "IC",
                version = id.removePrefix("idea-community-"),
                buildNumber = "IC-253.1",
                bundleDirName = bundleDir.fileName.toString(),
                launcherPath = "bin/idea.sh",
                downloadedAt = "2026-05-14T21:00:00Z",
            ),
        )
    }

    private fun gracefulLauncher(): String =
        """
        #!/usr/bin/env sh
        trap 'exit 0' TERM
        while true; do sleep 1; done
        """.trimIndent() + "\n"

    private fun sleepyLauncher(): String =
        // A process that deliberately does NOT exit on SIGTERM, so `stop` with a 0ms grace
        // period is forced down the SIGKILL path and reports "killed". A bare `sleep 60`
        // is wrong here: `sleep` terminates on SIGTERM, and Debian's dash exec-optimizes a
        // single trailing command into the `sleep` process, so on Linux the SIGTERM kills
        // it before the force-kill path runs -> outcome "stale" instead of "killed" (flaky
        // across macOS sh vs Linux dash). Ignore TERM/INT and loop so liveness is
        // deterministic on every platform.
        """
        #!/usr/bin/env sh
        trap '' TERM INT
        while true; do sleep 1; done
        """.trimIndent() + "\n"

    private fun noisyLauncher(): String =
        """
        #!/usr/bin/env sh
        echo "managed stdout"
        echo "managed stderr" >&2
        trap 'exit 0' TERM
        while true; do sleep 1; done
        """.trimIndent() + "\n"

    private fun startUnrelatedSleeper(): Process =
        ProcessBuilder("sh", "-c", "trap 'exit 0' TERM; while true; do sleep 1; done").start()

    private fun stopProcess(process: Process) {
        process.destroy()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private object StaticDownloader : ManagedBackendDownloader {
        override suspend fun resolve(id: BackendId): BackendDownloadResolution =
            BackendDownloadResolution(
                product = IdeProduct.IntelliJIdeaCommunity,
                version = id.version ?: "2025.3.3",
                build = "IC-253.1",
                url = "file:///unused",
            )

        override suspend fun downloadAndUnpack(
            resolution: BackendDownloadResolution,
            targetDir: Path,
        ): BackendDownloadArtifact = error("downloadAndUnpack should not be called by start/stop tests")
    }

    private object ThrowingDownloader : ManagedBackendDownloader {
        override suspend fun resolve(id: BackendId): BackendDownloadResolution =
            error("release resolver should not be called when a local backend is installed")

        override suspend fun downloadAndUnpack(
            resolution: BackendDownloadResolution,
            targetDir: Path,
        ): BackendDownloadArtifact = error("downloadAndUnpack should not be called by start/stop tests")
    }
}
