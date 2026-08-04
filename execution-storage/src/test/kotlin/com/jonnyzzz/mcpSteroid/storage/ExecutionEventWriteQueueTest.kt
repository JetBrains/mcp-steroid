/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.storage

import com.jonnyzzz.mcpSteroid.server.ExecCodeParams
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Contract of the serialized, fail-loud write pipeline behind execution-event storage. One guarantee
 * per historical defect:
 *
 *  - **Order (#284)**: a single worker awaits each write TO COMPLETION before the next, so file order
 *    equals submission order even though the storage re-dispatches internally
 *    (`withContext(Dispatchers.IO)` inside [ExecutionStorage.appendExecutionEventJsonLines]).
 *  - **Fail-loud (#433 follow-up)**: the first write failure is re-raised by [ExecutionEventWriteQueue.awaitCompletion],
 *    never swallowed-and-ACKed — while later records are STILL written, so one failure does not strand
 *    the rest of the audit log.
 *  - **Logging never throws or blocks**: submit is called from non-suspend logging callbacks (possibly
 *    on the EDT); overflow and late submits must not surface there.
 */
class ExecutionEventWriteQueueTest {

    @TempDir
    lateinit var tempDir: Path

    private fun runQueueTest(block: suspend () -> Unit) = runBlocking {
        withTimeout(10.seconds) { block() }
    }

    private val fixedClock = Clock.fixed(Instant.parse("2026-08-03T00:00:00.123Z"), ZoneOffset.UTC)

    private fun newStorage(): ExecutionStorage = ExecutionStorage(
        baseDirProvider = { tempDir },
        projectInfoProvider = { ExecutionProjectInfo("test-project", "/tmp/test-project") },
        backendInfoProvider = { ExecutionBackendInfo(kind = 's', name = "iu-test1234") },
        clock = fixedClock,
    )

    /** Throws on any batch whose text mentions FAIL; everything else is written for real. */
    private inner class FailOnMarkerStorage : ExecutionStorage(
        baseDirProvider = { tempDir },
        projectInfoProvider = { ExecutionProjectInfo("test-project", "/tmp/test-project") },
        backendInfoProvider = { ExecutionBackendInfo(kind = 's', name = "iu-test1234") },
        clock = Clock.fixed(Instant.parse("2026-08-03T00:00:00.123Z"), ZoneOffset.UTC),
    ) {
        override suspend fun appendExecutionEvents(executionId: ExecutionId, texts: List<String>) {
            if (texts.any { "FAIL" in it }) throw IOException("disk full")
            super.appendExecutionEvents(executionId, texts)
        }
    }

    /**
     * Blocks the FIRST write on [gate] (signalling [entered] once inside) and counts write calls, so a
     * test can deterministically pile records into the buffer while the worker is mid-write.
     */
    private inner class GatedCountingStorage(
        private val gate: CompletableDeferred<Unit>,
        private val entered: CompletableDeferred<Unit>,
    ) : ExecutionStorage(
        baseDirProvider = { tempDir },
        projectInfoProvider = { ExecutionProjectInfo("test-project", "/tmp/test-project") },
        backendInfoProvider = { ExecutionBackendInfo(kind = 's', name = "iu-test1234") },
        clock = Clock.fixed(Instant.parse("2026-08-03T00:00:00.123Z"), ZoneOffset.UTC),
    ) {
        var appendCalls = 0

        override suspend fun appendExecutionEvents(executionId: ExecutionId, texts: List<String>) {
            appendCalls++
            if (appendCalls == 1) {
                entered.complete(Unit)
                gate.await()
            }
            super.appendExecutionEvents(executionId, texts)
        }
    }

    private fun testExecParams(code: String) = ExecCodeParams(
        taskId = "test-task",
        code = code,
        reason = "test",
        timeout = 60,
    )

    private fun outputTexts(storage: ExecutionStorage, executionId: ExecutionId): List<String> {
        val outputJsonl = storage.resolveExecutionDir(executionId).resolve("output.jsonl")
        if (!Files.exists(outputJsonl)) return emptyList()
        return Files.readAllLines(outputJsonl).map {
            storage.oneLineJson.decodeFromString(TextMessage.serializer(), it).text
        }
    }

    @Test
    fun `records are written in submission order despite the storage's internal dispatcher hops`() = runQueueTest {
        val storage = newStorage()
        val executionId = storage.writeNewExecution(testExecParams("test"))
        val queue = ExecutionEventWriteQueue(CoroutineScope(coroutineContext), storage)

        val count = 500
        repeat(count) { index -> queue.submit(ExecutionEventRecord.Append(executionId, "line-$index")) }
        queue.awaitCompletion()

        assertEquals(
            (0 until count).map { "line-$it" },
            outputTexts(storage, executionId),
            "output.jsonl lines must be in submission order",
        )
    }

    @Test
    fun `a write failure surfaces at awaitCompletion while later records are still written`() = runQueueTest {
        // The failing record targets a DIFFERENT execution, so the coalescer is forced to split the
        // burst into three separate storage calls — the failure is contained to the middle one.
        val storage = FailOnMarkerStorage()
        val okExecution = storage.writeNewExecution(testExecParams("ok"))
        val failExecution = storage.writeNewExecution(testExecParams("fail"))
        val queue = ExecutionEventWriteQueue(CoroutineScope(coroutineContext), storage)

        queue.submit(ExecutionEventRecord.Append(okExecution, "before"))
        queue.submit(ExecutionEventRecord.Append(failExecution, "FAIL"))
        queue.submit(ExecutionEventRecord.Append(okExecution, "after"))

        val error = runCatching { queue.awaitCompletion() }.exceptionOrNull()
        assertTrue(error is IOException, "awaitCompletion must rethrow the write failure, got: $error")
        assertEquals("disk full", error?.message)
        assertEquals(
            listOf("before", "after"),
            outputTexts(storage, okExecution),
            "records after the failing one must still be written — one failure must not strand the queue",
        )
    }

    @Test
    fun `a submit after awaitCompletion never throws into the logging caller`() = runQueueTest {
        val storage = newStorage()
        val executionId = storage.writeNewExecution(testExecParams("test"))
        val queue = ExecutionEventWriteQueue(CoroutineScope(coroutineContext), storage)

        queue.submit(ExecutionEventRecord.Append(executionId, "first"))
        queue.awaitCompletion()

        queue.submit(ExecutionEventRecord.Append(executionId, "late")) // must not throw
        assertEquals(listOf("first"), outputTexts(storage, executionId), "a late record is dropped, not written")
    }

    @Test
    fun `overflow fails the call loudly instead of blocking the producer or buffering unboundedly`() = runQueueTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val storage = GatedCountingStorage(gate, entered)
        val executionId = storage.writeNewExecution(testExecParams("test"))
        val queue = ExecutionEventWriteQueue(CoroutineScope(coroutineContext), storage, capacity = 2)

        queue.submit(ExecutionEventRecord.Append(executionId, "w-0")) // worker takes it and parks in the write
        entered.await()
        queue.submit(ExecutionEventRecord.Append(executionId, "w-1")) // buffered
        queue.submit(ExecutionEventRecord.Append(executionId, "w-2")) // buffered — buffer now full
        queue.submit(ExecutionEventRecord.Append(executionId, "w-3")) // overflow: returns at once, recorded
        gate.complete(Unit)

        val error = runCatching { queue.awaitCompletion() }.exceptionOrNull()
        assertTrue(error is IllegalStateException && "overflow" in (error.message ?: ""), "got: $error")
        assertEquals(
            listOf("w-0", "w-1", "w-2"),
            outputTexts(storage, executionId),
            "every ACCEPTED record must still reach disk",
        )
    }

    @Test
    fun `parent scope cancellation tears the queue down without hanging or throwing at loggers`() = runQueueTest {
        // Production never observes awaitCompletion() after cancellation — build() runs in the same
        // cancelled scope — so what this pins is teardown liveness: no hang, and a logging-path submit
        // after the teardown drops instead of throwing.
        val gate = CompletableDeferred<Unit>() // never completed: the write hangs until cancelled
        val entered = CompletableDeferred<Unit>()
        val storage = GatedCountingStorage(gate, entered)
        val executionId = storage.writeNewExecution(testExecParams("test"))
        val scope = CoroutineScope(coroutineContext + Job())
        val queue = ExecutionEventWriteQueue(scope, storage)

        queue.submit(ExecutionEventRecord.Append(executionId, "w-0"))
        entered.await()
        scope.cancel()

        queue.awaitCompletion() // completes: cancellation is scope teardown, not a recorded write failure
        queue.submit(ExecutionEventRecord.Append(executionId, "late")) // must not throw
        assertEquals(emptyList<String>(), outputTexts(storage, executionId))
    }

    @Test
    fun `a buffered burst is coalesced into one write instead of one file open per line`() = runQueueTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val storage = GatedCountingStorage(gate, entered)
        val executionId = storage.writeNewExecution(testExecParams("test"))
        val queue = ExecutionEventWriteQueue(CoroutineScope(coroutineContext), storage)

        queue.submit(ExecutionEventRecord.Append(executionId, "line-0"))
        entered.await() // the worker is inside write #1; everything below lands in the buffer
        repeat(99) { index -> queue.submit(ExecutionEventRecord.Append(executionId, "line-${index + 1}")) }
        gate.complete(Unit)
        queue.awaitCompletion()

        assertEquals((0 until 100).map { "line-$it" }, outputTexts(storage, executionId))
        assertEquals(2, storage.appendCalls, "the 99 buffered records must drain as ONE coalesced write")
    }
}
