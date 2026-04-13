/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import java.io.File

/**
 * A handle for interacting with a running Docker test session from outside the test.
 *
 * Useful for debugging: take on-demand screenshots, send xcvb input, call MCP Steroid HTTP.
 *
 * ## How to use from test code (e.g. a debug helper or test helper method):
 *
 * ```kotlin
 * val handle = TestSessionHandle.findLatest()
 *     ?: error("No running test session found")
 *
 * println("Session  : ${handle.runDir}")
 * println("Container: ${handle.containerId}")
 * println("Display  : ${handle.display}")
 * println("MCP      : ${handle.mcpSteroidUrl}")
 *
 * // Take a screenshot (host-side path; read with the Read tool)
 * val screenshot = handle.takeScreenshot()
 * println("Screenshot: $screenshot")
 *
 * // Send keyboard input via xcvb / xdotool
 * handle.pressKey("ctrl+shift+a")
 * Thread.sleep(500)
 * handle.typeText("steroid_execute_code")
 * handle.pressKey("Return")
 *
 * // View latest periodic screenshot
 * val latest = handle.latestScreenshot()
 * println("Latest: $latest")
 * ```
 *
 * For use from the main IDE's `steroid_execute_code`, see the MCP resource
 * `mcp-steroid://test/container-interaction` — it implements the same logic inline
 * without importing this class.
 *
 * Created from `session-info.txt` written by [intelliJ-factory.kt].
 */
class TestSessionHandle(
    val runDir: File,
    val containerId: String,
    val display: String = ":99",
    val mcpSteroidUrl: String,
    val videoUrl: String,
) {
    private val screenshotDir: File get() = File(runDir, "screenshot")

    /**
     * Take an on-demand screenshot of the test container's display.
     * Returns the host-side path to the PNG file.
     */
    fun takeScreenshot(name: String = "debug-${System.currentTimeMillis()}.png"): File {
        screenshotDir.mkdirs()
        val containerPath = "/mcp-run-dir/screenshot/$name"
        val result = ProcessBuilder(
            "docker", "exec", containerId, "bash", "-c",
            "DISPLAY=$display scrot $containerPath 2>&1"
        ).redirectErrorStream(true).start().also { it.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) }
        val output = result.inputStream.bufferedReader().readText()
        if (result.exitValue() != 0) {
            println("[TestSessionHandle] scrot warning: $output")
        }
        val hostFile = File(runDir, "screenshot/$name")
        println("[TestSessionHandle] Screenshot: $hostFile")
        return hostFile
    }

    /** Find the most recently captured periodic screenshot. */
    fun latestScreenshot(): File? {
        return screenshotDir.listFiles { f -> f.extension == "png" }
            ?.maxByOrNull { it.lastModified() }
    }

    /** Press a key or key combination (e.g. "ctrl+shift+a", "Return", "Escape"). */
    fun pressKey(key: String) {
        dockerExec("DISPLAY=$display xdotool key $key")
    }

    /** Type text into the focused element. */
    fun typeText(text: String) {
        val escaped = text.replace("'", "\\'")
        dockerExec("DISPLAY=$display xdotool type --delay 50 -- '$escaped'")
    }

    /** Move mouse to (x, y) and click. */
    fun mouseClick(x: Int, y: Int, button: Int = 1) {
        dockerExec("DISPLAY=$display xdotool mousemove --sync $x $y && xdotool click $button")
    }

    private fun dockerExec(bashCommand: String) {
        ProcessBuilder("docker", "exec", containerId, "bash", "-c", bashCommand)
            .redirectErrorStream(true)
            .start()
            .also { it.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) }
    }

    companion object {
        private val testLogsRoot: File
            get() = File(
                System.getProperty("test.integration.testOutput")
                    ?: "test-integration/build/test-logs/test"
            )

        /**
         * Find the most recently started test session that has a valid session-info.txt
         * with a running container.
         */
        fun findLatest(): TestSessionHandle? {
            val sessionsDir = testLogsRoot
            if (!sessionsDir.isDirectory) return null

            val sessions = sessionsDir.listFiles()
                ?.filter { it.isDirectory && File(it, "session-info.txt").exists() }
                ?.sortedByDescending { it.lastModified() }
                ?: return null

            for (dir in sessions) {
                val handle = fromDir(dir) ?: continue
                // Check if container is still running
                val result = ProcessBuilder("docker", "inspect", "--format={{.State.Running}}", handle.containerId)
                    .redirectErrorStream(true).start()
                result.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                val running = result.inputStream.bufferedReader().readText().trim()
                if (running == "true") return handle
            }
            return null
        }

        /** Find all test sessions (including ended ones) ordered by most recent. */
        fun findAll(): List<TestSessionHandle> {
            val sessionsDir = testLogsRoot
            if (!sessionsDir.isDirectory) return emptyList()
            return sessionsDir.listFiles()
                ?.filter { it.isDirectory && File(it, "session-info.txt").exists() }
                ?.sortedByDescending { it.lastModified() }
                ?.mapNotNull { fromDir(it) }
                ?: emptyList()
        }

        /** Create a handle from a session directory that has session-info.txt. */
        fun fromDir(dir: File): TestSessionHandle? {
            val infoFile = File(dir, "session-info.txt")
            if (!infoFile.exists()) return null

            val props = infoFile.readLines()
                .filter { '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=') }

            val containerId = props["CONTAINER_ID"] ?: return null
            val mcpUrl = props["MCP_STEROID"] ?: return null
            val videoUrl = props["VIDEO_DASHBOARD"] ?: ""
            val display = props["DISPLAY"] ?: ":99"

            return TestSessionHandle(
                runDir = dir,
                containerId = containerId,
                display = display,
                mcpSteroidUrl = mcpUrl,
                videoUrl = videoUrl,
            )
        }
    }
}
