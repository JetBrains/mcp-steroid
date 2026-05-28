/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.TestApplication
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PidMarkerJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit

@TestApplication
class ServerUrlWriterTest {

    @Test
    fun writeCreatesMarkerUnderManagedMarkersDirectory() = withTemporaryUserHome { userHome ->
        val writer = ServerUrlWriter()
        try {
            writer.writeServerUrlToUserHome("http://localhost:6315/mcp")

            val pid = ProcessHandle.current().pid()
            val markerFile = PidMarker.markerDirectory(userHome).resolve(PidMarker.markerFileNameFor(pid))
            assertTrue(Files.isRegularFile(markerFile), "marker should be written to $markerFile")
            val marker = PidMarkerJson.decode(Files.readString(markerFile))
            assertEquals(pid, marker.pid)
            assertEquals("http://localhost:6315/mcp", marker.mcpSteroidServer.mcpUrl)
            assertNotNull(marker.intellijWebServer, "IntelliJ built-in web server info should be present")
            assertTrue(marker.intellijWebServer!!.port > 0, "web server port should be known")
            assertTrue(
                marker.intellijWebServer!!.headers["x-ijt"]?.isNotBlank() == true,
                "web server headers should carry the x-ijt token",
            )
        } finally {
            Disposer.dispose(writer)
        }
    }

    @Test
    fun writeCleansStaleMarkersInManagedDirectory() = withTemporaryUserHome { userHome ->
        val writer = ServerUrlWriter()
        try {
            val deadPid = deadPid()
            val markerDir = PidMarker.markerDirectory(userHome)
            Files.createDirectories(markerDir)
            val staleMarker = markerDir.resolve(PidMarker.markerFileNameFor(deadPid))
            Files.writeString(staleMarker, "stale")

            writer.writeServerUrlToUserHome("http://localhost:6317/mcp")

            assertFalse(Files.exists(staleMarker), "stale marker for dead pid should be removed")
            val currentMarker = markerDir.resolve(PidMarker.markerFileNameFor(ProcessHandle.current().pid()))
            assertTrue(Files.isRegularFile(currentMarker), "current marker should remain")
        } finally {
            Disposer.dispose(writer)
        }
    }

    private fun withTemporaryUserHome(block: (Path) -> Unit) {
        val originalUserHome = System.getProperty("user.home")
        val userHome = Files.createTempDirectory("server-url-writer-home")
        try {
            System.setProperty("user.home", userHome.toString())
            block(userHome)
        } finally {
            System.setProperty("user.home", originalUserHome)
            deleteRecursively(userHome)
        }
    }

    private fun deadPid(): Long {
        // Spawn a short-lived process to get a PID that's reliably dead by
        // the time the cleanup code runs. `/bin/echo` is fine on Unix; on
        // Windows it doesn't exist — use the always-present `cmd.exe /c rem`
        // (rem is a no-op so the process exits immediately).
        val isWindows = System.getProperty("os.name").lowercase().startsWith("windows")
        val command = if (isWindows) {
            listOf(System.getenv("ComSpec") ?: "cmd.exe", "/c", "rem")
        } else {
            listOf("/bin/echo", "server-url-writer-test")
        }
        val process = ProcessBuilder(command).start()
        check(process.waitFor(5, TimeUnit.SECONDS)) { "short-lived helper process should exit" }
        return process.pid()
    }

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { path ->
                Files.deleteIfExists(path)
            }
        }
    }
}
