# DPAIA MCP Steroid Run, 2026-05

Compact report for TeamCity root build [69750](https://dpaia.internal.teamcity.cloud/buildConfiguration/DpaiaBenchmark_dpaia_mcp_steroid_Slice_All/69750). Evidence appendix: [dpaia-mcp-steroid-2026-05-evidence.md](dpaia-mcp-steroid-2026-05-evidence.md).

## Result

MCP Steroid was operational and slightly improved score, but the run exposed prompt/tool-use inefficiencies and one transport cancellation.

| Metric | Baseline | MCP | Delta |
|---|---:|---:|---:|
| Tasks | 154 | 154 | - |
| Average score | 45.63 | 49.13 | +3.50 |
| Successful tasks | 55 | 58 | +3 |
| Better/tied/worse vs other mode | 15 better | 24 better | 115 tied |
| Total duration | 19,956s | 20,235s | +279s |
| Total tokens | 148.7M | 421.5M | 2.83x |
| Cost | $113.88 | $362.59 | 3.18x |

The cost multiplier is louder than the token multiplier because MCP mode was almost entirely `claude-opus-4-7[1m]`, while baseline was mostly `claude-sonnet-4-6`. Different models have different token behavior and price curves.

## MCP Runtime

IDEA logs show MCP Steroid itself was generally available:

- 154 unique MCP sessions.
- 468 MCP tool calls.
- 243 `steroid_execute_code`, 104 `steroid_apply_patch`, 99 `steroid_list_projects`, 22 `steroid_fetch_resource`.
- 331 explicit successful tool responses.
- 5 explicit tool errors, all `steroid_apply_patch` validation errors.
- 1 HTTP 500 / transport exception.

Most log noise was benign: 550 VCS-silencer warnings and 308 `GET /mcp` 405 protocol probes.

## Bottlenecks

1. **Project readiness was too shallow.** The harness waited for HTTP readiness (`GET /mcp`) but not project smart mode. Across IDEA starts, 20/99 first tool calls and 9/75 first `steroid_execute_code` calls happened while IDEA was in dumb mode.
2. **`execute_code` prompt failures.** Agents invented helpers or invalid snippets (`buildProject`, `context.project`, `createProjectFile`, `projectDir`, bad `readText(vf)`, `return` in script body).
3. **Threading/action context.** Generated scripts hit write-thread, EDT, and background-write-action constraints.
4. **Path and anchor guessing.** Several calls used brittle anchors or guessed paths before resolving files through IDE indexes.
5. **Patch recovery.** `steroid_apply_patch` correctly rejected bad hunks, but recovery hints should be richer.
6. **Low-level highlighting APIs.** One failed task tried daemon highlighting internals and hit `DaemonProgressIndicator` / `HighlightingSession` preconditions.

## HTTP 500

The single transport failure is build [68416](https://dpaia.internal.teamcity.cloud/buildConfiguration/DpaiaBenchmark_dpaia_mcp_steroid_Task_dpaia__jhipster__sample__app_4/68416), `dpaia__jhipster__sample__app-4`.

Timeline:

- `17:16:04`: MCP HTTP readiness returned 200.
- `17:16:17`: IDEA entered dumb mode.
- `17:16:38`: first MCP tool call happened during dumb mode.
- `17:17:08`: first `steroid_execute_code` request happened during dumb mode.
- `17:17:27`: `McpScriptContextImpl` waited for indexing.
- `17:18:08`: `JobCancellationException` logged by `ExecutionManager` and `McpHttpTransport`; HTTP 500 logged.
- `17:18:14`: IDEA exited dumb mode.

Conclusion: the IDE did not permanently die. The request was cancelled while `execute_code` was waiting for indexing. The actionable bug is cancellation/observability handling plus a separate readiness gap.

## Created Issues

- [#46](https://github.com/jonnyzzz/mcp-steroid/issues/46) HTTP 500 under indexing cancellation.
- [#47](https://github.com/jonnyzzz/mcp-steroid/issues/47) Invented `execute_code` helper APIs.
- [#48](https://github.com/jonnyzzz/mcp-steroid/issues/48) Threading/action-context guidance.
- [#49](https://github.com/jonnyzzz/mcp-steroid/issues/49) Path and anchor guessing.
- [#50](https://github.com/jonnyzzz/mcp-steroid/issues/50) `apply_patch` recovery hints.
- [#51](https://github.com/jonnyzzz/mcp-steroid/issues/51) Low-level daemon highlighting API misuse.
- [#52](https://github.com/jonnyzzz/mcp-steroid/issues/52) Wait for project smart mode before starting MCP agent run.

