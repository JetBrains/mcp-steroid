/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.seconds

/**
 * Contract test for [McpEditingGuard]. Pins the per-call overhead so the
 * Cli-level slowness regression (4-minute hangs traced to write-intent
 * contention from a parked-EDT test fixture) cannot reappear silently.
 *
 * The guard runs the canonical pre-/post-flight around every MCP edit:
 * commit + saveAll + awaitRefresh BEFORE, body, awaitRefresh AFTER. Each
 * `awaitRefresh` is capped at 30 s by [VfsRefreshService]. Under healthy
 * conditions the cap should never fire — a test breaking sub-second is
 * the canary for a future regression that re-introduces lock contention,
 * EDT-parking, or a similar pathology.
 */
class McpEditingGuardTest : BasePlatformTestCase() {

    /**
     * Critical: same opt-out [McpServerIntegrationTest] and
     * [VfsRefreshServiceTest] use. Without it the test parks on the EDT inside
     * a write-intent transaction; the guard's `awaitRefresh` (suspend
     * RefreshQueue.refresh) then can never acquire write-intent and hits its
     * 30 s safety timeout — exactly the production regression we are guarding
     * against.
     */
    override fun runInDispatchThread(): Boolean = false

    fun testEmptyBodyCompletesWellBelowRefreshCap(): Unit = timeoutRunBlocking(30.seconds) {
        val guard = mcpEditingGuard()
        val executionId = ExecutionId("guard-test-empty")

        val elapsedMs = measureTimeMillis {
            guard.withEditingGuard(project, executionId) { /* empty body */ }
        }

        // Two awaitRefresh calls (BEFORE + AFTER) plus commit + saveAll. Each
        // refresh is capped at 30 s; if either timer fires we'd be at 30+ s.
        // 5 s is a generous ceiling that still catches a regression shy of the
        // full timeout — Cli-level slowness manifests as ~30 s per refresh.
        assertTrue(
            "withEditingGuard with empty body should complete well below the refresh cap, took ${elapsedMs}ms",
            elapsedMs < 5_000L,
        )
    }

    fun testBackToBackCallsDoNotDegradeLinearly(): Unit = timeoutRunBlocking(30.seconds) {
        val guard = mcpEditingGuard()
        val n = 5
        val timings = mutableListOf<Long>()

        for (i in 1..n) {
            val executionId = ExecutionId("guard-test-back-to-back-$i")
            timings += measureTimeMillis {
                guard.withEditingGuard(project, executionId) { /* empty body */ }
            }
        }

        // Each subsequent call must remain in the same order of magnitude as
        // the first. A bug that re-introduces per-call write-intent contention
        // would show as monotonically growing latency (or one call >5 s) as
        // refresh queues pile up.
        for ((i, ms) in timings.withIndex()) {
            assertTrue(
                "call #${i + 1} took ${ms}ms — expected each call < 5_000ms; full timings: $timings",
                ms < 5_000L,
            )
        }
    }

    fun testConcurrentCallsAllCompleteUnderRefreshCap(): Unit = timeoutRunBlocking(60.seconds) {
        val guard = mcpEditingGuard()
        val burstSize = 4

        val timings = coroutineScope {
            (1..burstSize).map { i ->
                async {
                    val executionId = ExecutionId("guard-test-concurrent-$i")
                    measureTimeMillis {
                        guard.withEditingGuard(project, executionId) { /* empty body */ }
                    }
                }
            }.awaitAll()
        }

        // Burst of 4 simulates Claude's 60 s tool-timeout retry pattern: four
        // concurrent McpEditingGuard invocations stacking up on the same
        // project. Pre-fix this scenario produced 30 s timeouts on every BEFORE
        // and AFTER refresh; we cap each call well below that to catch any
        // regression that brings back write-intent contention under fan-out.
        for ((i, ms) in timings.withIndex()) {
            assertTrue(
                "concurrent call #${i + 1} took ${ms}ms — expected each < 10_000ms even under contention; full timings: $timings",
                ms < 10_000L,
            )
        }
    }

    fun testBodyExceptionPropagates(): Unit = timeoutRunBlocking(30.seconds) {
        val guard = mcpEditingGuard()
        val executionId = ExecutionId("guard-test-body-throws")
        val sentinel = "body-failure-sentinel"

        val thrown = try {
            guard.withEditingGuard(project, executionId) {
                throw RuntimeException(sentinel)
            }
            null
        } catch (e: RuntimeException) {
            e
        }
        assertNotNull("Body exception must propagate to the caller", thrown)
        assertEquals("Exception message must round-trip unchanged", sentinel, thrown!!.message)
    }
}
