# Top-10 Bash Tasks Agents Should Prefer MCP Steroid For

From RLM analysis of 666 Bash calls across 51 arena runs (3 passes × 17 scenarios).

| # | Task | Bash calls | MCP Steroid Alternative | Test to create |
|---|------|-----------|------------------------|----------------|
| 1 | Maven test execution | 244 (37%) | `MavenRunConfigurationType` + `SMTRunnerEventsListener` | MavenTestExecutionTest ✅ |
| 2 | File reading via Bash | 154 (23%) | Native `Read` tool | (agent behavior, not MCP) |
| 3 | Docker availability check | 71 (11%) | `File("/var/run/docker.sock").exists()` | DockerCheckTest |
| 4 | File discovery via ls/find | 66 (10%) | `FilenameIndex` / native `Glob` tool | FileDiscoveryTest |
| 5 | Maven compilation | 48 (7%) | `ProjectTaskManager.build()` | MavenCompileTest |
| 6 | Gradle test execution | 31 (5%) | `GradleRunConfiguration` + `setRunAsTest(true)` | GradleTestExecutionTest ✅ |
| 7 | JDK discovery | 8 (1%) | Already in first `exec_code` output | (prompt fix, no test) |
| 8 | Maven module install | 7 (1%) | `MavenRunner.run()` with install goal | MavenInstallTest |
| 9 | Gradle compilation | 4 (1%) | `ProjectTaskManager.build()` | GradleCompileTest |
| 10 | Git operations | 6 (1%) | `ChangeListManager` in `exec_code` | (already covered) |

✅ = test already exists

## Tests to Create (5 new)

1. **DockerCheckTest** — verify `exec_code` with `File("/var/run/docker.sock").exists()` works
2. **FileDiscoveryTest** — verify `exec_code` with `FilenameIndex` finds project files
3. **MavenCompileTest** — verify `exec_code` with `ProjectTaskManager.build()` compiles Maven project
4. **MavenInstallTest** — verify `exec_code` with `MavenRunner.run(install)` installs a module
5. **GradleCompileTest** — verify `exec_code` with `ProjectTaskManager.build()` compiles Gradle project
