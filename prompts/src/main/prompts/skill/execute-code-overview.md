Execute Code: Additional Rules

File creation, Edit tool constraint, ProcessBuilder ban, JDK/plugin restrictions, and required imports for steroid_execute_code.

## ⚡ File Creation: Use the Write Tool (NOT steroid_execute_code)

**Creating files via steroid_execute_code (`writeAction { VfsUtil.saveText(...) }`) is measured +47% slower**
than using the native Write tool — IntelliJ VFS overhead dominates file creation time.

**Use the Write tool for all file creation.** After writing files, trigger a VFS refresh
if you need IntelliJ to index them for PSI/compilation:

```kotlin
// ONE steroid_execute_code call to refresh after bulk file creation via Write tool:
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.LocalFileSystem
VfsUtil.markDirtyAndRefresh(false, true, true,
    LocalFileSystem.getInstance().findFileByPath(project.basePath!!)
)
println("VFS refreshed — IntelliJ now sees new files")
```

**Exception**: Use steroid_execute_code VFS file creation ONLY when you must create a file AND
immediately use PSI on it in the same steroid_execute_code call (e.g., create + run inspections atomically).

---

## ⚠️ steroid_execute_code VFS Reads Do NOT Satisfy the Native Edit Tool's Read-Before-Write Constraint

If you read a file via steroid_execute_code (`String(vf.contentsToByteArray(), vf.charset)`) and then try to use the native `Edit` tool on the same file, you will get `"File has not been read yet"`. These are tracked separately.

Options:
- **(a)** Also issue a native `Read` tool call for that file before using `Edit`
- **(b)** Use a `writeAction { }` block in steroid_execute_code to both read and write the file atomically — **PREFERRED** (saves a round-trip)

If you plan to modify a file via steroid_execute_code `writeAction { }`, do NOT also issue a native `Read` for that file — it wastes a turn and provides zero benefit. steroid_execute_code can read and write in a single call:
```kotlin
import com.intellij.openapi.vfs.VfsUtil

val vf = findProjectFile("src/main/java/com/example/MyClass.java")!!
val content = String(vf.contentsToByteArray(), vf.charset)  // read OUTSIDE writeAction
val updated = content.replace("oldMethod", "newMethod")
check(updated != content) { "replace matched nothing — check whitespace" }
writeAction { VfsUtil.saveText(vf, updated) }  // write INSIDE writeAction
// ↑ This replaces both Read + Edit tools in a single steroid_execute_code call
```

---

## ❌ BANNED: ProcessBuilder for Builds and Tests

**`ProcessBuilder("./mvnw", ...)` and `ProcessBuilder("./gradlew", ...)` inside `steroid_execute_code` are BANNED** for build and test execution.

**Why it's harmful:**
- Spawns an external child process from within IntelliJ's JVM — bypasses all IDE process management
- Causes classpath conflicts (child Maven/Gradle inherits IDE's classpath)
- Produces 200k+ character output → MCP token limit overflow → lost agent turns
- Prevents IntelliJ from tracking the build lifecycle

**What's allowed instead:**

| Task | Use instead of ProcessBuilder | Details |
|------|-------------------------------|---------|
| Run Maven tests | `MavenRunConfigurationType.runConfiguration()` + `SMTRunnerEventsListener` | [Maven patterns](mcp-steroid://skill/execute-code-maven) |
| Run Gradle tests | `GradleRunConfiguration` + `setRunAsTest(true)` + `SMTRunnerEventsListener` | [Spring/build patterns](mcp-steroid://skill/coding-with-intellij-spring) |
| Maven sync after pom.xml edit | `MavenProjectsManager.scheduleUpdateAllMavenProjects()` + `Observation.awaitConfiguration()` | [Maven sync](mcp-steroid://skill/execute-code-maven) |
| Gradle sync after build.gradle.kts edit | `ExternalSystemUtil.refreshProject(path, ImportSpecBuilder(project, GradleConstants.SYSTEM_ID).build())` | [Spring/build patterns](mcp-steroid://skill/coding-with-intellij-spring) |
| Check Docker availability | `java.io.File("/var/run/docker.sock").exists()` — no process spawn needed | [Spring patterns](mcp-steroid://skill/coding-with-intellij-spring) |
| Docker inspect/exec operations | **Bash tool** (outside steroid_execute_code) — e.g. `docker inspect`, `docker exec` | — |
| `dependency:resolve` workaround | `MavenProjectsManager.getInstance(project).forceUpdateAllProjectsOrFindAllAvailablePomFiles()` | [Maven sync](mcp-steroid://skill/execute-code-maven) |

**`GeneralCommandLine("docker", ...)` and `ProcessBuilder("docker", ...)` inside steroid_execute_code are BANNED** — same reason as `./mvnw`: child process inside IDE JVM causes classpath conflicts. Use the Bash tool outside steroid_execute_code instead.

**ProcessBuilder("./mvnw") is permitted ONLY when:**
1. pom.xml was just modified in this session, AND
2. Maven sync was already triggered (`scheduleUpdateAllMavenProjects` + `awaitConfiguration`), AND
3. `MavenRunConfigurationType.runConfiguration()` with `dialog_killer: true` has already timed out (>2 min)

---

## ⚠️ Do NOT Configure JDKs/SDKs or Install Plugins

This environment is pre-configured with the correct JDK and all required plugins. Do NOT call `ProjectJdkTable`, search hardcoded JVM paths, or attempt any JDK/SDK configuration — these calls either throw at runtime or silently do nothing, wasting a turn. Do NOT attempt `PluginsAdvertiser`, `PluginInstaller`, `PluginManagerCore` write APIs, or reflection-based plugin installation.

To check if a plugin is already installed (without installing it):
```kotlin
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
val pluginId = PluginId.getId("org.jetbrains.kotlin")
val installed = PluginManagerCore.isPluginInstalled(pluginId)
println("Plugin installed: $installed")
// If not installed: report the plugin ID and stop — do NOT attempt programmatic installation
```

---

## ⚠️ NO AUTO-IMPORTS — Every IntelliJ Class Must Be Imported Explicitly

A missing import produces `unresolved reference` (sometimes misleadingly as a type-inference error) and wastes a full retry turn. Common imports not auto-added by the preprocessor:
```kotlin[IU]
import com.intellij.psi.search.FilenameIndex        // getVirtualFilesByName, getAllFilesByExt
import com.intellij.psi.search.GlobalSearchScope    // projectScope(), allScope()
import com.intellij.openapi.roots.ProjectRootManager // contentSourceRoots
import com.intellij.openapi.vfs.VfsUtil              // saveText(), createDirectoryIfMissing()
import com.intellij.psi.search.PsiShortNamesCache   // allClassNames
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.search.searches.ReferencesSearch
```
