/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Proves that `test-experiments/build.gradle.kts` forwards `claude.model`, `codex.model`, and
 * `arena.pass.label` system properties from the Gradle JVM to this forked test JVM. Without that
 * forwarding, a Gradle-CLI `-Dclaude.model=...` never reaches [System.getProperty] here, and
 * `DockerClaudeSession`/`DockerCodexSession` silently fall back to their compiled-in default model.
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
}
