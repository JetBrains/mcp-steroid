# DPAIA MCP Steroid 2026-05 Evidence

This appendix backs [dpaia-mcp-steroid-2026-05-report.md](dpaia-mcp-steroid-2026-05-report.md). Source root: TeamCity build [69750](https://dpaia.internal.teamcity.cloud/buildConfiguration/DpaiaBenchmark_dpaia_mcp_steroid_Slice_All/69750).

## Fact Sources

Local analysis folder used to validate the report:

- `analysis/report-score-summary.json`
- `analysis/all-token-summary.json`
- `analysis/all-token-by-model.tsv`
- `analysis/mcp-idea-log-summary.json`
- `analysis/mcp-idea-log-tools.tsv`
- `analysis/mcp-idea-log-problem-categories.tsv`
- `analysis/actionable-mcp-failures.tsv`
- `analysis/mcp-indexing-readiness-summary.json`
- `analysis/mcp-indexing-readiness.tsv`
- `analysis/http500-68416/idea.log`
- `analysis/http500-68416/diagnostic-2026-05-11-17-18-14.409.json`

Artifact/log inventory:

- 154 child builds.
- 462 published child artifacts.
- 277,742,368 published artifact bytes.
- 6,312 IDEA-log entries scanned.
- 580,682,260 IDEA-log bytes scanned.

## Score Facts

From `analysis/report-score-summary.json`:

- `task_count`: 154
- `baseline_average_score`: 45.630402426953495
- `mcp_average_score`: 49.13410727008893
- `baseline_successes`: 55
- `mcp_successes`: 58
- `mcp_better`: 24
- `baseline_better`: 15
- `ties`: 115
- `baseline_total_duration_s`: 19956.424998044968
- `mcp_total_duration_s`: 20235.274941444397
- `baseline_total_tokens`: 148669285
- `mcp_total_tokens`: 421453532
- `baseline_total_cost`: 113.88022955
- `mcp_total_cost`: 362.59244765

## Token And Cost Facts

From `analysis/all-token-summary.json`:

- Total token ratio, MCP vs baseline: 2.834839301204684.
- Cost ratio, MCP vs baseline: 3.1839806530316226.
- Cache-read token ratio, MCP vs baseline: 2.8852622662136254.
- Output-token ratio, MCP vs baseline: 1.2989151662206708.

From `analysis/all-token-by-model.tsv`:

- Baseline: `claude-sonnet-4-6`, cost `$98.28165285000004`.
- Baseline: `claude-haiku-4-5-20251001`, cost `$15.598576700000006`.
- MCP: `claude-opus-4-7[1m]`, cost `$362.28957649999984`.
- MCP: `claude-haiku-4-5-20251001`, cost `$0.3028711499999999`.

## MCP Log Facts

From `analysis/mcp-idea-log-summary.json`:

- `builds_scanned`: 154
- `unique_sessions`: 154
- `tool_calls`: 468
- `tool_successes`: 331
- `tool_errors`: 5
- `http_500`: 1
- `http_405`: 154
- `mcp_matching_lines`: 48243
- `plugin_versions`: `0.95.0-b14969e1`

Tool calls:

- `steroid_execute_code`: 243 calls, 177 successes, 0 explicit tool errors.
- `steroid_apply_patch`: 104 calls, 55 successes, 5 explicit tool errors.
- `steroid_list_projects`: 99 calls, 99 successes.
- `steroid_fetch_resource`: 22 calls.

Problem categories:

- `mcp_vcs_silencer_warning`: 550
- `mcp_get_sse_405_benign`: 308
- `mcp_script_unexpected_error_warning`: 21
- `mcp_execute_code_runtime_failed`: 21
- `mcp_other_warning_line`: 17
- `mcp_execute_code_compile_warning`: 12
- `mcp_execute_code_compile_failed`: 7
- `mcp_tool_error_response`: 5
- `mcp_other_failed_line`: 5
- `mcp_transport_exception`: 1
- `mcp_http_500`: 1
- `mcp_test_run_failed_result`: 1

## Indexing Readiness Facts

From `analysis/mcp-indexing-readiness-summary.json`:

- `sessions`: 308
- `sessions_with_tool_call`: 99
- `sessions_with_execute_code`: 75
- `sessions_active_dumb_at_first_tool`: 20
- `sessions_active_dumb_at_first_execute_code`: 9
- `sessions_execute_code_waited_for_indexing`: 8
- `sessions_with_index_finish_after_first_tool`: 30

HTTP 500 build [68416](https://dpaia.internal.teamcity.cloud/buildConfiguration/DpaiaBenchmark_dpaia_mcp_steroid_Task_dpaia__jhipster__sample__app_4/68416):

- `17:16:04.449`: `GET /mcp` returned 200.
- `17:16:17.745`: IDEA entered dumb mode.
- `17:16:38.808`: first MCP tool call, `steroid_list_projects`.
- `17:17:08.712`: first `steroid_execute_code`.
- `17:17:27.091`: `McpScriptContextImpl` logged waiting for indexing.
- `17:17:33.940`: scanner completed 92,434 scanned files; 91,721 scheduled for indexing.
- `17:18:08.720`: `McpHttpTransport` logged `JobCancellationException`.
- `17:18:08.734`: HTTP 500 logged.
- `17:18:14.417`: IDEA exited dumb mode.

Diagnostic `analysis/http500-68416/diagnostic-2026-05-11-17-18-14.409.json`:

- `type`: `DumbIndexing`
- `numberOfFilesIndexedWithLoadingContent`: 91865
- `totalWallTimeWithPauses`: 40456397536 ns
- `dumbModeStart`: `2026-05-11T17:17:33.95221892Z`
- `updatingEnd`: `2026-05-11T17:18:14.408616456Z`

## GitHub Issues

- [#46](https://github.com/jonnyzzz/mcp-steroid/issues/46) `bug`
- [#47](https://github.com/jonnyzzz/mcp-steroid/issues/47) `enhancement`
- [#48](https://github.com/jonnyzzz/mcp-steroid/issues/48) `enhancement`
- [#49](https://github.com/jonnyzzz/mcp-steroid/issues/49) `enhancement`
- [#50](https://github.com/jonnyzzz/mcp-steroid/issues/50) `enhancement`
- [#51](https://github.com/jonnyzzz/mcp-steroid/issues/51) `enhancement`
- [#52](https://github.com/jonnyzzz/mcp-steroid/issues/52) `enhancement`

