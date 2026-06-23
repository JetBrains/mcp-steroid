/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.devrig.InstalledBackend
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.normalizeHome
import com.jonnyzzz.mcpSteroid.devrig.startableBackendName
import com.jonnyzzz.mcpSteroid.devrig.startableBackends
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Returns a human-readable display name for an IDE that includes name, version, and build.
 *
 * Examples:
 *  - `IntelliJ IDEA 2026.1 (IU-261.25134.95)` when build is non-blank
 *  - `WebStorm 2026.1` when build is blank
 */
fun ideDisplayName(ide: IdeInfo): String {
    val base = ide.displayName  // already handles name + version dedup
    val trimmedBuild = ide.build.trim()
    return if (trimmedBuild.isNotEmpty()) "$base ($trimmedBuild)" else base
}

/**
 * A flat descriptor for a candidate that can be passed to [DevrigBackendService.ensureBackendRunning].
 *
 * Exactly one of [running] or [startable] is non-null:
 * - [running] non-null → the IDE is already discovered via a pid marker.
 * - [startable] non-null → the IDE is installed but not yet running; devrig will start it.
 */
data class BackendCandidate(
    val backendName: String,
    /** Human-readable label shown in error messages; includes version and build number. */
    val displayName: String,
    val running: DiscoveredIde? = null,
    val startable: InstalledBackend? = null,
)

/** Thrown when a startable backend was launched but did not post its pid marker within the timeout. */
class BackendStartTimeoutException(message: String) : RuntimeException(message)

/**
 * Service that lists open-project candidates (running + startable) and can start a not-yet-running
 * IDE, blocking until its pid marker appears.
 *
 * All dependencies are injected so tests can drive fakes without any Java threading primitives.
 */
class DevrigBackendService(
    /** Returns the current list of discovered (running) IDEs. */
    private val stateProvider: () -> List<DiscoveredIde>,
    /** Returns the current list of devrig-managed installed IDEs. */
    private val installedProvider: () -> List<InstalledBackend>,
    /** Launches a managed IDE; must return only after the launch command has been issued (not after the IDE is reachable). */
    private val starter: suspend (InstalledBackend) -> Unit,
    /**
     * Returns the set of managed backend IDs that currently have a live pid file (RUNNING state).
     * Used to exclude running managed IDEs from the startable group so they never appear twice.
     * Defaults to [emptySet] for backwards-compat in tests that do not wire the BackendManager.
     */
    private val runningManagedIdsProvider: () -> Set<String> = { emptySet() },
) {

    /**
     * Returns running candidates first, followed by startable candidates (installed managed IDEs that
     * are not currently running).
     *
     * Running entries are filtered to compatible IDEs only (those whose pid marker carries a non-null
     * [DiscoveredIde.ideHome] — an absent ideHome means an OLD/incompatible plugin version that cannot
     * serve open_project). Running entries are then deduplicated by [BackendCandidate.backendName]
     * so that a single IDE seen via multiple discovery paths does not appear twice.
     */
    fun candidates(): List<BackendCandidate> {
        val running = stateProvider().filter { it.ideHome != null }
        val startable = startableBackends(installedProvider(), running, runningManagedIdsProvider())
        return (running.map { BackendCandidate(it.backendName, ideDisplayName(it.ide), running = it) }
            .distinctBy { it.backendName } +
                startable.map { BackendCandidate(startableBackendName(it), ideDisplayName(it.ide), startable = it) })
    }

    /**
     * Ensures the given [candidate] is reachable and returns the corresponding [DiscoveredIde].
     *
     * - [BackendCandidate] with [BackendCandidate.running] set: returns its [DiscoveredIde] immediately.
     * - [BackendCandidate] with [BackendCandidate.startable] set: calls [starter], then polls
     *   [stateProvider] until a marker with a matching `ideHome` appears, or until [timeout] elapses.
     *
     * @throws BackendStartTimeoutException if a startable backend was started but did not become
     *   reachable within [timeout].
     */
    suspend fun ensureBackendRunning(
        candidate: BackendCandidate,
        timeout: Duration = 300.seconds,
        progress: McpProgressReporter? = null,
    ): DiscoveredIde {
        return when {
            candidate.running != null -> candidate.running
            candidate.startable != null -> startAndWait(candidate.startable, timeout, progress)
            else -> error("BackendCandidate has neither running nor startable source")
        }
    }

    private suspend fun startAndWait(
        installed: InstalledBackend,
        timeout: Duration,
        progress: McpProgressReporter?,
    ): DiscoveredIde {
        progress?.report("Starting ${ideDisplayName(installed.ide)} — this can take up to ${timeout.inWholeSeconds}s...")
        starter(installed)
        progress?.report("Waiting for ${ideDisplayName(installed.ide)} to become reachable...")
        val result = withTimeoutOrNull(timeout) {
            var found: DiscoveredIde? = null
            while (found == null) {
                found = stateProvider().firstOrNull { sameHome(it.ideHome, installed.ideHome) }
                if (found == null) delay(250.milliseconds)
            }
            found
        }
        return result
            ?: throw BackendStartTimeoutException(
                "started ${installed.id} but it did not become reachable within $timeout"
            )
    }

    companion object {
        private fun sameHome(a: String?, b: String?): Boolean {
            if (a == null || b == null) return false
            return normalizeHome(a) == normalizeHome(b)
        }
    }
}
