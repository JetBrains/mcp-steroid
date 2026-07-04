package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.monitor.IdePidDiscoveryService
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeProjectMonitorService
import com.jonnyzzz.mcpSteroid.devrig.monitor.IntelliJPortDiscovery
import com.jonnyzzz.mcpSteroid.devrig.server.DevrigBackendService
import com.jonnyzzz.mcpSteroid.devrig.server.DevrigProjectRoutingService
import com.jonnyzzz.mcpSteroid.server.NPX_STREAM_IDLE_TIMEOUT_MILLIS
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import java.io.InputStream
import java.io.PrintStream
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DevrigServices(
    private val lifetime: CloseableStack,

    val homePaths: HomePaths,
    val mcpStdin: InputStream,
    val mcpStdout: PrintStream,
) {
    fun lifetime(name: String): CloseableStack = lifetime.nestedStack(name)

    private val backgroundJob = SupervisorJob().also { job ->
        lifetime.registerCleanupAction { job.cancel() }
    }

    val backgroundScope: CoroutineScope =
        CoroutineScope(CoroutineName("devrig-background") + Dispatchers.IO + backgroundJob)

    val backendManager: BackendManager by lazy {
        // Gate download/start on the bundled plugin's plugin.xml build range so we never install or
        // launch an IDE the plugin can't load (it would never become reachable).
        BackendManager(homePaths, pluginBuildRange = bundledPluginBuildRange)
    }

    val backendProvisioner: BackendProvisioner by lazy {
        BackendProvisioner()
    }

    val commandHttpClient: HttpClient by lazy {
        val httpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                connectTimeoutMillis = 3_000
                requestTimeoutMillis = 10_000
                socketTimeoutMillis = 10_000
            }
            expectSuccess = false
        }
        lifetime.registerCleanupAction { httpClient.close() }
        httpClient
    }

    val mcpHttpClient: HttpClient by lazy {
        val httpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = NPX_STREAM_IDLE_TIMEOUT_MILLIS
                connectTimeoutMillis = 10_000
            }
            expectSuccess = false
        }
        lifetime.registerCleanupAction { httpClient.close() }
        httpClient
    }

    val ideDiscovery: IdePidDiscoveryService by lazy {
        IdePidDiscoveryService(
            markersDir = homePaths.markersDir,
            allowHosts = listOf("localhost", "127.0.0.1", "host.docker.internal"),
        )
    }

    val ideMonitor: IdeProjectMonitorService by lazy {
        IdeProjectMonitorService(
            httpClient = mcpHttpClient,
            discovery = ideDiscovery,
        )
    }

    val projectRouting: DevrigProjectRoutingService by lazy {
        DevrigProjectRoutingService(
            stateProvider = { ideMonitor.stateSnapshot() },
        )
    }

    val portDiscovery: IntelliJPortDiscovery by lazy {
        IntelliJPortDiscovery(httpClient = commandHttpClient)
    }

    val beacon by lazy {
        DevrigBeacon(homePaths, lifetime)
    }

    val devrigBackendService: DevrigBackendService by lazy {
        DevrigBackendService(
            stateProvider = { ideDiscovery.stateSnapshot() },
            installedProvider = { installedBackends() },
            starter = { backendManager.start(parseBackendId(it.id)) },
            runningManagedIdsProvider = { runningManagedIds() },
        )
    }

    /**
     * Returns the set of managed backend IDs that currently have a live pid file (RUNNING state).
     * Used by both [devrigBackendService] and [runBackendCommand] so the exclusion logic stays
     * consistent across the service path and the CLI render path.
     */
    fun runningManagedIds(): Set<String> =
        backendManager.list()
            .filter { it.state == ManagedBackendState.RUNNING }
            .map { it.id }
            .toSet()

}
