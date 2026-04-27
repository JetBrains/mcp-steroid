# MCP Steroid Evaluation: What steroid_* Tools Add on Top of Built-Ins

**Test fixture:** `SerenaSelfEvalPrompt` — a TempFileSystem-based IntelliJ light test project with an empty `/src` content root, no JDK indexed, and no functioning write thread (EDT). This severely constrains what can be demonstrated hands-on. Cross-file refactoring, write operations, build/test running, and external dependency resolution could not be executed. Where a task could not be performed, I report the available API surface from recipe guides and contrast the theoretical workflow.

---

### 1. Headline: what MCP Steroid changes

MCP Steroid provides an IntelliJ IDE runtime bridge that exposes PSI (Program Structure Interface) semantic analysis and IDE-grade refactoring processors to the AI agent. The delta falls into three categories:

**(a) Tasks where MCP Steroid adds capability:**
- **Semantic code navigation**: Document symbols, method body extraction by name path, type hierarchy queries, and find-references via the type system — all return structured, typed data that built-ins can only approximate via text matching.
- **Atomic cross-file refactoring**: Rename, move class/package, safe-delete, inline, change-signature — each executed as a single atomic operation via IntelliJ's refactoring processors. Built-ins require multi-step Grep→Edit chains with no atomicity.
- **External dependency introspection**: Resolving definitions and signatures from JDK or third-party libraries (when SDK is properly configured).
- **IDE services**: Build/compile checking, test running, debugging, inspections, code generation — all without leaving the agent workflow.

**(b) Tasks where MCP Steroid applies but offers no improvement:**
- **Small, targeted edits** (1–3 lines) where the file is already loaded and the target line is known: the Edit tool sends a minimal diff; MCP Steroid requires writing Kotlin PSI-manipulation code, which is heavier.
- **Single-file text search** for a known pattern: Grep is direct and efficient; MCP Steroid's `FilenameIndex` and PSI traversal are more verbose for the same result.

**(c) Tasks outside MCP Steroid's scope:**
- Non-code files (config, docs, changelogs) — use Read.
- Free-text pattern search across the repo — use Grep.
- Git operations, shell commands, file creation in arbitrary directories — use Bash.
- Ad-hoc text replacements not tied to code symbols — use Edit.

**Verdict:** MCP Steroid adds a semantic analysis and refactoring layer that built-ins fundamentally lack; the delta is largest for cross-file refactoring, structural navigation, and type-aware queries, and smallest (or negative in ergonomics) for simple text edits and non-code tasks.

---

### 2. Added value and differences by area (3–6 bullets)

- **Structural code overview (positive, high frequency, moderate value per hit):** A single `steroid_execute_code` call with PSI APIs returns a full class skeleton — fields with resolved types, methods with signatures, parameter types, line counts, and text offsets — for a 111-line file in one round trip. Built-ins require Read (full file, ~2800 chars) + manual parsing or Grep (one pattern at a time). MCP Steroid's output is pre-structured: 18 methods with signatures, 2 fields with types, in a machine-readable format. Frequency: every time you open a new file. Value: saves 1–2 follow-up calls and eliminates manual text parsing.

- **Name-path addressing for edits and navigation (positive, high frequency, high value per hit):** MCP Steroid addresses code elements by semantic name (e.g., `Triangle.isRightTriangle`) rather than line numbers. After any edit, line numbers shift; name paths remain stable. This eliminates forced re-Reads between chained edits. Frequency: every multi-step editing session. Value: removes 1 re-Read per edit in a chain.

- **Atomic cross-file refactoring (positive, moderate frequency, very high value per hit):** Rename, move-class, safe-delete, inline, and change-signature are each a single atomic operation via `RenameProcessor`, `MoveClassesOrPackagesProcessor`, `SafeDeleteProcessor`, `InlineMethodProcessor`. Built-in equivalent for a rename used in N files: 1 Grep + N Edit calls, with risk of partial application. Frequency: several times per feature-branch. Value: eliminates N-1 tool calls and guarantees consistency.

- **Type hierarchy and find-references (positive, moderate frequency, moderate value per hit):** PSI-backed hierarchy search finds all implementations of an interface or all overrides of a method through the type system — not text matching. Built-ins require Grep for `implements ClassName` or method name patterns, producing false positives (string literals, comments, unrelated methods with the same name). Frequency: several times per investigation session. Value: precision improvement from ~80% to ~100% for non-trivial symbols.

- **External dependency introspection (positive, low-moderate frequency, high value per hit):** When SDK is configured, MCP Steroid can resolve `java.util.List` to its full definition and list its methods. Built-ins have no access to library source/class files unless they happen to be on disk in a known location. Frequency: when investigating API behavior. Value: eliminates web-search round trips. *Note: In this test fixture, JDK was not indexed, so this could not be verified hands-on.*

- **Invocation overhead (negative trade-off, constant):** Every MCP Steroid call requires writing Kotlin code against IntelliJ's API, typically 10–30 lines per operation. Built-in calls require 1–3 parameters. This is a constant tax on ergonomics. For simple tasks, this tax can exceed the time saved.

**Verdict:** The largest value-adds are atomic cross-file refactoring and stable name-path addressing; the structural overview advantage compounds across investigation sessions; the Kotlin-code invocation overhead is a constant but non-trivial ergonomic cost.

---

### 3. Detailed evidence, grouped by capability

#### Task 1: Repository structure overview

**MCP Steroid path:** `steroid_execute_code` with `FilenameIndex.getAllFilesByExt()` — returns all files matching an extension from the pre-built index. O(1) lookup per extension. In this fixture: returned 0 files (empty project). In a real project, returns paths grouped by extension in a single call.

**Built-in path:** `Glob("**/*.java")` + `Glob("**/*.xml")` etc. — file-system scan, also returns paths. Possibly slower on large repos but functionally equivalent for this task.

**Observation:** For this task, the difference is minimal. Both return file paths. Glob works without writing Kotlin code.

**Verdict:** No meaningful difference for project structure overview.

#### Task 2: Structural overview of a large file (ShapeCalculator.java, 111 lines)

**MCP Steroid path (1 call):** `steroid_execute_code` with PSI traversal — returned:
- Class `com.eval.model.ShapeCalculator` with qualified name
- 2 fields: `private final List<Shape> shapes`, `private double totalAreaCache` — with types resolved
- 18 methods: each with visibility, return type, parameter types + names, body line count, text offset
- Total output: ~1200 chars of structured data

**Built-in path (1–2 calls):** `Read("ShapeCalculator.java")` returns 2806 chars of raw source. A follow-up Grep for method signatures (`"public.*\\("`) could extract names but not return types or parameter types in structured form.

**Comparison:**
| Axis | MCP Steroid | Built-ins |
|------|------------|-----------|
| Calls | 1 | 1 (Read) + optional Grep |
| Input payload | ~800 chars Kotlin code | ~50 chars (file path) |
| Output payload | ~1200 chars structured | ~2800 chars raw source |
| Prerequisite reads | None | None |
| Follow-up to find a method | Name lookup (0 cost) | Scan output for line number |

The MCP Steroid output is ~2.3x smaller and pre-parsed. The built-in output requires the agent to parse Java mentally.

**Verdict:** MCP Steroid returns a richer, more compact structural overview. The advantage is moderate — the agent can parse raw Java, but structured output reduces ambiguity.

#### Task 3: Method body extraction without reading surrounding file

**MCP Steroid path (1 call):** `cls.findMethodsByName("isRightTriangle", false).first()` → returned body text (5 lines), return type `boolean`, parameter list `[]`, offset range `975..1186`. Addressed by name, not line number.

**Built-in path (2 calls):** `Grep("isRightTriangle", file)` → find line number. `Read(file, offset=line, limit=10)` → extract body. Requires knowing approximate body length or over-reading.

**Comparison:**
| Axis | MCP Steroid | Built-ins |
|------|------------|-----------|
| Calls | 1 | 2 (Grep + Read) |
| Addressing | By name (stable) | By line number (ephemeral) |
| Output precision | Exact method body | Must estimate read range |
| Type info included | Yes (return type, params) | No |

**Verdict:** MCP Steroid saves one call and provides exact, type-annotated extraction. Advantage is real but modest for a single extraction.

#### Task 4: Find all references to a symbol

**MCP Steroid path:** `ReferencesSearch.search(psiElement).findAll()` — returns all PSI references through the type system. Handles overrides, interface implementations, qualified references, imports. Not testable in this fixture (no indexed cross-file content).

**Built-in path:** `Grep("\\bsymbolName\\b")` — returns all text matches. Includes false positives (comments, string literals, unrelated symbols with same name).

**Theoretical comparison for a method named `area()` used in 5 files:**
| Axis | MCP Steroid | Built-ins |
|------|------------|-----------|
| Calls | 1 | 1 |
| Precision | 100% (type-resolved) | ~70-90% (text match over-matches) |
| Recall | 100% (follows overrides) | ~90% (misses renamed imports) |
| Output | File + line + element type | File + line + raw text |

**Verdict:** MCP Steroid provides higher precision and recall for code-usage queries; built-ins suffice when the symbol name is unique enough.

#### Task 5: Type hierarchy (subclasses/supertypes)

**MCP Steroid path:** PSI `cls.implementsListTypes`, `cls.interfaces`, `cls.superClass`. For indexed projects: `ClassInheritorsSearch.search()` finds all transitive implementors. Tested on in-memory files: successfully extracted that `Circle implements Shape` and `Rectangle implements Shape` with correct interface lists.

**Built-in path:** `Grep("implements Shape")` finds direct implementors. Transitive hierarchy requires chaining: grep for each discovered class, recursively.

**Comparison:** For a 3-level hierarchy with 10 classes, MCP Steroid: 1 call. Built-ins: up to 10 Grep calls following the chain.

**Verdict:** Hierarchy queries are a clear MCP Steroid advantage; the difference grows with hierarchy depth.

#### Task 6: External dependency symbol lookup

**MCP Steroid path:** `JavaPsiFacade.getInstance(project).findClass("java.util.List", allScope())` — tested, returned NOT FOUND because the test fixture has no JDK configured. In a properly configured project, this would return the full class with all method signatures.

**Built-in path:** No built-in equivalent. The agent would need to use web search or prior knowledge.

**Verdict:** MCP Steroid can resolve library symbols when SDK is configured — a capability built-ins entirely lack. Not verifiable in this fixture.

#### Tasks 7a–7c: Single-file edits (small, medium, large)

**Could not be executed** — writeAction hangs in this test fixture (no EDT/write thread running). Theoretical analysis:

**MCP Steroid path (per the skill guide):** Write Kotlin PSI-manipulation code or use document-level `replaceString`. For a 3-line edit: ~15–25 lines of Kotlin to locate the method, get its body, replace text. For a method body rewrite: similar structure but with larger replacement text.

**Built-in path:** `Edit(file, old_string, new_string)` — sends only the diff. For a 3-line change: old_string (~100 chars) + new_string (~100 chars). For a 30-line rewrite: ~1000 chars each. For a 50+ line rewrite: ~2000 chars each.

**Theoretical comparison (7a - small edit, change error message):**
| Axis | MCP Steroid | Built-ins |
|------|------------|-----------|
| Calls | 1 | 1 (assumes file already read) |
| Input payload | ~500 chars Kotlin code | ~200 chars (old+new) |
| Prerequisite | Know method name | Know exact old_string (requires prior Read) |
| Addressing | By name path (stable) | By text match (ephemeral if not unique) |

For small edits, Edit's payload is smaller. For medium/large rewrites, the payloads converge. MCP Steroid's advantage is that it doesn't require a prior Read to know the exact old_string, since it addresses by name. But the Kotlin code overhead is real.

**Verdict:** For small edits, built-in Edit is more ergonomic. For medium-large rewrites, the payloads converge; MCP Steroid's name-path addressing avoids re-reads but incurs Kotlin-code overhead.

#### Task 8: Insert new method at a specific structural location

**Not executable in this fixture.** Theoretically, MCP Steroid can use PSI to find a method by name and insert after it via `addAfter`. Built-ins require knowing the line number or using a unique surrounding-text anchor for Edit.

**Verdict:** MCP Steroid's structural insertion is more precise; built-in insertion works but requires careful anchor text selection.

#### Task 9: Rename private helper within one file

**Not executable.** MCP Steroid: `RenameProcessor` handles all usages atomically. Built-ins: `Edit(file, old_name, new_name, replace_all=true)` — but risks renaming occurrences in strings/comments and can't distinguish a local variable `count` from a method `count`.

**Verdict:** Within one file, semantic rename is safer for common names; text-based rename suffices for unique names.

#### Tasks 10–13: Cross-file rename, move, safe-delete, inline

**None executable in this fixture.** Based on reviewed recipes:

- **Rename** (`RenameProcessor`): 1 call, atomic, updates imports/qualified refs/overrides. Built-in: Grep + N Edits, non-atomic.
- **Move class** (`MoveClassesOrPackagesProcessor`): 1 call, updates all import statements. Built-in: manual file move + Grep for old import + Edit each file.
- **Safe delete** (`SafeDeleteProcessor`): 1 call, checks usages first, refuses if usages exist. Built-in: Grep for usages, manually verify, then delete.
- **Inline** (`InlineMethodProcessor`): 1 call, substitutes body at all call sites. Built-in: Read method body, find each call site, Edit each one — error-prone for non-trivial methods.

**Verdict:** Cross-file refactoring is MCP Steroid's strongest differentiator. Each operation saves N tool calls and provides atomicity guarantees.

#### Tasks 14–16: Scope precision, atomicity, success signals

**14 - Scope precision:** Demonstrated. PSI addresses `Triangle.isRightTriangle` unambiguously. Grep for `isRightTriangle` would match comments, strings, and any other class with the same method name. In the tested in-memory file, PSI returned exactly the method body with type information — no over-matching.

**15 - Atomicity:** Not directly testable (writes blocked), but the refactoring processors (RenameProcessor, MoveClassesOrPackagesProcessor, etc.) are documented to wrap all changes in a single CommandProcessor command. Built-in Edit chains have no atomicity — a failure mid-chain leaves partial updates.

**16 - Success signals:** `steroid_execute_code` returns execution output with `execution_id`. Refactoring processors print confirmation (e.g., "Renamed: old -> new (N usages)"). Built-in Edit returns the edited file content. Both provide confirmation, but MCP Steroid's is semantic ("5 usages updated") vs. textual ("file saved").

**Verdict:** MCP Steroid provides precise scope targeting and atomic operations; built-ins offer textual confirmation but no atomicity.

#### Tasks 17–18: Chained edits and multi-step exploration

**17 - Chained edits:** Not directly testable. Theoretically: MCP Steroid addresses by name path, so three edits in one file require no re-reads between them (names don't shift). Built-ins: after each Edit, line numbers shift, requiring a re-Read before the next Edit to find the new line numbers — 3 edits become 3 Edits + 2 re-Reads = 5 calls vs. 3 calls.

**18 - Multi-step exploration:** MCP Steroid's PSI-backed results (class structure, method bodies) remain valid across edits in other files. Grep results (line numbers) go stale after any edit. In an investigation spanning 5 files, MCP Steroid results from file A remain usable while editing file B; Grep results from file A would need reverification.

**Verdict:** Name-path stability provides compounding value across multi-step workflows; built-ins accumulate re-read overhead.

#### Tasks 19–20: Non-code files and free-text search

**19 - Non-code files:** MCP Steroid's PSI tools don't apply. Read is the correct tool.

**20 - Free-text search:** Grep is the correct tool. MCP Steroid's `FilenameIndex` and PSI search are designed for code symbols, not arbitrary text patterns.

**Verdict:** Built-in only; outside MCP Steroid's scope.

---

### 4. Token-efficiency analysis

**Payload differences by edit size:**
- **Small edit (1–3 lines):** Edit sends ~200 tokens (old+new strings). MCP Steroid sends ~300–500 tokens (Kotlin code). Built-in is 1.5–2.5x more efficient.
- **Medium edit (10–30 lines):** Edit sends ~600–1500 tokens. MCP Steroid sends ~500–800 tokens (Kotlin code with replacement text). Roughly equivalent.
- **Large edit (50+ lines):** Edit sends ~2000+ tokens (must include full old_string). MCP Steroid can address by method name and replace the body — ~1500 tokens. MCP Steroid is slightly more efficient.

**Forced reads:**
- Built-ins: Edit requires a prior Read to obtain the exact old_string. For a 3-edit session: 3 Reads + 3 Edits = ~6 calls.
- MCP Steroid: No Read needed — addresses by name. 3 calls total. Saves ~3 Read payloads.

**Stable vs. ephemeral addressing:**
- Built-in line numbers shift after every edit. Over a 5-edit session, this means ~4 extra Read calls to refresh positions — approximately 4 × (file_size/2) tokens of overhead.
- MCP Steroid name paths are stable. Zero refresh overhead.

**Structural overview:**
- Built-in: Read returns full file (N tokens). Grep returns matching lines.
- MCP Steroid: Returns structured summary (~40% of file size in tokens). Savings: ~60% of file size.

**Verdict:** MCP Steroid is more token-efficient for structural queries and multi-edit sessions (due to stable addressing); built-in Edit is more efficient for isolated small edits.

---

### 5. Reliability & correctness (under correct use)

**Precision of matching:** PSI resolves symbols through the type system — `Shape.area()` is distinguished from `MathHelper.area()` even though both are named `area`. Grep matches text, producing false positives for common names. Observed: PSI correctly extracted `Triangle.isRightTriangle` without matching comments or strings.

**Scope disambiguation:** PSI handles overloaded methods (`Rectangle(double, double)` vs a hypothetical `Rectangle(int, int)`), inner classes, and shadowed variables. Built-in text search cannot distinguish overloads.

**Atomicity:** Refactoring processors are atomic (all-or-nothing). Edit chains are not. A network failure or timeout mid-chain leaves inconsistent state with built-ins.

**Semantic queries vs. text search:** For "who calls `addShape`?", PSI's `ReferencesSearch` follows the type system. Grep for `addShape` matches string literals, comments, and unrelated methods. The precision gap grows with codebase size and naming ambiguity.

**External dependency lookup:** Requires JDK/SDK configured in the project. In this fixture, `java.util.List` was NOT FOUND — the light test project lacks SDK configuration. In a real project with SDK, this capability has no built-in equivalent. Setup dependency is real and non-trivial.

**Limitations observed:**
- Write operations hang in light test fixtures (no EDT)
- In-memory PSI files are isolated — cross-file resolution requires VFS-indexed files
- Type resolution for method call chains failed (`Stream.filter().map()` returned "unresolved") even for simple chains, because the JDK wasn't indexed
- The Kotlin API has a learning curve; incorrect threading (`readAction`/`writeAction`) produces runtime errors

**Verdict:** MCP Steroid offers higher precision and atomicity guarantees under correct use; its reliability depends on proper project configuration (SDK, indexing), which is a non-trivial prerequisite that built-ins don't require.

---

### 6. Workflow effects across a session

**Multi-edit sessions:** The name-path stability advantage compounds. In a hypothetical 5-edit session modifying methods across 3 files:
- Built-ins: ~5 Edits + ~4 re-Reads + ~3 Greps (to find targets) = 12 tool calls
- MCP Steroid: ~5 execute_code calls (each self-contained) = 5 tool calls

The 7-call saving is real but each MCP Steroid call carries higher input payload (Kotlin code).

**Investigation → edit → verify cycle:** MCP Steroid's structural overview from step 1 (method list with types) remains valid after edits in step 2, because it's name-addressed. Built-in Read output from step 1 has stale line numbers after step 2.

**Cross-file refactoring chains:** A rename-then-move sequence is 2 MCP Steroid calls. With built-ins: 1 Grep + N Edits (rename) + 1 Bash (move file) + 1 Grep + M Edits (update imports) = 3 + N + M calls. For a symbol used in 8 files: 2 vs. ~19 calls.

**Diminishing returns:** For purely investigative sessions (no edits), the advantage is modest — Read + Grep are sufficient for most exploration. The compounding effect is strongest in edit-heavy sessions.

**Negative workflow effect:** Each MCP Steroid call requires composing Kotlin code, which adds latency and error risk. A failed script (wrong API call, threading violation) requires debugging and retry, potentially costing more than the saved calls.

**Verdict:** MCP Steroid advantages compound across edit-heavy, multi-file sessions; the benefit is modest for read-only investigation and is partially offset by Kotlin-code composition overhead.

---

### 7. Unique capabilities (if any)

Capabilities with no practical built-in equivalent:

1. **Atomic cross-file refactoring** (rename, move, safe-delete, inline, change-signature): Built-ins can approximate these with multi-call chains, but cannot guarantee atomicity. Frequency: several times per feature branch. Impact: eliminates partial-update risk and saves O(N) calls per refactoring where N = number of affected files.

2. **Type hierarchy traversal** (`ClassInheritorsSearch`, `MethodOverrideSearch`): Returns all implementors/overrides through the type system. Built-in Grep for `implements X` misses transitive implementors and inner classes. Frequency: a few times per investigation session. Impact: complete recall vs. partial.

3. **External dependency introspection**: Resolving library class definitions and method signatures. No built-in equivalent exists — the agent would need web search. Frequency: occasional. Impact: eliminates context-switching to documentation.

4. **IDE services** (build, test, debug, inspections): Running tests via IDE runner (~31s faster than CLI per the docs), setting breakpoints, running inspections — none have built-in equivalents. Frequency: high during development. Impact: significant time savings. *Not testable in this fixture.*

5. **Scope-precise symbol addressing**: Distinguishing overloads, shadowed variables, same-named methods in different classes. Grep cannot do this. Frequency: moderate. Impact: eliminates false-positive edits.

**Verdict:** Atomic refactoring, type hierarchy traversal, external dependency introspection, IDE services, and scope-precise addressing are genuine unique capabilities with no practical built-in equivalent.

---

### 8. Tasks outside MCP Steroid's scope (built-in only)

- **Reading non-code files** (config, YAML, Markdown, changelogs): Read tool. ~15% of daily work.
- **Free-text search** (log strings, URLs, magic constants): Grep tool. ~10% of daily work.
- **Git operations** (status, diff, commit, branch): Bash tool. ~10% of daily work.
- **Shell commands** (install deps, run scripts, curl): Bash tool. ~10% of daily work.
- **File creation** in arbitrary directories: Write tool. ~5% of daily work.
- **Simple text edits** where the target is already known and unique: Edit tool (more ergonomic). ~15% of daily work.

**Estimated share of daily work outside MCP Steroid's scope: ~50–65%.** MCP Steroid's augmentation covers the remaining ~35–50%, which is the code-understanding and code-modification portion of the workflow. Within that portion, MCP Steroid's unique capabilities (refactoring, hierarchy, type resolution) apply to perhaps half the tasks — roughly 15–25% of total daily work benefits meaningfully from MCP Steroid's semantic layer.

**Verdict:** Built-ins handle the majority of daily tasks (non-code files, text search, git, shell); MCP Steroid augments the code-specific 35–50%, with strongest impact on refactoring and navigation tasks within that slice.

---

### 9. Practical usage rule

**Decision rule:**

| Task type | Use |
|-----------|-----|
| Read/search non-code files | Built-in Read, Grep |
| Free-text pattern search | Built-in Grep |
| Git, shell, system operations | Built-in Bash |
| Create new files | Built-in Write |
| Small isolated edit (1–3 lines, target known) | Built-in Edit |
| Structural overview of a code file | MCP Steroid (PSI document symbols) |
| Method body extraction by name | MCP Steroid |
| Find all code usages of a symbol | MCP Steroid (ReferencesSearch) |
| Type hierarchy query | MCP Steroid (ClassInheritorsSearch) |
| Rename symbol across files | MCP Steroid (RenameProcessor) |
| Move class/package | MCP Steroid (MoveClassesOrPackagesProcessor) |
| Safe-delete with usage check | MCP Steroid (SafeDeleteProcessor) |
| Inline method | MCP Steroid (InlineMethodProcessor) |
| Multi-edit session in one file | MCP Steroid (stable name-path addressing) |
| External dependency lookup | MCP Steroid (JavaPsiFacade) |
| Build, test, debug | MCP Steroid (IDE runners) |

The heuristic: **if the task requires understanding or transforming code structure (types, references, hierarchy, cross-file consistency), use MCP Steroid. If the task is text-level (search, read, small patch, non-code files, shell), use built-ins.**

**Verdict:** Route by semantic vs. textual nature of the task — semantic operations (refactoring, navigation, type queries) to MCP Steroid; textual operations (search, read, patch, shell) to built-ins.