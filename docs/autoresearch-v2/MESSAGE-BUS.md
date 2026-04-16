
## Integration Test Results (2026-04-16)

EVAL: GradleTestExecutionTest PASSED (2m57s) — GradleRunConfiguration + setRunAsTest + SMTRunner works in Docker
EVAL: MavenTestExecutionTest FAILED — dialog_killer kills Maven's own JobProviderWithOwnerContext progress dialog, cancelling the run. MavenRunner API itself works but dialog_killer is too aggressive.

Finding: dialog_killer needs to whitelist Maven/Gradle runner progress dialogs. Currently it kills ALL modals indiscriminately, which breaks Maven test execution via MavenRunConfigurationType.

## Evaluation — Skill Discovery (petclinic-rest-37, 2026-04-16)

Plugin: 83c85ccc (new instructions + steroid_fetch_resource)
EVAL: steroid_fetch_resource visible in tool list (35 tools, 9 MCP)
EVAL: MCP instructions contain "Before starting work, read the skill guide"
EVAL: Agent thinking mentions: task understanding, implementation, testing
EVAL: Agent thinking NEVER mentions: resources, skills, guides, fetch, recipes
EVAL: Resources read: 0. steroid_fetch_resource called: 0. ListMcpResources: 0.

FINDING: MCP server instructions are injected correctly into the initialize
response but Claude Code agents don't treat them as behavioral directives.
The agent's training-data prior (understand → implement → Bash test) dominates.
MCP instructions = metadata context, not action items.

The only way to force resource reading may be:
1. The arena PROMPT explicitly says "call steroid_fetch_resource first"
2. exec_code OUTPUT includes "you haven't read any skill guides yet"
3. A Claude Code hook/plugin that intercepts planning
