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

**The IDE knows the code better than any file search tool. `steroid_execute_code` is the default edit/navigate path — native tools only where the IDE genuinely does not apply.**

| Operation | IDE path (inside `steroid_execute_code`) | Why |
|-----------|------------------------------------------|------|
| Find files by extension | `FilenameIndex.getAllFilesByExt(project, "java", projectScope())` | O(1) indexed, index is canonical |
| Find file by exact name | `FilenameIndex.getVirtualFilesByName("UserService.java", projectScope())` | O(1) indexed lookup |
| Find all usages of symbol | `ReferencesSearch.search(element, projectScope())` | Type-aware, no false positives from strings/comments |
| Read a file | `String(findProjectFile("…")!!.contentsToByteArray(), charset)` | Stays inside the IDE; no separate Read-before-Edit contract to satisfy |
| List files | `FilenameIndex.getAllFilesByExt(project, "java", projectScope())` or PSI directory traversal | Same index backing |
| **Grep text content** | `FilenameIndex.getAllFilesByExt(project, ext, scope).flatMap { vf -> /* indexOf / Regex.findAll on vf.text */ }` | Works over the VFS so subsequent semantic queries see the same state you searched |
| **Multi-site literal edit** (any number of files) | `steroid_apply_patch` | One undoable command, atomic pre-flight, PSI commit in-place, no kotlinc compile cycle — see `mcp-steroid://skill/apply-patch-tool-description` |
| **In-place single-file edit** | `val vf = findProjectFile(…)!!; val updated = String(vf.contentsToByteArray(), vf.charset).replace(OLD, NEW); check(updated != …); writeAction { VfsUtil.saveText(vf, updated) }` | Same IDE-side read+write+VFS-refresh in one call; payload shape identical to `Edit(old,new)` |
| Create new files | `writeAction { VfsUtil.createDirectoryIfMissing(root, parentRel)!!.createChildData(this, name).also { VfsUtil.saveText(it, content) } }` | VFS creates the index entry immediately so PSI can parse the file without a refresh round-trip |
| Run Maven tests | `MavenRunConfigurationType.runConfiguration()` + `SMTRunnerEventsListener` | Structured pass/fail; Bash `./mvnw test` cold-starts ~31 s per run |
| Run Gradle tests | `ExternalSystemUtil.runTask()` or `GradleRunConfiguration` through the IDE runner | See `mcp-steroid://skill/execute-code-gradle`; same cold-start cost applies |
| Maven dependency sync | `MavenProjectsManager.forceUpdateAllProjectsOrFindAllAvailablePomFiles()` | No CLI equivalent |

**Native tools only where MCP Steroid genuinely does not apply** — keep this list tight, and prefer the IDE whenever the operation touches the project model:

| Operation | Native Tool | Why |
|-----------|-------------|-------|
| Docker inspect / exec | Bash tool | Docker CLI is outside the IDE's scope; spawning `docker` inside the IDE JVM via `GeneralCommandLine` is banned |
| Shell commands / git / package managers | Bash tool | The IDE does not own these processes |
| Docker availability probe | `java.io.File("/var/run/docker.sock").exists()` **inside** `steroid_execute_code` | Pure JVM, no process spawn — keeps you inside the IDE call |

Everything else — reads, edits, greps, renames, refactors, test runs, compile checks, inspections — goes through `steroid_execute_code`. The IDE's VFS + PSI stay authoritative and the next semantic query sees the state you just wrote.

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
