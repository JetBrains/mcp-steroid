/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.BuildSystem
import com.jonnyzzz.mcpSteroid.integration.infra.IdeChannel
import com.jonnyzzz.mcpSteroid.integration.infra.IdeDistribution
import com.jonnyzzz.mcpSteroid.integration.infra.IdeProduct
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Integration probe against **Android Studio** on the 261 platform baseline (marketing 2026.1.x — the
 * current canary/EAP generation), opening a real **Android Gradle project**
 * ([IntelliJProject.AndroidSampleProject]).
 *
 * Android Studio is a Google IDE built on the IntelliJ platform but with a different plugin/SDK surface,
 * so the MCP Steroid plugin and the agent flow are NOT expected to work cleanly here yet — **this build
 * is allowed to FAIL.** Its value is the signal: it shows exactly where Android Studio diverges (plugin
 * install, IDE boot, project open/sync, or the agent step), which tells us what to support next.
 */
class AndroidStudio261Test {

    @Test @Timeout(value = 30, unit = TimeUnit.MINUTES)
    fun `agent inspects an android project claude on android studio 261`() = agentInspectsAndroidProject(session.aiAgents.claude)

    @Test @Timeout(value = 30, unit = TimeUnit.MINUTES)
    fun `agent inspects an android project codex on android studio 261`() = agentInspectsAndroidProject(session.aiAgents.codex)

    private fun agentInspectsAndroidProject(agent: AiAgentSession) {
        val prompt = buildString {
            appendLine("You are working inside Android Studio with an Android Gradle project open.")
            appendLine("Using the IDE (not a terminal), identify the Android application module and report its")
            appendLine("`applicationId` / namespace from the Gradle build files.")
            appendLine("When done, print exactly: ANDROID_PROBE_DONE: <applicationId-or-namespace>")
        }

        val result = agent.runPrompt(prompt, timeoutSeconds = 1500).awaitForProcessFinish()
        result.assertExitCode(0, message = "android studio 261 probe for ${agent.displayName}")

        val combined = result.stdout + "\n" + result.stderr
        check(combined.contains("ANDROID_PROBE_DONE:")) {
            "Agent did not report ANDROID_PROBE_DONE on Android Studio 261.\nOutput:\n$combined"
        }
        println("[TEST] Agent '${agent.displayName}' inspected an Android project on Android Studio 261")
    }

    companion object {

        @JvmStatic
        val lifetime by lazy {
            CloseableStackHost()
        }

        val session by lazy {
            IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "android-studio-261",
                project = IntelliJProject.AndroidSampleProject,
                // Android Studio (Google) on the 261 platform baseline. Marketing 2026.1.x currently ships
                // on the canary/EAP channel; the resolver scrapes Google's feed and maps it to platform
                // build 261. Pinned by version prefix so it tracks 261 patches but not 262.
                distribution = IdeDistribution.Latest(
                    product = IdeProduct.AndroidStudio,
                    channel = IdeChannel.EAP,
                    version = "2026.1",
                ),
            )).waitForProjectReady(buildSystem = BuildSystem.GRADLE)
        }

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            session.toString()
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            lifetime.closeAllStacks()
        }
    }
}
