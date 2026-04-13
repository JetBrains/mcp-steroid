/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.writeFileInContainer

class XcvbSkillDriver(
    val lifetime: CloseableStack,
    val driver: ContainerDriver,
) {
    /**
     * Deploy the xcvb display control skill file into the container.
     * Agents can read this file to learn how to use xdotool, screenshots, etc.
     *
     * @return the guest path where the skill file was written
     */
    fun deploySkill(skillGuestPath: String = "/home/agent/.skills/xcvb-display-control.md"): String {
        val skillContent = XcvbDriver::class.java.getResource("/skills/xcvb-display-control.md")
            ?.readText()
            ?: error("xcvb-display-control.md skill resource not found on classpath")

        driver.writeFileInContainer(skillGuestPath, skillContent)
        println("[xcvb] Deployed display control skill to $skillGuestPath")
        return skillGuestPath
    }
}
