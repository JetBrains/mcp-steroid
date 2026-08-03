/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.storage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel

/**
 * Serializes append-style side effects (execution-event writes) so that:
 *
 *  1. **Order is deterministic.** Actions are drained by a single worker coroutine in
 *     the exact order they were [submit]ted. Callers that emit many events in quick
 *     succession no longer fan out into a race on a multi-threaded dispatcher — the
 *     root cause of `output.jsonl` lines scrambling into hundreds of orderings (#284).
 *
 *  2. **Failures are loud.** A write that throws is surfaced by [awaitCompletion];
 *     it is never logged-and-ACKed as success. This replaces the earlier
 *     `SupervisorJob` + `join()` fan-out, where a real write error was routed to the
 *     coroutine exception handler while the tool call still reported success.
 *
 * The worker runs under an isolated [SupervisorJob] child of [parentScope] so a write
 * failure is contained (does not cancel siblings) until it is deliberately re-raised by
 * [awaitCompletion]. When [parentScope] is cancelled, the worker is cancelled with it.
 */
class SerialWriteQueue(parentScope: CoroutineScope) {
    // A supervisor child of parentScope: a failing write stays contained here (does not
    // cancel parentScope) until awaitCompletion() re-raises it, yet parentScope cancellation
    // still tears the worker down. Completed by awaitCompletion() so it never keeps
    // parentScope structurally alive.
    private val supervisor = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + supervisor)
    private val channel = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val worker = scope.async {
        for (action in channel) {
            action()
        }
    }

    /**
     * Enqueue an action to run after all previously submitted actions, on the queue's
     * single worker. Non-suspending and ordered: the submission order is the execution
     * order. Must not be called after [awaitCompletion].
     */
    fun submit(action: suspend () -> Unit) {
        val result = channel.trySend(action)
        check(result.isSuccess) { "SerialWriteQueue is already closed; cannot submit more actions" }
    }

    /**
     * Close the queue, wait for every submitted action to finish, and rethrow the first
     * action failure if any occurred. Returning normally means every write succeeded.
     */
    suspend fun awaitCompletion() {
        channel.close()
        try {
            worker.await()
        } finally {
            supervisor.complete()
        }
    }
}
