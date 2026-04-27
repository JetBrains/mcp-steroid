# MCP Steroid Tools vs. Built-Ins: Evidence-Based Evaluation

## Test Fixture Context

This evaluation was conducted against a 14-file Java project (~2,000 LOC) created within an IntelliJ light test fixture. A significant constraint: files were written to the project's `basePath` but the module's content root pointed to an absolute `/src` that didn't exist on disk. This meant:
- **Single-file PSI analysis worked** (parsing, method enumeration, type inspection via `LocalFileSystem`).
- **Cross-file semantic features failed** (`ClassInheritorsSearch`, `ReferencesSearch`, `JavaPsiFacade.findClass` for JDK types all returned 0 results because files weren't in project scope).
- **PSI write operations timed out** (`writeAction {}` consistently hung, preventing live testing of rename/move/inline refactorings).

Where a feature couldn't be tested due to fixture limitations, I note the theoretical capability based on the API contract and mark it accordingly.

---

### 1. Headline: what MCP Steroid changes

MCP Steroid provides an **IDE API execution layer** that adds three categories of capability on top of built-ins:

**(a) Tasks where MCP Steroid adds capability:**
- **Semantic structural queries**: PSI-based class/method enumeration returns typed, structured metadata (return types, parameter types, modifiers, hierarchy) without reading file content. One call replaces Read + mental parsing.
- **Symbol-addressed method retrieval**: Fetch a specific method body by class and method name, without knowing its line number or needing to read the surrounding file.
- **Cross-file semantic refactoring** (theoretical, untestable in this fixture): Rename, move, inline, safe-delete as atomic IDE operations. These would replace multi-step Grep + Read + Edit chains.
- **External dependency resolution** (theoretical): Look up signatures of JDK/library classes not present in project source.
- **Compile checking**: Verify edits produce valid code via `ProjectTaskManager.buildAllModules()`.

**(b) Tasks where MCP Steroid applies but offers no improvement over built-ins:**
- Small text edits (1-3 lines) where the target string is unique. Both toolsets require ~1 call.
- Single-occurrence renames within a file. Grep + Edit is as fast.
- Reading file content when you need the full text. `Read` is simpler.

**(c) Tasks outside MCP Steroid's scope (built-in only):**
- Non-code file reading (config, YAML, markdown).
- Free-text/regex pattern search across the repo.
- Git operations, shell commands, file creation.

**Verdict:** MCP Steroid's primary delta is semantic addressing and structured code queries; its secondary delta is atomic multi-file refactoring — but the practical value depends heavily on project indexing working correctly, which was unreliable in this test fixture.

---

### 2. Added value and differences by area (3–6 bullets)

- **Structural code queries (positive, high frequency, high value):** PSI returns method signatures with types, modifiers, and sizes in one call (~800 chars output for a 12-method class). Built-in Read returns the full file (~3,900 chars) requiring the caller to parse structure mentally. For a "what methods does this class have?" question, MCP Steroid saves ~80% of output tokens and removes parsing effort. Frequency: multiple times per exploration session.

- **Symbol-addressed method access (positive, high frequency, medium value):** MCP Steroid targets `cls.methods.find { it.name == "updateUser" }` regardless of line numbers. Built-in Read requires knowing the line range (from prior Read or Grep). When line numbers shift after edits, MCP Steroid's addressing remains stable. Frequency: constant during edit workflows.

- **Multi-file refactoring (positive theoretical, medium frequency, very high value per hit):** IDE rename/move/inline would be 1 atomic call vs. Grep (1) + Read (N) + Edit (N) = 2N+1 calls for N files. The `describeAll` rename touched 2 files and required 5 built-in calls (1 Grep + 2 Read + 2 Edit). IDE rename would be 1 call. At scale (10+ files), the difference compounds. Frequency: several times per feature branch. Could not be verified in this fixture.

- **Disambiguation precision (positive, medium frequency, medium value):** The Edit tool failed on the first attempt for Task 7a because `"Invalid email format: "` appeared twice in the file. MCP Steroid's PSI addressing would target the specific method's body, avoiding ambiguity. Frequency: occurs whenever similar patterns repeat in a file.

- **External dependency lookup (positive theoretical, low-medium frequency, medium value):** Inspecting `java.util.Optional`'s methods or `BigDecimal`'s fields requires JDK source or decompiled classes. MCP Steroid's `JavaPsiFacade.findClass()` would provide this; built-ins cannot. Frequency: when working with unfamiliar library APIs. Not verified in this fixture.

- **Kotlin API overhead (negative, every MCP Steroid call):** Every MCP Steroid call requires writing valid Kotlin code with correct imports, `readAction`/`writeAction` wrappers, and IntelliJ API knowledge. The first file-listing attempt failed due to missing imports. The structural overview took ~15 lines of Kotlin vs. 1 `Glob` call. This overhead is constant and real.

**Verdict:** MCP Steroid's measurable value comes from structured queries and stable addressing; its largest theoretical value is in atomic multi-file refactoring, but the Kotlin boilerplate overhead partially offsets gains for simpler tasks.

---

### 3. Detailed evidence, grouped by capability

#### 3.1 Codebase Overview (Task 1)

**Built-in chain:**
1. `Glob("**/*.java")` → 14 file paths, sorted by modification time. ~200 chars output. 1 call.

**MCP Steroid chain:**
1. `steroid_execute_code` with `ProjectRootManager.contentRoots` + `VfsUtilCore.iterateChildrenRecursively` → returned content roots but 0 files (fixture issue). ~50 chars useful output. 1 call.
2. Retry with `FilenameIndex.getAllFilenames` → found 432 names (including IDE internals). Required filtering. ~300 chars useful output. 1 call.
3. Retry with `FilenameIndex.getAllFilesByExt("java")` → 0 results (scope issue). 1 call.

**Comparison:** Built-in Glob gave a clean, correct answer in 1 call. MCP Steroid required 3 attempts and produced noisy results mixed with IDE internal files. In a properly indexed project, `FilenameIndex` would work in 1 call with O(1) index lookup, but for simple file listing, Glob is already O(fast).

**Verdict:** No advantage for MCP Steroid on file listing. Built-in Glob is simpler and more reliable.

#### 3.2 Structural Overview of a File (Task 2)

**Built-in chain:**
1. `Read("UserService.java")` → full 126-line file, ~3,900 chars. 1 call.
2. Mental parsing to extract structure.

**MCP Steroid chain:**
1. `steroid_execute_code` (PSI query) → structured output: class name, qualified name, package, superclass, interfaces, 2 fields with types, 12 methods with return types + parameter types + sizes. ~800 chars. 1 call.

**Payloads:** Built-in sent 0 chars input (just the path), received ~3,900. MCP Steroid sent ~400 chars of Kotlin, received ~800 chars of structured data.

**Next step:** To read a specific method body, MCP Steroid needs 1 more call addressed by name. Built-in already has the content but must locate the method by scanning.

**Verdict:** MCP Steroid provides a 4.9x reduction in output tokens for structural queries, with typed metadata that built-in Read cannot provide.

#### 3.3 Specific Method Body (Task 3)

**Built-in chain:**
1. If line range is known (from prior Read): `Read(offset=56, limit=22)` → 22 lines, ~600 chars. 1 call.
2. If line range is unknown: `Grep("updateUser")` (1 call) + `Read(offset, limit)` (1 call) = 2 calls.

**MCP Steroid chain:**
1. `steroid_execute_code`: `cls.methods.find { it.name == "updateUser" }!!.body!!.text` → 679 chars method body. 1 call.
   - Also returns: return type (`User`), parameter types (`Long id, String name, String email`), text offset (2957-3316).

**Comparison:** MCP Steroid retrieves the method body by name in 1 call regardless of prior context. Built-in requires either a prior full read or a Grep + targeted Read (2 calls). MCP Steroid also returns type metadata.

**Verdict:** MCP Steroid saves 1 call for method retrieval and addresses by stable name instead of ephemeral line numbers.

#### 3.4 Find All References (Task 4)

**Built-in chain:**
1. `Grep("ValidationHelper")` → 17 lines across 4 files, with line numbers and content. ~1,200 chars. 1 call.
   - Includes: imports (4), class declaration (1), constructor (1), method calls (11).
   - Precision: 100% text match, no false negatives. Includes import statements and the declaration itself, which may or may not be desired.

**MCP Steroid chain:**
1. `steroid_execute_code` with text search across files → found 17 references in 4 files. ~300 chars. 1 call.
   - Note: Without project scope, fell back to `String.indexOf()` — effectively the same as Grep.
2. With proper indexing (theoretical): `ReferencesSearch.search(psiElement)` would return only code usage sites, excluding imports and declarations, with resolved type information.

**Comparison:** In this fixture, identical results. With proper indexing, MCP Steroid would add semantic filtering (code-only usages, excluding imports/declarations/comments).

**Verdict:** For "who calls this?", semantic find-usages would be more precise than Grep. For "where is this mentioned?", Grep is the right tool. In practice, Grep was equally effective here.

#### 3.5 Class Hierarchy (Task 5)

**Built-in chain:**
1. `Grep("implements Shape")` → Circle, Rectangle. 1 call.
2. `Grep("extends BaseEntity")` → User, Product, Address. 1 call.
3. `Grep("implements Entity")` → User, Product, Address. 1 call.
4. For transitive hierarchy: Grep each subclass for further subclasses. N additional calls.
   - Total: 3-4 calls for this codebase. Output clear and precise.

**MCP Steroid chain:**
1. `ClassInheritorsSearch.search(cls, scope, true)` → 0 results (fixture scope issue). 1 call.

**Comparison:** Built-in Grep worked perfectly for Java's explicit `extends`/`implements` syntax. MCP Steroid's transitive search would have been a single call but failed due to indexing. For languages without explicit inheritance keywords (e.g., Go interfaces, duck typing), text search would be unreliable while PSI analysis would still work.

**Verdict:** For Java with explicit keywords, Grep is sufficient and worked here. MCP Steroid's transitive search is theoretically better for deep hierarchies but could not be verified.

#### 3.6 External Dependency Lookup (Task 6)

**Built-in chain:** Not possible. Cannot inspect `java.util.Optional`'s methods or `BigDecimal`'s fields without JDK source on the filesystem.

**MCP Steroid chain:**
1. `JavaPsiFacade.findClass("java.util.Optional", allScope)` → null (JDK not configured in fixture). 0 useful output.

**Comparison:** Neither worked in this fixture. In a production IDE setup with JDK configured, MCP Steroid would return the full class structure. Built-ins would need JDK source files on disk + Read.

**Verdict:** Theoretical unique capability for MCP Steroid, not verifiable.

#### 3.7a Small Edit (Task 7a)

**Built-in chain:**
1. `Read("UserService.java")` → full file. Already done earlier.
2. `Edit(old_string="...Invalid email format...", new_string="...Email format is not valid...")` → **FAILED**: 2 matches found, string not unique.
3. `Edit` with expanded context (5 lines) → succeeded.
   - Total: 3 calls. Input payload for successful Edit: ~220 chars old_string + ~225 chars new_string.

**MCP Steroid chain:**
1. `steroid_execute_code`: Read via PSI (target createUser method) + replace via `file.readText().replace(...)`.
   - Total: 1 call. Input: ~300 chars of Kotlin. Output: confirmation.
   - With PSI write (if it worked): could target by method name, replacing only within that method's text range.

**Comparison:** Built-in Edit failed on the first attempt due to non-unique match. PSI-based editing could have targeted the specific method body, avoiding ambiguity. However, MCP Steroid's file I/O approach (`File.readText().replace()`) has the same ambiguity issue — it's the PSI-addressed body replacement that would solve it.

**Verdict:** For edits in files with repeated patterns, PSI addressing avoids disambiguation issues that require expanded context in Edit.

#### 3.7b Medium Rewrite (Task 7b)

**Built-in chain:**
1. `Read` (already done) or `Read(offset=98, limit=9)` for the method.
2. `Edit(old_string=<full 9-line method>, new_string=<rewritten 20-line method>)` → succeeded.
   - Input payload: ~314 chars old + ~600 chars new = ~914 chars.

**MCP Steroid chain:**
1. `steroid_execute_code`: PSI to get method text range → direct document replacement by offset.
   - Input payload: ~400 chars Kotlin code containing ~600 chars of new method body.
   - Advantage: does not need to send the old body content, just the method name.

**Comparison:** For medium rewrites, MCP Steroid saves ~300 chars by not sending the old method body (uses offset-based replacement). Built-in Edit requires the full old string for matching.

**Verdict:** Modest token savings for MCP Steroid on medium rewrites by avoiding old-body transmission. Practical difference is small.

#### 3.7c Large Rewrite (Task 7c)

No method with 50+ lines existed in the codebase. The largest was `UserService.updateUser` at 22 lines / 679 chars. At 50+ lines (~1,500+ chars), the Edit tool would require sending the full old body, while MCP Steroid's offset-based replacement would only need the new content + method name.

**Verdict:** No suitable candidate. At scale, the token savings from offset-based replacement grow linearly with method size.

#### 3.8 Insert Method at Structural Location (Task 8)

**Built-in chain:**
1. `Read(offset=49, limit=10)` to find the insertion point (after `hasVerifiedEmail`).
2. `Edit(old_string=<anchor lines>, new_string=<anchor + new method>)`.
   - Input: ~130 chars old (anchor: closing brace + @Override) + ~260 chars new. Total: ~390 chars.
   - Requires knowing which lines to anchor against.

**MCP Steroid chain:**
1. `steroid_execute_code`: `cls.addAfter(factory.createMethodFromText(...), afterMethod)`.
   - Input: ~300 chars Kotlin + ~150 chars new method.
   - Addresses insertion point by method name, not anchor text.
   - Timed out in this fixture.

**Comparison:** MCP Steroid addresses the insertion point semantically ("after hasVerifiedEmail") while built-in Edit requires finding specific anchor text. For insertion, both are ~1 call. MCP Steroid's addressing is more robust to nearby changes.

**Verdict:** Similar call count. MCP Steroid's structural addressing is slightly more robust, but built-in Edit works well when anchor text is clear.

#### 3.9 Rename Within One File (Task 9)

**Built-in chain:** `Read` + `Edit(replace_all=true)` = 2 calls for single-file rename.

**MCP Steroid chain:** IDE RenameRefactoring = 1 call (not tested, writeAction timeout).

**Verdict:** Marginal difference for single-file rename. Built-in Edit with `replace_all` is effective.

#### 3.10 Multi-File Rename (Task 10)

**Built-in chain:**
1. `Grep("describeAll")` → 2 files, 2 occurrences. 1 call.
2. `Read("ShapeUtils.java")` + `Read("ShapeService.java")` = 2 calls.
3. `Edit` on each file = 2 calls.
   - **Total: 5 calls.** Non-atomic: if Edit #2 fails, Edit #1 is already applied.

**MCP Steroid chain (theoretical):**
1. IDE `RenameRefactoring` → updates all files atomically. **1 call.**
   - Includes: method definition, all call sites, Javadoc references, string literals (optional).

**Verdict:** For multi-file rename, MCP Steroid would save 4 calls and provide atomicity. This is the largest practical delta for refactoring workflows.

#### 3.11–3.13 Move, Delete, Inline (Tasks 11–13)

These could not be tested due to writeAction timeouts. For completeness:

- **Move class**: Built-in = create new file + copy content + update all imports (Grep + Edit per file). MCP Steroid = 1 `MoveClassRefactoring` call.
- **Safe delete**: Built-in = Grep for usages, verify 0, then delete. MCP Steroid = 1 `SafeDeleteRefactoring` call (refuses if usages exist).
- **Inline**: No suitable single-expression inlinable function in this codebase. "No suitable candidate."
- **Delete with propagation**: Built-in = Grep + manual removal of each call site. MCP Steroid = `SafeDeleteRefactoring` with cascading option.

**Verdict:** Multi-file refactoring is the area where MCP Steroid's theoretical value is highest — each operation is 1 atomic call vs. O(N) built-in calls for N affected files.

#### 3.14 Scope Precision (Task 14)

`Grep("validate")` in this codebase would match:
- Method declarations: `public void validate()` in User, Product, Address
- Method calls: `user.validate()`, `address.validate()`, `product.validate()`
- Field calls: `entities.get(i).validate()`
- Import-like patterns: none, but in larger codebases, comments and strings too

PSI can target specifically `User.validate()` by navigating `cls.methods.find { it.name == "validate" }` on the User class. This is unambiguous.

**Verdict:** MCP Steroid PSI provides exact scope disambiguation. Grep requires manual filtering of results. Impact scales with codebase size and symbol commonality.

#### 3.15 Atomicity (Task 15)

Built-in Edit calls are independent. A 3-file rename applies edits sequentially. If the 3rd fails, the first 2 are already on disk. Recovery requires manual revert or `git checkout`.

MCP Steroid IDE refactorings are atomic by API contract: all changes are applied within a single `WriteCommandAction`, committed or rolled back as a unit.

**Verdict:** MCP Steroid provides transactional guarantees that built-in Edit chains cannot match.

#### 3.16 Success Signals (Task 16)

- **Built-in Edit:** Returns "file updated successfully" or an error message. No semantic validation.
- **MCP Steroid:** Can run `ProjectTaskManager.buildAllModules()` after edits to verify the code compiles. Returns compile errors if any. Also PSI re-parsing verifies structural validity.

**Verdict:** MCP Steroid can verify semantic correctness post-edit; built-ins only confirm the text substitution succeeded.

#### 3.17 Chained Edits (Task 17)

**Built-in:** After Edit #1, string matching for Edit #2 still works as long as the target string is unique (Edit uses string content, not line numbers). No re-read needed between edits if the agent remembers the file structure. However, if Edit #1 changes text near Edit #2's target, the old_string context may no longer match.

**MCP Steroid:** PSI tree updates after each modification. Method names remain stable. Subsequent edits use name-based addressing that doesn't shift. However, each edit is a separate `steroid_execute_code` call requiring Kotlin code.

**Verdict:** Both handle chained edits well. Built-in Edit's string matching is stable across nearby edits. MCP Steroid's name-based addressing is more robust but each call has higher overhead.

#### 3.18 Multi-Step Exploration (Task 18)

**Built-in:** Grep results include line numbers that go stale after edits. But for exploration (read-only), they remain valid. Re-running Grep is cheap.

**MCP Steroid:** PSI-based exploration uses the parsed tree. Symbol lookups remain valid across edits because they're name-based. However, VFS must be refreshed after file I/O changes.

**Verdict:** Comparable. Grep results are stable for read-only exploration. PSI is more robust across edit cycles.

#### 3.19 Non-Code Files (Task 19)

Built-in `Read` is the right tool. MCP Steroid adds nothing for config.yaml, config.properties, README.md.

**Verdict:** Not applicable — outside MCP Steroid's scope.

#### 3.20 Free-Text Search (Task 20)

Built-in `Grep("cannot be empty")` → 9 results across 4 files, with file names and line numbers. 1 call.

MCP Steroid has no equivalent — would fall back to the same text search via Kotlin code, with more overhead.

**Verdict:** Not applicable — Grep is the right tool, MCP Steroid adds nothing.

---

### 4. Token-efficiency analysis

| Task Type | Built-in Input | Built-in Output | MCP Steroid Input | MCP Steroid Output |
|-----------|---------------|----------------|-------------------|-------------------|
| File listing | ~20 chars (glob pattern) | ~400 chars (14 paths) | ~400 chars (Kotlin) | ~300 chars |
| Structural overview | ~60 chars (path) | ~3,900 chars (full file) | ~400 chars (Kotlin) | ~800 chars |
| Method body | ~80 chars (path+offset) | ~600 chars (22 lines) | ~300 chars (Kotlin) | ~700 chars (body+types) |
| Find references | ~40 chars (pattern) | ~1,200 chars (17 matches) | ~600 chars (Kotlin) | ~300 chars |
| Small edit | ~350 chars (expanded context) | ~50 chars (confirmation) | ~300 chars (Kotlin) | ~100 chars |
| Medium rewrite | ~914 chars (old+new body) | ~50 chars | ~1,000 chars (Kotlin+new) | ~100 chars |
| Multi-file rename | ~200 chars (5 calls total) | ~300 chars | ~200 chars (1 call) | ~100 chars |

**Key patterns:**
- **Forced reads:** Built-in Edit requires a prior Read of each file. MCP Steroid can address by name without reading the full file first. For N files, this saves N Read calls.
- **Stable vs. ephemeral addressing:** MCP Steroid uses method names (stable). Built-in uses string content (stable within edits) or line numbers (ephemeral). Edit's string-based matching is surprisingly robust — it doesn't depend on line numbers.
- **Kotlin boilerplate overhead:** Every MCP Steroid call includes ~100-200 chars of boilerplate (imports, readAction wrappers). This partially offsets output savings.
- **Structural overview is the biggest win:** 3,900 chars (Read) vs. 800 chars (PSI) — a 4.9x reduction in output for the same information density.

**Verdict:** MCP Steroid reduces output tokens for structural queries by ~4-5x and eliminates prerequisite reads for targeted method access; the Kotlin input overhead partially offsets gains for simple operations.

---

### 5. Reliability & correctness (under correct use)

**Precision of matching:**
- Built-in Edit: String-based. Failed when the target string appeared twice in one file (Task 7a). Required adding 4 lines of context to disambiguate. This is a real correctness risk at scale.
- MCP Steroid PSI: Name-based. `cls.methods.find { it.name == "createUser" }` is unambiguous regardless of similar patterns elsewhere. No over-matching.

**Scope disambiguation:**
- Built-in Grep: `Grep("validate")` matches all occurrences across the codebase, including method declarations, calls, and string literals. Filtering requires human judgment.
- MCP Steroid: PSI scoping can target `com.example.model.User.validate()` specifically.

**Atomicity:**
- Built-in: Non-atomic. Each Edit is independent. Partial failure leaves the codebase in an inconsistent state.
- MCP Steroid: IDE refactorings are atomic (transactional write commands).

**Semantic queries vs. text search:**
- Grep `"implements Entity"` works for Java's explicit syntax. Would fail for languages with structural typing or implicit interfaces.
- PSI `ClassInheritorsSearch` is language-aware. (Not verified in this fixture.)

**External dependency lookup:**
- Built-in: Cannot resolve. No access to compiled .class files or library source.
- MCP Steroid: Requires proper JDK/library configuration. When configured, provides full resolution. Not verified here.

**Reliability in this fixture:**
- Built-in tools: 100% success rate across all tasks attempted.
- MCP Steroid: PSI read operations succeeded. All write operations (`writeAction`) timed out. Cross-file queries returned 0 results due to scope misconfiguration. Effective success rate for advanced features: ~30%.

**Verdict:** Under correct use with proper project setup, MCP Steroid offers superior precision and atomicity; in this test fixture, built-in tools were more reliable due to indexing and write-action limitations.

---

### 6. Workflow effects across a session

**Multi-step exploration:** Both toolsets performed well for read-only exploration. Grep results remained valid across the session. PSI queries were stable but each required writing Kotlin boilerplate.

**Edit chaining:** Built-in Edit's string matching was stable across consecutive edits — editing method A didn't invalidate the string context for method B. MCP Steroid's PSI addressing would be equally stable (by name), but couldn't be tested due to write timeouts.

**Compounding advantages:** MCP Steroid's structural overview → method body → edit chain would theoretically save reads at each step (no need to re-read the file). In practice, built-in Read caches file content in conversation context, so the re-read cost is mainly token budget, not latency.

**Compounding disadvantages:** Each MCP Steroid call requires ~5-15 lines of Kotlin. Over 10+ calls, this accumulates significant input tokens. If a call fails (wrong API, threading issue), the retry cost is high because the full Kotlin snippet must be resent with corrections.

**Verdict:** MCP Steroid's advantages do not strongly compound across a session — the Kotlin boilerplate tax is constant per call, while built-in tools' simplicity remains constant.

---

### 7. Unique capabilities (if any)

1. **Semantic structural overview** (verified): Returns typed method signatures, field types, and class relationships in structured form. Built-in Read returns raw text requiring mental parsing. *Frequency: high (multiple times per exploration). Impact: medium (saves ~3,000 output tokens per file query).*

2. **Compile verification** (not tested but API available): `ProjectTaskManager.buildAllModules()` verifies edits produce compilable code. Built-ins have no equivalent — they confirm text substitution, not semantic validity. *Frequency: after every significant edit. Impact: high (catches type errors, missing imports).*

3. **Atomic multi-file refactoring** (theoretical, not verified): Single-call rename/move/inline/safe-delete across all affected files. Built-ins require O(N) calls with no atomicity. *Frequency: several times per feature branch. Impact: very high for renames touching 10+ files.*

4. **External dependency introspection** (theoretical, not verified): Query methods/fields of JDK and library classes not in project source. *Frequency: medium. Impact: medium.*

5. **PSI-based method body replacement by name** (partially verified — read worked, write timed out): Address edit targets by class + method name rather than string content or line numbers. *Frequency: every edit. Impact: medium (avoids disambiguation, survives edits to neighboring code).*

**Verdict:** Semantic structural overview is the one unique capability fully verified in this evaluation; atomic refactoring and compile verification are high-value capabilities that could not be confirmed.

---

### 8. Tasks outside MCP Steroid's scope (built-in only)

- **Non-code file reading** (config, YAML, markdown, JSON): `Read` tool. ~5-10% of daily work.
- **Free-text/regex search across repo**: `Grep` tool. ~15-20% of daily work.
- **File creation and directory management**: `Write`, `Bash`. ~5% of daily work.
- **Git operations** (status, diff, commit, branch): `Bash`. ~10-15% of daily work.
- **Shell command execution** (build tools, package managers, linters): `Bash`. ~10% of daily work.
- **Simple file reads** where full content is needed: `Read`. ~10% of daily work.

**Estimated share of daily work outside MCP Steroid's scope: ~55-65%.** MCP Steroid targets the remaining ~35-45% — the code understanding, navigation, and refactoring portion.

**Verdict:** MCP Steroid augments roughly a third to half of coding work; the majority of daily tasks (file management, search, git, shell) remain built-in territory.

---

### 9. Practical usage rule

**Use MCP Steroid when:**
- You need to understand a file's structure without reading it entirely (what classes, methods, fields, types?).
- You need to retrieve a specific method body and know the class/method name but not the line range.
- You're performing a multi-file refactoring (rename, move) and want atomicity + completeness.
- You need to verify code compiles after edits.
- You need to inspect external dependency APIs.

**Use built-ins when:**
- You need to search for text patterns across the repo (Grep).
- You need to read non-code files or small code files in full (Read).
- You're making a small, clearly-scoped text edit where the target string is unique (Edit).
- You need to create files, run commands, or interact with git (Bash/Write).
- The project isn't properly indexed in the IDE (built-ins always work).

**Decision heuristic:** If the task is "understand or navigate code structure," prefer MCP Steroid. If the task is "find text or make a targeted edit," prefer built-ins. If the task is "refactor across files," prefer MCP Steroid if available and indexed.

**Verdict:** Use MCP Steroid for semantic code understanding and multi-file refactoring; use built-ins for everything else and as the reliable fallback when IDE indexing is incomplete.