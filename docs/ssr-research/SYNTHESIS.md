# SSR research — Wave 1 synthesis

> **AI generated** — orchestrator-level synthesis of `MESSAGE-BUS.md` after Wave 1 (6
> parallel research sub-agents) completed on 2026-05-08. Source for every claim is the
> corresponding FACT entry on the bus. No code outside `docs/ssr-research/` was modified.

## TL;DR for the skill author

IntelliJ's **Structural Search & Replace** is a PSI-aware grep/sed: a matching engine
(`com.intellij.structuralsearch.Matcher` + `Replacer`) plus a per-language
`StructuralSearchProfile` extension point. From an MCP-Steroid skill perspective, three
shippable articles cover ~95% of agent needs:

1. **Concept + dialog-driven workflow** — anchored in JetBrains help terminology so
   agents can talk to humans about the IDE feature.
2. **Programmatic recipe** for `steroid_execute_code` — the ~80-line Kotlin block in
   `MSG-…-claude-research-6-r01` works against every supported language, parameterised
   by `LanguageFileType.INSTANCE`.
3. **Language coverage matrix** — what's supported, what isn't, and the per-language
   gotchas (typed-var prefix, profile class, replace handler differences).

Optional per-language deep-dives (Java, Kotlin, JS/TS, PHP, SQL, XML/HTML/JSP, Go) are
worth shipping after wave-2 review confirms the core articles.

## Sources

All 6 wave-1 sub-agents wrote disciplined FACT/COMPLETE entries on
`docs/ssr-research/MESSAGE-BUS.md`. Tally:

| Task | FACTs | Total entries | Source breadth |
|---|---:|---:|---|
| `SSR-A1-DOCS` (claude) | 11 | 13 | 13 JetBrains help pages + 5 blog posts |
| `SSR-A2-PLATFORM-API` (codex) | 16 | 17 | `community/platform/structuralsearch/{source,testSource}` |
| `SSR-A3-JAVA-PROFILE` (codex) | 8 | 10 | `community/java/structuralsearch-java/`, predicates, predefined-template source |
| `SSR-A4-KOTLIN-PROFILE` (claude) | 10 | 12 | both K1 and K2 modules + tests |
| `SSR-A5-MULTILANG` (codex) | 20 | 21 | survey across 18 languages |
| `SSR-A6-RECIPE-USECASES` (claude) | 7 | 9 | platform tests + Kotlin replace tests + mcp-steroid `execute-code-tool-description.md` |

## 1. Conceptual model (consolidated from A1 + A2)

SSR matches **PSI subtrees** that fit a *template*. A template is regular source code in
the chosen language, with **template variables** (placeholders that bind on first use)
and optional **modifiers** that constrain each variable.

The user-facing surfaces are (A1 FACT `…-a1g`, `…-a1m`):

| Surface | Action / location | What it adds |
|---|---|---|
| Search Structurally dialog | Edit \| Find \| Search Structurally | Template editor + filter panel (Count/Type/Reference/Text/Script) + scope picker |
| Replace Structurally dialog | Edit \| Find \| Replace Structurally | + replacement template + Reformat / Shorten FQN / Static import flags |
| Existing Templates gallery | Cog menu in either dialog | Pre-built templates per language (Class-based, Expressions, Operators, Comments, Interesting…) |
| Save Template… | Cog menu | Persists current template under "User Defined" (`ConfigurationManager` service, `structuralSearch.xml`) |
| Custom inspection | Settings \| Editor \| Inspections \| Add Custom Inspection \| **Add Structural Search/Replace Inspection…** | Promotes a template into a project-wide inspection (default suppress id `SSBasedInspection`) |
| Run Inspection by Name | Code \| Analyze Code \| Run Inspection by Name… | Runs an SSR inspection on demand |
| Qodana | `qodana.yaml` → `profile: { path: <profile.xml> }` | Shares SSR inspections across CI |

The five modifiers (A1 `…-a1h`):
- **Count** `[min,max]` — empty bound means unlimited.
- **Type** — expected expression type, with **Within type hierarchy** widening + **Invert** negation.
- **Reference** — variable matches another saved template by name.
- **Text** — regex / plain text, with hierarchy + invert flags.
- **Script** — Groovy script with the variable bound to a PSI node (`x.text`,
  `x.parent`, `__context__` for the whole template). Evaluated against **unresolved**
  PSI; reference resolution must be done explicitly inside the script.

Per-variable toggles independent of modifiers: **This variable is the target** (which
binding the result anchors on; default = whole template) and dialog-level **Match case**
+ **Injected code**.

## 2. Pattern variable syntax — three working forms, but only one parses inline constraints

This section was rewritten twice during research; the version below is the one
empirically validated against the live IntelliJ 2026.1.1 in §13 (probes T1–T8).

### 2.1 What the live IDE actually does

Run this in `steroid_execute_code`:

```kotlin
val o = MatchOptions().apply { setFileType(JavaFileType.INSTANCE) }
o.fillSearchCriteria("System.out.println(\$msg\$);")
println(o.variableConstraintNames)   //  → [__context__]   (msg NOT registered)

val o2 = MatchOptions().apply { setFileType(JavaFileType.INSTANCE) }
o2.fillSearchCriteria("System.out.println('_msg);")
println(o2.variableConstraintNames)  //  → [__context__, msg]   (msg registered)
```

`StringToConstraintsTransformer` only scans for the apostrophe character (`'`)
(`StringToConstraintsTransformer.java:53`). When it sees `$x$` characters in the
input, they fall through and are appended verbatim to the compiled pattern; **no
`MatchVariableConstraint` is created**. Yet the *language profile* itself recognises
`$x$` as a typed-var identifier when it parses the compiled pattern into a PSI tree
— so the search still matches. Probe T7 confirms: dollar form input matches all
println calls (no constraint = "match anything").

### 2.2 The three working forms

| Form | What `fillSearchCriteria` does | When it matters |
|---|---|---|
| **Apostrophe** `'_x:[…]` | Parses the variable name, registers a `MatchVariableConstraint`, parses inline constraints (`:[regex(…)]`, `:[exprtype(…)]`, `:[ref(…)]`, etc.), and writes back the dollar-form compiled pattern. | The **only** form that supports inline constraints, quantifiers (`?`, `+`, `*`, `{n,m}`), targeting, anonymous variables (`'_`), and the leading-bracket `[…]` `__context__` condition. |
| **Dollar** `$x$` | Passes through as literal characters. The language profile's pattern parser still recognises `$x$` as a typed-var, so matching works — but no constraint is registered, so it matches anything. | Predefined templates ship in this form because their `MatchVariableConstraint` entries are populated programmatically via `addVariableConstraint(...)` — they bypass the apostrophe scanner (probe T5 + T8 confirmed). |
| **Mixed** | Both forms can coexist in one template. Variables introduced via apostrophe get constraints; variables in dollar form pass through. | Rare but legal. Mostly an artefact of editing predefined templates. |

### 2.3 Internal typed-var prefix (what's in the PSI tree)

Independent of input form, the language profile wraps each `$x$` placeholder with an
internal prefix when it builds the PSI tree:

| Profile | Prefix | Source |
|---|---|---|
| Java | `__$_` | `JavaCompiledPattern.java:12` |
| Kotlin (K1 + K2) | `_____` (5 underscores) | `KotlinStructuralSearchProfile.kt:503` (K1) / `:512` (K2) |
| Other profiles | varies | language-specific |

So a Java `$msg$` in the compiled pattern becomes the identifier `__$_msg` in the PSI
tree — a real `PsiIdentifier` that the matcher pairs with candidate identifiers.

### 2.4 Practical rule for skill recipes

> **Default to the apostrophe form when you author a template programmatically**,
> because anything beyond a trivial structural shape needs constraints/quantifiers,
> and only the apostrophe form parses those inline. Use the dollar form only when
> consuming an existing `Configuration` (e.g. a predefined template or a saved
> inspection) where the constraints are already attached to `MatchOptions`.

A fourth form exists in source but is not in JetBrains' public docs: a leading bracketed
condition `[…]` at the start of the template applies to `__context__` (the whole match)
— see `StringToConstraintsTransformer.java:45-49`. Use cases: `:[within(...)]` and
`:[context(...)]` constraints on the whole template.

Quantifiers are independent of form:
- `'_x*` / `$x$` with Count `[0, ∞]` — zero-or-more
- `'_x+` / Count `[1, ∞]` — one-or-more
- `'_x{n,m}` / Count `[n, m]` — explicit range

## 3. Programmatic API map (A2)

The skill article's "advanced" tier needs to teach this layer. FQNs:

| Concept | Class |
|---|---|
| Saved template (abstract) | `com.intellij.structuralsearch.plugin.ui.Configuration` |
| Saved search | `…ui.SearchConfiguration` |
| Saved replace | `com.intellij.structuralsearch.plugin.replace.ui.ReplaceConfiguration` |
| Search criteria struct | `com.intellij.structuralsearch.MatchOptions` |
| Replace criteria struct | `…plugin.replace.ReplaceOptions` |
| Search executor | `com.intellij.structuralsearch.Matcher` |
| Replace executor | `…plugin.replace.impl.Replacer` |
| Result tree | `com.intellij.structuralsearch.MatchResult` (impl `MatchResultImpl`) |
| Result sink | `…plugin.util.CollectingMatchResultSink` (most useful for scripts) |
| Per-variable constraint | `MatchVariableConstraint` (regex/text, count, reference, type, script, hierarchy, invert, target, greedy) |
| Predicate runtime classes | `…impl.matcher.predicates.{RegExpPredicate, ReferencePredicate, ScriptPredicate, ContainsPredicate, WithinPredicate, NotPredicate, AndPredicate}` |
| Language profile EP | `com.intellij.structuralsearch.profile` → `StructuralSearchProfile` |
| Profile lookup | `StructuralSearchUtil.getProfileByFileType(LanguageFileType)` |
| Persisted templates service | `ConfigurationManager.getInstance(project)` (state file `structuralSearch.xml`) |
| Constraint UI labels | `UIUtil.{TEXT, REFERENCE, TYPE, EXPECTED_TYPE, MINIMUM_ZERO, MAXIMUM_UNLIMITED, CONTEXT}` |

**Defaults to remember** (A2 `…-fields`): `MatchOptions.looseMatching=true`,
`recursiveSearch=false`, `caseSensitiveMatch=false`, `searchInjectedCode=true`. Variable
constraints default to `min=1, max=1, greedy=true`.

## 4. Threading, validation, and safety — the load-bearing skill content

This is where a careless agent script will break. Listed in the order the skill article
should teach them.

### 4.1 Validate first, every time — non-negotiable

`Matcher(project, options)` compiles with `checkForErrors=false` (`Matcher.java:75-76`)
and `PatternCompiler` **silently swallows** several `MalformedPatternException` paths
when that flag is false (`PatternCompiler.java:516-518, 546-547, 580-582, 605-611`).
Only `Matcher.validate(project, options)` compiles with `checkForErrors=true`
(`Matcher.java:116-117`). Skipping validation does not raise an error — it produces
zero matches that look like a clean miss.

**Rule**: every recipe MUST call `Matcher.validate(project, matchOptions)` (and, when
replacing, `Replacer.checkReplacementPattern(project, replaceOptions)`) **before**
constructing the `Matcher`. Both throw `MalformedPatternException` with localised
messages — catch, log, rethrow.

### 4.2 Threading rules

1. **Do NOT wrap `Matcher.findMatches(sink)` in an outer `readAction { }`.**
   `findMatches` (`Matcher.java:172-213`) wraps its workload in
   `PsiManager.runInBatchFilesMode { … }` and runs the indexable-files iteration under
   `ReadAction.runBlocking` itself (`Matcher.java:234`). Outer read action causes nested
   locks and, with `GlobalSearchScope`, can deadlock. Confirmed by R2: this rule
   **also applies to `LocalSearchScope`** in production code; only `testFindMatches()`
   skips the read-action scheduler.
2. **From a coroutine: prefer `Replacer.replace(info)` (singular) over `replaceAll`.**
   `replaceAll` enters `runWriteActionWithCancellableProgressInDispatchThread`
   (`Replacer.java:172-178`) — designed for the modal SSR dialog; deadlocks the EDT when
   invoked from `mcpScript`. The singular `replace(info)` is the same path used by the
   inspection quick-fix (`SSBasedInspection.java:340-345`) and by both K1 and K2 replace
   tests.
3. **All replacements MUST live in one `CommandProcessor.executeCommand` block.** Single
   Ctrl+Z undo for all matches.
4. **Smart mode required.** `mcpScript` calls `waitForSmartMode()` first; the `Matcher`
   will throw if invoked during dumb mode.

### 4.3 Replacement match-lifetime safety (R3 finding)

`MatchResultImpl` and `ReplacementInfoImpl` rely on `SmartPsiElementPointer`s
(`MatchResultImpl.java:17, 40, 59-63`; `ReplacementInfoImpl.java:21-23, 53-54, 98-107`).
Smart pointers explicitly return null when their element was deleted
(`SmartPsiElementPointer.java:12, 22-23`). `Replacer.doReplace` silently returns null
for null, non-writable, or invalid targets (`Replacer.java:220-222`). A naive
`replaced=${sink.matches.size}` tally is therefore optimistic.

**Rule**: build all `ReplacementInfo`s before any write; reject overlapping/nested
matches (or process a deterministic non-overlapping subset); pre-check writable files;
report attempted vs skipped vs replaced counts separately.

### 4.4 Scope and `searchInjectedCode` (R3 finding)

`MatchOptions.searchInjectedCode` defaults to **true** (`MatchOptions.java:61, 211-212,
249`). When true, `CompileContext` does NOT restrict a global scope by file type, and
`Matcher` recursively enumerates injected PSI from each `PsiLanguageInjectionHost`
(`Matcher.java:403-409`). That means a SQL/RegExp/Java SSR can run inside injected
fragments in Java/Kotlin string hosts (`@Language("SQL") String sql = "..."`), not over
literal text — and it broadens the search.

**Rule for skill recipes**: explicitly set `setSearchInjectedCode(false)` for bulk
rewrites unless injected fragments are intended. Document the `@Language`/injection
behaviour when keeping it on. For global scope, prefer `LocalSearchScope` or
module/production scopes; chunk whole-project rewrites by content root. `Matcher`
queues one task per indexable file for global scopes (`Matcher.java:223-234, 494-552`)
— a dry-run match count first is cheap and saves a lot of grief.

### 4.5 Replace-side knobs

- **`isToReformatAccordingToStyle`** is per-replacement-region (a few lines around
  each match), not whole-file (`Replacer.java:237-254`).
- **`isToShortenFQN`** is implemented per-profile. Java and Kotlin override
  `supportsShortenFQNames` to return true; profiles that do not override (`JS`, `PHP`,
  `Groovy`, `XML`, etc., per the live profile table in
  `SSR-LANGUAGE-AND-MACROS.md` §4) silently ignore the flag because their replace
  handler is `DocumentBasedReplaceHandler` and never calls a shortening pass. Languages
  with no SSR profile at all (Python, Ruby, YAML, JSON) cannot consume the flag because
  they have no `MatchOptions` route — see §7.
- **`isToUseStaticImport`** is currently Java-only.
- **K2 FQN shortening is asynchronous** (R3 finding):
  `KotlinStructuralReplaceHandler.postProcess` on K2 submits a non-blocking
  `analyze { collectPossibleReferenceShorteningsInElementForIde(...) }` then
  `invokeShortening()` on the EDT under a separate write command
  (`KotlinStructuralReplaceHandler.kt` K2 lines 85-94). K1 calls
  `ShortenReferences.DEFAULT.process(...)` synchronously
  (`KotlinStructuralReplaceHandler.kt` K1 lines 80-82). A script that reads file
  contents immediately after `Replacer.replace(info)` may see unshortened FQNs on K2.
  **Rule**: for deterministic scripts, set `isToShortenFQN = false` and let the agent
  invoke `ShortenReferences` (or the K2 equivalent) explicitly afterwards.

The canonical recipe lives in §11 below — A4's recipe in the bus has a known bug (see
§11 "broken recipe to avoid"); use A6's shape.

## 5. Java profile (A3) — reference implementation

`com.intellij.structuralsearch.JavaStructuralSearchProfile` registered at
`community/java/structuralsearch-java/resources/intellij.java.structuralSearch.xml:12-18`.
Internal typed-var prefix **`__$_`**. Custom predicates wired by Java specifically:

- `ExprTypePredicate` — implements the dialog's `Type` modifier (`exprtype(SomeType)`).
  Walks expression type, lambda/functional-expression type, method-call type. Supports
  regex input, negation, hierarchy (`exprtype(*Number)`).
- `FormalArgTypePredicate` — `formal(SomeType)`. Reuses `ExprTypePredicate` with
  expected/formal-arg type via `ExpectedTypeUtils.findExpectedType()`.
- Hierarchy matching for text constraints uses `SubstitutionHandler` flags +
  `JavaMatchingVisitor.matchWithinHierarchy()` walking `HierarchyNodeIterator`.

Predefined Java templates are **source-defined** in
`JavaPredefinedConfigurations.createPredefinedTemplates()`, **not** XML. All shipped
predefined templates are **search-only** — no replacement text. The 12 quoted in A3
`…-a3m5` are the most useful first-page recipes (method calls, new expressions,
`exprtype`-constrained expressions, `instanceof` patterns, if/else skeletons,
"logging without if", method/constructor declarations, hierarchy-aware methods,
implementors-in-hierarchy, records, double-checked locking).

Replacement specifics — `JavaReplaceHandler` handles class/member context vs block
context, special-cases annotations, expression-list statements, parameters, anonymous
classes, try/catch/finally preservation, symbol renames; `postProcess` applies static
imports first (when requested) then `JavaCodeStyleManager.shortenClassReferences()`.

**Names that DO NOT exist** in current source (so don't put them in the skill):
`JavaScriptedPredicate`, `JavaTypeMatcher`, `WithinHierarchy` (as a class), and the
profile methods `getMeaningfulExpressionParents`, `isApplicableContext`,
`isReplacementTypeApplicable`. Their current equivalents are listed in A3's COMPLETE
entry.

## 6. Kotlin profile (A4) — K1 vs K2 split

Both modules ship simultaneously; only one is loaded at runtime depending on
`KotlinPluginMode` (current default is K2):

| | K1 (deprecated, still bundled) | K2 (default) |
|---|---|---|
| Module | `community/plugins/kotlin/code-insight/structural-search-k1/` | `…/structural-search-k2/` |
| Profile FQN | `o.j.k.idea.structuralsearch.KotlinStructuralSearchProfile` | `o.j.k.idea.k2.codeinsight.structuralsearch.KotlinStructuralSearchProfile` |
| Type resolution | FE10 `resolveToDescriptorIfAny` + `KotlinType` | Analysis-API `analyze(node) { … }` + `KaType` |
| Render | `DescriptorRenderer.SHORT_NAMES_IN_TYPES` / `FQ_NAMES_IN_TYPES` | `KaTypeRendererForSource.WITH_SHORT_NAMES` ×2 + `WITH_QUALIFIED_NAMES` |
| Supertype walk | `KotlinType.supertypes()` | `KaType.allSupertypes` |
| FQ-name shortening | `ShortenReferences.DEFAULT.process(...)` synchronous | Async: `analyze { collectPossibleReferenceShorteningsInElementForIde(...) }` then `invokeShortening()` on EDT under write command |

**The user-side filter applicability rules ARE byte-for-byte identical** between K1 and
K2 (R2 verified by SHA-256 diff of `isApplicableConstraint`: same body, same enabled
filters, same fallthrough). **Whole-file equivalence is overstated** — K2 lives in a
different package (`o.j.k.idea.k2.codeinsight.structuralsearch`), K1 carries
`@K1Deprecation` on every public type, K2 uses `nameOfExprType.isNotBlank()` where K1
uses `StringUtil.isEmptyOrSpaces`, K2 returns `StructuralReplaceHandler` explicitly,
and K2's replace `postProcess` is asynchronous (see §4.5). So:

- **Pattern syntax, predicates, predefined templates, applicability rules, replace
  handler structural transforms** — identical between K1 and K2 from a skill-author's
  perspective.
- **Type resolution** — divergent (FE10 descriptors vs Analysis-API `analyze {}`).
- **FQN shortening timing** — K1 synchronous, K2 async; relevant when a script reads
  file contents right after `replace(info)`.

User-visible Kotlin SSR specifics:
- Internal typed-var prefix `_____` (5 underscores). User syntax `'name`.
- Two pattern contexts: `default` and `property` (the latter for property
  getter/setter patterns, parsed via `KtPsiFactory.createProperty`).
- 4 custom filters: **AlsoMatchVal** (a `var` pattern also matches `val`),
  **AlsoMatchVar** (and vice versa), **AlsoMatchCompanionObject** (a non-companion
  object pattern also matches a companion), **MatchCallSemantics** (argument-order-
  insensitive matching for `KtCallElement`).
- `exprtype` predicate supports regex (`exprtype(.*Number)`), invert (`!exprtype(...)`),
  within-hierarchy (`exprtype(*Number)`), and `null` short-circuit.

12 documented limitations (A4 `…-a11`) — most relevant for the skill: no smart-cast
constraint, no nullability-only constraint (must inline as `exprtype(Int?)`), no
expect/actual cross-module link, `KtPackageDirective` unconditionally skipped, no first-
class top-level visibility predicate, replacement requires shape parity (declaration vs
expression). Full list in the bus.

The canonical mcp-steroid Kotlin recipe — `runCatching{}.onFailure{}` → `try/catch`
(this is a banned pattern per repo `CLAUDE.md`) — is in A4 `…-a10` with full pattern
text, replacement text, and a `steroid_execute_code` skeleton.

## 7. Multi-language coverage matrix (A5)

Bundled in this `~/Work/intellij` checkout:

| Language | State | Profile FQN | Notes |
|---|---|---|---|
| Java | SUPPORTED | `JavaStructuralSearchProfile` | Reference implementation; full feature set |
| Kotlin (K1+K2) | SUPPORTED | two profiles, see §6 | Two modules, identical user surface |
| Groovy | SUPPORTED | `com.intellij.structuralsearch.groovy.GroovyStructuralSearchProfile` | file/class contexts; class context wraps as `class AAAAA { … }`; document-based replace; no profile-specific predefined templates |
| JS / TS / JSX / TSX | SUPPORTED | `JSStructuralSearchProfile` + `JSStructuralSearchProfile2` | Single profile pair covers all JS dialects via dialect detection; ships predefined general/suspicious templates; document-based replace |
| Go | SUPPORTED | `com.goide.structuralsearch.GoStructuralSearchProfile` | Wraps patterns in `package main`; validator rejects unsupported template forms (e.g. `GoTag`); ships predefined templates |
| PHP | SUPPORTED | `com.jetbrains.php.structuralsearch.PhpStructuralSearchProfile` | Wraps with `<?php`; type constraint only on `PhpTypedElement` parents; ships PHP predefined templates |
| SQL (DataGrip) | SUPPORTED | `com.intellij.sql.structuralsearch.SqlStructuralSearchProfile` | `SqlLanguage` + `SqlLanguageDialectEx`; falls back to expression parsing on dialect parse errors; tests skip Generic/Redis/Sybase |
| HTML | SUPPORTED | `XmlStructuralSearchProfile` (shared) | XML profile keyed by `HtmlFileType`; `XmlReplaceHandler` |
| XML | SUPPORTED | `XmlStructuralSearchProfile` | Templates for tags, attributes, `within(...)` containment |
| JSP | SUPPORTED | `XmlStructuralSearchProfile` + `JspXmlTagExtractor` | No standalone profile; uses XML profile + a `specialXmlTagExtractor` EP for JSP-specific behaviour |
| CSS | SUPPORTED | `CssStructuralSearchProfile` | Minimal `StructuralSearchProfileBase` extender; declarations wrapped as `.c { $$PATTERN_PLACEHOLDER$$ }`; document-based replace; no predefined templates |
| Flex / ActionScript | SUPPORTED | `JSStructuralSearchProfile2` (shared) | Through JS dialect; ECMA_SCRIPT_L4 wrapped as `class A { function f() { … } }` |
| properties | SUPPORTED | `PropertiesStructuralSearchProfile` | Narrow key/value/comment matching; ships duplicated-word + double-quote predefined templates |
| Rust | SUPPORTED (experimental, no replace) | `org.rust.ide.ssr.RsStructuralSearchProfile` | Gated by `RsExperiments.SSR`; replacement throws unsupported |
| C# / .NET (Rider) | THIRD-PARTY ENGINE | n/a | Uses ReSharper engine, NOT IntelliJ `StructuralSearchProfile` EP. Document **separately** in any skill article. |
| Scala | SUPPORTED via plugin | `org.jetbrains.plugins.scala.structuralSearch.ScalaStructuralSearchProfile` | NOT bundled in IntelliJ community/ultimate; ships in the separate JetBrains Scala plugin repo (R2 verified). The plugin's profile registers on the same `com.intellij.structuralsearch.profile` EP and supports `ScalaLanguage.INSTANCE` + `Scala3Language.INSTANCE`. Available when the Scala plugin is installed; absent in this checkout. |
| Python | NOT SUPPORTED | n/a | Confirmed by both A1 (PyCharm help: "PyCharm doesn't support structural search and replace for Python at the moment") and A5 (no profile under `python/`). |
| Ruby | NOT SUPPORTED | n/a | No profile in checkout |
| YAML / JSON / Markdown | NOT SUPPORTED | n/a | No profiles |

**Default replace handler when a profile doesn't override**: `DocumentBasedReplaceHandler`
(text-level rewrite). Profiles that override (Java, Kotlin, XML) get PSI-aware
replacements, FQN shortening, etc.

**Documentation contradictions worth flagging in the skill**:
- A1 found a contradiction in the **GoLand help** page — its prose says SSR "supports
  SQL, JavaScript, XML and HTML" while the walkthrough is a Go example. A5's source
  evidence confirms Go SSR is real and shipped.
- The IntelliJ IDEA help page lists "Java, Kotlin, Scala and Groovy"; this list is
  out-of-date (JS/TS, PHP, SQL, HTML/XML, Go, properties, etc. are all supported when
  the corresponding plugin is installed).
- Scala — A1 mentions it as an officially supported language, A5 found no profile in
  this checkout. Likely the third-party Scala plugin owns its own SSR profile; this
  needs a follow-up data point before we publish the skill.

## 8. Use case catalogue (A6)

⚠️ **Caveat surfaced by R3**: the `runCatching{}.onFailure{}` → `try/catch` rewrite
**does not preserve the `Result<T>` return value** of the original chain. If the call
sites consume the result (`.getOrElse { … }`, `.getOrThrow()`, etc.), the patch is
unsafe. Skill articles must present this rewrite as **search-only by default**, or as a
statement-context-only rewrite gated on manual review. The same caveat applies to any
rewrite that drops a value-producing expression in favour of a statement form.


33 catalogued patterns across 5 categories, all with search/replace text + filter usage.
The skill article should excerpt 4–6 per category. Highlights worth quoting verbatim:

- **Migration**: `junit.framework.Assert.assertEquals('_actual, '_expected)` → JUnit4
  with argument re-order; Guava `Lists.newArrayList()` → `new ArrayList<>()`;
  `Hamcrest.assertThat(_, is(_))` → AssertJ.
- **Code-style enforcement**: ban `runCatching{}.onFailure{}` (mcp-steroid `CLAUDE.md`
  policy); ban `println` in Kotlin; audit `Optional.get()` calls via
  `:[exprtype(java\.util\.Optional<.*>)]`; `if (x != null) x.f()` → `x?.f()`.
- **Refactoring**: anonymous `Runnable` → lambda; `someProperty.let { it.f() }` →
  `someProperty?.f()`; remove redundant `<T>` on `new ArrayList<T>()`.
- **Audit (search-only)**: all `System.out.println`; `Class.forName(...)`;
  `getDeclaredMethod`/`getDeclaredField`; JS `eval(...)`; Kotlin `runBlocking`;
  `@Suppress("UNCHECKED_CAST")`.
- **Bulk renames where text-find fails**: rename only `foo(int)` overload (preserve
  `foo(String)`); rewrite usages of constant `MAX_LEN` excluding declaration via
  `:[!ref(MyClass\.MAX_LEN)]`; convert `assertTrue(a.equals(b))` to `assertEquals(b, a)`
  overload-safe.

## 9. Open questions — RESOLVED by wave 2

| # | Question | Resolution | Source |
|---|---|---|---|
| 1 | Pattern syntax — third form? | Three input forms documented: dollar (`$x$`), apostrophe (`'_x` shorthand), and the leading bracketed `[…]` `__context__`-condition form. All three accepted by `MatchOptions.fillSearchCriteria`. §2 reflects this. | R2 `MSG-…-q1`, source `StringToConstraintsTransformer.java:34-212, 243-479` |
| 2 | Scala SSR availability | NOT bundled in checkout, but the third-party JetBrains Scala plugin ships `o.j.p.scala.structuralSearch.ScalaStructuralSearchProfile` registered on the same EP. §7 row updated. | R2 `MSG-…-q2`, [scala/structural-search](https://github.com/JetBrains/intellij-scala/blob/idea261.x/scala/structural-search/) |
| 3 | Threading under `LocalSearchScope` | Same advice holds: don't wrap `findMatches` in outer `readAction`. The production path enqueues per-PSI-element matching that runs inside `ReadAction.nonBlocking().inSmartMode().executeSynchronously()`. Only `testFindMatches()` skips this. §4.2 reflects this. | R2 `MSG-…-q3`, `Matcher.java:172-552` |
| 4 | Predefined-template discoverability | Use `StructuralSearchUtil.getPredefinedTemplates()` (all profiles, sorted, cached) or `profile.getPredefinedTemplates()` (one profile). Skill should prefer programmatic discovery. §10 reflects this. | R2 `MSG-…-q4`, `StructuralSearchUtil.java:170-179`, `JSPredefinedConfigurationsTest.java:24-28` |
| 5 | K1 vs K2 user-side equivalence | `isApplicableConstraint` is byte-for-byte identical (SHA-256 verified); whole files differ (package, deprecation annotations, K2 async shortening). §6 + §4.5 reflect this. | R2 `MSG-…-q5` |
| 6 | Custom inspection persistence schema | `.idea/inspectionProfiles/*.xml` has `<inspection_tool class="SSBasedInspection">` containing `<searchConfiguration>` / `<replaceConfiguration>` children whose attribute set matches `Configuration`/`MatchOptions`/`ReplaceOptions` `writeExternal/readExternal`. Scripts can generate one without the dialog. | R2 `MSG-…-q6`, `Configuration.java:178-248`, `idea_default.xml:1825-1853` |

## 10. Proposed skill article structure (input to wave 3)

Folder: `prompts/src/main/prompts/skill/structural-search/`

| File | Audience | Contents |
|---|---|---|
| `overview.md` | first-touch | What SSR is, when to use it (vs Find/Replace, Find Usages, intentions/inspections), supported languages summary table, 3 search-only example patterns, **explicit safety box: any rewrite/bulk edit MUST go through `api-recipe.md` (validate first, don't wrap `findMatches`, scope rules, single executeCommand) — this is non-optional**, links to subsequent articles |
| `syntax.md` | learning the template language | The three pattern forms from §2, the **9 macros** plus `_<custom>` (`SSR-LANGUAGE-AND-MACROS.md` §3), quantifiers, modifiers, the PSI-tree mental model (lead with the dump from `SSR-LANGUAGE-AND-MACROS.md` §1), `__context__`, target variable selection |
| `api-recipe.md` | scripts using `steroid_execute_code` | The corrected canonical recipe (§11), validation rule (§4.1), threading rules (§4.2), match-lifetime safety (§4.3), `searchInjectedCode` and scope guidance (§4.4), replace-side knobs incl. K2 async shortening (§4.5), error handling |
| `language-coverage.md` | quick lookup | The matrix in §7 with profile FQN + file type per row, "use this `LanguageFileType.INSTANCE` line" guidance, programmatic enumeration recipe via `StructuralSearchUtil.getPredefinedTemplates()` |
| `use-cases.md` | recipe gallery | A6's catalogued patterns grouped by category, with the `runCatching` caveat from §8 attached to lossy rewrites, marked as "search-only" or "replace-with-review" |
| `inspections-from-script.md` | sharing rewrites with the team | Generating `.idea/inspectionProfiles/*.xml` `<searchConfiguration>` / `<replaceConfiguration>` entries programmatically per the schema in R2 Q6, plus Qodana `qodana.yaml` integration per A1 `…-a1n` |
| (optional) `language-java.md` | Java specialists | A3's predicates, full 98-template enumeration via `JavaPredefinedConfigurations.getPredefinedTemplates()`, both Java pattern contexts (`default`, `member`), replacement handler |
| (optional) `language-kotlin.md` | Kotlin specialists | The K1↔K2 narrowed equivalence statement from §6, the 4 custom filters (`_AlsoMatchVal`/`Var`/`CompanionObject`/`MatchCallSemantics`), the 12 limitations, the K2-async shortening caveat, `runCatching → try/catch` as **search-only by default** |

Each article respects the repo's `MarkdownArticleContractTest` constraints (title ≤80
chars, description ≤200 chars, no bare code outside fences) and references articles via
generated URI classes (per `NoHardcodedMcpSteroidUriUsageTest`).

## 11. Canonical recipe — and the broken recipe to avoid

### 11.1 Broken recipe (do NOT ship — A4's flagship Kotlin recipe in the bus)

R3's adversarial review caught a real bug in A4 `MSG-…-a10` lines 645-660 of the bus:

```kotlin
// BROKEN — do NOT use as-is
val cfg = SearchConfiguration().apply {
    matchOptions.setFileType(KotlinFileType.INSTANCE)
    matchOptions.fillSearchCriteria(search)        // step (1): cfg.matchOptions.searchPattern = compiled SEARCH
}
val replaceOptions = ReplaceConfiguration(cfg).replaceOptions.apply {
    StringToConstraintsTransformer.transformCriteria(replace, cfg.matchOptions)  // step (2): OVERWRITES cfg.matchOptions.searchPattern with compiled REPLACE
    replacement = cfg.matchOptions.searchPattern   // step (3): replacement = the just-compiled REPLACE pattern (correct value, but...)
}
val sink = CollectingMatchResultSink().also { Matcher(project, cfg.matchOptions).findMatches(it) }
//                                                              ^^^ now searching for the REPLACE template, not the SEARCH template
```

`StringToConstraintsTransformer.transformCriteria` writes back into `options.searchPattern`
(`StringToConstraintsTransformer.java:212`) — so the second call clobbers the first.
Net effect: the `Matcher` searches for `try { … } catch { … }` instead of
`runCatching { … }.onFailure { … }`. Silent zero matches, or worse, a wrong rewrite.

### 11.2 Corrected canonical recipe (built on A6's shape + R3's safety rules)

```kotlin
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.structuralsearch.MatchOptions
import com.intellij.structuralsearch.MatchResult
import com.intellij.structuralsearch.Matcher
import com.intellij.structuralsearch.plugin.replace.ReplaceOptions
import com.intellij.structuralsearch.plugin.replace.impl.Replacer
import com.intellij.structuralsearch.plugin.util.CollectingMatchResultSink

// ---- INPUTS ----
val fileType: LanguageFileType = JavaFileType.INSTANCE
val searchPattern  = "System.out.println('_msg:[exprtype( java\\.lang\\.String )]);"
val replacePattern: String? = "LOG.info(\$msg\$);"     // null → search-only
val scope: SearchScope       = GlobalSearchScope.projectScope(project)
val reformat = true; val shortenFqNames = true
val searchInjectedCode = false                          // explicit; default true is risky for bulk edits

// ---- BUILD ----
val matchOptions = MatchOptions().apply {
    fillSearchCriteria(searchPattern)
    setFileType(fileType); dialect = fileType.language
    setRecursiveSearch(true); setScope(scope)
    setSearchInjectedCode(searchInjectedCode)
}
val replaceOptions = replacePattern?.let {
    ReplaceOptions(matchOptions).apply {
        replacement = it
        isToReformatAccordingToStyle = reformat
        isToShortenFQN = shortenFqNames
    }
}

// ---- VALIDATE FIRST (non-negotiable; without this, malformed patterns silently match nothing) ----
readAction { Matcher.validate(project, matchOptions) }
if (replaceOptions != null) readAction { Replacer.checkReplacementPattern(project, replaceOptions) }

// ---- SEARCH (Matcher manages its own read action; do NOT wrap) ----
val sink = CollectingMatchResultSink()
Matcher(project, matchOptions).findMatches(sink)

// ---- REPORT ----
readAction {
    sink.matches.forEach { m: MatchResult ->
        val el = m.match ?: return@forEach          // SmartPsiElementPointer may have invalidated
        val pf = el.containingFile ?: return@forEach
        val doc = PsiDocumentManager.getInstance(project).getDocument(pf)
        val line = (doc?.getLineNumber(el.textRange.startOffset) ?: 0) + 1
        printJson(mapOf("path" to pf.virtualFile?.path, "line" to line,
                        "text" to el.text.lineSequence().first().take(160)))
    }
}
println("attempted=${sink.matches.size}")

// ---- REPLACE (when applicable) ----
if (replaceOptions != null) {
    var replaced = 0; var skipped = 0
    val replacer = Replacer(project, replaceOptions)
    // Build all ReplacementInfos under read; check writability and target validity before write.
    val infos = readAction {
        sink.matches.mapNotNull { m ->
            val target = m.match ?: run { skipped++; return@mapNotNull null }
            val vf = target.containingFile?.virtualFile
            if (vf == null || !vf.isWritable) { skipped++; null } else replacer.buildReplacement(m)
        }
    }
    writeAction {
        CommandProcessor.getInstance(project).executeCommand(project, {
            infos.forEach { info ->
                try { replacer.replace(info); replaced++ }
                catch (e: Exception) { skipped++ }
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }, "SSR Replace", null)
    }
    println("replaced=$replaced skipped=$skipped (of ${sink.matches.size} matches)")
}
```

**What this fixes vs A4**: never re-runs the transformer on populated options;
`replacement` is set directly to the dollar-form replacement text. **What this adds vs
A6**: explicit `setSearchInjectedCode(false)`, validation calls before search,
writability/null-target filtering, separate counters for attempted/replaced/skipped.

For Kotlin: change `fileType` to `org.jetbrains.kotlin.idea.KotlinFileType.INSTANCE`,
set `isToShortenFQN = false` if reading file contents immediately after `replace` (K2
async shortening — see §4.5), and use the apostrophe form `'_BODY*` etc. Anchor:
`SSR-LANGUAGE-AND-MACROS.md` §7 verified this shape against the live IDE.

## 13. Empirical validation log (live IntelliJ 2026.1.1)

Before unblocking wave 3, every load-bearing claim that drives recipe code was tested
against the running IDE via `steroid_execute_code` (task `SSR-WAVE2-VALIDATION`). All
probes used the `mcp-steroid` open project. Results below.

| # | Claim under test | Probe | Result | Verdict |
|---|---|---|---|---|
| T1 | `fillSearchCriteria("$msg$")` registers a `msg` variable | dump `variableConstraintNames` | `[__context__]` only — msg NOT registered | **Refines §2** — drove the rewrite above |
| T2 | `fillSearchCriteria("$msg$:[regex( foo )]")` binds the regex constraint | dump `getVariableConstraint("msg")` | no variable created; constraint not bound | **Confirms §2** rule about inline `:[…]` |
| T3 | `fillSearchCriteria("'_msg:[regex( foo )]")` binds the regex constraint | dump `getVariableConstraint("msg").regExp` | `regExp='foo'`, msg registered | **Confirms §2** apostrophe path |
| T4a | `Matcher(project, malformed)` constructor does NOT throw | construct and inspect | constructed normally, no exception | **Confirms §4.1** that validation is non-optional |
| T4b | `Matcher.validate(project, malformed)` throws `MalformedPatternException` | call validate on `"class { broken"` | `MalformedPatternException` raised | **Confirms §4.1** validate-first rule |
| T5 | Predefined templates ship in dollar form with pre-registered constraints | dump "Method calls" template | `searchPattern = "$Instance$.$MethodCall$($Parameter$)"`, `variableConstraintNames = [__context__, Instance, MethodCall, Parameter]` with `Parameter min=0 max=∞`, `Instance min=0 max=1` | **Refines §2** — explains how dollar-form templates work |
| T6 | §11.2 canonical recipe (apostrophe + exprtype) works | `testFindMatches` against 3-call source | 1 match: only `System.out.println("hello")`; the int call and the unrelated `log("hello")` excluded | **Confirms §11.2** end-to-end |
| T7 | Dollar form without registered constraints still matches (no filtering) | same source, `$msg$` form, no constraints | 2 matches: both println calls (string + int) | **Refines §2** — dollar form matches anything when constraints are absent |
| T8 | Dollar form + programmatically-attached constraints behaves like the predefined-template path | same source with `addNewVariableConstraint("msg")` | 2 matches (no filter set on the constraint) | **Confirms §2.2** predefined-template path |
| T9 | A4's broken recipe — second `fillSearchCriteria` overwrites `searchPattern` | step 1 fills with search, step 2 fills with replace, then `testFindMatches` | step 2 set `searchPattern = "LOG.info($msg$);"`; testFindMatches returned 0 (no LOG.info calls in source) | **Confirms R3 BLOCKER** — A4 recipe ships zero matches |

Net outcome: SYNTHESIS.md §2 was rewritten one more time after T1 and T7 made the
"both forms accepted programmatically" framing too coarse. The corrected framing —
"both forms produce working matches; only apostrophe binds inline `:[…]`
constraints; predefined templates ship dollar form with constraints pre-attached" — is
the version above and is what wave 3 should teach.

The §11.2 canonical recipe is empirically green. R3's blocker on A4 is real and the
synthesis tells wave 3 authors to avoid it.

## 12. Wave 2 status

| Wave | State | Notes |
|------|-------|-------|
| 1 — research × 6 | DONE | 72 FACTs |
| 1.5 — macro enumeration via MCP Steroid | DONE | `SSR-LANGUAGE-AND-MACROS.md` |
| 2 — review × 3 | **DONE** | R1 fact-check (APPROVE w/ minor edits, 20/22 verified); R2 open questions (all 6 resolved); R3 adversarial (BLOCKER on A4 recipe + 8 majors). All findings folded into §§2, 3, 4, 6, 7, 8, 9, 10, 11. |
| 3 — skill prompts | UNBLOCKED | Wave 3 author can now use this synthesis as the source of truth, including §11.2's verified canonical recipe. Avoid §11.1's broken pattern. |

(§11 superseded by §11/§12 above — wave-status table now lives in §12.)
