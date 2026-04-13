/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.createFromSnapshot
import com.jonnyzzz.mcpSteroid.testHelper.docker.ImageDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

class IntelliJWarmSnapshotIntegrationTest {
    @Test
    @Timeout(value = 240, unit = TimeUnit.MINUTES)
    fun `can create warm snapshot and restart without indexing`() {
        val snapshotTag = "mcp-steroid-intellij-warm-snapshot-${System.currentTimeMillis()}"
        val seedImageRef = System.getenv("MCP_STEROID_WARM_SNAPSHOT_SEED_IMAGE")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        lateinit var snapshotImage: ImageDriver

        runWithCloseableStack { lifetime ->
            val seedSession = IntelliJContainer.create(
                lifetime = lifetime,
                dockerFileBase = "ide-agent",
                consoleTitle = "intellij-warm-snapshot-seed",
                project = IntelliJProject.IntelliJMasterProject,
                sourceImage = seedImageRef?.let { ImageDriver(it, logPrefix = "IDE") },
            )
            snapshotImage = seedSession.createIndexedSnapshot(snapshotTag)
        }

        runWithCloseableStack { lifetime ->
            val warmSession = IntelliJContainer.createFromSnapshot(
                lifetime = lifetime,
                snapshotImage = snapshotImage,
                consoleTitle = "intellij-warm-snapshot-restart",
                project = IntelliJProject.IntelliJMasterProject,
            )

            warmSession.waitForSnapshotReadyWithoutIndexing()

            val projectDir = warmSession.intellijDriver.getGuestProjectDir()
            val systemDir = warmSession.intellijDriver.getGuestSystemDir()
            warmSession.scope.startProcessInContainer {
                this
                    .args("test", "-d", "$projectDir/.git")
                    .description("Verify IntelliJ checkout exists after snapshot restart")
                    .timeoutSeconds(10)
                    .quietly()
            }.assertExitCode(0) { "Missing IntelliJ checkout after warm snapshot restart: $projectDir" }

            warmSession.scope.startProcessInContainer {
                this
                    .args("test", "-d", "$systemDir/index")
                    .description("Verify IntelliJ index directory exists after snapshot restart")
                    .timeoutSeconds(10)
                    .quietly()
            }.assertExitCode(0) { "Missing IntelliJ system index directory after warm snapshot restart: $systemDir/index" }
        }
    }
}
