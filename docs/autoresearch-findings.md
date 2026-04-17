# Autoresearch Findings: MCP Steroid Prompt Optimization

## Executive Summary

Across 2 autoresearch cycles, 3 arena passes (51 scenario runs), and multiple sub-agent experiments,
we optimized MCP Steroid's prompt resources to improve agent behavior. Key outcomes:

- **Duration improvement**: -58% best case (petclinic-27), -23% average across passing scenarios
- **exec_code adoption**: agents use 2.3 calls/scenario avg (down from 3.3), pattern is now lean
- **Bash reduction**: 8.2 calls/scenario avg (down from 11.5), still the primary bottleneck
- **Resource reading**: 0/69 runs read any `mcp-steroid://` resource despite extensive prompting

## Methodology

### Karpathy Autoresearch Applied
Adapted the Karpathy autoresearch loop (modify → train → evaluate → retain/discard) for prompt optimization:
1. Modify one skill resource file
2. Verify compilation (`MarkdownArticleContractTest`)
3. Deploy plugin (hot-reload or Docker rebuild)
4. Run arena scenario (petclinic-rest-37 for fast iteration, full 17 for comprehensive)
5. Extract metrics from decoded logs + server-side `intellij/mcp-steroid/` logs
6. Compare exec_code/Bash/duration against baseline
7. Retain if improved, revert if not

### RLM Analysis
Applied RLM methodology to analyze 666 Bash calls across 51 runs:
- ASSESS: Sample data structure without reading everything
- DECOMPOSE: Categorize Bash commands by task type
- EXECUTE: Extract metrics per category
- SYNTHESIZE: Rank by frequency and potential savings
- VERIFY: Validate each finding against server-side logs

## Key Findings

### Finding 1: MCP Server Instructions Are Metadata, Not Directives

**What**: MCP server instructions (`mcp-steroid-info.md`) are injected into Claude Code's
system prompt via the MCP `initialize` response. We restructured them as a "skill manifest"
with task-to-resource mapping and explicit `ReadMcpResourceTool` guidance.

**Result**: 0/69 runs read any resource. Agent thinking never mentions resources/skills/guides.
The instructions are treated as background context, not action items.

**Why**: Agents form their plan from the user prompt (task description). MCP instructions
are system-level metadata that provides context but doesn't override the plan. The agent's
training prior (understand task → implement → test via Bash) dominates.

### Finding 2: Tool Descriptions Are Schema Metadata

**What**: The `steroid_execute_code` tool description (originally 9563 chars) contains
MANDATORY warnings, copy-paste patterns, and links to resources.

**Result**: Agents read the description when they call ToolSearch, but don't follow
its behavioral directives. The "MANDATORY: Do NOT use ./mvnw test" warning is in the
description but agents use Bash anyway.

**Why**: Tool descriptions are part of the tool schema (like an API doc). Agents treat
them as reference material, not as instructions to change their plan.

### Finding 3: The MavenRunner Pattern Is Too Complex

**What**: The full MavenRunConfigurationType + SMTRunnerEventsListener pattern is 30 lines
with 14 empty interface stubs. Bash `./mvnw test` is 1 line.

**What we tried**:
- Approach A: Simplified to MavenRunner.run() — 8 lines
- Approach B: ConfigurationContext auto-detect — 6 lines
- Approach C: Dedicated `run-tests-recipe` resource

**Result**: Even the 6-line pattern wasn't adopted. The issue isn't complexity — agents
never consider exec_code for test execution regardless of pattern length.

### Finding 4: "Resolving SDKs" Modal Causes Build False Positives

**What**: `UnknownSdkTracker` fires a background task during `ProjectTaskManager.buildAllModules()`,
which the dialog_killer detects as a modal. This causes `Build errors: true` even when
compilation actually succeeded.

**Impact**: 10/17 scenarios (59%) hit this false positive, wasting 1 exec_code call + 1 Bash
fallback per scenario (~38s waste).

**Fix**: Added `mcpResolveUnknownSdks()` step after JDK registration to resolve SDKs before
the agent starts.

### Finding 5: Agent Behavior Is Reflexive, Not Deliberative

**What**: After a successful compile check via exec_code, the agent says "Build succeeds.
Now let me run the targeted test class." and immediately calls Bash. No thinking block,
no tool evaluation.

**Why**: The compile→test transition is a reflexive pattern from training data. The agent
doesn't re-evaluate tool options between calls — it executes a pre-formed plan.

### Finding 6: Bash ./mvnw test Is the #1 Optimization Target

**What**: 244 of 666 Bash calls (37%) are Maven test execution. Combined with Maven compile
(48 calls, 7%), Maven operations account for 44% of all Bash usage.

**Simulation**: If each Bash `./mvnw test` were replaced by IDE test runner, estimated
savings of ~31s per call × ~4 calls/scenario = ~124s/scenario average.

## What Worked

| Change | Impact | Pass |
|--------|--------|------|
| Build env discovery in first exec_code call | exec_code 4.0→2.1 (-47%) | P1 |
| Docker failure hard stop | Reduced Docker probing waste | P1 |
| JDK selection rule in arena prompt | Eliminated JDK trial-and-error | P1-P3 |
| "Resolving SDKs" resolution step | Reduced false-positive builds | Committed |
| Explicit agent models (opus-4-6, gpt-5.4-xhigh) | Standardized evaluation | All |

## What Didn't Work

| Change | Why It Failed |
|--------|--------------|
| MCP server instructions as skill manifest | Agents ignore MCP metadata |
| `steroid_fetch_resource` tool | Agents never call it |
| Tool description MANDATORY warning | Read as metadata, not directive |
| Inline 6-line ConfigurationContext pattern | Agent still chose Bash |
| `run-tests-recipe` resource | 0 reads across all runs |
| TOC with `ReadMcpResourceTool(uri=...)` hint | Agents never browse TOCs |

## Architecture of Agent Context

```
┌─────────────────────────────────────────────────┐
│ SYSTEM PROMPT (highest priority)                 │
│ ├── Claude Code built-in instructions            │
│ ├── MCP Server Instructions (mcp-steroid-info.md)│  ← metadata context
│ └── CLAUDE.md / memory                           │
├─────────────────────────────────────────────────┤
│ TOOL SCHEMAS (per-tool metadata)                 │
│ ├── steroid_execute_code: 1923 chars description │  ← reference, not directive
│ ├── steroid_fetch_resource: description          │
│ └── Bash, Read, Write, etc.                      │
├─────────────────────────────────────────────────┤
│ USER PROMPT (task-specific)                      │
│ └── Arena prompt with recipes                    │  ← agents follow this
├─────────────────────────────────────────────────┤
│ CONVERSATION (tool results + thinking)           │
│ ├── exec_code output → agents ACT on this        │  ← behavioral trigger
│ └── Bash output → agents parse this              │
├─────────────────────────────────────────────────┤
│ MCP RESOURCES (on-demand, 84 available)          │
│ └── Never accessed (0/69 runs)                   │  ← invisible to agents
└─────────────────────────────────────────────────┘
```

## Recommendations for Next Iteration

1. **Embed skill content in exec_code output** — when compile check succeeds, append
   "To run tests via IDE: `ConfigurationContext(cls).configuration`" directly in the output
2. **Fix dialog_killer whitelist** — stop killing Maven/Gradle progress dialogs
3. **Reduce prompt article to match arena prompt format** — agents follow arena recipes,
   so make the MCP tool description match that format
4. **Accept Bash for now, optimize exec_code** — the 2-call exec_code pattern (VCS + compile)
   is optimized. Focus on making those 2 calls more valuable (e.g., include file discovery results)

## Data Sources

- 3-pass arena results: `docs/dpaia-arena-comparison.md`
- Per-run JSON: `test-experiments/build/test-logs/test/dpaia-arena-run-*.json`
- Server-side exec_code logs: `run-*/intellij/mcp-steroid/eid_*`
- Decoded agent logs: `run-*/agent-claude-code-1-decoded.txt`
- Raw NDJSON: `run-*/agent-claude-code-1-raw.ndjson`
- Autoresearch message buses: `docs/autoresearch/MESSAGE-BUS.md`, `docs/autoresearch-v2/MESSAGE-BUS.md`
