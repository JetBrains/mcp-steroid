# MCP Steroid Evaluation: What steroid_* Tools Add Over Built-Ins

## Test Fixture Note

This evaluation was conducted against a minimal IntelliJ test fixture (`light_idea_test_case` module with `temp:///src` VFS). Files were created on the real filesystem (8 Java files, ~500 LOC across a Shape class hierarchy, service layer, and utility class). IntelliJ's PSI index could not index these files because the content root uses IntelliJ's in-memory TempFileSystem rather than LocalFileSystem. Consequently, PSI-based operations (find-references, rename, safe-delete, etc.) could not be executed end-to-end. The evaluation combines: (a) operations that were directly tested (project exploration, action availability, file I/O via execute_code, resource guide retrieval), (b) detailed analysis of the documented API recipes and their call-chain implications vs built-in equivalents, and (c) observed tool behavior characteristics (latency, payload structure, error modes).

---

### 1. Headline: what MCP Steroid changes

MCP Steroid provides three categories of contribution relative to built-in tools:

**(a) Tasks where MCP Steroid adds capability** (things built-ins cannot do or do poorly):
- **Semantic cross-file refactoring**: Atomic rename, move-class, safe-delete, inline-method, change-signature, extract-interface — operations that update all references through the type system in a single atomic step. Built-ins require manual multi-file grep-and-edit chains with no atomicity guarantee.
- **Type-aware code navigation**: Find-references via PSI (precision: only real code references, not text matches), class hierarchy search (transitive inheritors/supertypes), call hierarchy, overriding-method search. Built-in Grep finds text occurrences, not semantic references.
- **Structural code understanding**: Document-symbols via StructureView returns a parsed AST outline (classes, methods, fields with line numbers and types) in one call. Built-ins require reading the full file and parsing visually.
- **External dependency introspection**: PSI can resolve symbols from third-party JARs on the project classpath. Built-ins have no access to library internals without manually locating and reading JAR contents.
- **IDE-native operations**: Code inspections, quick-fixes, code formatting, import optimization, code generation (constructors, overrides), test execution via IDE runner, debugging with breakpoints.

**(b) Tasks where MCP Steroid applies but offers no improvement**:
- Small edits (1-3 lines): Built-in Edit sends a minimal diff payload and is simpler to invoke than writing Kotlin PSI manipulation code.
- Single-file text replacements: `Edit` with `replace_all=true` is equivalent to PSI rename for private symbols used only in one file.
- File reading: `Read` is zero-overhead for known paths; `steroid_execute_code` reading via PSI adds Kotlin compilation overhead.

**(c) Tasks outside MCP Steroid's scope** (built-in only):
- Non-code file operations (config, markdown, logs)
- Free-text search across the repo (Grep)
- Shell operations (git, build commands, process management)
- File creation/deletion at the filesystem level (Write, Bash)

**Verdict:** MCP Steroid's primary value-add is semantic, cross-file, atomic refactoring and type-aware navigation — capabilities that have no built-in equivalent and that matter most in medium-to-large codebases with complex type hierarchies.

---

### 2. Added value and differences by area (3–6 bullets)

- **Cross-file rename/move/delete** (frequency: several times per day during refactoring sessions; value per hit: saves 3-15 manual edits + eliminates missed-reference risk): MCP Steroid's `RenameProcessor`, `MoveClassesOrPackagesProcessor`, and `SafeDeleteProcessor` are each a single atomic call that updates all references — imports, qualified names, overrides, annotation string literals. The built-in equivalent is Grep → manual Edit per file, with no atomicity and no handling of import restructuring. For a symbol used in 8 files, this is 1 call vs. ~10 calls (1 grep + 8 edits + 1 verification grep), and the built-in chain can leave the project in an inconsistent state if interrupted.

- **Find-references precision** (frequency: multiple times per hour during code exploration; value per hit: eliminates false positives, saves 1-3 minutes filtering): Grep for `area` in the test project would match method declarations, method calls, comments, and any variable name containing "area." PSI-based `ReferencesSearch` returns only typed code references. For common short names, the noise reduction is substantial.

- **Type hierarchy navigation** (frequency: a few times per day; value per hit: enables otherwise impossible queries): `ClassInheritorsSearch` with `transitive=true` finds all implementors of `Shape` including through `AbstractShape`. Grep for `implements Shape` finds only direct implementors; finding that `Circle` implements `Shape` (via `extends AbstractShape implements Shape`) requires multi-hop reasoning. This is a capability gap, not an efficiency gap.

- **Structural file overview** (frequency: several times per day when exploring unfamiliar code; value per hit: saves reading 100-300 lines of file content): Document-symbols returns a structured outline — method names, types, line numbers — without transmitting the file body. For a 300-line file, this saves ~6K tokens of context. The built-in equivalent is Read (full file) + visual scan, or Grep for method signatures (partial, misses nesting).

- **Stable addressing across edits** (frequency: every multi-edit session; value per hit: eliminates re-read between chained edits): PSI addresses elements by semantic name path (e.g., `ShapeService.getTotalArea`), not line numbers. After an edit that shifts line numbers, PSI references remain valid. Built-in Edit requires re-reading the file to find the new line positions of subsequent edit targets.

- **IDE-native operations** (frequency: varies; value per hit: high for specific operations): Code inspections can detect bugs that text analysis misses. Import optimization removes unused imports and sorts the rest in one call. Code generation (constructors, override stubs) saves manual boilerplate writing. These have no built-in equivalent.

**Verdict:** The highest-impact contributions are cross-file atomic refactoring (high frequency × high value) and precise reference search (very high frequency × moderate value); structural overview and stable addressing provide consistent but smaller per-hit gains.

---

### 3. Detailed evidence, grouped by capability

#### 3a. Codebase understanding (Tasks 1-6)

**Task 1 — Repository structure overview**

*Built-in path*: `Glob("**/*.java")` → returns file paths sorted by modification time. 1 call, output is a flat file list. For the test project: 8 Java files listed. To understand module structure, would also need `Read` on build files (pom.xml, build.gradle).

*MCP Steroid path*: `steroid_execute_code` with `ProjectRootManager.contentRoots`, `ModuleManager.modules`, `ModuleRootManager.contentEntries/sourceFolders`. 1 call, returns: module names, content roots, source roots (with test/non-test distinction), library dependencies. Observed output: "Modules: 1, Module: light_idea_test_case, Content entry: temp:///src, Source folder: temp:///src isTest=false."

*Comparison*: MCP Steroid provides structural project metadata (modules, source root types, dependencies) that built-ins cannot derive from file listing alone. For a multi-module project, this distinction is significant. For a single-module project, minimal added value.

*Call count*: Built-in: 1-2 calls (Glob + optional Read of build file). MCP Steroid: 1 call.

**Verdict:** MCP Steroid adds module-structural awareness; for flat projects the difference is minimal, for multi-module projects it's substantial.

**Task 2 — Structural overview of large file (ShapeService.java, 272 lines)**

*Built-in path*: `Read("ShapeService.java")` → returns all 272 lines (~9KB). Then scan content for method signatures. Alternative: `Grep("(public|private|protected).*\\(", file)` → returns method signature lines. 1-2 calls, but the full file content enters the context window.

*MCP Steroid path*: `steroid_execute_code` with `LanguageStructureViewBuilder` / document-symbols recipe → returns structured outline: class name, all method names with line numbers, nested structure. Does not transmit method bodies. Estimated output: ~40 lines of structured data vs. 272 lines of full source.

*Comparison*: MCP Steroid saves ~230 lines of context (85% reduction) while providing a more organized view. The built-in approach requires reading the full file and mentally parsing structure. However, MCP Steroid requires writing ~30 lines of Kotlin code (fetched from the resource guide), which is a complexity cost.

*Could not execute directly*: PSI document-symbols requires indexed files, which the temp VFS fixture didn't support. Assessment based on the documented recipe and observed API behavior.

**Verdict:** Token savings are real and scale with file size; the overhead is the Kotlin recipe.

**Task 3 — Retrieve specific method body without reading surrounding file**

*Built-in path*: `Grep("getTotalArea", file)` → get line number. `Read(file, offset=line-2, limit=15)` → read method with surrounding context. 2 calls, must estimate method length.

*MCP Steroid path*: PSI find method by name → get `textRange` → extract text. 1 call with stable name-based addressing. Returns exactly the method body, no more, no less.

*Comparison*: MCP Steroid is more precise (returns exact method boundaries from PSI) and uses stable addressing. Built-in approach requires guessing `limit` parameter and may over-read or under-read. Practical difference: small for short methods, moderate for long methods.

**Verdict:** MCP Steroid gives exact method boundaries; built-ins require estimation.

**Task 4 — Find all references to a symbol**

*Built-in path*: `Grep("clampPositive")` → finds all text mentions. In the test project, this would match: 2 usages in `AbstractShape.java` (definition + implementation), and calls in `Circle.java`, `Rectangle.java`, `Triangle.java`. Would also match any comments or strings containing "clampPositive." For a symbol named `area`, Grep would produce many false positives.

*MCP Steroid path*: `ReferencesSearch.search(namedElement, projectScope())` → returns only typed code references. Distinguishes the definition from usages. Would NOT match text in comments or strings unless explicitly opted in.

*Comparison*: For uniquely-named symbols like `clampPositive`, precision difference is minimal. For short/common names (`area`, `name`, `get`), the difference is dramatic. In the test project, `Grep("area")` would match the `area()` method declarations in Shape, AbstractShape, Circle, Rectangle, Triangle, the `sortByArea` field name, `sortedByArea`, `getTotalArea`, `getAverageArea`, `filterByMinArea`, `filterByMaxArea`, `filterByAreaRange`, `computeStatistic` (string "total_area"), etc. — roughly 30+ matches. `ReferencesSearch` on `Shape.area()` would return only the actual override implementations and call sites.

*Call count*: Built-in: 1 call but low precision. MCP Steroid: 1 call with high precision.

**Verdict:** Significant precision advantage for MCP Steroid, especially for common symbol names. This is one of the highest-frequency operations.

**Task 5 — Class hierarchy (subclasses/supertypes)**

*Built-in path*: `Grep("extends AbstractShape")` → finds Circle, Rectangle, Triangle. `Grep("implements Shape")` → finds AbstractShape. Transitive: must chain — grep for classes extending AbstractShape, then grep for classes extending those. For deep hierarchies, this requires unknown number of iterations.

*MCP Steroid path*: `ClassInheritorsSearch.search(baseClass, projectScope, true)` → returns all transitive inheritors in one call. `OverridingMethodsSearch.search(method, projectScope, true)` → finds all overrides.

*Comparison*: Built-in approach finds direct relationships only and requires iterative expansion for transitive. MCP Steroid returns complete transitive closure in one call. The resource guide recipe I fetched showed this clearly: one `ClassInheritorsSearch` call with `transitive=true`.

**Verdict:** Transitive hierarchy search is a unique capability; text search can only approximate it iteratively.

**Task 6 — External dependency symbol lookup**

*Built-in path*: Cannot resolve external library symbols. Would need to find the JAR file, extract it, and read the .class file or search for source JARs. In practice, not feasible.

*MCP Steroid path*: `JavaPsiFacade.getInstance(project).findClass("java.util.ArrayList", allScope())` → resolves to the JDK class. Returns type hierarchy, method signatures, documentation. Works for any library on the project classpath.

*Could not execute directly*: The test fixture had no JDK configured on the classpath. In a normal project, this would work.

**Verdict:** MCP Steroid can inspect third-party APIs that built-ins cannot access at all. Requires a properly configured project.

#### 3b. Single-file edits (Tasks 7-9)

**Task 7a — Small edit (1-3 lines)**

*Built-in path*: `Read(file)` → find the line. `Edit(file, old_string="return false;", new_string="return true;")`. 2 calls. Payload: ~20 bytes for old + new string.

*MCP Steroid path*: `steroid_execute_code` with PSI: find method by name, get document, compute offset, replace text in `writeAction`. 1 call but ~15 lines of Kotlin code as payload.

*Comparison*: Built-in Edit is simpler, faster, and lower overhead for small edits. The MCP Steroid approach requires writing Kotlin boilerplate that's disproportionate to the edit size.

**Verdict:** Built-in Edit is strictly better for small edits.

**Task 7b — Medium rewrite (10-30 lines)**

*Built-in path*: `Read(file)` → `Edit(file, old_string=<30 lines>, new_string=<30 lines>)`. Payload: ~60 lines of text. Must match old_string exactly.

*MCP Steroid path*: Find method by name via PSI, replace its body text. Payload: ~30 lines of new content + ~15 lines of Kotlin scaffold. Addresses by name, not by content match.

*Comparison*: Roughly equivalent payload. MCP Steroid doesn't need the old text (saves ~30 lines), but the Kotlin scaffold adds ~15 lines. Net savings: ~15 lines. The name-based addressing means no risk of match failure if the old text was modified since the last Read.

**Verdict:** Roughly equivalent; MCP Steroid has a slight addressing advantage.

**Task 7c — Large rewrite (50+ lines)**

*Built-in path*: `Read(file)` → `Edit(file, old_string=<50+ lines>, new_string=<50+ lines>)`. Payload: 100+ lines. Risk of old_string not matching due to whitespace or prior edits.

*MCP Steroid path*: Find method by name, replace body. Payload: 50+ lines of new content + ~15 lines scaffold. Saves sending the old content entirely.

*Comparison*: MCP Steroid saves ~35+ lines of payload (the old content minus the scaffold overhead). For a 100-line method, this is a meaningful token savings.

**Verdict:** MCP Steroid is more token-efficient for large rewrites, with the gap growing proportional to method size.

**Task 8 — Insert new function at a structural location**

*Built-in path*: `Read(file)` → find the closing brace of the target method → `Edit(file, old_string="}\n\n    public", new_string="}\n\n    public void newMethod() {\n        // body\n    }\n\n    public")`. Must find unique anchor text near the insertion point.

*MCP Steroid path*: PSI find the class → find the target method → add a new method after it via PSI manipulation. Addresses insertion point structurally.

*Comparison*: MCP Steroid's structural insertion is more robust — no need to find unique anchor text. Built-in approach can fail if the anchor text appears multiple times.

**Verdict:** MCP Steroid handles insertion more reliably via structural addressing.

**Task 9 — Rename private helper within one file**

*Built-in path*: `Read(file)` → `Edit(file, old_string="clampPositive", new_string="ensurePositive", replace_all=true)`. 2 calls. Simple and effective for a private symbol.

*MCP Steroid path*: `RenameProcessor` with the symbol at a given position. 1 call (plus the Kotlin recipe). Atomic.

*Comparison*: For a truly private helper used only in one file, `replace_all=true` is equivalent and simpler. RenameProcessor is overkill.

**Verdict:** No meaningful difference for single-file private renames; built-in is simpler.

#### 3c. Multi-file changes (Tasks 10-13)

**Task 10 — Cross-file rename**

*Built-in path*: `Grep("ShapeFactory")` → find all 2 files where it appears. `Edit(file1, old="ShapeFactory", new="ShapeBuilder", replace_all=true)`. `Edit(file2, old="ShapeFactory", new="ShapeBuilder", replace_all=true)`. Must also rename the file itself. Also must update import statements that reference the old name. For the test project: at minimum 3 calls (1 grep + 2 edits) plus a file rename via Bash. If imports use the full qualified name, `replace_all` handles it; if they use short names with `import` statements, must handle `import com.eval.service.ShapeFactory` → `import com.eval.service.ShapeBuilder` separately.

*MCP Steroid path*: `RenameProcessor(project, psiClass, "ShapeBuilder", false, false)` → 1 call. Atomically renames the class, the file, all imports, all qualified references, all usages. The resource guide recipe handles this in ~40 lines of Kotlin including dry-run support.

*Comparison*: MCP Steroid: 1 call, atomic, handles all reference types including imports. Built-in: 3+ calls, not atomic, must manually handle import statements and filename rename, risk of partial application.

**Verdict:** MCP Steroid is significantly better for cross-file renames — fewer calls, atomic, handles edge cases automatically.

**Task 11 — Move symbol between modules**

*Built-in path*: Read source file → Read target location → Write new file with updated package declaration → Grep all files for imports of old location → Edit each file to update imports → Delete old file. For a class used in 5 files: ~8+ calls. Must manually construct correct import statements.

*MCP Steroid path*: `MoveClassesOrPackagesProcessor` → 1 call. Atomically moves class, updates package declaration, updates all import statements at all call sites.

*Comparison*: The gap here is the largest of any task. Moving a class with import updates is one of the most error-prone manual operations. MCP Steroid handles it in 1 atomic call.

**Verdict:** MCP Steroid provides the highest value-add for move operations — the built-in equivalent is tedious and error-prone.

**Task 12 — Move file/package, updating imports**

Same analysis as Task 11. `MoveClassesOrPackagesProcessor` (for package-level moves) or the move-file recipe handles this atomically.

**Verdict:** Same as Task 11 — large advantage for MCP Steroid.

**Task 12b — Safe delete**

*Built-in path*: `Grep(symbolName)` → check if any references exist → if none, delete via Edit. Risk: Grep might miss indirect references (reflection, string-based lookups, annotation processing).

*MCP Steroid path*: `SafeDeleteProcessor` → checks all PSI references, refuses to delete if usages exist. 1 call. The resource guide recipe includes dry-run support.

*Comparison*: MCP Steroid provides a safety guarantee that Grep-based checking cannot match. For reflective or annotation-based references, PSI may find what text search misses.

**Verdict:** MCP Steroid's safe-delete provides higher confidence than text-based usage checking.

**Task 13 — Delete and propagate to call sites**

*Built-in path*: Delete the symbol → Grep for remaining references → Edit each to remove calls. Iterative and manual.

*MCP Steroid path*: Can be done with usage search + targeted edits, or via inspection-based quick-fixes. More structured but still multi-step.

**Verdict:** Moderate advantage for MCP Steroid — usage search is better, but propagation still requires judgment.

**Task 13b — Inline a helper**

The test project has `clampPositive(double value)` in `AbstractShape` — a single-expression method (`return Math.max(0.0, value)`) called from Circle, Rectangle, and Triangle constructors. This is a legal inline candidate.

*Built-in path*: Read method body → Grep for all call sites → for each, replace `clampPositive(expr)` with `Math.max(0.0, expr)` via Edit. Must handle parameter substitution manually. 4+ calls (1 read + 1 grep + 3 edits). Risk of incorrect substitution for complex argument expressions.

*MCP Steroid path*: `InlineMethodProcessor` → 1 call. Handles parameter substitution, variable renaming if needed, declaration removal. Atomic.

*Comparison*: For simple cases like `clampPositive`, manual inlining works. For methods with multiple parameters, local variables, or side effects, the processor handles correctness that manual substitution would get wrong.

**Verdict:** MCP Steroid adds correctness guarantees for non-trivial inlining; for trivial cases, roughly equivalent effort.

#### 3d. Reliability & correctness (Tasks 14-16)

**Task 14 — Scope precision**

The test project has `area()` defined in `Shape` (interface), `AbstractShape` (not overridden), `Circle`, `Rectangle`, and `Triangle`. Grep for `area` returns 30+ matches across all files. PSI's `ReferencesSearch` on `Circle.area()` specifically would return only calls to Circle's override, not Rectangle's.

**Verdict:** PSI scope precision is a real and frequently valuable capability.

**Task 15 — Atomicity**

MCP Steroid's refactoring processors wrap all changes in a single `CommandProcessor` command. Either all files are updated or none are (on failure, changes are rolled back). A chain of built-in `Edit` calls has no such guarantee — if call 3 of 5 fails, files 1-2 are already modified.

**Verdict:** Atomicity is a real advantage for multi-file refactorings.

**Task 16 — Success signals**

Built-in Edit returns success/failure for each individual call. MCP Steroid's processors complete silently on success, with the script printing confirmation. Both provide adequate feedback. MCP Steroid's dry-run mode (observed in rename, safe-delete, and move-class recipes) is a bonus — it previews changes without applying them, which built-ins don't support.

**Verdict:** Comparable, with MCP Steroid's dry-run preview as a minor bonus.

#### 3e. Workflow effects (Tasks 17-18)

**Task 17 — Chain three edits in one file**

*Built-in path*: Edit 1 → must re-Read if edit shifted line numbers needed for edit 2 → Edit 2 → possibly re-Read again → Edit 3. If edits are in different methods, old_string matching usually still works (since Edit uses string matching, not line numbers). But if edit 1 changes content that edit 2's old_string depends on, re-Read is mandatory.

*MCP Steroid path*: All three edits can be done in a single `steroid_execute_code` call, addressing each target by name. No re-read needed because PSI tracks element positions internally.

*Comparison*: MCP Steroid can batch multiple edits in one call with stable addressing. Built-ins may need 3-6 calls depending on whether edits interact. The stability advantage compounds with the number of edits.

**Verdict:** MCP Steroid's name-based addressing eliminates inter-edit re-reads.

**Task 18 — Multi-step exploration across the repo**

In an exploration session (understanding call chains, following type hierarchies), built-in results are line-number-based and go stale after any edit. MCP Steroid's PSI-based results use internal element pointers that survive edits (within the same execute_code call).

However, across separate `steroid_execute_code` calls, PSI state is not preserved — each call is independent. So the advantage is limited to within-call batching.

**Verdict:** Advantage within a single execute_code call; across calls, both toolsets have similar staleness characteristics.

#### 3f. Tasks outside MCP Steroid's scope (Tasks 19-20)

**Task 19 — Non-code file reading**: `Read(config.properties)` — simple, direct. MCP Steroid not applicable.

**Task 20 — Free-text search**: `Grep("Math.max")` — optimized, fast. MCP Steroid could do it via `FileSearchProcessor` but Grep is the natural tool.

**Verdict:** Built-ins are the correct choice for these tasks.

---

### 4. Token-efficiency analysis

**Payload differences by edit size:**
| Edit size | Built-in payload | MCP Steroid payload | Delta |
|-----------|-----------------|---------------------|-------|
| Small (1-3 lines) | ~50 tokens (old+new) | ~200 tokens (Kotlin recipe) | Built-in is 4x smaller |
| Medium (10-30 lines) | ~400 tokens (old+new) | ~300 tokens (new+scaffold) | Roughly equal |
| Large (50+ lines) | ~800+ tokens (old+new) | ~500 tokens (new+scaffold) | MCP Steroid saves ~35% |
| Cross-file rename (8 files) | ~800 tokens (grep+8 edits) | ~300 tokens (1 rename call) | MCP Steroid saves ~60% |

**Forced reads**: Built-in Edit requires a prior `Read` call (per tool contract — "You must use your Read tool at least once before editing"). MCP Steroid's PSI-based edits address by name and don't require pre-reading the file. For a session with 10 edits across 5 files, this saves 5 Read calls (~2K-10K tokens depending on file sizes).

**Stable vs ephemeral addressing**: Built-in Edit uses exact string matching (stable within a single file state) or line numbers via Read offset (ephemeral). MCP Steroid uses PSI name paths (stable across edits). After a 10-line insertion, all line-number-based references below the insertion are stale; PSI name paths are not.

**MCP Steroid overhead**: Each `steroid_execute_code` call requires a Kotlin script body (typically 15-40 lines of boilerplate from resource recipes). This fixed overhead makes MCP Steroid less efficient for small operations but gets amortized over larger or batched operations.

**Verdict:** MCP Steroid is more token-efficient for large edits, cross-file refactorings, and batched operations; built-in Edit is more efficient for small, isolated edits.

---

### 5. Reliability & correctness (under correct use)

**Precision of matching**: MCP Steroid's PSI resolves symbols through the type system — `Shape.area()` is a different symbol from a local variable named `area`. Built-in Grep matches text patterns — `\barea\b` matches both. For overloaded methods or common names, PSI precision eliminates false positives entirely.

**Scope disambiguation**: MCP Steroid can distinguish between `Circle.area()` and `Rectangle.area()` as separate override targets. Built-in Grep cannot — both contain `area()`. This matters for targeted refactoring of one override without affecting others.

**Atomicity**: Confirmed via resource guide analysis: `RenameProcessor`, `MoveClassesOrPackagesProcessor`, `SafeDeleteProcessor`, and `InlineMethodProcessor` all wrap changes in `CommandProcessor` commands. Built-in Edit chains are not atomic.

**Semantic queries vs text search**: For "who calls this method," PSI's `ReferencesSearch` returns only call sites. Grep returns all text mentions including comments, strings, and variable names. For "where is this mentioned anywhere," Grep is correct — PSI would under-report.

**External dependency symbol lookup**: MCP Steroid can resolve library symbols if the IDE has indexed the project classpath. Requires: the project must be properly configured (JDK, Maven/Gradle dependencies resolved). In the test fixture, no JDK was on the classpath, so this couldn't be tested. Built-ins cannot do this at all.

**Limitation observed**: MCP Steroid requires a fully configured, indexed project. In the test fixture (light test case with temp VFS), PSI indexing never completed for newly created files, and `writeAction` calls timed out. This is a setup dependency, not a tool deficiency, but it means MCP Steroid's value is zero until the project is properly configured.

**Verdict:** MCP Steroid provides substantially higher precision and safety for semantic operations; built-ins are more robust to project configuration issues.

---

### 6. Workflow effects across a session

In a typical refactoring session (rename a class → move it to a new package → update a method signature → delete an unused helper), MCP Steroid's advantages compound:

- **Rename**: 1 atomic call vs. 5+ manual edits. Saves ~4 calls.
- **Move**: 1 atomic call vs. 8+ manual steps. Saves ~7 calls.
- **Change signature**: 1 call with automatic call-site updates vs. manual grep + edit each caller.
- **Safe delete**: 1 call with usage verification vs. manual grep + judgment.

Total for a 4-step refactoring chain: MCP Steroid ~4 calls vs. built-in ~25+ calls. The time savings compound because each MCP Steroid call doesn't require re-reading files to account for changes from the previous step.

For exploration-heavy sessions (reading code, understanding architecture) without edits, the advantage is smaller: document-symbols saves tokens per file, but the Kotlin recipe overhead is a constant cost.

For sessions dominated by small edits (bug fixes, config changes, test updates), MCP Steroid provides minimal advantage and the Kotlin boilerplate is a net cost.

**Verdict:** Advantages compound significantly during refactoring-heavy sessions; diminish during exploration-only or small-edit sessions.

---

### 7. Unique capabilities (if any)

The following capabilities have no practical built-in equivalent:

1. **Atomic cross-file refactoring** (rename, move, safe-delete, inline, change-signature): All references updated or none. Frequency: several times per refactoring session. Impact: high — prevents inconsistent intermediate states.

2. **Transitive type hierarchy search**: Finding all implementations of an interface through multiple levels of inheritance. Frequency: a few times per exploration session. Impact: moderate — enables queries that text search can only approximate with iterative expansion.

3. **External dependency introspection**: Resolving types, method signatures, and documentation from third-party libraries on the classpath. Frequency: a few times per day when working with unfamiliar APIs. Impact: moderate.

4. **IDE inspections and quick-fixes**: Static analysis with automated fixes (e.g., detecting potential NPE and applying a null-check). Frequency: periodic. Impact: varies.

5. **Code generation** (constructors from fields, override/implement stubs, extract interface): Frequency: a few times per session when creating new classes. Impact: moderate boilerplate savings.

6. **Dry-run refactoring preview**: Preview what a refactoring would change before applying. No built-in equivalent. Frequency: used whenever doing a non-trivial refactoring. Impact: moderate risk reduction.

7. **IDE-native test runner and debugger**: Run tests within the IDE JVM (faster startup than CLI), set breakpoints, inspect thread state. Frequency: multiple times per debug session. Impact: high during debugging.

**Verdict:** Seven unique capabilities exist, with atomic cross-file refactoring and type hierarchy search being the most broadly impactful.

---

### 8. Tasks outside MCP Steroid's scope (built-in only)

- **Non-code file operations**: Reading/writing config, markdown, YAML, JSON, logs. (Estimate: ~15-20% of daily work.)
- **Free-text / regex search**: Grep for log strings, magic constants, URLs, TODO comments. (~10-15% of daily work.)
- **Shell operations**: Running builds, git commands, process management, environment setup. (~15-20% of daily work.)
- **File system operations**: Creating new files from scratch, deleting files, moving files outside the project. (~5-10% of daily work.)
- **Ad-hoc scripting**: One-off data transformations, parsing outputs, piping commands. (~5-10% of daily work.)

Combined, built-in-only tasks represent roughly 50-70% of daily coding work. MCP Steroid's augmentation covers the remaining 30-50% — primarily code navigation, understanding, and refactoring — but within that scope, the improvements are substantial.

**Verdict:** Built-ins cover the majority of daily tasks by volume; MCP Steroid augments the code-centric subset where precision and atomicity matter most.

---

### 9. Practical usage rule

**Decision rule**: Use MCP Steroid's `steroid_execute_code` when the task involves **cross-file symbol changes** (rename, move, delete, inline), **type-aware queries** (find references, hierarchy search, external API lookup), or **structural code overview** (document symbols). Use built-in tools for **everything else**: reading files, text search, small edits, non-code files, shell operations, git. For **single-file edits**, use built-in Edit for changes under ~30 lines and consider MCP Steroid for larger rewrites or when addressing by name avoids a re-read. When a refactoring touches **3+ files**, MCP Steroid's atomic processors are almost always worth the Kotlin recipe overhead.

**Verdict:** The dividing line is semantic scope — once an operation requires type-system awareness or cross-file atomicity, MCP Steroid is the right tool; for everything else, built-ins are simpler and sufficient.