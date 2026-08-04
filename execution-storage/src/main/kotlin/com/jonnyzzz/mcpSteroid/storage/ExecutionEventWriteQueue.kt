/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.storage

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One execution's storage event, as plain data. The queue deliberately buffers records, not lambdas:
 * a queued lambda captures its enclosing scope (a `Project`, its services) and ties their reachability
 * to the queue's, while a record carries only what the write needs.
 */
sealed interface ExecutionEventRecord {
    val executionId: ExecutionId

    /** One line appended to the execution's `output.jsonl`. */
    data class Append(override val executionId: ExecutionId, val text: String) : ExecutionEventRecord

    /** The `error.txt` marker written next to the event log when a script fails. */
    data class CodeError(override val executionId: ExecutionId, val text: String) : ExecutionEventRecord
}

/**
 * Serialized, fail-loud write pipeline for execution-event storage. One instance serves one tool call.
 *
 *  1. **Order is deterministic.** A single worker coroutine drains records in submission order
 *     and awaits each write TO COMPLETION before taking the next. That guarantee survives suspension
 *     points inside the write ([ExecutionStorage.appendExecutionEventJson] re-dispatches via
 *     `withContext(Dispatchers.IO)`) — which is exactly what a `limitedParallelism(1)` dispatcher view
 *     cannot promise: its single slot is released at every suspension, so the next launched coroutine's
 *     write would race the first on the unlimited pool.
 *
 *  2. **Failures are loud, and never strand the queue.** Each write failure is contained per record
 *     run: the FIRST is kept and re-raised by [awaitCompletion] (at `build()` — a failed write fails
 *     the tool call, never a false ACK), later ones are logged, and draining CONTINUES, so one
 *     transient error does not silently discard every following event.
 *
 *  3. **[submit] never throws and never blocks.** It is called from non-suspend logging callbacks that
 *     may run on the EDT, so a full buffer must not park the caller and a closed queue must not throw
 *     into code that was merely logging: overflow is recorded as the call's failure (re-raised at
 *     `build()`), a late submit is logged to stderr and dropped.
 *
 *  4. **Memory is bounded.** [capacity] caps the buffer, and the batch-draining worker (one file open
 *     per buffered burst, [ExecutionStorage.appendExecutionEvents]) keeps the depth near zero — the cap
 *     is reachable only when the write backend is pathologically stuck, at which point failing the call
 *     loudly is the honest outcome.
 *
 * The worker is a plain child of [parentScope] (the per-tool-call scope): scope cancellation tears it
 * down, and [awaitCompletion] — the terminal operation called from `build()` — ends it normally.
 * Per-record containment means the worker itself cannot fail, so it needs no supervision. Because
 * cancellation kills the worker, the owner must also call [flushRemaining] on the way out, so a
 * cancelled call still keeps the part of its log that had already been emitted.
 */
class ExecutionEventWriteQueue(
    parentScope: CoroutineScope,
    private val storage: ExecutionStorage,
    private val capacity: Int = 10_000,
    private val flushTimeout: Duration = DEFAULT_FLUSH_TIMEOUT,
) {
    private val channel = Channel<ExecutionEventRecord>(capacity)

    // The first write (or overflow) failure. Written by the worker AND by submit(), re-raised once by
    // awaitCompletion(); the CAS keeps exactly the first.
    private val firstFailure = AtomicReference<Throwable?>(null)

    private val worker = parentScope.launch { drain { channel.receiveCatching().getOrNull() } }

    init {
        // The moment the worker completes FOR ANY REASON (normal drain or scope cancellation), close
        // the channel: every later submit fails fast into the log-and-drop arm instead of buffering
        // records nobody will ever read. Closed, not cancelled — cancelling discards whatever is still
        // buffered, and on the cancellation path that buffer is exactly what [flushRemaining] rescues.
        worker.invokeOnCompletion { channel.close() }
    }

    /**
     * Drain records until [nextRecord] reports the buffer exhausted, coalescing each run of appends to
     * one execution into a single write. Used by the worker (suspending receive) and, after the worker
     * is gone, by [flushRemaining] (non-suspending drain of what it left behind).
     */
    private suspend fun drain(nextRecord: suspend () -> ExecutionEventRecord?) {
        while (true) {
            val first = nextRecord() ?: return
            // Coalesce the burst that is already buffered so a run of appends to one execution costs
            // one file open, not one per line. Bounded: an unbounded batch would join the whole
            // buffer into one String, peaking at twice the pending bytes.
            val batch = ArrayList<ExecutionEventRecord>()
            batch.add(first)
            while (batch.size < MAX_BATCH_RECORDS) {
                batch.add(channel.tryReceive().getOrNull() ?: break)
            }
            var index = 0
            while (index < batch.size) {
                val record = batch[index]
                var end = index + 1
                try {
                    // Deliberately cancellable: shielding the write would make the worker impossible
                    // to end, and it is a child of the call's scope — a wedged backend would then hang
                    // the whole execution instead of just losing the batch in flight. What cancellation
                    // can cost here is bounded by MAX_BATCH_RECORDS; everything still buffered is
                    // rescued by flushRemaining().
                    when (record) {
                        is ExecutionEventRecord.Append -> {
                            while (end < batch.size) {
                                val next = batch[end]
                                if (next is ExecutionEventRecord.Append && next.executionId == record.executionId) end++
                                else break
                            }
                            storage.appendExecutionEvents(
                                record.executionId,
                                batch.subList(index, end).map { (it as ExecutionEventRecord.Append).text },
                            )
                        }
                        is ExecutionEventRecord.CodeError ->
                            storage.writeCodeErrorEvent(record.executionId, record.text)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!firstFailure.compareAndSet(null, e)) {
                        System.err.println("ExecutionEventWriteQueue: another write failed after the first recorded failure: $e")
                    }
                }
                index = end
            }
        }
    }

    /**
     * Non-suspending and never throwing: safe from any logging callback on any thread, including the
     * EDT (which must never be blocked on disk backpressure). A full buffer fails the CALL — loudly,
     * at [awaitCompletion] — never the logging caller.
     */
    fun submit(record: ExecutionEventRecord) {
        val result = channel.trySend(record)
        when {
            result.isSuccess -> return
            result.isClosed ->
                // After awaitCompletion() or scope cancellation: the call's outcome no longer depends
                // on this write — too late to fail it, and logging must not throw. Drop loudly.
                System.err.println("ExecutionEventWriteQueue: record submitted after completion was dropped: $record")
            else -> {
                val overflow = IllegalStateException(
                    "ExecutionEventWriteQueue overflow: more than $capacity storage writes are pending; " +
                        "the write backend cannot keep up, so the call fails instead of buffering unboundedly"
                )
                if (!firstFailure.compareAndSet(null, overflow)) {
                    System.err.println("ExecutionEventWriteQueue: $record dropped on overflow after the first recorded failure")
                }
            }
        }
    }

    /**
     * The mandatory terminal operation: close the queue, drain every accepted record, then re-raise
     * the first write (or overflow) failure. Returning normally means every accepted write reached
     * disk. Called from `build()`, so a genuine IO failure fails the tool call instead of being
     * ACKed as success.
     */
    suspend fun awaitCompletion() {
        flushRemaining()
        firstFailure.get()?.let { throw it }
    }

    /**
     * Finish the queue without raising: close it, let the worker end, and write whatever it left
     * buffered. Cancelling the tool call cancels the worker too, so without this the tail of the log
     * would be dropped exactly when a cancelled or timed-out run is being read to find out how far it
     * got. Runs under [NonCancellable] so it works on that path, and stays quiet so a write failure
     * cannot displace the reason the call ended. Idempotent.
     */
    suspend fun flushRemaining() {
        channel.close()
        withContext(NonCancellable) {
            // Bounded: NonCancellable means an outside cancellation can no longer break us out of a
            // stuck write, so the wait needs its own ceiling or a wedged disk would hang the call
            // permanently. Losing the tail beats never returning.
            val drained = withTimeoutOrNull(flushTimeout) {
                worker.join()
                drain { channel.tryReceive().getOrNull() }
            }
            if (drained == null) {
                System.err.println(
                    "ExecutionEventWriteQueue: giving up on the event log after $flushTimeout; " +
                        "the write backend is not draining, so the tail is incomplete"
                )
            }
        }
    }

    companion object {
        /** Upper bound on records coalesced into one write. */
        const val MAX_BATCH_RECORDS = 256

        /** How long the terminal drain waits for a stuck write backend before giving up. */
        val DEFAULT_FLUSH_TIMEOUT = 30.seconds
    }
}
