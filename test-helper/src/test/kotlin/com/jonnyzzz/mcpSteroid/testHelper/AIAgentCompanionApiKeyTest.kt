/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.filter.AgentProgressOutputFilter
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests the documented Gemini-only skip-when-key-missing exception:
 *
 *  1. [AIAgentCompanion.isApiKeyAvailable] correctly distinguishes (a) a real key,
 *     (b) a missing key, and (c) an unresolved TeamCity `%credentialsJSON:…%` reference.
 *  2. With `skipTestWhenKeyMissing = true`, `requireApiKey()` (called from `create()`)
 *     throws `AssumptionViolatedException` for case (b) — JUnit 5 reports it as ignored,
 *     and `UsefulTestCase.shouldRunTest()` callers see `false`.
 *  3. With `skipTestWhenKeyMissing = true`, the unresolved-TC-ref branch (case c) still
 *     fails fast with `IllegalStateException` — that case is a real misconfiguration that
 *     must stay visible.
 *  4. With `skipTestWhenKeyMissing = false` (the default for Anthropic / OpenAI),
 *     missing keys still fail fast.
 *
 * This is the unit-level guarantee for CLAUDE.md "BANNED: detecting failures and
 * skipping tests" — the documented Gemini exception. Companion classes that opt
 * into the skip MUST be exercised through this test harness so future refactors
 * cannot silently regress the contract.
 */
class AIAgentCompanionApiKeyTest {

    private class FakeAgent : Any()

    /** Companion configured to skip-when-missing (mirrors `DockerGeminiSession.Companion`). */
    private class SkipCompanion(private val keySupplier: () -> String?) :
        AIAgentCompanion<FakeAgent>("fake-skip-cli") {
        override val displayName = "Fake-Skip"
        override val outputFilter: AgentProgressOutputFilter
            get() = error("not used by these tests")
        override val apiKeyHint = "set FAKE_API_KEY or ~/.fake"
        override val skipTestWhenKeyMissing = true
        override fun readApiKey(): String? = keySupplier()
        override fun createImpl(session: ContainerDriver, apiKey: String): FakeAgent = FakeAgent()
    }

    /** Companion using the default fail-fast behaviour (mirrors `DockerClaudeSession`). */
    private class FailFastCompanion(private val keySupplier: () -> String?) :
        AIAgentCompanion<FakeAgent>("fake-failfast-cli") {
        override val displayName = "Fake-FailFast"
        override val outputFilter: AgentProgressOutputFilter
            get() = error("not used by these tests")
        override val apiKeyHint = "set FAKE_API_KEY or ~/.fake"
        override fun readApiKey(): String? = keySupplier()
        override fun createImpl(session: ContainerDriver, apiKey: String): FakeAgent = FakeAgent()
    }

    @Test
    fun `isApiKeyAvailable true when readApiKey returns real key`() {
        assertTrue(SkipCompanion { "real-key" }.isApiKeyAvailable())
    }

    @Test
    fun `isApiKeyAvailable false when readApiKey returns null`() {
        assertFalse(SkipCompanion { null }.isApiKeyAvailable())
    }

    @Test
    fun `isApiKeyAvailable false when readApiKey returns unresolved TC reference`() {
        assertFalse(SkipCompanion { "%credentialsJSON:abcdef-…%" }.isApiKeyAvailable())
    }

    @Test
    fun `requireApiKey on skip-companion with missing key throws AssumptionViolatedException`() {
        val companion = SkipCompanion { null }
        val ex = assertThrows(org.junit.AssumptionViolatedException::class.java) {
            invokeRequireApiKey(companion)
        }
        assertNotNull(ex.message)
        assertTrue(ex.message!!.contains("API key not found"))
        assertTrue(ex.message!!.contains("Fake-Skip"))
    }

    @Test
    fun `requireApiKey on skip-companion with unresolved TC ref throws IllegalStateException`() {
        // The unresolved-TC-ref branch must never be turned into a skip — that case
        // is a real misconfiguration on TeamCity that must stay visible.
        val companion = SkipCompanion { "%credentialsJSON:abcdef-…%" }
        val ex = assertThrows(IllegalStateException::class.java) {
            invokeRequireApiKey(companion)
        }
        assertTrue(ex.message!!.contains("unresolved TeamCity reference"))
    }

    @Test
    fun `requireApiKey on fail-fast companion with missing key throws IllegalStateException`() {
        // Anthropic / OpenAI keep failing fast — do not extend the Gemini opt-in.
        val companion = FailFastCompanion { null }
        val ex = assertThrows(IllegalStateException::class.java) {
            invokeRequireApiKey(companion)
        }
        assertTrue(ex.message!!.contains("API key not found"))
    }

    @Test
    fun `requireApiKey on fail-fast companion with unresolved TC ref throws IllegalStateException`() {
        val companion = FailFastCompanion { "%credentialsJSON:abcdef-…%" }
        val ex = assertThrows(IllegalStateException::class.java) {
            invokeRequireApiKey(companion)
        }
        assertTrue(ex.message!!.contains("unresolved TeamCity reference"))
    }

    @Test
    fun `requireApiKey returns the key when present and not a TC reference`() {
        val companion = SkipCompanion { "real-gemini-key" }
        assertEquals("real-gemini-key", invokeRequireApiKey(companion))
    }

    @Test
    fun `default skipTestWhenKeyMissing is false`() {
        // Guards against accidentally extending the Gemini opt-in to other agents.
        assertFalse(FailFastCompanion { null }.exposeSkipTestWhenKeyMissing())
    }

    @Test
    fun `Gemini companion opts into skip-when-key-missing`() {
        // Documented contract: Gemini, and only Gemini, sets the flag to true.
        assertTrue(DockerGeminiSession.exposeSkipTestWhenKeyMissing())
        assertFalse(DockerClaudeSession.exposeSkipTestWhenKeyMissing())
        assertFalse(DockerCodexSession.exposeSkipTestWhenKeyMissing())
    }

    @Test
    fun `isApiKeyAvailable resolves null key as not available`() {
        // Coverage for the path UsefulTestCase shouldRunTest() relies on.
        assertNull((SkipCompanion { null }).readApiKeyForTest())
        assertFalse(SkipCompanion { null }.isApiKeyAvailable())
    }

    @Test
    fun `companion construction does not call readApiKey — session creation must stay lazy`() {
        // Per CLAUDE.md "Single documented exception: Gemini API key on CI", session
        // creation must be lazy: the API key check happens at requireApiKey()-time
        // (inside `create()`), NOT at companion construction or test-class init.
        // That guarantees a missing-key failure is reported against the test method
        // that actually called `newAiSession()` — not against `setUp()`, the class
        // loader, or a Gradle filter.
        var keySupplierCalls = 0
        val companion = SkipCompanion {
            keySupplierCalls++
            null
        }
        // Constructing the companion plus reading its eager properties must NOT
        // trigger readApiKey.
        assertEquals("Fake-Skip", companion.displayName)
        assertEquals(0, keySupplierCalls, "readApiKey must not be invoked eagerly")

        // Now exercise the documented entry point. Only here may readApiKey fire.
        assertThrows(org.junit.AssumptionViolatedException::class.java) {
            invokeRequireApiKey(companion)
        }
        assertTrue(keySupplierCalls > 0, "readApiKey is expected at requireApiKey()-time")
    }

    // ── Reflection helpers — `requireApiKey`, `readApiKey`, and `skipTestWhenKeyMissing`
    //    are protected on AIAgentCompanion. The tests want to exercise them on real
    //    subclasses without weakening visibility on production code.
    private fun invokeRequireApiKey(companion: AIAgentCompanion<*>): String {
        val method = AIAgentCompanion::class.java.getDeclaredMethod("requireApiKey")
        method.isAccessible = true
        return try {
            method.invoke(companion) as String
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun AIAgentCompanion<*>.readApiKeyForTest(): String? {
        val method = AIAgentCompanion::class.java.getDeclaredMethod("readApiKey")
        method.isAccessible = true
        return method.invoke(this) as String?
    }

    private fun AIAgentCompanion<*>.exposeSkipTestWhenKeyMissing(): Boolean {
        val getter = AIAgentCompanion::class.java
            .getDeclaredMethod("getSkipTestWhenKeyMissing")
        getter.isAccessible = true
        return getter.invoke(this) as Boolean
    }
}
