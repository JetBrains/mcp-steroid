package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.parseDockerHostPathMappings
import com.jonnyzzz.mcpSteroid.integration.infra.remapPathForDockerHost
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.io.File

class IdeTestHelpersTest {
    @Test
    fun `parseDockerHostPathMappings returns empty for blank input`() {
        Assertions.assertEquals(emptyList<Pair<String, String>>(), parseDockerHostPathMappings(null))
        Assertions.assertEquals(emptyList<Pair<String, String>>(), parseDockerHostPathMappings(""))
        Assertions.assertEquals(emptyList<Pair<String, String>>(), parseDockerHostPathMappings("   "))
    }

    @Test
    fun `remapPathForDockerHost remaps matched prefix`() {
        val remapped = remapPathForDockerHost(
            File("/workspace/test-integration/build/test-logs/test"),
            "/workspace=/host-workspace",
        )

        Assertions.assertEquals(
            File("/host-workspace/test-integration/build/test-logs/test").absolutePath,
            remapped.absolutePath,
        )
    }

    @Test
    fun `remapPathForDockerHost keeps path when no mapping matches`() {
        val original = File("/tmp/somewhere")
        val remapped = remapPathForDockerHost(original, "/workspace=/host-workspace")

        Assertions.assertEquals(original.absolutePath, remapped.absolutePath)
    }

    @Test
    fun `remapPathForDockerHost prefers longer source prefix`() {
        val remapped = remapPathForDockerHost(
            File("/workspace/test-integration/build/test-logs/test"),
            "/workspace=/host-workspace,/workspace/test-integration=/host-workspace-special",
        )

        Assertions.assertEquals(
            File("/host-workspace-special/build/test-logs/test").absolutePath,
            remapped.absolutePath,
        )
    }

    @Test
    fun `parseDockerHostPathMappings rejects invalid entries`() {
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            parseDockerHostPathMappings("/workspace")
        }
    }
}