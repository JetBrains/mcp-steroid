/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.ProjectHomeDirectory
import com.jonnyzzz.mcpSteroid.testHelper.process.RunProcessRequest
import com.jonnyzzz.mcpSteroid.testHelper.process.startProcess
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

private const val REAPER_IMAGE_BUILD_TIMEOUT_SECONDS = 600L
private const val REAPER_IMAGE_TAG = "mcp-steroid-reaper:latest"

/**
 * Custom Docker resource reaper that automatically cleans up containers
 * when the JVM process crashes or is killed with SIGKILL.
 * - Builds and starts a custom reaper container (Docker CLI + socat) via [buildDockerImage]
 *   and [startDockerContainerAndForget] — the reaper container is NOT registered in any
 *   lifetime; it exits by itself once the socket closes and cleanup is done.
 * - Connects via TCP socket and sends line-based commands
 * - Protocol: `container=<id-or-name>` registers a container, `ping` keeps alive
 * - Reaper kills all registered containers if no ping for 3 seconds or connection lost
 * - Registrations are buffered in a [Channel] with capacity 128 before connection is established
 * - The reaper's own container ID is filtered out of the channel
 *
 * Three complementary cleanup layers (issue #412 — leaked full-IDE containers starved the
 * TC agent's Docker daemon into 5-minute `docker run` timeouts):
 * 1. [registerContainerName] BEFORE `docker run` — the reaper can `docker rm -f <name>` a
 *    container whose `docker run` client was killed by timeout and never returned an ID.
 * 2. [registerContainer] with the ID once the start succeeded (historical path).
 * 3. [sweepTestRunContainers] on JVM exit — force-removes everything labeled with this
 *    run's [testRunId], catching containers whose registration never reached the reaper.
 *
 * No mutable fields: socket and writer are local to [start] and captured by coroutines.
 * [shutdown] cancels child coroutines, whose `finally` blocks close the socket.
 * All background work runs on [Dispatchers.IO] (daemon threads).
 */
object DockerReaper {

    /** Docker label key stamped on every test container; the value is [testRunId]. */
    const val TEST_RUN_LABEL = "com.jonnyzzz.mcp-steroid.test-run"

    private val started = AtomicBoolean(false)
    private val containerChannel = Channel<String>(128)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sweepHookInstalled = AtomicBoolean(false)

    /**
     * Identifies one test run for container labels and names: the TeamCity build id on CI, a
     * per-JVM session id locally. All Gradle test JVMs of one TC build share the id, so each
     * JVM-exit sweep collects the whole build's strays (safe: the docker-heavy test tasks are
     * serialised by the ciIntegrationTests mustRunAfter chain, so no sibling task can have live
     * containers when a test JVM exits). Locally each JVM only ever sweeps its own containers.
     */
    val testRunId: String by lazy {
        val raw = teamCityBuildId()?.let { "tc-$it" }
            ?: "local-${ProcessHandle.current().pid()}-${System.currentTimeMillis()}"
        // The id is embedded into container names, which only allow [a-zA-Z0-9_.-].
        raw.replace(Regex("[^a-zA-Z0-9_.-]"), "-")
    }

    private fun teamCityBuildId(): String? {
        System.getenv("TEAMCITY_BUILD_ID")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

        // TeamCity does not export the build id as an env var by default, but every build
        // exposes `teamcity.build.id` via the build properties file.
        val propertiesPath = System.getenv("TEAMCITY_BUILD_PROPERTIES_FILE")?.trim()?.takeIf { it.isNotEmpty() }
        if (propertiesPath != null) {
            try {
                val properties = java.util.Properties()
                File(propertiesPath).inputStream().use { properties.load(it) }
                properties.getProperty("teamcity.build.id")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            } catch (e: Exception) {
                System.err.println("[REAPER] Failed to read TeamCity build properties $propertiesPath: ${e.message}")
            }
        }

        // TeamCity (and Jenkins) also export the per-configuration build counter.
        return System.getenv("BUILD_NUMBER")?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * The container ID of the running reaper container, or null if not started.
     * The reaper container is NOT killed explicitly on [shutdown] — it exits on its own
     * once the socket closes and cleanup completes.
     */
    @Volatile
    var reaperContainerId: String? = null
        private set

    private data class ReaperEndpoint(
        val host: String,
        val port: Int,
        val label: String,
    )

    /**
     * Start the custom reaper container and establish connection.
     * Idempotent — only the first call performs actual work.
     *
     * Builds the reaper image via [buildDockerImage] and starts the container via
     * [startDockerContainerAndForget] (no lifetime, no explicit kill — the reaper exits
     * by itself after the socket closes and registered containers are cleaned up).
     * Container IDs registered before the connection is established are buffered
     * in a [Channel] with capacity 128.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return

        println("[REAPER] Starting custom reaper container...")
        // Build the reaper image from the docker/reaper directory
        val reaperDockerfile = ProjectHomeDirectory.requireProjectHomeDirectory()
            .resolve("test-helper/src/main/docker/reaper/Dockerfile")
            .toFile()
        require(reaperDockerfile.isFile) { "Reaper Dockerfile must exist: $reaperDockerfile" }

        val reaperImageId = resolveCachedReaperImage() ?: buildDockerImage(
            logPrefix = "REAPER",
            reaperDockerfile,
            REAPER_IMAGE_BUILD_TIMEOUT_SECONDS,
            quietly = true,
        ).tagDockerImage(REAPER_IMAGE_TAG)

        val port8080 = ContainerPort(8080)
        val containerDriver = startDockerContainerAndForget(
            StartContainerRequest()
                .image(reaperImageId)
                .volumes(ContainerVolume(File("/var/run/docker.sock"), "/var/run/docker.sock"))
                .ports(port8080)
                .quietly()
                .withoutReaperRegistration()
        )

        reaperContainerId = containerDriver.containerId

        // Map the container port to host port using ContainerDriver
        val hostPort = containerDriver.mapGuestPortToHostPort(port8080)
        val containerIp = containerDriver.queryContainerIp()

        val endpoints = buildList {
            // Works for tests running directly on host.
            add(ReaperEndpoint(host = "localhost", port = hostPort, label = "mapped host port"))
            // Works for tests running in a dockerized builder container.
            add(ReaperEndpoint(host = "host.docker.internal", port = hostPort, label = "docker host alias"))
            // Works from sibling containers on the default bridge network.
            if (!containerIp.isNullOrBlank()) {
                add(ReaperEndpoint(host = containerIp, port = port8080.containerPort, label = "container bridge IP"))
            }
        }.distinctBy { it.host to it.port }

        // Connect to the reaper socket with retries.
        val socket = connectWithRetry(endpoints)
        val writer = PrintWriter(socket.getOutputStream(), true)
        val writeLock = Any()

        val sendLine: (String) -> Unit = { line ->
            synchronized(writeLock) {
                try {
                    writer.println(line)
                } catch (e: Exception) {
                    println("[REAPER] Failed to send '$line': ${e.message}")
                }
            }
        }

        // Consumer coroutine: drains the channel and sends container IDs to reaper.
        // Filters out the reaper's own container ID — the reaper exits on its own after cleanup.
        // On cancellation: closes the socket, which signals the reaper to kill all registered
        // containers and exit.
        scope.launch {
            try {
                for (containerId in containerChannel) {
                    if (containerId == containerDriver.containerId) continue
                    sendLine("container=$containerId")
                }
            } finally {
                withContext(NonCancellable) {
                    runCatching { socket.close() }
                }
            }
        }

        // Ping loop: sends "ping" every 1 second to keep the reaper alive
        scope.launch {
            while (isActive) {
                delay(1000)
                sendLine("ping")
            }
        }

        println("[REAPER] Ready.")
    }

    private fun resolveCachedReaperImage(): ImageDriver? {
        val inspectResult = RunProcessRequest()
            .logPrefix("REAPER")
            .command("docker", "image", "inspect", "--format", "{{.Id}}", REAPER_IMAGE_TAG)
            .description("Check cached reaper image")
            .timeoutSeconds(20L)
            .quietly()
            .startProcess()
            .awaitForProcessFinish()

        if (inspectResult.exitCode != 0) return null

        val rawId = inspectResult.stdout
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return null

        val normalizedId = rawId.removePrefix("sha256:")
        println("[REAPER] Using cached reaper image: $REAPER_IMAGE_TAG (${normalizedId.take(10)})")
        return ImageDriver(imageId = normalizedId, logPrefix = "REAPER")
    }

    /**
     * Register a container for cleanup by ID.
     * Implicitly starts the reaper on a daemon thread if not already started.
     * Registrations are buffered in a [Channel] with capacity 128 —
     * safe to call before the reaper connection is established.
     */
    fun registerContainer(container: ContainerDriver) {
        registerForCleanup(container.containerId)
    }

    /**
     * Register a container NAME for cleanup — called BEFORE `docker run` even starts.
     * `docker kill` / `docker rm -f` accept names, so a container whose `docker run` client was
     * killed by timeout (its ID never reached this JVM) is still reaped when the socket closes.
     */
    fun registerContainerName(containerName: String) {
        registerForCleanup(containerName)
    }

    private fun registerForCleanup(containerIdOrName: String) {
        ensureTestRunSweepOnJvmExit()

        val sendResult = containerChannel.trySend(containerIdOrName)
        if (sendResult.isFailure) {
            // Buffer overflowed before the reaper connection drained it — the reaper will miss
            // this container; the test-run label sweep is the remaining safety net.
            System.err.println("[REAPER] Could not buffer '$containerIdOrName' for the reaper: $sendResult")
        }

        if (!started.get()) {
            scope.launch { start() }
        }
    }

    /**
     * The JVM-exit sweep complements the reaper container: shutdown hooks do not run on SIGKILL
     * (the reaper covers that), but they DO run on normal JVM exit — where they catch containers
     * whose registration never made it into the reaper (channel overflow, reaper start failure).
     */
    private fun ensureTestRunSweepOnJvmExit() {
        if (!sweepHookInstalled.compareAndSet(false, true)) return
        Runtime.getRuntime().addShutdownHook(Thread({ sweepTestRunContainers() }, "docker-test-run-sweep"))
    }

    /**
     * Force-remove every container labeled [TEST_RUN_LABEL]=[testRunId] — the strays of this
     * test run. Idempotent and safe to race with the reaper container's own cleanup: both sides
     * issue `docker rm -f`, and "No such container" failures are tolerated.
     */
    fun sweepTestRunContainers() {
        val list = RunProcessRequest()
            .logPrefix("REAPER")
            .command("docker", "ps", "-aq", "--filter", "label=$TEST_RUN_LABEL=$testRunId")
            .description("List stray containers of test run $testRunId")
            .timeoutSeconds(30)
            .quietly()
            .startProcess()
            .awaitForProcessFinish()

        if (list.exitCode != 0) {
            System.err.println(
                "[REAPER] Test-run sweep failed to list containers of run $testRunId " +
                        "(exit code ${list.exitCode}): ${list.stderr.trim()}"
            )
            return
        }

        val containerIds = list.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (containerIds.isEmpty()) {
            println("[REAPER] Test-run sweep: no stray containers for test run $testRunId")
            return
        }

        println("[REAPER] Test-run sweep: force-removing ${containerIds.size} container(s) of test run $testRunId: $containerIds")
        val rm = RunProcessRequest()
            .logPrefix("REAPER")
            .command(listOf("docker", "rm", "-f") + containerIds)
            .description("Force-remove stray containers of test run $testRunId")
            .timeoutSeconds(120)
            .quietly()
            .startProcess()
            .awaitForProcessFinish()

        if (rm.exitCode == 0) {
            println("[REAPER] Test-run sweep removed ${containerIds.size} container(s)")
        } else {
            System.err.println(
                "[REAPER] Test-run sweep could not remove all containers of run $testRunId " +
                        "(exit code ${rm.exitCode}): ${rm.stderr.trim()}"
            )
        }
    }

    /**
     * Shutdown the reaper.
     * Cancels child coroutines; their `finally` blocks close the socket, which signals the
     * reaper container to kill all registered containers and exit by itself.
     * Uses [cancelChildren] so the scope stays usable for subsequent [start] calls.
     */
    fun shutdown() {
        println("[REAPER] Shutting down...")
        scope.coroutineContext.cancelChildren()
        started.set(false)
        reaperContainerId = null
    }

    private fun connectWithRetry(endpoints: List<ReaperEndpoint>): Socket {
        require(endpoints.isNotEmpty()) { "No reaper endpoints provided" }

        var lastException: Exception? = null
        repeat(20) {
            for (endpoint in endpoints) {
                try {
                    val socket = Socket(endpoint.host, endpoint.port)
                    println("[REAPER] Connected to reaper socket via ${endpoint.host}:${endpoint.port} (${endpoint.label})")
                    return socket
                } catch (e: Exception) {
                    lastException = e
                }
            }
            Thread.sleep(500)
        }
        val targets = endpoints.joinToString { "${it.host}:${it.port}" }
        error("Failed to connect to reaper after retries (targets: $targets): ${lastException?.message}")
    }
}
