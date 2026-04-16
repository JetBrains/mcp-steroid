# Create Resource Reading Validation Test

You are an **implementation agent**. Create an integration test that validates AI agents read MCP Steroid resources during their work.

## Context

Analysis of 68 arena runs shows 0% of agents ever call `ReadMcpResourceTool` or `ListMcpResourcesTool`. The MCP server instructions (`mcp-steroid-info.md`) have been updated to explicitly guide agents to read resources. We need a test to verify this works.

## Test to Create

File: `test-integration/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/tests/ResourceReadingTest.kt`

### Test: `claude reads MCP resources when given a coding task`

1. Start IntelliJ container with the Gradle test project
2. Wait for project ready
3. Run a Claude agent with this prompt:
   ```
   You have access to MCP Steroid. Before writing any code, read the available MCP resources to understand what IDE capabilities are available. Use ListMcpResourcesTool to see what resources exist, then ReadMcpResourceTool to read at least one guide. After reading, report what resources you found by printing RESOURCES_READ=<count>.
   ```
4. After the agent completes, check the decoded log for:
   - `ListMcpResourcesTool` call (should be present)
   - `ReadMcpResourceTool` call (should be present, at least 1)
   - Output containing `RESOURCES_READ=`
5. Assert at least one resource was read

### Test: `claude reads test-skill resource before running tests`

1. Same setup as above
2. Prompt: "Run the tests in this project. The MCP server has guides at mcp-steroid://prompt/test-skill — read it first to learn the best approach."
3. Check decoded log for `ReadMcpResourceTool` with URI containing `test-skill`
4. Assert the resource was read

## Pattern

Use MCP Steroid (`steroid_execute_code` on project `mcp-steroid`) to research existing test patterns:
- Look at `MavenRunnerAdoptionTest.kt` for how to run an agent and check decoded logs
- Look at `ConsoleAwareAgentSession` for `runPrompt()` API

## Verification

```bash
./gradlew :test-integration:compileTestKotlin
```

## Commit

```
git add test-integration/ && git commit -m "test: add resource reading validation test — agents should read MCP resources"
```
