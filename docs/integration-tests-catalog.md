# Integration Tests Catalog

## test-integration (Docker IntelliJ tests)

### MCP Steroid API Validation
| Test | What it validates | Status |
|------|------------------|--------|
| MavenTestExecutionTest | MavenRunConfigurationType + SMTRunner in Docker | FAIL (dialog_killer kills Maven progress) |
| GradleTestExecutionTest | GradleRunConfiguration + setRunAsTest + SMTRunner | PASS |
| MavenCompileTest | ProjectTaskManager.build() on Maven project | PASS |
| GradleCompileTest | ProjectTaskManager.build() on Gradle project | PASS |
| MavenInstallTest | MavenRunner.run() with install goal | Has dialogKiller |
| DockerCheckTest | File.exists("/var/run/docker.sock") in exec_code | Simple, should pass |
| FileDiscoveryTest | FilenameIndex in exec_code | Simple, should pass |

### Agent Behavior Validation
| Test | What it validates | Status |
|------|------------------|--------|
| MavenRunnerAdoptionTest | Agent uses exec_code for tests (not Bash) | Created, untested |
| ResourceReadingTest | Agent reads mcp-steroid:// resources | Created, untested |

### Known Issues
- **dialog_killer too aggressive**: Kills Maven runner's `JobProviderWithOwnerContext` progress dialog, cancelling the Maven execution. Needs whitelist for build runner progress tasks.
- **"Resolving SDKs" false positive**: `UnknownSdkTracker` fires during build, causing `Build errors: true` when compilation actually succeeded. Partially fixed by `mcpResolveUnknownSdks()` step.

## ij-plugin (CLI integration tests)

### Agent Model Validation
| Test | Agent | Model | Status |
|------|-------|-------|--------|
| CliClaudeIntegrationTest | Claude | claude-opus-4-6 | 3/7 pass (kotlinc missing) |
| CliCodexIntegrationTest | Codex | gpt-5.4-xhigh | 2/7 pass (stream disconnect) |
| CliGeminiIntegrationTest | Gemini | gemini-3.1-pro-preview | 4/7 pass (kotlinc missing) |

### MCP Tool Tests
| Test | What it validates | Status |
|------|------------------|--------|
| FetchResourceToolTest | steroid_fetch_resource returns resource content | Created |

## test-experiments (DPAIA arena)

17 scenarios × 3 agents (claude, codex, gemini) × 2 modes (mcp, none).
See `docs/arena-3pass-results.md` for full comparison table.
