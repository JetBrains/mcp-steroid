Coding with IntelliJ - Comprehensive Guide

Comprehensive guide for writing IntelliJ API code via steroid_execute_code.

# Coding with IntelliJ APIs - Comprehensive Guide for AI Agents

This guide teaches you how to write effective Kotlin code that executes inside IntelliJ IDEA's runtime environment via `steroid_execute_code`. You'll learn the execution model, available APIs, and best practices for working with PSI (Program Structure Interface), VFS (Virtual File System), and other IntelliJ platform APIs.

## Sections

- [Introduction & Execution Model](mcp-steroid://skill/coding-with-intellij-intro) — Script structure, coroutine context, helper function rules
- [McpScriptContext API Reference](mcp-steroid://skill/coding-with-intellij-context-api) — project, output methods, readAction/writeAction, file helpers
- [Threading and Read/Write Actions](mcp-steroid://skill/coding-with-intellij-threading) — Threading model, smart mode, modal dialogs
- [Common Patterns](mcp-steroid://skill/coding-with-intellij-patterns) — Project info, plugin discovery, file navigation
- [PSI Operations & Code Analysis](mcp-steroid://skill/coding-with-intellij-psi) — PSI tree navigation, find usages, inspections
- [Document, Editor & VFS Operations](mcp-steroid://skill/coding-with-intellij-vfs) — Document/editor manipulation, VFS read/write
- [Java & Spring Boot Patterns](mcp-steroid://skill/coding-with-intellij-spring) — Maven/Gradle, Spring annotations, test execution
- [Refactoring, Services & Best Practices](mcp-steroid://skill/coding-with-intellij-refactoring) — Refactoring, services, error handling, quick reference

## Quick Reference

**The IDE knows the code better than any file search tool. Use it only where it adds value.**

| Operation | Use IntelliJ API (steroid_execute_code) | Why? |
|-----------|------------------------------|------|
| Find files by extension | `FilenameIndex.getAllFilesByExt(project, "java", scope)` | O(1) indexed vs O(n) filesystem scan |
| Find file by exact name | `FilenameIndex.getVirtualFilesByName("UserService.java", scope)` | O(1) indexed lookup |
| Find all usages of symbol | `ReferencesSearch.search(element, scope)` | Understands code semantics |
| Manual text replacement | Refactoring APIs | Maintains code correctness |
| Run Maven tests | `MavenRunConfigurationType.runConfiguration()` + `SMTRunnerEventsListener` | **✅ PRIMARY** — structured pass/fail; Bash `./mvnw test` is LAST-RESORT only |
| Run Gradle tests | `GradleRunConfiguration` + `setRunAsTest(true)` + `SMTRunnerEventsListener` | **✅ PRIMARY** — structured pass/fail; Bash `./gradlew test` is LAST-RESORT only |
| Maven dependency sync | `MavenProjectsManager.forceUpdateAllProjectsOrFindAllAvailablePomFiles()` | **✅ Use this** — no CLI equivalent |

**Operations that do NOT need steroid_execute_code — use native agent tools instead:**

| Operation | Native Tool | Why NOT steroid_execute_code? |
|-----------|-------------|-------------------|
| **Create new files** | **Write tool** | steroid_execute_code file creation is **+47% slower** (A/B measured) |
| **Create directories** | **Bash `mkdir -p`** | `VfsUtil.createDirectoryIfMissing` adds ~8s JVM overhead; `mkdir -p` is instant |
| Read a file | Read tool | Zero JVM overhead; steroid_execute_code adds ~12s per call |
| List files | Glob tool | Zero overhead; steroid_execute_code not needed |
| `grep`/search text | Grep tool | Zero overhead |
| **Run Maven/Gradle tests** | **See Quick Reference above — use IDE runners inside steroid_execute_code** | Bash `./mvnw test` / `./gradlew test` is LAST-RESORT only |
| Docker availability | Bash tool | Just a socket check — no IntelliJ value |
| Docker inspect/exec | Bash tool | No IntelliJ API equivalent; use Bash directly |
| Simple file existence | Bash `test -f` | No IntelliJ value for POSIX checks |

## ❌ BANNED Anti-Patterns: ProcessBuilder for Builds

**Never use `ProcessBuilder("./mvnw", ...)` or `ProcessBuilder("./gradlew", ...)` inside `steroid_execute_code`** for build or test execution. These patterns bypass IntelliJ's process management, cause classpath conflicts, and produce output that overflows MCP token limits.

**Allowed ProcessBuilder uses** (no IntelliJ API equivalent):
- `ProcessBuilder("git", "diff", ...)` — git operations (use ChangeListManager when possible)

**Docker availability** — check the socket directly, no process spawn needed:
```kotlin
val dockerOk = java.io.File("/var/run/docker.sock").exists()
```

**Docker CLI operations** (inspect, exec, etc.) — use the **Bash tool** outside steroid_execute_code:
- `GeneralCommandLine("docker", ...)` inside steroid_execute_code is **BANNED** — same as ProcessBuilder
- ✅ `docker inspect --format='{{.State.Running}}' <id>` → Bash tool
- ✅ `docker exec <id> bash -c "..."` → Bash tool

See [execute-code-overview](mcp-steroid://skill/execute-code-overview) for the full banned list and replacements.
