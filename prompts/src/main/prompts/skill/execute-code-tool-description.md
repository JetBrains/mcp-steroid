Execute Code Tool

MCP tool description for the steroid_execute_code tool.

###_NO_AUTO_TOC_###
WHAT: Finally SEE IntelliJ-based IDEs - not just read code. The only MCP server with visual understanding and full IDE control.
HOW: Execute Kotlin code directly in IntelliJ's runtime with full API access.

📖 **COMPLETE GUIDE**: [mcp-steroid://skill/coding-with-intellij]

This is a **stateful** API - everything you do changes the IDE state. The IntelliJ IDE is running exclusively for you. Use it aggressively instead of manual file operations.

**Quick Start:**
- Your code is a suspend function body (never use runBlocking)
- Use readAction { } for PSI/VFS reads, writeAction { } for modifications
- ⚠️ Helper functions calling readAction/writeAction MUST be `suspend fun` — omitting `suspend` causes compile error: "suspension functions can only be called within coroutine body"
- waitForSmartMode() runs automatically before your script
- Available: project, println(), printJson(), printException(), progress()

**Common Operations:**
- Code navigation: Find usages, go to definition, symbol search
- Inspections: Run code analysis, get warnings/errors
- Actions: Trigger any IDE action programmatically
- **Compile check**: `ProjectTaskManager.getInstance(project).build(*modules).await()` — 2-5s incremental, replaces `./mvnw test-compile` (25-60s)
- **Run Maven tests via IDE** — `MavenRunConfigurationType.runConfiguration()` + `SMTRunnerEventsListener` gives structured pass/fail in ~4s, saves ~31s vs Bash `./mvnw test` (Maven cold start). Read [mcp-steroid://skill/coding-with-intellij-spring] for the copy-paste pattern (section "Maven projects — MavenRunConfigurationType"). **Do NOT use Bash `./mvnw test` as primary approach — it is a LAST-RESORT fallback only.**
- **Run Gradle tests via IDE** — `GradleRunConfiguration` + `setRunAsTest(true)` + `SMTRunnerEventsListener`. Read [mcp-steroid://skill/coding-with-intellij-spring] (section "Gradle projects").

**⚠️ MANDATORY — before your first `./mvnw` or `./gradlew` Bash command**: STOP. Read `mcp-steroid://skill/coding-with-intellij-spring` for IDE test runner patterns. Each Bash Maven/Gradle call wastes ~31s on cold JVM startup that the IDE runner avoids. Only fall back to Bash after the IDE pattern has timed out (>2 min).

**Power Features — use these aggressively:**
- **Debugger:** Set breakpoints, launch debug sessions, suspend at breakpoints, evaluate expressions at any call frame, step over, inspect thread stacks. Full IntelliJ XDebugger API works in all IDEs (IDEA, Rider, GoLand, …). Read `mcp-steroid://prompt/debugger-skill`
- **Refactoring:** Rename symbols, extract method/variable, move files, inline, change signature — all via `RefactoringActionHandler` and IntelliJ refactoring APIs. Use `ActionManager.getInstance().getAction("RenameElement")` etc.
- **Tests:** Launch via context action — open test file, position caret on test class/method, fire action. IntelliJ: `DebugContextAction` (fallback: JUnitConfiguration with explicit module). Rider: `RiderUnitTestDebugContextAction`. See `mcp-steroid://prompt/debugger-skill`
- **Reflection (when API is unclear):** Use Java reflection to access private fields, methods, and internal state when the public API is not obvious: `obj.javaClass.getDeclaredField("fieldName").also { it.isAccessible = true }.get(obj)`. List all fields of a class: `clazz.declaredFields.forEach { println("${it.name}: ${it.type}") }`. Call a private method: `clazz.getDeclaredMethod("name", ArgType::class.java).also { it.isAccessible = true }.invoke(obj, arg)`. Inspect class hierarchy: `generateSequence(obj.javaClass) { it.superclass }.forEach { println(it.name) }`.

**After a compile error**: fix and retry — do NOT switch to Bash/Read/Write. Common fixes:
- `suspension functions can only be called within coroutine body` → mark your helper as `suspend fun`
- `unresolved reference` → add the missing import explicitly
- `Write access is allowed from write thread only` → wrap in `writeAction { }`
- `Read access is allowed from inside read-action only` → wrap the PSI/VFS call in `readAction { }`. Example: `val vf = readAction { FilenameIndex.getVirtualFilesByName("Foo.java", GlobalSearchScope.projectScope(project)).firstOrNull() }`

**NEVER use steroid_execute_code just to read files you already know the path for**: Use the native Read/Glob/Grep tools to read file *content* by known path — they have zero compilation overhead (~0s vs ~8s per steroid_execute_code call). Reserve steroid_execute_code for: writing files via VFS, PSI queries, test execution, compile checks. The only exception is reading files that were modified in this session via writeAction — in that case use `String(vf.contentsToByteArray(), vf.charset)` to see in-memory VFS state.

**INSIDE steroid_execute_code: use FilenameIndex for file discovery** — by exact filename OR by extension. FilenameIndex is an O(1) IDE-indexed lookup, much faster than filesystem traversal. Use the native Glob/Grep tools for file discovery OUTSIDE steroid_execute_code.
```kotlin
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
val scope = GlobalSearchScope.projectScope(project)
// ❌ WRONG inside steroid_execute_code: filesystem scan is slow and may miss indexed files
// ✅ RIGHT: FilenameIndex in steroid_execute_code
val javaFiles = readAction { FilenameIndex.getAllFilesByExt(project, "java", scope) }  // replaces Glob("**/*.java")
val yamlFiles = readAction { FilenameIndex.getAllFilesByExt(project, "yaml", scope) }  // replaces Glob("**/*.yaml")
val sqlFiles  = readAction { FilenameIndex.getAllFilesByExt(project, "sql", scope) }   // replaces Glob("**/*.sql")
val byName = readAction { FilenameIndex.getVirtualFilesByName("UserService.java", scope) }  // replaces Glob("**/UserService.java")
```
See `mcp-steroid://skill/coding-with-intellij-vfs` for more file-discovery patterns.

**Prefer VFS read over native Read tool** (only when you already have a steroid_execute_code call for other work): use `val vf = findProjectFile("path/File.java")!!; String(vf.contentsToByteArray(), vf.charset)` to see unsaved modifications from prior writeAction calls. Native Read bypasses VFS and may return stale content.

**Best Practice: Use Sub-Agents**
For complex IntelliJ API work, delegate to a sub-agent:
- Sub-agent can retry without polluting your context
- Errors stay isolated
- Provide detailed 'reason' parameter

**Resources:**
- [Script Context API](mcp-steroid://skill/coding-with-intellij-context-api) - Full McpScriptContext reference (println, readAction, findFile, progress, etc.)
- [Complete Coding Guide](mcp-steroid://skill/coding-with-intellij) - Patterns, examples, best practices
- [API Power User Guide](mcp-steroid://prompt/skill) - Essential patterns
- [Debugger Guide](mcp-steroid://prompt/debugger-skill) - Debug workflows
- [Test Runner Guide](mcp-steroid://prompt/test-skill) - Test execution

IntelliJ API Version: IU-253.31033.145

**When to use other steroid tools instead:**
- steroid_list_projects — list open projects and their paths
- steroid_list_windows — check window state, indexing progress, modal dialogs
- steroid_open_project — open a project directory in the IDE
- steroid_action_discovery — discover quick-fixes, intentions, and actions at a file location
- steroid_take_screenshot / steroid_input — visual UI inspection and interaction

💡 Call steroid_execute_feedback after execution to rate success
