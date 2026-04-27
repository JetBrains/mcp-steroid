#!/usr/bin/env python3
"""
DPAIA autoresearch metrics extractor.

Reads a single agent's raw NDJSON output from a DPAIA run dir and emits a
JSON-structured metrics block covering the four axes requested by the user:

  1. tokens cost — input / output / cache-read / cache-creation sums
  2. complexity — steroid_execute_code script line-count stats (total, avg, p50, max)
  3. ease of use — proxy = number of steroid_fetch_resource fetches + number of
     retry calls (repeated similar exec_code attempts) + number of edit-tool calls
  4. errors — tool_result blocks with is_error=true, grouped by tool name

The extractor is defensive — it never throws on malformed lines, it just skips
them with a warning on stderr. The intent is that this script runs unattended
against dozens of run dirs and aggregates the output for the iteration log.

Usage:
    python3 metrics.py <run_dir>                 # one run, pretty JSON
    python3 metrics.py <run_dir1> <run_dir2> ... # multiple runs, one record per line
    python3 metrics.py --csv <run_dir> ...       # CSV, suitable for summary.md

Output schema (keys are stable):
    {
      "run_dir":            "<path>",
      "agent":              "claude-code" | "codex" | "gemini-cli",
      "iteration":          <int, from agent-NAME-N>,
      "tokens":             { input, output, cache_read, cache_creation, total },
      "calls": {
         "total":            <int>,
         "by_name":          { "Read": N, "mcp__mcp-steroid__steroid_execute_code": N, ... },
         "errors_by_name":   { ... }
      },
      "complexity": {
         "exec_code_calls":  <int>,
         "exec_code_lines_total": <int>,
         "exec_code_lines_avg":   <float>,
         "exec_code_lines_p50":   <int>,
         "exec_code_lines_max":   <int>
      },
      "ease_of_use": {
         "fetch_resource_calls":   <int>,      # agent searched skill guides
         "native_edit_calls":      <int>,      # agent used built-in Edit instead of apply-patch
         "exec_code_retries":      <int>,      # heuristic: same opening tokens repeated
         "native_bash_calls":      <int>
      },
      "errors":             <int>,
      "apply_patch": {
         "called":           <bool>,          # script source contains "applyPatch " or "hunk("
         "hunks_estimate":   <int>             # sum of hunk( occurrences across scripts
      }
    }
"""

import argparse
import json
import os
import re
import statistics
import sys


_CLAUDE_EXEC_CODE_NAME = "mcp__mcp-steroid__steroid_execute_code"
_CLAUDE_FETCH_RESOURCE_NAME = "mcp__mcp-steroid__steroid_fetch_resource"
_CLAUDE_APPLY_PATCH_NAME = "mcp__mcp-steroid__steroid_apply_patch"

# Negative metric: new DSL methods added to McpScriptContext are a cognitive
# tax for agents (API surface to learn, documentation, test burden, drift
# risk). Count them by scanning McpScriptContext.kt for member declarations
# whose name is not in the set of primitive APIs that were present at
# baseline — anything beyond that baseline increases `dsl_methods_added`.
#
# Scoring: penalise 1 point per net-new method beyond the baseline set.
_DSL_BASELINE_METHODS = frozenset(
    {
        # Identity / inputs
        "project", "params", "disposable", "isDisposed",
        # Output
        "println", "printJson", "printException", "progress", "takeIdeScreenshot",
        # Waiting / analysis
        "waitForSmartMode", "isEditorHighlightingCompleted",
        "waitForEditorHighlighting", "getHighlightsWhenReady",
        "runInspectionsDirectly",
        # Modality
        "doNotCancelOnModalityStateChange",
        # Core action wrappers (generic — not DSL methods)
        "readAction", "writeAction", "smartReadAction",
        # Scopes / file lookup (generic convenience)
        "projectScope", "allScope",
        "findFile", "findPsiFile", "findProjectFile", "findProjectFiles",
        "findProjectPsiFile",
    }
)


def _count_lines(text):
    if not text:
        return 0
    return text.count("\n") + 1


def count_dsl_methods_added(mcp_script_context_path):
    """Count methods declared in McpScriptContext.kt that are not in the
    baseline primitive set. Treated as a negative metric — each addition is
    cognitive tax on agents.

    Returns (added_count, added_names_sorted).
    """
    if not os.path.exists(mcp_script_context_path):
        return 0, []
    text = open(mcp_script_context_path, "r", encoding="utf-8").read()
    # match `fun <name>`, `suspend fun <name>`, `val <name>:` — member decls.
    # Excludes nested lambdas / types because those patterns don't apply to
    # the interface's top-level member block we care about.
    pattern = re.compile(
        r"^\s*(?:suspend\s+)?(?:fun\s+(?:<[^>]+>\s+)?(?P<fn>\w+)\s*\(|val\s+(?P<val>\w+)\s*:)",
        re.MULTILINE,
    )
    names = set()
    for m in pattern.finditer(text):
        names.add(m.group("fn") or m.group("val"))
    added = sorted(n for n in names if n not in _DSL_BASELINE_METHODS)
    return len(added), added


def _find_agent_file(run_dir):
    """Pick the latest agent-<name>-<N>-raw.ndjson in the run dir."""
    candidates = []
    for f in os.listdir(run_dir):
        m = re.match(r"^agent-(?P<name>[^-]+(?:-[a-z]+)?)-(?P<n>\d+)-raw\.ndjson$", f)
        if m:
            candidates.append((int(m.group("n")), m.group("name"), f))
    if not candidates:
        return None, None
    candidates.sort()
    _, name, fname = candidates[-1]
    return os.path.join(run_dir, fname), name


def _is_codex_format(ev):
    """Codex NDJSON uses item.started / item.completed wrappers instead of
    Anthropic's assistant.message.content[] blocks."""
    return ev.get("type", "").startswith("item.") or ev.get("type") in (
        "thread.started",
        "turn.started",
        "turn.completed",
    )


def _analyse_codex(ndjson_path, agent_name, run_dir):
    """Parse OpenAI Codex CLI's NDJSON schema. Edit-equivalent is `file_change`
    (a single event bundling N paths). Command_execution is the Bash-equivalent.
    MCP calls arrive as `mcp_tool_call` items with server=mcp-steroid."""
    tokens = {"input": 0, "output": 0, "cache_read": 0, "cache_creation": 0, "total": 0}
    mcp_calls_by_tool = {}
    command_calls = 0
    file_change_events = 0
    file_change_total_paths = 0
    exec_code_line_counts = []
    exec_code_raw_codes = []
    fetch_resource_calls = 0
    apply_patch_called = False
    apply_patch_hunks = 0
    errors = 0

    with open(ndjson_path, "r", encoding="utf-8") as fh:
        for line in fh:
            try:
                ev = json.loads(line)
            except Exception:
                continue
            usage = ev.get("usage") or {}
            # Codex reports usage with cached_input_tokens — map to Claude-ish schema
            if usage:
                tokens["input"] += int(usage.get("input_tokens") or 0)
                tokens["output"] += int(usage.get("output_tokens") or 0)
                tokens["cache_read"] += int(usage.get("cached_input_tokens") or 0)
            if ev.get("type") == "item.completed":
                it = ev.get("item") or {}
                itype = it.get("type")
                if itype == "mcp_tool_call":
                    tool = it.get("tool", "?")
                    mcp_calls_by_tool[tool] = mcp_calls_by_tool.get(tool, 0) + 1
                    args = it.get("arguments") or {}
                    if tool == "steroid_execute_code":
                        code = args.get("code") or ""
                        exec_code_line_counts.append(_count_lines(code))
                        exec_code_raw_codes.append(code)
                        if "applyPatch" in code or "hunk(" in code:
                            apply_patch_called = True
                            apply_patch_hunks += code.count("hunk(")
                    elif tool == "steroid_fetch_resource":
                        fetch_resource_calls += 1
                    if it.get("error"):
                        errors += 1
                elif itype == "command_execution":
                    command_calls += 1
                    if it.get("exit_code") not in (0, None):
                        errors += 1
                elif itype == "file_change":
                    file_change_events += 1
                    file_change_total_paths += len(it.get("changes") or [])

    tokens["total"] = tokens["input"] + tokens["output"] + tokens["cache_read"]

    # Retries heuristic (same as Claude side):
    retries = 0
    for i in range(1, len(exec_code_raw_codes)):
        a = exec_code_raw_codes[i - 1][:80].strip()
        b = exec_code_raw_codes[i][:80].strip()
        if a and a == b:
            retries += 1

    total_mcp = sum(mcp_calls_by_tool.values())
    # Codex native-ops on a calls-basis (file_change = 1 event, command = 1 event).
    native_calls = command_calls + file_change_events
    total_calls = total_mcp + native_calls
    mcp_share = (total_mcp / total_calls) if total_calls else 0.0

    # Secondary "edit-basis" share: file_change batches counted as N edits.
    native_edits = command_calls + file_change_total_paths
    mcp_share_edit_basis = (
        (total_mcp / (total_mcp + native_edits)) if (total_mcp + native_edits) else 0.0
    )

    by_name = dict(mcp_calls_by_tool)
    by_name["command_execution"] = command_calls
    by_name["file_change"] = file_change_events
    by_name["file_change_paths_total"] = file_change_total_paths

    complexity = {
        "exec_code_calls": len(exec_code_line_counts),
        "exec_code_lines_total": sum(exec_code_line_counts),
        "exec_code_lines_avg": (
            statistics.fmean(exec_code_line_counts) if exec_code_line_counts else 0.0
        ),
        "exec_code_lines_p50": (
            statistics.median(exec_code_line_counts) if exec_code_line_counts else 0
        ),
        "exec_code_lines_max": max(exec_code_line_counts) if exec_code_line_counts else 0,
    }
    return {
        "run_dir": run_dir,
        "agent": agent_name,
        "tokens": tokens,
        "calls": {
            "total": total_calls,
            "mcp_steroid": total_mcp,
            "native": native_calls,
            "mcp_share": round(mcp_share, 3),
            "mcp_share_edit_basis": round(mcp_share_edit_basis, 3),
            "by_name": by_name,
            "errors_by_name": {},
        },
        "complexity": complexity,
        "ease_of_use": {
            "fetch_resource_calls": fetch_resource_calls,
            "native_edit_calls": file_change_total_paths,
            "exec_code_retries": retries,
            "native_bash_calls": command_calls,
        },
        "errors": errors,
        "apply_patch": {
            "called": apply_patch_called,
            "hunks_estimate": apply_patch_hunks,
        },
    }


def analyse(run_dir):
    ndjson_path, agent_name = _find_agent_file(run_dir)
    if ndjson_path is None:
        return None

    # Sniff first non-empty event to decide which format parser to use.
    with open(ndjson_path, "r", encoding="utf-8") as fh:
        first_ev = None
        for line in fh:
            try:
                first_ev = json.loads(line)
                if first_ev:
                    break
            except Exception:
                continue
    if first_ev is not None and _is_codex_format(first_ev):
        return _analyse_codex(ndjson_path, agent_name, run_dir)

    tokens = {
        "input": 0,
        "output": 0,
        "cache_read": 0,
        "cache_creation": 0,
        "total": 0,
    }
    tool_calls_by_name = {}
    errors_by_name = {}
    exec_code_line_counts = []
    exec_code_raw_codes = []
    fetch_resource_calls = 0
    native_edit_calls = 0
    native_bash_calls = 0
    errors = 0
    apply_patch_hunks = 0
    apply_patch_called = False

    with open(ndjson_path, "r", encoding="utf-8") as fh:
        for line in fh:
            try:
                ev = json.loads(line)
            except Exception:
                continue
            msg = ev.get("message") or {}
            usage = msg.get("usage") or {}
            tokens["input"] += int(usage.get("input_tokens") or 0)
            tokens["output"] += int(usage.get("output_tokens") or 0)
            tokens["cache_read"] += int(usage.get("cache_read_input_tokens") or 0)
            tokens["cache_creation"] += int(usage.get("cache_creation_input_tokens") or 0)
            for block in msg.get("content") or []:
                btype = block.get("type")
                if btype == "tool_use":
                    name = block.get("name", "?")
                    tool_calls_by_name[name] = tool_calls_by_name.get(name, 0) + 1
                    if name == _CLAUDE_EXEC_CODE_NAME:
                        code = (block.get("input") or {}).get("code") or ""
                        exec_code_line_counts.append(_count_lines(code))
                        exec_code_raw_codes.append(code)
                        if "applyPatch" in code or "hunk(" in code:
                            apply_patch_called = True
                            apply_patch_hunks += code.count("hunk(")
                    elif name == _CLAUDE_APPLY_PATCH_NAME:
                        # Dedicated MCP tool: hunks ship as a JSON array in the tool input.
                        # Counts as apply-patch adoption whether or not the DSL string appears.
                        apply_patch_called = True
                        hunks_json = (block.get("input") or {}).get("hunks") or []
                        apply_patch_hunks += len(hunks_json) if isinstance(hunks_json, list) else 0
                    elif name == _CLAUDE_FETCH_RESOURCE_NAME:
                        fetch_resource_calls += 1
                    elif name == "Edit":
                        native_edit_calls += 1
                    elif name == "Bash":
                        native_bash_calls += 1
                elif btype == "tool_result":
                    is_error = block.get("is_error", False)
                    if is_error:
                        errors += 1
                        # best-effort tool-name recovery via tool_use_id grep
                        tu_id = block.get("tool_use_id") or ""
                        errors_by_name[tu_id] = errors_by_name.get(tu_id, 0) + 1

    tokens["total"] = (
        tokens["input"] + tokens["output"] + tokens["cache_read"] + tokens["cache_creation"]
    )

    # Heuristic: retries = adjacent exec_code calls with identical first 80 chars.
    retries = 0
    for i in range(1, len(exec_code_raw_codes)):
        a = exec_code_raw_codes[i - 1][:80].strip()
        b = exec_code_raw_codes[i][:80].strip()
        if a and a == b:
            retries += 1

    complexity = {
        "exec_code_calls": len(exec_code_line_counts),
        "exec_code_lines_total": sum(exec_code_line_counts),
        "exec_code_lines_avg": (
            statistics.fmean(exec_code_line_counts) if exec_code_line_counts else 0.0
        ),
        "exec_code_lines_p50": (
            statistics.median(exec_code_line_counts) if exec_code_line_counts else 0
        ),
        "exec_code_lines_max": max(exec_code_line_counts) if exec_code_line_counts else 0,
    }

    # Secondary goal: MAXIMIZE MCP Steroid tool calls (the whole point of the
    # plugin). We surface the ratio as a headline metric so iteration drift is
    # visible at a glance. MCP Steroid tool calls are anything whose name starts
    # with `mcp__mcp-steroid__steroid_`. "native" tools are everything else
    # (Read / Edit / Write / Glob / Grep / Bash / etc.) that the agent could
    # have routed through the IDE instead.
    mcp_calls = sum(
        n for name, n in tool_calls_by_name.items()
        if name.startswith("mcp__mcp-steroid__")
    )
    total_calls = sum(tool_calls_by_name.values())
    native_calls = total_calls - mcp_calls
    mcp_share = (mcp_calls / total_calls) if total_calls else 0.0

    return {
        "run_dir": run_dir,
        "agent": agent_name,
        "tokens": tokens,
        "calls": {
            "total": total_calls,
            "mcp_steroid": mcp_calls,
            "native": native_calls,
            "mcp_share": round(mcp_share, 3),  # headline: 0..1 — bigger is better
            "by_name": tool_calls_by_name,
            "errors_by_name": errors_by_name,
        },
        "complexity": complexity,
        "ease_of_use": {
            "fetch_resource_calls": fetch_resource_calls,
            "native_edit_calls": native_edit_calls,
            "exec_code_retries": retries,
            "native_bash_calls": native_bash_calls,
        },
        "errors": errors,
        "apply_patch": {
            "called": apply_patch_called,
            "hunks_estimate": apply_patch_hunks,
        },
    }


def _csv_header():
    return ",".join(
        [
            "run_dir",
            "agent",
            # Headline: MCP Steroid tool-call share. Primary optimization target.
            "mcp_share",
            "mcp_steroid_calls",
            "native_calls",
            "tokens_input",
            "tokens_output",
            "tokens_cache_read",
            "tokens_cache_creation",
            "tokens_total",
            "calls_total",
            "exec_code_calls",
            "exec_code_lines_avg",
            "exec_code_lines_max",
            "native_edit_calls",
            "fetch_resource_calls",
            "retries",
            "errors",
            "apply_patch_called",
            "apply_patch_hunks",
        ]
    )


def _csv_row(a):
    return ",".join(
        str(v)
        for v in [
            a["run_dir"],
            a["agent"],
            a["calls"]["mcp_share"],
            a["calls"]["mcp_steroid"],
            a["calls"]["native"],
            a["tokens"]["input"],
            a["tokens"]["output"],
            a["tokens"]["cache_read"],
            a["tokens"].get("cache_creation", 0),
            a["tokens"]["total"],
            a["calls"]["total"],
            a["complexity"]["exec_code_calls"],
            round(a["complexity"]["exec_code_lines_avg"], 1),
            a["complexity"]["exec_code_lines_max"],
            a["ease_of_use"]["native_edit_calls"],
            a["ease_of_use"]["fetch_resource_calls"],
            a["ease_of_use"]["exec_code_retries"],
            a["errors"],
            int(a["apply_patch"]["called"]),
            a["apply_patch"]["hunks_estimate"],
        ]
    )


def main(argv):
    p = argparse.ArgumentParser()
    p.add_argument("run_dirs", nargs="*")
    p.add_argument("--csv", action="store_true")
    p.add_argument(
        "--dsl-methods",
        action="store_true",
        help="Report the DSL-methods-added penalty for the current tree "
        "(scans ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/execution/"
        "McpScriptContext.kt) and exit.",
    )
    # __file__ = .../docs/autoresearch/dpaia/metrics.py  →  four dirname hops to repo root.
    _repo_root = os.path.dirname(
        os.path.dirname(
            os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        )
    )
    p.add_argument(
        "--context-path",
        default=os.path.join(
            _repo_root,
            "ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/execution/McpScriptContext.kt",
        ),
    )
    args = p.parse_args(argv[1:])

    if args.dsl_methods:
        count, names = count_dsl_methods_added(args.context_path)
        print(json.dumps({
            "mcp_script_context": args.context_path,
            "dsl_methods_added_vs_baseline": count,
            "added_names": names,
            "scoring": "negative — each added method is cognitive tax on agents",
        }, indent=2))
        return

    if not args.run_dirs:
        sys.stderr.write("no run dirs given; see --help\n")
        sys.exit(2)

    if args.csv:
        print(_csv_header())
    for d in args.run_dirs:
        a = analyse(d)
        if a is None:
            sys.stderr.write(f"# skip (no agent-*-raw.ndjson): {d}\n")
            continue
        if args.csv:
            print(_csv_row(a))
        else:
            print(json.dumps(a, indent=2))


if __name__ == "__main__":
    main(sys.argv)
