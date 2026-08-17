/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SemanticRippleCompileGateTest {

    private val case = RippleCases.renameMethodWide
    private val script = buildCompileGateScript(case, "/work/keycloak")

    @Test
    fun `gate compiles test sources, not just main`() {
        assertTrue(script.contains("test-compile")) {
            "All 445 references live in test sources; `compile` alone would not see them:\n$script"
        }
    }

    @Test
    fun `gate covers every compile-gate module exactly once`() {
        case.compileGateSelectors().forEach { selector ->
            assertTrue(script.contains(selector)) { "Module $selector missing from the gate:\n$script" }
        }
        assertTrue(script.contains("-pl")) { script }
    }

    @Test
    fun `gate selects modules by artifactId and enables the testsuite profile`() {
        assertTrue(script.contains("-pl :")) {
            "A colon-less -pl token is read as a directory path and selects nothing:\n$script"
        }
        assertTrue(script.contains("-P ${SemanticRippleSpec.reactorProfile}")) {
            "Without the profile the arquillian module is not in the reactor:\n$script"
        }
    }

    @Test
    fun `gate never uses also-make`() {
        assertFalse(script.contains("-am")) {
            "-am walks the upstream graph and OOM-kills the container:\n$script"
        }
    }

    @Test
    fun `gate pins JAVA_HOME to the configured JDK`() {
        assertTrue(script.contains("temurin-${SemanticRippleSpec.projectJdkVersion}-jdk-")) { script }
        assertFalse(script.contains("JAVA_HOME=*")) { "No wildcard JAVA_HOME assignments:\n$script" }
    }

    @Test
    fun `gate prefers the project wrapper and fails loudly when absent`() {
        assertTrue(script.contains("mvnw")) { script }
        assertTrue(script.contains("exit 1")) {
            "A missing wrapper must fail the gate, not silently fall through:\n$script"
        }
    }

    @Test
    fun `the reactor install is whole-reactor, keeps going, and may reach the network`() {
        val install = buildReactorInstallScript("/work/keycloak")
        assertFalse(install.contains("-pl")) {
            "The missing artifacts ARE the upstream closure, so selecting modules defeats the purpose:\n$install"
        }
        assertTrue(install.contains("-fae")) {
            "The distribution modules cannot build here and must not stop the rest:\n$install"
        }
        assertFalse(install.contains("mvnw -o")) {
            "A cold agent has none of the third-party dependencies yet, so offline would fail at once:\n$install"
        }
        assertFalse(install.contains("-am")) { "-am stays banned:\n$install" }
    }

    @Test
    fun `the gate stays offline so a repository outage cannot read as a missed call site`() {
        assertTrue(script.contains("mvnw -o")) { script }
    }

    /** The tail of the install that killed 17 of 25 arms on 2026-08-17 (build 1033125902). */
    private val networkFailureTail = listOf(
        "[IDE OUT] [ERROR] Failed to execute goal org.infinispan.protostream:" +
            "proto-schema-compatibility-maven-plugin:6.0.7:proto-schema-compatibility-check (default) on " +
            "project keycloak-model-infinispan: An error occurred while running protolock: Server " +
            "returned HTTP response code: 429 for URL: https://raw.githubusercontent.com/keycloak/" +
            "keycloak/refs/heads/archive/release/26.0/model/infinispan/proto.lock -> [Help 1]",
        "[IDE OUT] [ERROR] After correcting the problems, you can resume the build with the command",
        "[IDE OUT] [ERROR]   mvn <args> -rf :keycloak-model-infinispan",
    ).joinToString("\n")

    @Test
    fun `a rate-limited proto lock download is recognised as transient and resumable`() {
        assertTrue(isTransientInstallFailure(networkFailureTail)) {
            "429 on raw.githubusercontent.com is the network, not the tree:\n$networkFailureTail"
        }
        assertTrue(resumeModuleOrNull(networkFailureTail) == ":keycloak-model-infinispan") {
            "Retrying from the top costs twenty minutes: ${resumeModuleOrNull(networkFailureTail)}"
        }
    }

    @Test
    fun `a compile failure of the tree under test is never retried`() {
        val tail = """
            [ERROR] /work/keycloak/core/src/main/java/A.java:[12,5] cannot find symbol
            [ERROR]   mvn <args> -rf :keycloak-core
        """.trimIndent()
        assertFalse(isTransientInstallFailure(tail)) {
            "A real compile error reaches the same verdict on every attempt:\n$tail"
        }
    }

    @Test
    fun `the MDEP-187 symptom alone does not count as transient`() {
        val tail = "[ERROR] Artifact has not been packaged yet. … see MDEP-187. -> [Help 1]"
        assertFalse(isTransientInstallFailure(tail)) {
            "MDEP-187 is the consequence; retrying on it would hide a genuinely broken tree"
        }
    }

    @Test
    fun `the resume point reaches the install command line`() {
        val resumed = buildReactorInstallScript("/work/keycloak", resumeFrom = ":keycloak-model-infinispan")
        assertTrue(resumed.contains("-rf :keycloak-model-infinispan")) { resumed }
        assertFalse(buildReactorInstallScript("/work/keycloak").contains("-rf")) {
            "The first attempt must build the whole reactor"
        }
    }

    @Test
    fun `error lines are extracted for the abort message`() {
        val lines = mavenErrorLines(networkFailureTail)
        assertTrue(lines.size == 3) { lines.toString() }
        assertTrue(lines.first().contains("429")) { lines.toString() }
    }

    @Test
    fun `result passes only on a zero exit code`() {
        assertTrue(CompileGateResult(exitCode = 0, tail = "").passed)
        assertFalse(CompileGateResult(exitCode = 1, tail = "BUILD FAILURE").passed)
    }
}
