/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * How the cumulative output-token column of a trajectory was obtained.
 *
 * The distinction is published with every curve, and it is not a detail. The second denominator of
 * this experiment IS cumulative model output tokens, so a curve drawn against a silently estimated
 * column would be a claim about model compute backed by arithmetic nobody checked.
 */
enum class AcquisitionTokenAccounting {
    /**
     * Every assistant message in the transcript carried its own `usage.output_tokens`, and the sum of
     * those (one per distinct message id) agrees with the run's terminal `result` event.
     */
    PER_MESSAGE,

    /**
     * The per-message usage did not add up to the run total — the streaming events carry partial
     * counts — so the run total is attributed across the assistant turns in proportion to the
     * characters each of them emitted (visible text plus the JSON of the tool calls it issued).
     *
     * Exact at the end of the run by construction, monotone, and identical in both arms. It is an
     * estimator and is labelled as one wherever it is used.
     */
    PROPORTIONAL,
}

/** One budgeted environment interaction, with everything the curve needs about it. */
data class AcquisitionToolCall(
    /** 1-based position among the BUDGETED interactions — the x axis of the first curve. */
    val ordinal: Int,
    val toolName: String,
    val requestJson: String,
    val resultText: String,
    /** Model output tokens emitted from the first turn up to and including the turn that issued this call. */
    val cumulativeOutputTokens: Long,
    /** Seconds from the first assistant event to this call's result, when the transcript timestamps it. */
    val elapsedSeconds: Long?,
)

/**
 * A research run, reduced to the sequence of things the agent asked the environment and got back.
 *
 * The reduction is the point of the whole design. The understanding-note round spent one paid research
 * run per budget; here one run to `B = 40` yields the knowledge state at 5, 10, 20 and 40 by slicing,
 * so the acquisition curve is measured WITHIN a trajectory and the between-trajectory variance — which
 * the last round showed to be enormous — is not mixed into the shape of the curve. What replication
 * buys is then spent where it belongs: on more independent trajectories, not on more points.
 *
 * Slicing is only legitimate because a prefix of a transcript is exactly what the agent had seen at
 * that moment. It is NOT legitimate to claim the agent would have *stopped* there and written the same
 * note; that is why the actionable curve is produced by re-distilling each prefix through a fixed
 * procedure ([buildAcquisitionDistillPrompt]) rather than by pretending the final note was written at
 * every checkpoint.
 */
data class AcquisitionTrajectory(
    val trajectoryId: String,
    val caseId: String,
    val arm: String,
    val model: String,
    val calls: List<AcquisitionToolCall>,
    /**
     * Calls the budget hook does not charge for — tool discovery, the agent's own to-do list, and the
     * semantic arm's connection bootstrap. Reported, never scored, and deliberately NOT counted as
     * semantic usage: an arm that only ever asked which projects are open has not asked the repository
     * anything.
     */
    val exemptCalls: Int,
    /** Calls the hook refused after the wall. A large number means the budget, not the agent, ended the run. */
    val refusedCalls: Int,
    val totalOutputTokens: Long,
    val tokenAccounting: AcquisitionTokenAccounting,
    val finalMessage: String?,
) {
    val budgetedCalls: Int get() = calls.size

    /**
     * Everything the agent had been told by the environment after its first [k] budgeted interactions.
     *
     * `k` larger than the run is not an error and is not silently clamped into a lie: the prefix is the
     * whole run and [AcquisitionPrefix.complete] says so, which is how a trajectory that stopped at
     * six calls appears on a curve drawn to forty without inventing thirty-four.
     */
    fun prefix(k: Int): AcquisitionPrefix {
        require(k >= 0) { "a checkpoint is a non-negative number of interactions, got $k" }
        val taken = calls.take(k)
        return AcquisitionPrefix(
            trajectory = this,
            requestedCalls = k,
            calls = taken,
            complete = k <= calls.size,
        )
    }
}

/** The state of a trajectory after a checkpoint, and the two denominators that go with it. */
data class AcquisitionPrefix(
    val trajectory: AcquisitionTrajectory,
    val requestedCalls: Int,
    val calls: List<AcquisitionToolCall>,
    val complete: Boolean,
) {
    val actualCalls: Int get() = calls.size
    val cumulativeOutputTokens: Long get() = calls.lastOrNull()?.cumulativeOutputTokens ?: 0L
    val elapsedSeconds: Long? get() = calls.lastOrNull()?.elapsedSeconds
    val toolResults: List<String> get() = calls.map { it.resultText }
    val toolNames: List<String> get() = calls.map { it.toolName }
}

private val acquisitionJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Parse a Claude Code `stream-json` transcript into a trajectory.
 *
 * Two rules here reproduce the in-container budget hook rather than approximating it, because a
 * trajectory whose call numbering disagreed with the hook's would put every checkpoint at the wrong
 * place: a call is charged unless its tool is in [UNDERSTANDING_BUDGET_EXEMPT_TOOLS], and a call whose
 * result is the refusal the hook emits is not charged either — it never reached the environment.
 * [UnderstandingHarnessTest] pins both against the hook's own constants.
 *
 * The transcript is read line by line and unparseable lines are skipped rather than fatal: the raw
 * NDJSON of a real run is interleaved with the container's own console output, and a strict reader
 * would throw away a paid run over a log line.
 */
fun parseAcquisitionTrajectory(
    rawNdjson: String,
    trajectoryId: String,
    caseId: String,
    arm: String,
    exemptTools: List<String> = UNDERSTANDING_BUDGET_EXEMPT_TOOLS,
): AcquisitionTrajectory {
    var model = "unknown"
    var finalMessage: String? = null
    var resultOutputTokens: Long? = null
    var firstTimestampMs: Long? = null

    // Assistant turns, in order, with the two things a token column can be built from.
    val turnOutputTokens = LinkedHashMap<String, Long>()
    val turnEmittedChars = LinkedHashMap<String, Long>()
    // toolUseId -> the id of the assistant turn that issued it, and the call itself.
    data class PendingCall(val turnId: String, val toolName: String, val requestJson: String)

    val pending = LinkedHashMap<String, PendingCall>()
    data class RawResult(val toolUseId: String, val text: String, val timestampMs: Long?)

    val results = ArrayList<RawResult>()

    for (line in rawNdjson.lineSequence()) {
        val start = line.indexOf("{\"type\":")
        if (start < 0) continue
        val event = try {
            acquisitionJson.parseToJsonElement(line.substring(start)).jsonObject
        } catch (e: Exception) {
            continue
        }
        when (event["type"]?.jsonPrimitive?.contentOrNull) {
            "assistant" -> {
                val message = event["message"]?.jsonObject ?: continue
                val turnId = message["id"]?.jsonPrimitive?.contentOrNull ?: continue
                message["model"]?.jsonPrimitive?.contentOrNull?.let { model = it }
                val usage = message["usage"]?.jsonObject
                val output = usage?.get("output_tokens")?.jsonPrimitive?.longOrNull ?: 0L
                turnOutputTokens[turnId] = maxOf(turnOutputTokens[turnId] ?: 0L, output)
                var emitted = turnEmittedChars[turnId] ?: 0L
                for (block in message["content"]?.jsonArray.orEmpty()) {
                    val obj = block.jsonObject
                    when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                        "text" -> emitted += (obj["text"]?.jsonPrimitive?.contentOrNull ?: "").length
                        "thinking" -> emitted += (obj["thinking"]?.jsonPrimitive?.contentOrNull ?: "").length
                        "tool_use" -> {
                            val requestJson = obj["input"]?.toString() ?: "{}"
                            emitted += requestJson.length
                            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
                            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
                            pending[id] = PendingCall(turnId, name, requestJson)
                        }
                    }
                }
                turnEmittedChars[turnId] = emitted
            }

            "user" -> {
                val timestampMs = event["timestamp"]?.jsonPrimitive?.contentOrNull?.let(::parseIsoMillis)
                if (timestampMs != null && firstTimestampMs == null) firstTimestampMs = timestampMs
                val content = event["message"]?.jsonObject?.get("content") as? JsonArray ?: continue
                for (block in content) {
                    val obj = block.jsonObject
                    if (obj["type"]?.jsonPrimitive?.contentOrNull != "tool_result") continue
                    val id = obj["tool_use_id"]?.jsonPrimitive?.contentOrNull ?: continue
                    results += RawResult(id, renderToolResult(obj), timestampMs)
                }
            }

            "result" -> {
                finalMessage = event["result"]?.jsonPrimitive?.contentOrNull ?: finalMessage
                resultOutputTokens = event["usage"]?.jsonObject?.get("output_tokens")?.jsonPrimitive?.longOrNull
                    ?: resultOutputTokens
            }
        }
    }

    val perMessageTotal = turnOutputTokens.values.sum()
    val declaredTotal = resultOutputTokens ?: 0L
    val accounting = when {
        declaredTotal <= 0L -> AcquisitionTokenAccounting.PER_MESSAGE
        perMessageTotal >= declaredTotal * 0.9 -> AcquisitionTokenAccounting.PER_MESSAGE
        else -> AcquisitionTokenAccounting.PROPORTIONAL
    }
    val totalOutputTokens = if (accounting == AcquisitionTokenAccounting.PER_MESSAGE) {
        maxOf(perMessageTotal, declaredTotal)
    } else {
        declaredTotal
    }
    val totalEmitted = turnEmittedChars.values.sum().coerceAtLeast(1L)
    val cumulativeByTurn = LinkedHashMap<String, Long>()
    var runningTokens = 0L
    var runningChars = 0L
    for (turnId in turnEmittedChars.keys) {
        runningChars += turnEmittedChars[turnId] ?: 0L
        runningTokens += turnOutputTokens[turnId] ?: 0L
        cumulativeByTurn[turnId] = when (accounting) {
            AcquisitionTokenAccounting.PER_MESSAGE -> runningTokens
            AcquisitionTokenAccounting.PROPORTIONAL -> totalOutputTokens * runningChars / totalEmitted
        }
    }

    var ordinal = 0
    var exempt = 0
    var refused = 0
    val startMs = firstTimestampMs
    val calls = ArrayList<AcquisitionToolCall>()
    for (result in results) {
        val call = pending[result.toolUseId] ?: continue
        if (exemptTools.any { it.equals(call.toolName, ignoreCase = true) }) {
            exempt++
            continue
        }
        if (result.text.contains(UNDERSTANDING_BUDGET_EXHAUSTED_MESSAGE)) {
            refused++
            continue
        }
        ordinal++
        calls += AcquisitionToolCall(
            ordinal = ordinal,
            toolName = call.toolName,
            requestJson = call.requestJson,
            resultText = result.text,
            cumulativeOutputTokens = cumulativeByTurn[call.turnId] ?: 0L,
            elapsedSeconds = if (result.timestampMs != null && startMs != null) {
                (result.timestampMs - startMs) / 1000
            } else {
                null
            },
        )
    }

    return AcquisitionTrajectory(
        trajectoryId = trajectoryId,
        caseId = caseId,
        arm = arm,
        model = model,
        calls = calls,
        exemptCalls = exempt,
        refusedCalls = refused,
        totalOutputTokens = totalOutputTokens,
        tokenAccounting = accounting,
        finalMessage = finalMessage,
    )
}

/**
 * Flatten a `tool_result` block into the text the agent actually read.
 *
 * The shape differs between the shell tools (a string) and the MCP tools (a list of typed blocks), and
 * a reader that handled only the first would score the mcp arm as having observed nothing — the exact
 * shape of silent defect that cost the previous round twenty paid runs. Unknown block types are
 * serialised whole rather than dropped, on the same principle.
 */
private fun renderToolResult(block: JsonObject): String {
    val content = block["content"] ?: return block.toString()
    (content as? JsonPrimitive)?.contentOrNull?.let { return it }
    val array = content as? JsonArray ?: return content.toString()
    return array.joinToString("\n") { element ->
        val obj = element as? JsonObject ?: return@joinToString element.toString()
        obj["text"]?.jsonPrimitive?.contentOrNull ?: obj.toString()
    }
}

private fun parseIsoMillis(text: String): Long? = try {
    java.time.Instant.parse(text).toEpochMilli()
} catch (e: Exception) {
    null
}
