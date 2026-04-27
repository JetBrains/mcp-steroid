Coding with IntelliJ: Threading and Read/Write Actions

IntelliJ threading model, read/write action patterns, smart mode, VFS mutation rules, modal dialogs, and ModalityState usage.

## Rules

### ⚠️ THREADING RULE — NEVER SKIP

Any PSI access (`JavaPsiFacade`, `PsiShortNamesCache`, `PsiManager.findFile`, `ProjectRootManager.contentSourceRoots`, module roots, annotations, etc.) **MUST** be wrapped in `readAction { }`. Modifications require `writeAction { }`. Threading violations throw immediately at runtime — they are not silently ignored. **This applies to ALL PSI calls including your very first exploration call** (e.g. listing source roots). This is the most common first-attempt error.

### IntelliJ Threading Model

1. **EDT (Event Dispatch Thread)** — UI updates only
2. **Read actions** required for PSI/VFS reads
3. **Write actions** required for PSI/VFS writes
4. **Smart mode** required for index-dependent operations

**See**: [IntelliJ Threading Rules](https://plugins.jetbrains.com/docs/intellij/general-threading-rules.html)

### Quick Start

Your code is a **suspend function body** (never use `runBlocking`):
- Use `readAction { }` for PSI/VFS reads, `writeAction { }` for modifications
- Use `smartReadAction { }` for index-dependent PSI reads
- Use `Observation.awaitConfiguration(project)` after project import/sync/configuration
- Available: `project`, `println()`, `printJson()`, `printException()`, `progress()`

**⚠️ Helper functions that call `readAction`/`writeAction` MUST be `suspend fun`** — a regular `fun` that calls these gets a compile error: `"suspension functions can only be called within coroutine body"`. This applies to ALL suspend-context APIs: `readAction`, `writeAction`, `smartReadAction`, `waitForSmartMode`, `runInspectionsDirectly`.

### writeAction { } Is NOT a Coroutine Scope

Calling `readAction { }` or ANY suspend function inside `writeAction { }` throws `suspension functions can only be called within coroutine body` at **runtime** (not compile time). **ALWAYS read first (outside), then write (inside)**. Use `edtWriteAction { }` if you genuinely need suspend calls inside a write block.

### ALL VFS Mutation Ops Need writeAction

`createDirectoryIfMissing()`, `createChildData()`, `createChildFile()`, `createChildDirectory()`, `delete()`, `rename()`, `move()`, and `saveText()` ALL require `writeAction`. Put the ENTIRE create-directory-and-write sequence inside a SINGLE `writeAction` block.

### Smart Mode

During indexing, the IDE is in "dumb mode" — many APIs are unavailable. Use `smartReadAction`
when you need both smart mode and read access. `waitForSmartMode()` is called automatically before
your script starts, but it is only a point-in-time wait: IntelliJ may enter dumb mode again before
the next statement. For initial project import/sync/configuration, await `Observation.awaitConfiguration(project)`,
then keep the indexed query inside `smartReadAction`.

```kotlin[IU]
import com.intellij.platform.backend.observation.Observation
import com.intellij.psi.JavaPsiFacade

Observation.awaitConfiguration(project)
val psiClass = smartReadAction {
    JavaPsiFacade.getInstance(project).findClass("com.example.MyService", allScope())
}
println("Class found: ${psiClass != null}")
```

### Modal Dialogs and ModalityState

When a modal dialog is open, the default EDT dispatcher (`Dispatchers.EDT`) will **not execute** your code. Use `ModalityState.any()` to run on EDT regardless of modal state.

**Use `ModalityState.any()` for:** enumerating windows/dialogs, taking screenshots, closing dialogs programmatically.
**Don't use it for:** normal UI operations (use plain `Dispatchers.EDT`), read/write actions (use `readAction`/`writeAction`).

---

## Examples

### readAction

```kotlin
val virtualFile = LocalFileSystem.getInstance().findFileByPath("/path/to/File.kt")!!
val psiFile = readAction {
    PsiManager.getInstance(project).findFile(virtualFile)
}
println("PSI file: ${psiFile?.name}")
```

### smartReadAction

```kotlin[IU]
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex

val classes = smartReadAction {
    KotlinClassShortNameIndex.get("MyService", project, projectScope())
}
println("Found ${classes.size} classes")
```

### writeAction — Read Outside, Write Inside

```kotlin
val vf = findProjectFile("src/main/java/com/example/Foo.java")!!
val content = String(vf.contentsToByteArray(), vf.charset)  // read OUTSIDE writeAction

// ⚠️ BEFORE content.replace() — ALWAYS print the excerpt BEFORE THE FIRST ATTEMPT:
val idx = content.indexOf("methodName")
println("EXCERPT:\n" + content.substring(idx, (idx + 250).coerceAtMost(content.length)))

val updated = content.replace("oldString", "newString")
check(updated != content) { "content.replace matched nothing — whitespace mismatch!" }
writeAction { VfsUtil.saveText(vf, updated) }    // write INSIDE — no suspend calls allowed

// After bulk VFS edits, flush to disk before running git/shell subprocesses:
LocalFileSystem.getInstance().refresh(false)
```

### WriteCommandAction (Undo Stack)

```kotlin
val vf = LocalFileSystem.getInstance().findFileByPath("/path/to/file.kt")!!
val document = FileDocumentManager.getInstance().getDocument(vf)!!

import com.intellij.openapi.command.WriteCommandAction

WriteCommandAction.runWriteCommandAction(project) {
    document.replaceString(0, 0, "// Added comment\n")
}
```

### VFS Mutation — Directory + File Creation

```kotlin
val content = "package com.example.model;\npublic class Product { }"
writeAction {
    val root = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)!!
    val dir = VfsUtil.createDirectoryIfMissing(root, "src/main/java/com/example/model")
    val f = dir.findChild("Product.java") ?: dir.createChildData(this, "Product.java")
    VfsUtil.saveText(f, content)
}
// WRONG: createDirectoryIfMissing OUTSIDE writeAction → "Write access is allowed inside write-action only"
```

### Create/Write a Source File

One file per steroid_execute_code call when possible (makes error attribution trivial):
```kotlin
writeAction {
    val root = LocalFileSystem.getInstance().findFileByPath(project.basePath!!)!!
    val dir = VfsUtil.createDirectoryIfMissing(root, "src/main/java/com/example/model")
    val f = dir.findChild("Product.java") ?: dir.createChildData(this, "Product.java")
    // Use joinToString() — NOT a triple-quoted string with 'import' at line start
    // ⚠️ VfsUtil.saveText() REPLACES THE ENTIRE FILE — for adding a single method,
    // use PSI writeCommandAction + factory.createMethodFromText() instead (see guide).
    VfsUtil.saveText(f, listOf(
        "package com.example.model;",
        "import" + " jakarta.persistence.Entity;",
        "import" + " jakarta.persistence.Id;",
        "@Entity public class Product { @Id private Long id; }"
    ).joinToString("\n"))
}
println("File created")
// After bulk file creation: await configuration, then use smartReadAction for ReferencesSearch
```

### ModalityState Usage

```kotlin
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Runs on EDT even when a modal dialog is showing
withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
    val isModal = ModalityState.current() != ModalityState.nonModal()
    println("Modal dialog showing: $isModal")
}
```

### DumbService Check

```kotlin
import com.intellij.openapi.project.DumbService

if (DumbService.isDumb(project)) {
    println("IDE is indexing - indices not available")
} else {
    println("Smart mode - all APIs available")
}
```

---

## Pitfalls

### Import-in-Strings

Never put `import foo.Bar;` at the start of a line inside a triple-quoted Kotlin string. The script preprocessor extracts those lines as Kotlin imports, causing compile errors. Use `"import" + " foo.Bar;"` or `joinToString` to build the content, or use `java.io.File(path).writeText(content)` as an alternative.

### Generating Java Code Inline — `.class` and Dollar-Sign

Java code often contains `.class` references and dollar-sign characters. In double-quoted Kotlin strings, `.class)` can be mis-parsed and a bare dollar sign triggers string interpolation. Use `java.io.File(path).writeText()` with string concatenation:
```kotlin
java.io.File("${project.basePath}/src/main/java/com/example/SecurityConfig.java").writeText(
    "package com.example;\n" +
    "import" + " org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;\n" +
    "public class SecurityConfig {\n" +
    "    public void configure(HttpSecurity http) throws Exception {\n" +
    "        http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);\n" +
    "    }\n" +
    "}"
)
// For dollar signs in Java string literals: use "${'\$'}Bearer"
```
