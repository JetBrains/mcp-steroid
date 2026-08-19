/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.testHelper.process.PROCESS_TIMEOUT_EXIT_CODE
import com.jonnyzzz.mcpSteroid.testHelper.process.PROCESS_TIMEOUT_STDERR_MARKER
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult

/**
 * True when the HARNESS killed the agent because its own time budget ran out, false for every other way
 * a run can end — including one the agent ended itself with a non-zero code.
 *
 * Read off the runner's real report rather than the wall clock. `ArenaTestRunner.runTest` hands the
 * case's `agentTimeoutSeconds` to `AiAgentSession.runPrompt`, so the budget is enforced by
 * `StartedProcess.awaitForProcessFinish`, which on expiry destroys the process and returns
 * [PROCESS_TIMEOUT_EXIT_CODE] with [PROCESS_TIMEOUT_STDERR_MARKER] at the head of stderr. Comparing
 * `agentDurationMs` against the budget instead would be a second, weaker implementation of a decision
 * the harness already made — and would mislabel a container that died a second before the limit.
 *
 * BOTH halves are required because each alone lies: a `docker exec` whose container disappeared also
 * exits [PROCESS_TIMEOUT_EXIT_CODE] but never carries the marker (an instrument failure, which must
 * keep withholding a verdict), and an agent may print or read the words itself while exiting normally.
 *
 * Why the distinction is worth a named function: the readiness pilot grades a run that exhausted its
 * budget as an UNSUCCESS (`Y=0`) and folds it into `V` — see [DpaiaRunOutcome.agentTimedOut] — while an
 * instrument failure is withheld. TeamCity build 1035674856 (`arm=mcp checkpoint=2 replicate=3`) spent
 * the whole 1800 s, exited -1 and was published as `LOST reason=not-graded`, quietly shrinking the
 * sample for that checkpoint.
 */
fun agentRunTimedOut(agentResult: ProcessResult): Boolean =
    agentResult.exitCode == PROCESS_TIMEOUT_EXIT_CODE &&
        agentResult.stderr.startsWith(PROCESS_TIMEOUT_STDERR_MARKER)
