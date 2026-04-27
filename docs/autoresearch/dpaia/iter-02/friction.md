# iter-02 friction analysis — same DpaiaPetclinicRest37 scenario, flipped prompts

Fresh run dir: `run-20260422-231251-dpaia__spring__petclinic__rest-37-mcp`.
Plugin snapshot includes the iter-01 prompt changes (2d4af091 + e56523ca):
anti-MCP-bias table removed, inline Maven runner recipe added, "default to
steroid_execute_code" paragraph at the top of the tool description.

## Metrics vs iter-01

| metric               | iter-01  | iter-02  | delta |
|---------------------|----------|----------|-------|
| mcp_share           | 0.091    | 0.091    | **no change** |
| exec_code_calls     | 1        | 1        | no change |
| Read                | 2        | 2        | no change |
| Grep                | 2        | 1        | -1 |
| Edit                | 2        | 2        | no change |
| Bash                | 3        | 3        | **no change** |
| ToolSearch          | 1        | 2        | +1 |
| applyPatch_called   | false    | false    | no change |
| fetch_resource_calls| 0        | 0        | no change |
| errors              | 0        | 0        | — |
| total tokens        | 694 k    | 726 k    | +4.6% (larger tool description) |

Bottom line: **prompt edits alone did not move the needle**. The three
`./mvnw test` Bash calls still happened; the two `Edit` calls still happened;
the agent never fetched a skill guide; the applyPatch DSL was never invoked.

## Why Claude ignored the new Maven recipe

The tool description now carries a full Maven runner recipe inline (~15 lines
of Kotlin, 6 imports, a `runnerParameters.goals = listOf(...)` block). Even
seeing it on every `steroid_execute_code` call, the agent still prefers

    Bash: JAVA_HOME=… ./mvnw test -Dtest=PetRestControllerTests -q

because it's **one line of familiar shell**. Information nudges don't override
Claude's strong prior toward the shortest ergonomic path.

## iter-03 plan — structural fix, not prompt fix

Add a plugin-side DSL method on `McpScriptContext`:

    suspend fun runMavenTest(testPattern: String, extraGoals: List<String> = emptyList()): MavenTestResult

so the agent's call collapses to one line:

    val r = runMavenTest("PetRestControllerTests")
    println("passed=${r.passed} failed=${r.failed} aborted=${r.aborted}")

Matches the `applyPatch` DSL pattern: ship data, the plugin owns threading +
APIs. Same structural win — ONE line of Kotlin in `steroid_execute_code`
beats ONE line of Bash only if `steroid_execute_code` also delivers structured
pass/fail counts that Bash cannot.

### Additional iter-03 considerations

- `applyPatch` didn't fire because the scenario's edit was a single-site
  method add. DSL adoption needs a scenario that exercises multi-site rename
  or logger-key change — candidates: petclinic-71 (cross-file rename), or
  microshop-18.
- `./mvnw test` re-ran three times because the agent iterated on test
  results. The IDE runner should return structured JSON that the agent can
  parse in one turn.

