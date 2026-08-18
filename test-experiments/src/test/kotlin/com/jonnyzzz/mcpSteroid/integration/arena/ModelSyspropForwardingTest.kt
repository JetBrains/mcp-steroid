/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Proves that `test-experiments/build.gradle.kts` forwards `claude.model`, `codex.model`,
 * `arena.pass.label` and the `ripple.checkpoint.*` coordinates from the Gradle JVM to this forked test
 * JVM. Without that forwarding, a Gradle-CLI `-Dclaude.model=...` never reaches [System.getProperty]
 * here, and `DockerClaudeSession`/`DockerCodexSession` silently fall back to their compiled-in default
 * model.
 *
 * The `ripple.checkpoint.*` keys are the worst kind of silent failure: the solution-readiness pilot's 50
 * probe builds differ ONLY by them, so an unforwarded key produces 50 green builds that all measured the
 * same cell.
 *
 * Always executes (no runtime skips): when the verification invocation sets `-Dclaude.model=...`
 * it also sets `EXPECT_CLAUDE_MODEL` in the environment so this test can assert the forwarded value
 * verbatim; otherwise it asserts the property is absent.
 */
class ModelSyspropForwardingTest {

    @Test
    fun `claude_model sysprop is forwarded to the forked test jvm`() {
        val forwarded = System.getProperty("claude.model")
        val expected = System.getenv("EXPECT_CLAUDE_MODEL")
        if (expected != null) assertEquals(expected, forwarded) else assertNull(forwarded)
    }

    @Test
    fun `codex_model sysprop is forwarded to the forked test jvm`() {
        val forwarded = System.getProperty("codex.model")
        val expected = System.getenv("EXPECT_CODEX_MODEL")
        if (expected != null) assertEquals(expected, forwarded) else assertNull(forwarded)
    }

    @Test
    fun `arena_pass_label sysprop is forwarded to the forked test jvm`() {
        val forwarded = System.getProperty("arena.pass.label")
        val expected = System.getenv("EXPECT_ARENA_PASS_LABEL")
        if (expected != null) assertEquals(expected, forwarded) else assertNull(forwarded)
    }

    @Test
    fun `ripple_checkpoint_arm sysprop is forwarded to the forked test jvm`() {
        val forwarded = System.getProperty("ripple.checkpoint.arm")
        val expected = System.getenv("EXPECT_RIPPLE_CHECKPOINT_ARM")
        if (expected != null) assertEquals(expected, forwarded) else assertNull(forwarded)
    }

    @Test
    fun `ripple_checkpoint_index sysprop is forwarded to the forked test jvm`() {
        val forwarded = System.getProperty("ripple.checkpoint.index")
        val expected = System.getenv("EXPECT_RIPPLE_CHECKPOINT_INDEX")
        if (expected != null) assertEquals(expected, forwarded) else assertNull(forwarded)
    }

    @Test
    fun `ripple_checkpoint_replicate sysprop is forwarded to the forked test jvm`() {
        val forwarded = System.getProperty("ripple.checkpoint.replicate")
        val expected = System.getenv("EXPECT_RIPPLE_CHECKPOINT_REPLICATE")
        if (expected != null) assertEquals(expected, forwarded) else assertNull(forwarded)
    }

    /**
     * The guest IDE's heap cap, read by `generateVmOptions` in `intelliJ.kt`. The keycloak-semantic TC
     * builds pass it in `gradleParams` to keep the IDE and the agent's own Maven build inside the Docker
     * VM, so an unforwarded value means those runs quietly kept the default heap.
     */
    @Test
    fun `ide vm xmx sysprop is forwarded to the forked test jvm`() {
        val forwarded = System.getProperty("test.integration.ide.vm.xmx")
        val expected = System.getenv("EXPECT_TEST_INTEGRATION_IDE_VM_XMX")
        if (expected != null) assertEquals(expected, forwarded) else assertNull(forwarded)
    }
}
