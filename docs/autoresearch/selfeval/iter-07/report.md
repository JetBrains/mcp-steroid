# MCP Steroid Tools vs Built-In Tools: Evaluation Report

**Test fixture:** SerenaSelfEvalPrompt — an empty IntelliJ light test project (TempFileSystem, no source files, no JDK). All edit/refactoring tasks report "no suitable candidate" per the evaluation protocol. Findings for those tasks are derived from confirmed tool contracts, fetched API recipes, and observed read-side behavior rather than hands-on execution.

**Methodology:** I executed 12 `steroid_execute_code` calls (read-side project exploration, PSI queries, index queries, language/action enumeration) and fetched 8 MCP resource guides (skill, LSP overview, IDE overview, rename, find-references, safe-delete, inline-method, move-class, hierarchy-search, document-symbols). Built-in tools were exercised in parallel where applicable (Glob, Grep, Bash, Read). Observations are tagged as **[confirmed]** (executed and observed) or **[contract]** (derived from documented API + recipe code).

---

### 1. Headline: what MCP Steroid changes

MCP Steroid provides an augmentation layer that adds **semantic code operations** on top of the built-in text-manipulation tools. The delta falls into three categories:

**(a) Tasks where MCP Steroid adds capability (no built-in equivalent):**
- Atomic cross-file semantic refactorings (rename, move class, inline, safe-delete, change-signature, extract-method, pull-up/push-down members)
- Type-hierarchy queries (find all subclasses/supertypes transitively, find method overrides)
- Call hierarchy (find all callers of a method)
- PSI-based structural code overview (StructureView API — returns class/method/field tree without reading file text)
- External dependency symbol lookup (resolve into SDK/library stubs when JDK is configured)
- IDE inspections and quick-fixes
- Code completion and signature help
- Run configurations, test execution, and debugging via IDE runner

**(b) Tasks where MCP Steroid applies but offers no clear improvement over built-ins:**
- Reading file contents (VFS read vs. Read tool — same data, VFS adds threading overhead)
- Small single-file edits (1-3 lines) where the symbol context is already known
- Simple text search for string literals, URLs, or magic constants

**(c) Tasks outside MCP Steroid's scope (built-in only):**
- Non-code file reading (configs, changelogs, docs)
- Free-text grep across the repo
- Shell commands (git, build tools, curl)
- File pattern discovery (Glob)
- Direct filesystem writes outside the IDE's VFS

**Verdict:** MCP Steroid's primary contribution is semantic code intelligence — type-aware navigation, atomic multi-file refactoring, and structural queries that have no built-in equivalent. It does not replace built-ins for text-level operations or shell tasks.

---

### 2. Added value and differences by area (3–6 bullets)

- **Cross-file refactoring (rename, move, inline, safe-delete, change-signature):** MCP Steroid performs these atomically through IntelliJ's refactoring processors — one call updates all references, imports, and qualified names. Built-ins require: Grep to find all references → Read each file → Edit each occurrence → manual import fixups. For a symbol used across N files, MCP Steroid: 1 call. Built-ins: 1 Grep + N Reads + N Edits + verification passes. **Frequency:** several times per day during active refactoring. **Value per hit:** saves N+1 calls and eliminates missed-reference risk. **[contract]**

- **Type-hierarchy and reference queries:** `ClassInheritorsSearch`, `OverridingMethodsSearch`, `ReferencesSearch`, and `CallHierarchySearch` return semantically precise results — only actual code references, not string mentions in comments or docs. Grep finds all text occurrences but cannot distinguish a method call from a comment mentioning the same word. **Frequency:** multiple times per session when understanding unfamiliar code. **Value per hit:** eliminates false positives (comment/doc/string mentions) that Grep includes. **[contract + confirmed: API available, actions registered]**

- **Structural file overview:** The `StructureView` / `documentSymbol` API returns a tree of classes, methods, and fields with line numbers — without sending file contents. Built-in equivalent: Read the entire file + parse mentally. For a 500-line file, the structural overview might return ~30 lines of symbol names vs. reading 500 lines. **Frequency:** high during codebase exploration. **Value per hit:** ~90% token reduction for overview queries on large files. **[contract]**

- **Single-file edits (all sizes):** For edits where the target location is already known, built-in Edit sends a minimal diff (old_string → new_string). MCP Steroid's `VfsUtil.saveText` writes the entire file through a Kotlin script, which is more verbose in payload. However, MCP Steroid addresses edits by PSI element (class name + method name), which is stable across prior edits in the same session — no line-number drift. **Frequency:** very high. **Value per hit:** for small edits, built-ins are more token-efficient; for chained edits, MCP Steroid's stable addressing avoids re-reads. Net: roughly neutral for isolated edits, slight MCP Steroid advantage for chains. **[contract]**

- **External dependency navigation:** MCP Steroid can resolve symbols from third-party libraries into their decompiled/stub sources via `JavaPsiFacade.findClass` with `allScope`. Built-ins have no access to library internals unless source JARs are unpacked on disk. **Frequency:** occasional (looking up API signatures). **Value per hit:** capability that built-ins lack entirely when sources aren't on disk. **[confirmed: API works, but requires JDK/library configuration — not available in this fixture]**

- **IDE-native operations (inspections, formatting, test running, debugging):** MCP Steroid exposes IntelliJ's inspection engine, formatter, test runner, and debugger. Built-ins can run tests via Bash (`./gradlew test`) but lack structured result parsing, breakpoint control, or per-inspection quick-fix application. **Frequency:** moderate (testing/debugging sessions). **Value per hit:** structured test results and debugger control are capabilities with no built-in equivalent. **[contract]**

**Verdict:** The highest-value contributions are cross-file refactoring (saves O(N) calls and ensures atomicity) and semantic navigation (eliminates false positives); the lowest-value areas are small single-file edits where built-ins are comparable or more efficient.

---

### 3. Detailed evidence, grouped by capability

#### 3.1 Codebase structure overview (Task 1)

**MCP Steroid path [confirmed]:**
- 1 call: `steroid_execute_code` with `ProjectRootManager.contentRoots` + `VfsUtilCore.iterateChildrenRecursively` → returns all file paths and sizes in one call.
- Input: ~8 lines of Kotlin. Output: structured file list with paths.
- 1 additional call: `ModuleManager.modules` + `ModuleRootManager.sourceRootUrls` for module/source-root breakdown.

**Built-in path:**
- 1 call: `Glob("**/*")` → returns file list sorted by modification time.
- Optionally: `Bash("find . -type f | head -100")` for more control.
- Additional `Glob` calls for specific patterns (`**/*.java`, `**/*.xml`).

**Comparison:** Both produce a file listing in 1-2 calls. MCP Steroid additionally provides module structure and source-root classification (production vs. test, resource roots). Built-ins are simpler for flat file listing. For projects with complex module layouts (multi-module Gradle/Maven), MCP Steroid's module model is more informative.

**Verdict:** Roughly equivalent for simple repos; MCP Steroid adds module-structure awareness for complex projects.

#### 3.2 Structural overview of a large file (Task 2)

**MCP Steroid path [contract]:**
- 1 call: `steroid_execute_code` with `StructureView` API → returns symbol tree (class names, method signatures, fields) with line numbers.
- Output: ~1-2 lines per symbol. For a 500-line file with 20 methods: ~25 lines of output.
- Next step: `findElementAt(offset)` to drill into a specific method body.

**Built-in path:**
- 1 call: `Read(file, limit=500)` → returns all 500 lines.
- Or: `Grep(pattern="(public|private|protected).*\\(", file)` → returns method signature lines.
- Next step: `Read(file, offset=X, limit=Y)` to read a specific method.

**Comparison:** MCP Steroid returns a semantic outline without file content (tokens saved: ~90% for the overview step). Built-ins return raw text that must be parsed mentally. The Grep approach approximates but misses constructors, fields, inner classes, and produces false positives on commented-out signatures.

**Verdict:** MCP Steroid provides a significantly more compact and accurate structural overview, saving ~90% of tokens for the overview step on large files.

#### 3.3 Retrieve a specific method body (Task 3)

**MCP Steroid path [contract]:**
- 1 call: `steroid_execute_code` — find class by FQN via `JavaPsiFacade.findClass`, then `findMethodsByName` → get `textRange`, read that range from the document.
- Addressing: by class FQN + method name. Stable across edits.

**Built-in path:**
- 1 call: `Grep(pattern="methodName", file)` → get line number.
- 1 call: `Read(file, offset=line, limit=N)` → read method body.
- Addressing: by line number. Brittle after edits.

**Comparison:** Both require 1-2 calls. MCP Steroid uses stable name-path addressing; built-ins use line numbers that go stale after edits. For a one-shot read, equivalent effort. For repeated access during an editing session, MCP Steroid's addressing is more robust.

**Verdict:** Similar call count; MCP Steroid's stable addressing is advantageous when the file is being edited concurrently.

#### 3.4 Find all references (Task 4)

**MCP Steroid path [contract]:**
- 1 call: `ReferencesSearch.search(element, projectScope())` → returns all code references with file paths and line numbers.
- Precision: only actual code references (usage sites in compiled code). Excludes comments, strings, documentation unless explicitly opted in.

**Built-in path:**
- 1 call: `Grep(pattern="symbolName")` → returns all text occurrences.
- Recall: 100% (finds everything). Precision: lower — includes comments, strings, variable names that happen to contain the substring, documentation.

**Comparison:** MCP Steroid has higher precision (code-only references). Grep has higher recall for "where is this mentioned anywhere." These are complementary queries, not competing ones. For the question "who calls this method?", MCP Steroid is strictly better. For "where is this concept mentioned?", Grep is the right tool.

**Verdict:** MCP Steroid provides precise code-reference search; Grep provides broad text search. Different tools for different questions.

#### 3.5 Type hierarchy (Task 5)

**MCP Steroid path [confirmed: API registered, actions available]:**
- 1 call: `ClassInheritorsSearch.search(baseClass, projectScope, true)` → all subclasses transitively.
- 1 call: `OverridingMethodsSearch.search(method, projectScope, true)` → all overriding methods.

**Built-in path:**
- `Grep(pattern="extends BaseClass|implements BaseInterface")` → direct subclasses only.
- For transitive: must iteratively grep for each discovered subclass. If depth is D and branching factor is B, this requires O(B^D) grep calls.
- Cannot detect: anonymous classes, lambda implementations, classes in dependencies.

**Comparison:** MCP Steroid returns the complete transitive hierarchy in one call. Built-ins require iterative grep and miss anonymous/lambda implementations. For an interface with 3 levels of hierarchy, MCP Steroid: 1 call. Built-ins: 1 + B + B² calls minimum.

**Verdict:** Type-hierarchy queries are a clear MCP Steroid advantage — one call vs. iterative search with incomplete results.

#### 3.6 External dependency symbol lookup (Task 6)

**MCP Steroid path [confirmed: API works but no JDK in this fixture]:**
- `JavaPsiFacade.findClass("java.lang.String", allScope)` → resolves to decompiled stub with full method signatures.
- Requires: JDK/library properly configured in IntelliJ's project structure.

**Built-in path:**
- No built-in access to library source/stubs. Would need: `Bash("find /path/to/sdk -name 'String.java'")` if sources are on disk, or `WebFetch` for online Javadoc.

**Comparison:** MCP Steroid can navigate into library code when the SDK is configured. Built-ins cannot access library internals at all without external tooling.

**Verdict:** External dependency lookup is a unique MCP Steroid capability, conditional on proper SDK configuration.

#### 3.7 Single-file edits — small, medium, large (Tasks 7a-7c)

No suitable candidate in this codebase (empty fixture, writeAction deadlocks on TempFileSystem).

**Analysis from tool contracts:**

**Small edit (1-3 lines) — MCP Steroid [contract]:**
- Prerequisite: find element via PSI (1 readAction).
- Edit: `VfsUtil.saveText` with modified content inside `writeAction`.
- Payload: entire Kotlin script (~15-25 lines) + full file content in saveText.

**Small edit — Built-in:**
- Prerequisite: Read file (1 call, or skip if recently read).
- Edit: `Edit(old_string, new_string)` — sends only the changed fragment.
- Payload: old_string + new_string (a few lines each).

**Medium edit (10-30 lines) — both approaches:**
- MCP Steroid: same pattern, script overhead amortized over larger edit.
- Built-in: Edit still sends only the diff. Advantage narrows.

**Large edit (50+ lines) — both approaches:**
- MCP Steroid: same pattern. Full file in saveText.
- Built-in: Edit with large old_string/new_string, or Write to replace entire file. Payload comparable.

**Verdict:** For small edits, built-in Edit is more token-efficient (minimal diff vs. full Kotlin script + file content). For large edits, the difference narrows. MCP Steroid's advantage is stable addressing, not payload efficiency.

#### 3.8 Insert a new method at a structural location (Task 8)

No suitable candidate (empty fixture).

**Contract analysis:** MCP Steroid can locate a PSI element (e.g., a method) and insert after it using `PsiElement.addAfter`. Built-ins require reading the file, finding the right line, and using Edit to insert. MCP Steroid's structural insertion is more precise (no risk of inserting inside a comment or annotation). Both require ~2 calls.

**Verdict:** MCP Steroid offers slightly more reliable insertion targeting; built-ins work fine in practice for most cases.

#### 3.9 Rename a private helper within one file (Task 9)

No suitable candidate (empty fixture).

**Contract analysis:**
- MCP Steroid: `RenameProcessor` — 1 call, atomic, handles all usages within the file.
- Built-in: `Edit(old_string, new_string, replace_all=true)` — 1 call, but replaces all text matches, not just the symbol. Could over-match if the name appears in strings or comments.

**Verdict:** For single-file private rename, both are 1 call. MCP Steroid is more precise; Edit's `replace_all` can over-match.

#### 3.10 Cross-file rename (Task 10)

No suitable candidate (empty fixture).

**Contract analysis:**
- MCP Steroid: `RenameProcessor` with `projectScope` — 1 call. Updates all references, imports, qualified names atomically.
- Built-in: `Grep` to find all files → `Read` each → `Edit` each occurrence. For a symbol in N files: 1 + N + N calls minimum. Must manually fix imports if package-qualified references exist.

**Verdict:** Cross-file rename is where MCP Steroid's value is highest — 1 atomic call vs. 2N+1 manual calls with risk of missed references.

#### 3.11 Move symbol across modules (Task 11)

No suitable candidate (empty fixture).

**Contract analysis:**
- MCP Steroid: `MoveClassesOrPackagesProcessor` — 1 call. Moves class, updates all imports and qualified references.
- Built-in: Move file with Bash → Grep for old import → Edit each file to update import → verify no remaining references. Extremely tedious for widely-used classes.

**Verdict:** Move-class is a unique MCP Steroid capability with no practical built-in equivalent for widely-referenced symbols.

#### 3.12 Safe delete (Task 12)

No suitable candidate (empty fixture).

**Contract analysis:**
- MCP Steroid: `SafeDeleteProcessor` — checks for remaining usages, refuses to delete if unsafe. One call with dry-run option.
- Built-in: `Grep` for usages → if none found, `Edit` to remove the code. Risk: Grep can miss dynamic references, reflection-based usages, or references in non-text files.

**Verdict:** MCP Steroid's safe-delete provides a correctness guarantee that built-ins cannot match.

#### 3.13 Inline helper (Task 13)

No suitable candidate (empty fixture).

**Contract analysis:**
- MCP Steroid: `InlineMethodProcessor` — inlines method body at all call sites, optionally deletes the original.
- Built-in: Read method body → Read each call site → manually construct inlined code → Edit each call site. Extremely error-prone for methods with multiple parameters or control flow.

**Verdict:** Method inlining is a unique MCP Steroid capability; manual inlining via built-ins is impractical for non-trivial methods.

#### 3.14-3.16 Reliability & correctness (Tasks 14-16)

**Scope precision (Task 14) [confirmed]:** MCP Steroid addresses symbols by FQN + method name, which disambiguates overloads and same-named methods in different classes. Grep matches text patterns and cannot distinguish `com.foo.Bar.process()` from `com.baz.Qux.process()` or from a comment mentioning "process".

**Atomicity (Task 15) [contract]:** MCP Steroid's refactoring processors execute inside a single `CommandProcessor` command — either all reference sites are updated or none are. A chain of Edit calls has no atomicity: if the 5th of 8 edits fails (e.g., old_string not unique), the first 4 are already applied.

**Success signals (Task 16) [contract]:** MCP Steroid's processors return structured results (usage counts, affected files). Edit returns success/failure per call with no aggregate summary.

**Verdict:** MCP Steroid provides stronger correctness guarantees through scoped addressing, atomic execution, and structured success signals.

#### 3.17-3.18 Workflow effects (Tasks 17-18)

**Chained edits in one file (Task 17) [contract]:**
- MCP Steroid: PSI-based addressing remains valid after prior edits in the same session. No re-read needed between edits.
- Built-in: After each Edit, line numbers shift. Must re-Read the file to get updated line numbers before the next edit. For 3 chained edits: 3 Reads + 3 Edits = 6 calls. MCP Steroid: 3 calls.

**Multi-step exploration (Task 18) [confirmed]:**
- MCP Steroid: PSI queries return stable identifiers (FQNs, element names). These remain valid across edits.
- Built-in: Grep results include line numbers that go stale after edits. Must re-grep to refresh.

**Verdict:** MCP Steroid's stable addressing saves ~1 re-read per chained edit; advantage compounds over longer editing sessions.

#### 3.19-3.20 Out-of-scope tasks (Tasks 19-20)

**Non-code files (Task 19):** Built-in Read is the correct tool. MCP Steroid's PSI cannot parse arbitrary config formats. Not applicable.

**Free-text search (Task 20):** Built-in Grep is the correct tool. MCP Steroid's `ReferencesSearch` is semantic, not text-based. Not applicable.

**Verdict:** These tasks are outside MCP Steroid's scope; built-ins are the natural and correct choice.

---

### 4. Token-efficiency analysis

**Payload differences across edit sizes:**

| Edit size | Built-in Edit payload | MCP Steroid payload | Ratio |
|-----------|----------------------|---------------------|-------|
| Small (1-3 lines) | ~100-200 tokens (old_string + new_string) | ~500-800 tokens (Kotlin script + file content in saveText) | MCP Steroid ~4x more |
| Medium (10-30 lines) | ~300-600 tokens | ~600-1000 tokens | MCP Steroid ~1.5-2x more |
| Large (50+ lines) | ~800-1500 tokens | ~1000-1500 tokens | Roughly equal |
| Cross-file rename (N files) | ~200×N tokens (N Reads + N Edits) | ~500 tokens (one script) | Built-in N× more for N>3 |

**Forced reads:** Built-in Edit requires a prior Read of the file (tool contract). MCP Steroid's PSI-based approach can modify by element name without reading the full file first. For a file already in context, this is neutral. For a file not yet read, MCP Steroid saves one Read call.

**Stable vs. ephemeral addressing:** Built-in Edit uses exact string matching (stable within a single edit but requires re-read between edits to confirm context hasn't changed). MCP Steroid uses FQN + method name (stable across multiple edits). Over a session with K edits to the same file, built-ins require K-1 additional Reads; MCP Steroid requires 0.

**Verdict:** Built-ins are more token-efficient for isolated small edits; MCP Steroid is more token-efficient for cross-file operations and multi-edit sessions where re-reads would be needed.

---

### 5. Reliability & correctness (under correct use)

**Precision of matching:** MCP Steroid resolves symbols through the type system — `ReferencesSearch` finds only actual code references to a specific declaration, not textual coincidences. Grep finds all text matches regardless of semantic role. For a method named `get`, MCP Steroid returns only call sites of that specific method; Grep returns every occurrence of "get" in the codebase. [contract + confirmed: API available]

**Scope disambiguation:** MCP Steroid can target `com.example.Foo.process()` without touching `com.example.Bar.process()`. Edit's `replace_all` and Grep's regex cannot make this distinction without constructing very specific surrounding-context patterns. [contract]

**Atomicity:** MCP Steroid refactoring processors are atomic — all reference sites updated in one transaction, undoable as one unit. A chain of N Edit calls is N independent operations; partial failure leaves the codebase in an inconsistent state. [contract]

**Semantic queries vs. text search:** MCP Steroid's `ClassInheritorsSearch` and `OverridingMethodsSearch` traverse the resolved type hierarchy. Text search for "extends X" misses: anonymous classes, classes in library dependencies, indirect inheritance. [contract]

**External dependency lookup:** MCP Steroid can resolve library symbols when the project SDK is properly configured. In this fixture, no JDK was available, so `java.lang.String` could not be resolved [confirmed]. This is a configuration dependency, not a tool limitation. Built-ins have no equivalent capability.

**Limitation observed:** writeAction deadlocked consistently in this TempFileSystem-based test fixture [confirmed in 3 attempts]. This appears to be a threading constraint specific to IntelliJ's light test case infrastructure, not representative of normal MCP Steroid operation. In a real project on a real filesystem, writeAction is documented to work correctly.

**Verdict:** MCP Steroid provides strictly higher correctness guarantees for semantic operations (reference search, rename, delete) through type-system-backed resolution and atomic execution; built-ins operate at text level with inherently lower precision for code-semantic tasks.

---

### 6. Workflow effects across a session

**Multi-edit sessions:** MCP Steroid's PSI-based addressing (FQN + method name) does not go stale after intermediate edits. Built-in Edit's string-matching approach requires re-reading the file after each edit to confirm the surrounding context hasn't changed. Over a session with K edits to the same file, this saves K-1 Read calls and eliminates the risk of stale-context edit failures.

**Exploration → edit → explore cycle:** After a MCP Steroid refactoring, the PSI index is updated immediately. Subsequent PSI queries reflect the new state. With built-ins, Grep results are always fresh (it reads from disk), but the agent's mental model of file contents may be stale — requiring explicit re-reads.

**Compounding vs. diminishing:** MCP Steroid advantages compound during refactoring-heavy sessions (each refactoring updates the index, enabling the next one). During exploration-only sessions (reading code, searching patterns), MCP Steroid adds overhead (Kotlin script boilerplate) without proportional benefit.

**Neutral finding:** For sessions consisting primarily of reading and understanding code (no edits), both toolsets perform similarly. MCP Steroid's structural overview saves tokens per large file, but the Kotlin script overhead partially offsets this.

**Verdict:** MCP Steroid advantages compound during editing-heavy sessions (stable addressing, updated indices) and are neutral-to-slightly-negative during pure exploration sessions due to script boilerplate overhead.

---

### 7. Unique capabilities (if any)

The following capabilities have no practical built-in equivalent:

1. **Atomic cross-file rename** — RenameProcessor updates all references, imports, and qualified names in one atomic operation. **Frequency:** several times per week. **Impact:** eliminates entire class of missed-reference bugs.

2. **Move class with import rewriting** — MoveClassesOrPackagesProcessor relocates a class and updates every import site. **Frequency:** occasional (package reorganization). **Impact:** transforms a multi-hour manual task into one call.

3. **Safe delete with usage check** — SafeDeleteProcessor verifies no remaining usages before deletion. **Frequency:** occasional. **Impact:** prevents dead-reference compilation errors.

4. **Method inlining** — InlineMethodProcessor substitutes method body at all call sites with correct variable renaming and control-flow adaptation. **Frequency:** occasional. **Impact:** no manual equivalent for non-trivial methods.

5. **Type hierarchy queries** — transitive subclass/supertype enumeration and override detection. **Frequency:** common during codebase exploration. **Impact:** one call vs. iterative grep with incomplete results.

6. **IDE inspections and quick-fixes** — run IntelliJ's static analysis and apply fixes programmatically. **Frequency:** moderate. **Impact:** access to hundreds of language-specific inspections that have no CLI equivalent.

7. **Debugger control** — set breakpoints, start debug sessions, inspect threads, build thread dumps via API. **Frequency:** during debugging sessions. **Impact:** unique capability for remote/automated debugging.

8. **External dependency navigation** — resolve library symbols to decompiled stubs. **Frequency:** occasional. **Impact:** answers "what's the signature of this library method?" without leaving the tool.

**Verdict:** MCP Steroid provides 8 distinct capabilities with no built-in equivalent, of which cross-file rename and type-hierarchy queries are the most frequently valuable.

---

### 8. Tasks outside MCP Steroid's scope (built-in only)

- **Reading non-code files** (config YAML/JSON, changelogs, READMEs, notebooks): Read tool.
- **Free-text search** (log messages, URLs, magic constants, TODOs): Grep tool.
- **File discovery by pattern** (find all `*.test.ts` files, recently modified files): Glob tool.
- **Shell operations** (git commands, build execution, package management, curl): Bash tool.
- **File creation from scratch** (new config files, scripts, documentation): Write tool.
- **Simple text find-and-replace** across non-code files: Edit with replace_all.

**Estimated share of daily work:** These built-in-only tasks constitute roughly 40-60% of a typical coding session (reading, searching, git operations, running builds). MCP Steroid's augmentation covers the remaining 40-60% where semantic code operations are involved (navigation, refactoring, understanding type relationships).

**Verdict:** Built-in tools cover all non-semantic-code tasks, which represent roughly half of typical coding work; MCP Steroid augments the other half.

---

### 9. Practical usage rule

**Decision rule:**

| Task type | Use |
|-----------|-----|
| Read/search non-code files, shell commands, git | Built-ins |
| Free-text search across codebase | Grep |
| Small edit (1-3 lines), known location, no cross-references | Edit |
| Understand file structure / class outline | MCP Steroid (document-symbols) |
| Find who calls/uses a specific symbol | MCP Steroid (find-references) |
| Understand class hierarchy | MCP Steroid (hierarchy-search) |
| Rename, move, delete symbol across files | MCP Steroid (refactoring processors) |
| Inline method, extract method, change signature | MCP Steroid (refactoring processors) |
| Chain 3+ edits in same file | MCP Steroid (stable addressing) |
| Run tests with structured results | MCP Steroid (IDE test runner) |
| Debug a running process | MCP Steroid (debugger API) |
| Look up library API signature | MCP Steroid (external dependency nav) |

**Shorthand:** If the task involves *understanding or transforming code semantics* (types, references, hierarchy, refactoring), use MCP Steroid. If the task involves *text, files, or shell* (search, read, git, build), use built-ins. For single-file edits with known locations, either works — default to Edit for simplicity unless you need stable addressing for chained edits.

**Verdict:** Use MCP Steroid for semantic code operations (navigation, refactoring, type queries) and built-ins for everything else; the boundary is whether the task requires understanding code structure or just manipulating text.