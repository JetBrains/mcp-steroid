MCP Steroid — Design philosophy

The three tenets that govern every MCP Steroid change. Read before proposing a new tool, a new McpScriptContext method, or a wrapper helper.

# Three tenets

If a change touches the MCP tool surface, the `McpScriptContext` runtime, or wraps an IntelliJ API in a "helper" — these tenets apply.

## Tenet 1 — minimal MCP tool surface

Don't propose new `steroid_*` tools. The current set is intentional and intentionally small:

- `steroid_list_projects`
- `steroid_list_windows`
- `steroid_open_project`
- `steroid_execute_code`
- `steroid_apply_patch`
- `steroid_execute_feedback`
- `steroid_action_discovery`
- `steroid_take_screenshot`
- `steroid_input`
- `steroid_fetch_resource`

Improvements come from richer prompt resources (this file is one), sharper tool descriptions, and teaching you to call IntelliJ APIs directly inside `steroid_execute_code` — not from new tools.

A tool is added only when **all** of:

1. The need cannot be met by `steroid_execute_code` + an IntelliJ API call. **Document the specific IntelliJ API path you ruled out.**
2. It cannot be met by a richer `mcp-steroid://` recipe.
3. Three independent reviewers (`run-agent.sh codex` / `claude` / `gemini`) agree, after reading this file. **One reviewer disagreeing kills the proposal** — propose a recipe instead.

Anything short of that — write a recipe instead.

## Tenet 2 — power lives in prompts and direct IntelliJ APIs

Inside `steroid_execute_code`, call IntelliJ's APIs the way IntelliJ exposes them. The full plugin classpath is loaded; typed imports work for `FilenameIndex`, `JavaPsiFacade`, `ProjectTaskManager`, `XDebuggerUtil`, `VfsUtil`, `ReferencesSearch`, `MainPassesRunner`, `Observation.awaitConfiguration`, `smartReadAction { }`, `writeAction { }`, etc.

Don't:

- Wrap an IntelliJ API in an "agent-friendly" abstraction. The unwrapped API is what's evolving in IntelliJ; the wrapper drifts.
- Ask for a new `McpScriptContext` helper to save 5 lines of IntelliJ API. The 5 lines teach you a transferable skill.
- Use reflection (`Class.forName`, `setAccessible(true)`) in the final recipe — only as a probe. The full reflection policy lives in the MCP server's startup instructions (delivered to every agent at session init) and at `mcp-steroid://prompt/skill`.

This is what the strategy page means by "Give AI the whole IDE, not just the files." The **MCP tool surface** stays narrow; the **IntelliJ capability surface** stays full, exposed through `steroid_execute_code` plus recipes like the ones at `mcp-steroid://ide/...` and `mcp-steroid://skill/...`.

## Tenet 3 — `McpScriptContext` methods are last-resort

**Don't add methods to `McpScriptContext` casually.** The current surface (see the `McpScriptContext` source for the exact list — `project`, `disposable`, `printJson(...)`, `progress(...)`, `applyPatch { }`, `findProjectFile(...)`, `projectScope()`, the inspection / highlighting helpers, etc.) exists because the IntelliJ API genuinely couldn't cover those cases at the time. They are not the extension point — the IntelliJ API is. The surface is already substantial; that's why "don't grow it" is a tenet, not a preference.

A new context method requires:

1. A written argument that the IntelliJ-native path is genuinely intractable (not just "less convenient").
2. Three-reviewer consensus across `run-agent.sh codex` / `claude` / `gemini`. **One reviewer disagreeing kills the proposal** — propose a recipe instead.
3. The new method teaches an idiom reusable across many tasks, not one specific scenario.

`applyPatch { }` is the canonical example: the script-context DSL exists, but `mcp-steroid://ide/apply-patch` routes you to the dedicated `steroid_apply_patch` tool first. The DSL is the fallback. New context methods must arrive with a similar fallback story.

# In practice

When you're about to make a change in this repo, ask in order:

1. Can this be a richer prompt resource? → write the recipe.
2. Can this be solved by calling an IntelliJ API directly inside `steroid_execute_code`? → write the recipe with that snippet.
3. Does it need a new MCP tool or context method? → it almost certainly doesn't. If you still believe so, see the three-reviewer requirement above.
