package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeDiscoveryService
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorService
import com.jonnyzzz.mcpSteroid.devrig.monitor.IntelliJPortDiscovery
import com.jonnyzzz.mcpSteroid.devrig.server.DevrigProjectRoutingService
import com.jonnyzzz.mcpSteroid.server.NPX_STREAM_IDLE_TIMEOUT_MILLIS
import com.jonnyzzz.mcpSteroid.server.NpxStreamClientInfo
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import java.io.InputStream
import java.io.PrintStream
import java.util.UUID
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
        BackendManager(homePaths)
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

    val ideDiscovery: IdeDiscoveryService by lazy {
        IdeDiscoveryService(
            markersDir = homePaths.markersDir,
            allowHosts = listOf("localhost", "127.0.0.1", "host.docker.internal"),
        )
    }

    val ideMonitor: IdeMonitorService by lazy {
        IdeMonitorService(
            httpClient = mcpHttpClient,
            discovery = ideDiscovery,
            clientInfo = clientInfo,
        )
    }

    val projectRouting: DevrigProjectRoutingService by lazy {
        DevrigProjectRoutingService(ideMonitor)
    }

    val portDiscovery: IntelliJPortDiscovery by lazy {
        val discovery = IntelliJPortDiscovery(httpClient = commandHttpClient)
        lifetime.registerCleanupAction { discovery.close() }
        discovery
    }

    val clientInfo: NpxStreamClientInfo by lazy {
        NpxStreamClientInfo(
            client = "devrig",
            clientPid = ProcessHandle.current().pid(),
            clientVersion = DevrigVersionMetadata.getDevrigVersion(),
            clientInstanceId = "devrig-${UUID.randomUUID()}",
            platform = System.getProperty("os.name"),
            arch = System.getProperty("os.arch"),
        )
    }

    val beacon by lazy {
        DevrigBeacon(homePaths, lifetime)
    }

}
