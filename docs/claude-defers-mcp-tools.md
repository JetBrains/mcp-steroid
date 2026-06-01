# Claude Code defers the mcp-steroid tools → "NO_IDE_ACCESS" without trying

## Symptom

A Claude Code agent (`claude -p`, v2.1.x) configured with the mcp-steroid MCP server sometimes answers
an IDE question (e.g. "describe the IntelliJ IDEA state") with **"no IDE access"** in a single turn,
**without calling any tool** — even though the IDE and the MCP server are up and reachable. Codex and
Gemini, with the identical MCP URL, do not have this problem.

Found while making `:test-integration` `WhatYouSeeTest` deterministic. `checkWhatYouSee claude`,
`FindDuplicatesPromptTest` (claude/gemini) and `MavenRunnerAdoptionTest` (claude) failed **identically
across 2–3 reruns** — not flaky, deterministic.

## Root cause: deferred MCP tool schemas (NOT a connection race)

Investigated with claude's `--debug mcp`, the IDE-side `McpHttpTransport` request log, and the agent's
raw NDJSON:

- **Claude connects fine.** The server logs a `claude-code/2.1.159` `initialize` on **every** `claude -p`
  invocation, completing in ~20 ms. The MCP URL (`http://localhost:6754/mcp`) is identical to the one
  codex/gemini use successfully.
- **The `steroid_*` tools are absent from claude's initial tool list.** Claude's system-init message lists
  only built-ins + `ToolSearch` (no `steroid_*`), with `mcp_servers: [{name: mcp-steroid, status:
  pending}]`. Claude Code **defers** MCP tool schemas (lazy-load): the model must call `ToolSearch` to
  load a tool's schema before it can be invoked.
- **On the bail, claude made ZERO tool calls.** It read its tool list, saw no IDE tools, did **not** call
  `ToolSearch`, and answered `NO_IDE_ACCESS` straight away — because the prompt offered a no-tool escape
  hatch.

### Why some prompts pass and others fail (deterministically)

| Prompt shape | Claude behavior | Result |
|---|---|---|
| **Forces** a steroid tool ("list your MCP tools", "use steroid_execute_code to…") | calls `ToolSearch` → loads schemas → uses them | PASS |
| Offers a **no-tool escape hatch** ("…or say NO_IDE_ACCESS") | never `ToolSearch`es → bails | FAIL |
| Has a **native fallback** (Read/Grep for find-duplicates, `bash mvn` for run-tests) | proceeds with native tools, never loads steroid | FAIL ("didn't use steroid") |

Codex/Gemini include the steroid tools in their initial tool list (no deferral) → they always pass.

## Impact — this is a real production UX gap, not just a test artifact

A real user who asks Claude Code a casual IDE question with mcp-steroid configured can get "no IDE access"
because the tools are deferred and claude won't `ToolSearch` for them unless the request forces a tool.
The IDE integration then *appears absent* even though it is connected.

## Fix directions

1. **Reduce/avoid deferral.** Claude Code defers above a visible-tool-count threshold; the ~10 narrow
   steroid tools plus claude's ~30 built-ins push it over. Investigate a Claude-Code knob to eager-load
   this server's tools, or otherwise keep the co-loaded tool count down.
2. **Strengthen the agent-facing prompt / server `instructions`** so that *accessing IntelliJ IDEA goes
   through the MCP tools* is stated up front and `ToolSearch`-first is unmistakable — and verify Claude
   Code injects + acts on the server `instructions` in `-p` mode. (This is the change made alongside this
   note.)
3. **Tests**: prime a forced-`ToolSearch` setup turn before measuring, or treat the tool-adoption checks
   as prompt-quality signals (the IMPROVEMENTS harness) rather than hard stability gates.

## Validation: strengthening the prompt is necessary but NOT sufficient

After rewriting the server `instructions` (`mcp-steroid-info.md`) to demand "access the IDE via the
`steroid_*` tools; load them with `ToolSearch` first; never say NO_IDE_ACCESS before trying them",
`checkWhatYouSee claude` **still failed identically**: `mcp_servers: pending`, no `steroid_*` in the
initial tool list, **zero tool calls**, `NO_IDE_ACCESS`.

Why: the server `instructions` are returned in the **`initialize` response**, which lands only *after*
the MCP connection completes — but claude answers the 1-turn prompt **while the connection is still
`pending`**, so the improved instructions are not yet in its context when it bails. The prompt change
still helps the paths where claude *does* connect + read (production interactive sessions, and the
forcing test prompts), but it cannot fix the pre-connection bail.

**The actual lever is the deferral (#1):** get the `steroid_*` tools into claude's *initial* tool list
(prevent deferral / eager-load this server), or make claude wait for the MCP connection before its first
turn. For the tests specifically, a connection-readiness gate (prime a forced tool call until it succeeds)
would also remove the race.

## Evidence pointers

- Server requests by client: `claude-code/2.1.159`, `codex-mcp-client`, `gemini-cli-mcp-client`, `curl`
  (harness) — all to `http://localhost:6754/mcp`.
- Agent NDJSON: `…/run-*-what-you-see/agent-claude-code-1-raw.ndjson` — system-init `tools` list has no
  `steroid_*`; the `checkWhatYouSee` turn makes no tool call and returns `NO_IDE_ACCESS`.
- Full sweep context: `test-integration/TODO-stability-report.md`.
