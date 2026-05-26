/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.ConsoleDriver
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.docker.copyFromContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.copyToContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessStreamType
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ManagedBackendGuiIntegrationTest {
    @Test
    @Timeout(value = 45, unit = TimeUnit.MINUTES)
    fun `devrig downloads starts and stops IDEA Community inside a GUI container`() = runWithCloseableStack { lifetime ->
        val container = IntelliJContainer.create(
            lifetime = lifetime,
            dockerFileBase = "managed-backend-host",
            consoleTitle = "managed-backend",
            aiMode = AiMode.NONE,
            startIde = false,
            mountSshAgent = false,
            repoCacheDir = null,
        )
        val devrig = container.deployDevrigLauncher()
        container.console.writeHeader("Managed-backend integration test")

        container.execAndAssert(
            description = "prepare managed backend home",
            script = """
                set -euo pipefail
                rm -rf /tmp/mcp-home
                mkdir -p /tmp/mcp-home/downloads
            """.trimIndent(),
        )

        val hostArchiveCacheDir = resolveManagedBackendArchiveCache(container.console)
        if (hostArchiveCacheDir != null) {
            stageCachedManagedBackendArchives(container, hostArchiveCacheDir)
        }

        val download = container.execAndAssertWithConsoleStream(
            description = "download IDEA Community managed backend",
            timeoutSeconds = 35 * 60L,
            script = """
                set -euo pipefail
                mkdir -p /tmp/mcp-home/downloads
                DEVRIG_HOME=/tmp/mcp-home "$devrig" --debug backend download idea-community
            """.trimIndent(),
        )
        assertTrue(download.stdout.contains("id: idea-community-"), download.stdout)
        if (hostArchiveCacheDir != null) {
            backPopulateManagedBackendArchiveCache(container, hostArchiveCacheDir)
        }

        val id = container.execAndAssert(
            description = "resolve managed backend id",
            script = """
                set -euo pipefail
                basename "${'$'}(find /tmp/mcp-home/backends -mindepth 1 -maxdepth 1 -type d -name 'idea-community-*' | sort | head -1)"
            """.trimIndent(),
        ).stdout.trim()
        assertTrue(Regex("""idea-community-\d+\.\d+.*""").matches(id), id)

        container.execAndAssert(
            description = "assert managed backend files",
            script = """
                set -euo pipefail
                backend_dir="/tmp/mcp-home/backends/$id"
                bundle="${'$'}(find "${'$'}backend_dir" -mindepth 1 -maxdepth 1 -type d | head -1)"
                test -n "${'$'}bundle"
                test -f "${'$'}backend_dir/backend.json"
                jq -e --arg id "$id" '.id == ${'$'}id and .productKey == "idea-community" and (.launcherPath | length > 0)' "${'$'}backend_dir/backend.json"
                test -f "${'$'}bundle/product-info.json" -o -f "${'$'}bundle/Contents/Resources/product-info.json"
                vmoptions="${'$'}backend_dir/${'$'}(basename "${'$'}bundle").vmoptions"
                test -f "${'$'}vmoptions"
                grep -F -- "-Didea.config.path=/tmp/mcp-home/caches/$id/config" "${'$'}vmoptions"
                grep -F -- "-Didea.system.path=/tmp/mcp-home/caches/$id/system" "${'$'}vmoptions"
                grep -F -- "-Didea.log.path=/tmp/mcp-home/caches/$id/logs" "${'$'}vmoptions"
                grep -F -- "-Didea.plugins.path=/tmp/mcp-home/caches/$id/plugins" "${'$'}vmoptions"
                grep -F -- "-Dmcp.steroid.updates.enabled=false" "${'$'}vmoptions"
                grep -F -- "-Dmcp.steroid.analytics.enabled=false" "${'$'}vmoptions"
                grep -F -- "-Dmcp.steroid.idea.description.enabled=false" "${'$'}vmoptions"
                grep -F -- "-Dmcp.steroid.dialog.killer.enabled=true" "${'$'}vmoptions"
                grep -F -- "-Dmcp.steroid.storage.path=/tmp/mcp-home/caches/$id/execution-storage" "${'$'}vmoptions"
                grep -F -- "-Djb.consents.confirmation.enabled=false" "${'$'}vmoptions"
                test -d "/tmp/mcp-home/caches/$id/plugins/mcp-steroid/lib"
                test -f "/tmp/mcp-home/caches/$id/plugins/mcp-steroid/EULA"
            """.trimIndent(),
        )

        val start = container.execAndAssertWithConsoleStream(
            description = "start IDEA Community managed backend",
            timeoutSeconds = 5 * 60L,
            script = """
                set -euo pipefail
                DEVRIG_HOME=/tmp/mcp-home "$devrig" backend start idea-community
            """.trimIndent(),
        )
        val pid = Regex("""pid: (\d+)""").find(start.stdout)?.groupValues?.get(1)
            ?: error("No pid in backend start output: ${start.stdout}")

        container.execAndAssert(
            description = "assert managed backend pid and startup config",
            script = """
                set -euo pipefail
                test -f "/tmp/mcp-home/state/$id.pid"
                test "${'$'}(cat "/tmp/mcp-home/state/$id.pid")" = "$pid"
                kill -0 "$pid"
                grep -F -- "experimental.ui.onboarding.proposed.version" "/tmp/mcp-home/caches/$id/config/options/other.xml"
                grep -F -- "switched.from.classic.to.islands" "/tmp/mcp-home/caches/$id/config/early-access-registry.txt"
                grep -F -- 'option name="wasShown" value="true"' "/tmp/mcp-home/caches/$id/config/options/AIOnboardingPromoWindowAdvisor.xml"
                grep -F -- 'entry key="euacommunity_accepted_version" value="999.999"' "/home/agent/.java/.userPrefs/jetbrains/privacy_policy/prefs.xml"
                grep -F -- 'entry key="euacommunity_accepted_version" value="999.999"' "/home/agent/.java/.userPrefs/jetbrains/_!(!!cg\"p!(}!}@\"j!(k!|w\"w!'8!b!\"p!':!e@==/prefs.xml"
                grep -F -- 'rsch.send.usage.stat:1.1:0:' "/home/agent/.config/JetBrains/consentOptions/accepted"
            """.trimIndent(),
        )

        container.waitForIntelliJBuiltInHttpServer(timeoutSeconds = 180)

        container.console.writeStep(6, "Waiting for MCP Steroid pid marker (up to 180s)...")
        container.execAndAssert(
            description = "wait for MCP Steroid pid marker",
            timeoutSeconds = 180,
            script = """
                set -euo pipefail
                marker="/home/agent/.mcp-steroid/markers/$pid.mcp-steroid"
                deadline=${'$'}((SECONDS + 180))
                found=0
                while [ "${'$'}SECONDS" -lt "${'$'}deadline" ]; do
                  if [ -f "${'$'}marker" ] && jq -e --argjson pid "$pid" '.pid == ${'$'}pid and (.mcpSteroidServer.mcpUrl | startswith("http://")) and .ide.name and .plugin.id == "com.jonnyzzz.mcp-steroid"' "${'$'}marker" >/dev/null; then
                    cat "${'$'}marker"
                    found=1
                    break
                  fi
                  sleep 2
                done
                if [ "${'$'}found" = "1" ]; then
                  :
                else
                echo "MCP Steroid marker did not appear at ${'$'}marker" >&2
                find /home/agent/.mcp-steroid/markers -maxdepth 1 -name '*.mcp-steroid' -print -exec cat {} \; >&2 || true
                exit 1
                fi
            """.trimIndent(),
        )
        container.console.writeSuccess("MCP Steroid marker present")

        container.execAndAssertWithConsoleStream(
            description = "assert proxy discovers managed backend marker",
            timeoutSeconds = 120,
            script = """
                set -euo pipefail
                DEVRIG_HOME=/tmp/mcp-home "$devrig" backend --json > /tmp/backend.json
                cat /tmp/backend.json
                jq -e --argjson pid "$pid" '.backends[] | select(.source == "marker" and .pid == ${'$'}pid and .pluginInstalled == true and .managed == true)' /tmp/backend.json
            """.trimIndent(),
        )

        container.execAndAssert(
            description = "assert managed backend cache paths",
            script = """
                set -euo pipefail
                test -d "/tmp/mcp-home/caches/$id/config"
                test -d "/tmp/mcp-home/caches/$id/system"
                test -d "/tmp/mcp-home/caches/$id/logs"
                test -d "/tmp/mcp-home/caches/$id/plugins"
                test "${'$'}(find "/tmp/mcp-home/caches/$id/logs" -type f | wc -l)" -gt 0
            """.trimIndent(),
        )

        container.execAndAssertWithConsoleStream(
            description = "stop IDEA Community managed backend",
            timeoutSeconds = 120,
            script = """
                set -euo pipefail
                DEVRIG_HOME=/tmp/mcp-home "$devrig" backend stop idea-community
                test ! -f "/tmp/mcp-home/state/$id.pid"
                deadline=${'$'}((SECONDS + 30))
                while kill -0 $pid 2>/dev/null && [ "${'$'}SECONDS" -lt "${'$'}deadline" ]; do
                  sleep 1
                done
                if kill -0 $pid 2>/dev/null; then
                  ps -fp $pid >&2 || true
                  exit 1
                fi
            """.trimIndent(),
        )
        container.console.writeSuccess("Managed backend lifecycle verified")
    }
}

private const val MANAGED_BACKEND_HOME = "/tmp/mcp-home"
private const val MANAGED_BACKEND_DOWNLOADS_DIR = "/tmp/mcp-home/downloads"
private const val MANAGED_BACKEND_ARCHIVE_CACHE_ENV = "MCP_STEROID_TEST_ARCHIVE_CACHE"
private val ideaCommunityArchiveName = Regex("""ideaIC-.*\.tar\.gz""")
private val managedBackendArchiveExtensions = listOf(".tar.gz", ".dmg", ".zip", ".exe")

private fun resolveManagedBackendArchiveCache(console: ConsoleDriver): File? {
    val configuredDir = System.getenv(MANAGED_BACKEND_ARCHIVE_CACHE_ENV)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val cacheDir = (configuredDir
        ?.let { File(it) }
        ?: File(System.getProperty("user.home"), ".cache/mcp-steroid-test"))
        .absoluteFile

    try {
        Files.createDirectories(cacheDir.toPath())
    } catch (e: Exception) {
        console.writeInfo("WARN: managed-backend archive cache disabled; cannot create ${cacheDir.absolutePath}: ${e.message}")
        return null
    }

    if (!cacheDir.isDirectory) {
        console.writeInfo("WARN: managed-backend archive cache disabled; not a directory: ${cacheDir.absolutePath}")
        return null
    }
    if (!cacheDir.canRead() || !cacheDir.canWrite()) {
        console.writeInfo("WARN: managed-backend archive cache disabled; directory is not readable/writable: ${cacheDir.absolutePath}")
        return null
    }

    console.writeInfo("using managed-backend archive cache: ${cacheDir.absolutePath}")
    return cacheDir
}

private fun stageCachedManagedBackendArchives(
    container: IntelliJContainer,
    hostCacheDir: File,
) {
    val archives = try {
        hostCacheDir.listFiles { file -> file.isFile && ideaCommunityArchiveName.matches(file.name) }
            ?.sortedBy { it.name }
            ?: emptyList()
    } catch (e: Exception) {
        container.console.writeInfo("WARN: cannot list managed-backend archive cache ${hostCacheDir.absolutePath}: ${e.message}")
        return
    }

    if (archives.isEmpty()) {
        container.console.writeInfo("no cached IDEA Community archives staged from ${hostCacheDir.absolutePath}")
        return
    }

    container.execAndAssert(
        description = "ensure managed backend downloads directory",
        script = """
            set -euo pipefail
            mkdir -p $MANAGED_BACKEND_DOWNLOADS_DIR
        """.trimIndent(),
    )

    archives.forEach { archive ->
        try {
            container.scope.copyToContainer(archive, "$MANAGED_BACKEND_DOWNLOADS_DIR/${archive.name}")
            container.console.writeInfo("staged cached archive: ${archive.name}")
        } catch (e: Exception) {
            container.console.writeInfo("WARN: failed to stage cached archive ${archive.name}: ${e.message}")
        }
    }
}

private fun backPopulateManagedBackendArchiveCache(
    container: IntelliJContainer,
    hostCacheDir: File,
) {
    val archiveNames = container.execAndAssert(
        description = "list managed backend downloads",
        script = """
            set -euo pipefail
            ls $MANAGED_BACKEND_DOWNLOADS_DIR
        """.trimIndent(),
    ).stdout
        .lineSequence()
        .map { it.trim() }
        .filter { it.isManagedBackendArchiveFileName() }
        .sorted()
        .toList()

    if (archiveNames.isEmpty()) {
        container.console.writeInfo("no managed-backend archive files found to cache")
        return
    }

    archiveNames.forEach { archiveName ->
        cacheManagedBackendArchive(container, hostCacheDir, archiveName)
    }
}

private fun cacheManagedBackendArchive(
    container: IntelliJContainer,
    hostCacheDir: File,
    archiveName: String,
) {
    val guestPath = "$MANAGED_BACKEND_DOWNLOADS_DIR/$archiveName"
    val hostFile = File(hostCacheDir, archiveName)
    val containerSize = container.execAndAssert(
        description = "stat managed backend archive $archiveName",
        script = """
            set -euo pipefail
            stat -c %s ${bashSingleQuote(guestPath)}
        """.trimIndent(),
    ).stdout.trim().toLong()

    if (hostFile.isFile && hostFile.length() == containerSize) {
        container.console.writeInfo("archive cache already up to date: $archiveName")
        return
    }

    val temporaryHostFile = File(hostCacheDir, ".${archiveName}.${System.nanoTime()}.tmp")
    var cached = false
    try {
        container.scope.copyFromContainer(guestPath, temporaryHostFile)
        val copiedSize = temporaryHostFile.length()
        if (copiedSize != containerSize) {
            container.console.writeInfo(
                "WARN: copied archive has unexpected size for $archiveName: host=$copiedSize container=$containerSize",
            )
            return
        }

        Files.move(
            temporaryHostFile.toPath(),
            hostFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
        cached = true
        container.console.writeInfo("cached archive for next run: $archiveName")
    } catch (e: Exception) {
        container.console.writeInfo("WARN: failed to cache archive $archiveName: ${e.message}")
    } finally {
        if (!cached) {
            try {
                Files.deleteIfExists(temporaryHostFile.toPath())
            } catch (e: Exception) {
                container.console.writeInfo("WARN: failed to remove temporary archive file ${temporaryHostFile.absolutePath}: ${e.message}")
            }
        }
    }
}

private fun String.isManagedBackendArchiveFileName(): Boolean =
    managedBackendArchiveExtensions.any { extension -> endsWith(extension) }

private fun bashSingleQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

/**
 * Runs a devrig script inside this container while live-streaming stdout/stderr
 * to the on-video xterm. The full transcript is still returned for assertions.
 */
internal fun IntelliJContainer.execAndAssertWithConsoleStream(
    description: String,
    script: String,
    timeoutSeconds: Long = 120,
): ProcessResult {
    console.writeHeader(description)

    val wrappedScript = """
        set -euo pipefail
        (
        ${script.trimIndent()}
        ) 2>&1
    """.trimIndent()

    val streamedStdoutLines = AtomicInteger(0)
    val streamedStderrLines = AtomicInteger(0)
    val process = scope.startProcessInContainer {
        args("bash", "-lc", wrappedScript)
            .timeoutSeconds(timeoutSeconds)
            .description(description)
    }

    val streamer = thread(
        start = true,
        isDaemon = true,
        name = "devrig-console-stream-${System.nanoTime()}",
    ) {
        try {
            runBlocking {
                process.messagesFlow.collect { line ->
                    when (line.type) {
                        ProcessStreamType.STDOUT -> {
                            streamedStdoutLines.incrementAndGet()
                            console.writeLine("${ConsoleDriver.GREEN}[devrig]${ConsoleDriver.RESET} ${line.line}")
                        }

                        ProcessStreamType.STDERR -> {
                            streamedStderrLines.incrementAndGet()
                            console.writeLine("${ConsoleDriver.RED}[devrig]${ConsoleDriver.RESET} ${line.line}")
                        }

                        ProcessStreamType.INFO -> Unit
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("Failed to stream devrig output to the visible console: ${e.message}")
            console.writeError("Failed to stream devrig output: ${e.message}")
        }
    }

    val result = process.awaitForProcessFinish()
    try {
        streamer.join(3_000)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        System.err.println("Interrupted while waiting for devrig console streamer: ${e.message}")
    }

    result.stdout.lineSequence()
        .drop(streamedStdoutLines.get())
        .filter { line -> line.isNotEmpty() }
        .forEach { line -> console.writeLine("${ConsoleDriver.GREEN}[devrig]${ConsoleDriver.RESET} $line") }
    result.stderr.lineSequence()
        .drop(streamedStderrLines.get())
        .filter { line -> line.isNotEmpty() }
        .forEach { line -> console.writeLine("${ConsoleDriver.RED}[devrig]${ConsoleDriver.RESET} $line") }

    return result.assertExitCode(0) { "$description failed\nstdout=$stdout\nstderr=$stderr" }
}
