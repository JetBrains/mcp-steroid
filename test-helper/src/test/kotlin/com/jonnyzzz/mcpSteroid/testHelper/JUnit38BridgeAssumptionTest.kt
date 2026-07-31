/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import org.junit.AssumptionViolatedException
import org.junit.internal.runners.JUnit38ClassRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.runner.Description
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import org.junit.runner.notification.RunNotifier

/**
 * Runner-level regression pin for issue #408 — the JUnit 3↔4 bridge behaviour that
 * made `CliGeminiIntegrationTest`'s missing-key `AssumptionViolatedException` show up
 * as 6 test FAILURES on TeamCity instead of skips.
 *
 * Two facts of junit 4.13.2 are load-bearing for the fix and pinned here:
 *
 *  1. THE BUG: plain [JUnit38ClassRunner] (what Gradle's `useJUnit()` picks for any
 *     un-annotated `junit.framework.TestCase`, e.g. `BasePlatformTestCase` subclasses)
 *     reports an `AssumptionViolatedException` thrown from a JUnit 3 test as a
 *     FAILURE — its `OldTestClassAdaptingListener.addError` fires `fireTestFailure`
 *     for every `Throwable`, with no assumption branch. Never rely on the
 *     `requireApiKey()` assumption alone in a JUnit 3 test.
 *
 *  2. THE FIX MECHANISM: the bridge reports through the overridable
 *     `RunNotifier.fireTestFailure`, so a notifier-level reroute of
 *     `AssumptionViolatedException` to `fireTestAssumptionFailed` turns the same run
 *     into a SKIPPED result while the test stays visible (started/finished still
 *     fire). That reroute is exactly what the IntelliJ test-framework's
 *     `JUnit38AssumeSupportRunner` installs (verified against its sources in
 *     test-framework 261.22158.277; the class itself lives in the IntelliJ platform
 *     and is not on `:test-helper`'s classpath) — `CliGeminiIntegrationTest` runs
 *     under `@RunWith(JUnit38AssumeSupportRunner::class)` for this reason.
 */
class JUnit38BridgeAssumptionTest {

    /**
     * Minimal stand-in for a `BasePlatformTestCase` test whose body throws the
     * `requireApiKey()` assumption. NOT auto-discovered: `:test-helper` runs on the
     * JUnit Platform with the Jupiter engine only (no vintage engine), so
     * `junit.framework.TestCase` subclasses are collected solely when driven
     * explicitly, as below.
     */
    class AssumingJUnit3Case : junit.framework.TestCase() {
        fun testMissingKey() {
            throw AssumptionViolatedException("simulated missing Gemini API key")
        }
    }

    private class RecordingListener : RunListener() {
        val started = mutableListOf<Description>()
        val finished = mutableListOf<Description>()
        val failures = mutableListOf<Failure>()
        val assumptionFailures = mutableListOf<Failure>()

        override fun testStarted(description: Description) {
            started += description
        }

        override fun testFinished(description: Description) {
            finished += description
        }

        override fun testFailure(failure: Failure) {
            failures += failure
        }

        override fun testAssumptionFailure(failure: Failure) {
            assumptionFailures += failure
        }
    }

    @Test
    fun `plain JUnit38ClassRunner reports a JUnit 3 body assumption as a FAILURE`() {
        val recorder = RecordingListener()
        val notifier = RunNotifier().apply { addListener(recorder) }

        JUnit38ClassRunner(AssumingJUnit3Case::class.java).run(notifier)

        assertEquals(1, recorder.started.size, "exactly one test must have started")
        assertEquals(
            1, recorder.failures.size,
            "the plain JUnit 3 bridge must turn the assumption into a failure — " +
                "if this ever changes, the JUnit38AssumeSupportRunner workaround can be revisited"
        )
        assertInstanceOf(AssumptionViolatedException::class.java, recorder.failures.single().exception)
        assertEquals(0, recorder.assumptionFailures.size, "no assumption channel on the plain bridge")
        assertEquals(1, recorder.finished.size, "the test still finishes (stays visible in reports)")
    }

    @Test
    fun `rerouting fireTestFailure to fireTestAssumptionFailed reports the assumption as skipped`() {
        val recorder = RecordingListener()
        val original = RunNotifier().apply { addListener(recorder) }

        // The same wrapper JUnit38AssumeSupportRunner installs around the notifier.
        val rerouting = object : RunNotifier() {
            override fun fireTestStarted(description: Description) = original.fireTestStarted(description)
            override fun fireTestFinished(description: Description) = original.fireTestFinished(description)
            override fun fireTestAssumptionFailed(failure: Failure) = original.fireTestAssumptionFailed(failure)
            override fun fireTestFailure(failure: Failure) {
                if (failure.exception is AssumptionViolatedException) {
                    original.fireTestAssumptionFailed(failure)
                    return
                }
                original.fireTestFailure(failure)
            }
        }

        JUnit38ClassRunner(AssumingJUnit3Case::class.java).run(rerouting)

        assertTrue(recorder.failures.isEmpty(), "the rerouted assumption must not count as a failure")
        assertEquals(1, recorder.assumptionFailures.size, "the assumption must surface as a skip")
        assertInstanceOf(
            AssumptionViolatedException::class.java,
            recorder.assumptionFailures.single().exception,
        )
        assertEquals(1, recorder.started.size, "the test is still started (visible in reports)")
        assertEquals(1, recorder.finished.size, "the test still finishes (visible in reports)")
    }
}
