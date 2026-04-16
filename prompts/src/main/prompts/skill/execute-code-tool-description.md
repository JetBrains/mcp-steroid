Execute Code Tool

MCP tool description for the steroid_execute_code tool.

###_NO_AUTO_TOC_###
Execute Kotlin code directly in IntelliJ's runtime with full API access — builds, tests, refactoring, inspections, debugging, navigation.

**Before your first call, read the guide for your task** with `ReadMcpResourceTool`:
- Building/compiling → `mcp-steroid://prompt/test-skill`
- Running tests → `mcp-steroid://prompt/test-skill`
- Debugging → `mcp-steroid://prompt/debugger-skill`
- Any IDE task → `mcp-steroid://prompt/skill`

**Quick Start:**
- Code is a suspend function body (never use runBlocking)
- `readAction { }` for reads, `writeAction { }` for modifications
- `waitForSmartMode()` runs automatically
- Available: `project`, `println()`, `printJson()`, `progress()`

**Compile check** (use after every edit — do NOT use `./mvnw compile`):

```kotlin
import com.intellij.task.ProjectTaskManager
import org.jetbrains.concurrency.await

val result = ProjectTaskManager.getInstance(project).buildAllModules().await()
println("Compile errors: ${result.hasErrors()}, aborted: ${result.isAborted()}")
```

**Do NOT use Bash `./mvnw test` or `./gradlew test`** — read `mcp-steroid://prompt/test-skill` for IDE test runner patterns that save ~31s per invocation.

**After a compile error**: fix and retry. Common fixes:
- `suspension functions can only be called within coroutine body` → mark helper as `suspend fun`
- `unresolved reference` → add missing import
- `Write access is allowed from write thread only` → wrap in `writeAction { }`
- `Read access is allowed from inside read-action only` → wrap in `readAction { }`

**File discovery INSIDE steroid_execute_code**: use `FilenameIndex` (O(1) indexed), not filesystem scan.
**File reading by known path**: use native `Read` tool (zero overhead), not steroid_execute_code.

💡 Call `steroid_execute_feedback` after execution to rate success.
