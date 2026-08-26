/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pins which files a repair turn is told about, against REAL javac output.
 *
 * The failure this guards is silent by construction: the repair turn reads the files this function
 * names, so a pattern that returns nothing produces an agent asked to fix errors it cannot see — which
 * looks exactly like an agent that failed to fix them. The first version of the pattern did that: an
 * optional greedy path prefix swallowed everything up to the last slash and returned bare file names.
 */
class ArenaCompilerDiagnosticsTest {

    private val projectDir = "/home/agent/project-home"

    @Test
    fun `the repository-relative path is recovered from a real diagnostic`() {
        val out = """
            [ERROR] COMPILATION ERROR :
            [ERROR] $projectDir/services/src/main/java/org/keycloak/protocol/oidc/grants/OfflineRefreshTokenGrantType.java:[84,33] cannot find symbol
            [ERROR]   symbol:   variable INVALID_GRANT
            [ERROR]   location: interface org.keycloak.events.Errors
            [ERROR] $projectDir/services/src/main/java/org/keycloak/protocol/oidc/grants/OfflineRefreshTokenGrantType.java:[86,35] cannot find symbol
            [ERROR] $projectDir/services/src/test/java/org/keycloak/Foo${'$'}Bar.java:[12,1] class expected
        """.trimIndent()

        assertEquals(
            listOf(
                "services/src/main/java/org/keycloak/protocol/oidc/grants/OfflineRefreshTokenGrantType.java",
                "services/src/test/java/org/keycloak/Foo\$Bar.java",
            ),
            arenaFailingSourcePaths(out, projectDir),
            "the full repo-relative path is what the repair turn reads; a bare file name is unusable",
        )
    }

    @Test
    fun `a path outside the project is dropped rather than guessed at`() {
        val out = "[ERROR] /opt/idea/plugins/Whatever.java:[1,1] boom\n" +
            "[ERROR] $projectDir/core/src/main/java/A.java:[2,2] boom"
        assertEquals(listOf("core/src/main/java/A.java"), arenaFailingSourcePaths(out, projectDir))
    }

    @Test
    fun `a clean build names no files`() {
        assertEquals(emptyList<String>(), arenaFailingSourcePaths("[INFO] BUILD SUCCESS", projectDir))
        // A warning carries no [line,col] marker and must not be mistaken for an error.
        val warn = "[WARNING] $projectDir/services/src/main/java/A.java: uses unchecked operations"
        assertEquals(emptyList<String>(), arenaFailingSourcePaths(warn, projectDir))
    }
}
