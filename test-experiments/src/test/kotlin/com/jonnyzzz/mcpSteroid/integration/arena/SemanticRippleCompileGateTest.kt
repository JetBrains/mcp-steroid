/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SemanticRippleCompileGateTest {

    private val script = buildCompileGateScript("/work/keycloak")

    @Test
    fun `gate compiles test sources, not just main`() {
        assertTrue(script.contains("test-compile")) {
            "All 445 references live in test sources; `compile` alone would not see them:\n$script"
        }
    }

    @Test
    fun `gate covers every compile-gate module exactly once`() {
        SemanticRippleSpec.compileGateSelectors.forEach { selector ->
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

    @Test
    fun `result passes only on a zero exit code`() {
        assertTrue(CompileGateResult(exitCode = 0, tail = "").passed)
        assertFalse(CompileGateResult(exitCode = 1, tail = "BUILD FAILURE").passed)
    }
}
