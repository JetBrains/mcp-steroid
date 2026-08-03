/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.storage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * Contract for the serialized, fail-loud write pipeline that backs execution-event
 * storage. Two guarantees, one per historical defect:
 *
 *  - **Order** (#284): actions run in submission order, never interleaved — even when
 *    each action would otherwise race on a multi-threaded dispatcher. This is what
 *    keeps `output.jsonl` lines deterministic instead of scrambling into hundreds of
 *    permutations.
 *  - **Fail-loud** (#433 follow-up): a failing action is surfaced by `awaitCompletion()`,
 *    never swallowed-and-ACKed. A genuine write error must reach the caller.
 */
class SerialWriteQueueTest {

    private fun runQueueTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(10.seconds) { block() }
    }

    @Test
    fun `actions run in submission order despite per-action jitter`() = runQueueTest {
        val queue = SerialWriteQueue(this)
        val executionOrder = CopyOnWriteArrayList<Int>()

        val count = 500
        repeat(count) { index ->
            queue.submit {
                // Jitter that would reorder the recorded sequence if actions ran concurrently.
                delay((index % 5).toLong())
                executionOrder.add(index)
            }
        }
        queue.awaitCompletion()

        assertEquals((0 until count).toList(), executionOrder.toList(),
            "actions must execute in the exact order they were submitted")
    }

    @Test
    fun `a failing action is surfaced by awaitCompletion instead of being swallowed`() = runQueueTest {
        val queue = SerialWriteQueue(this)
        queue.submit { throw java.io.IOException("disk full") }

        val error = runCatching { queue.awaitCompletion() }.exceptionOrNull()
        assertTrue(error is java.io.IOException,
            "awaitCompletion must rethrow the write failure, got: $error")
        assertEquals("disk full", error?.message)
    }

    @Test
    fun `awaitCompletion drains every queued action before returning`() = runQueueTest {
        val queue = SerialWriteQueue(CoroutineScope(coroutineContext + Job() + Dispatchers.Default))
        val done = CopyOnWriteArrayList<Int>()
        repeat(50) { index -> queue.submit { delay(1); done.add(index) } }

        queue.awaitCompletion()

        assertEquals(50, done.size, "all queued actions must complete before awaitCompletion returns")
    }
}
