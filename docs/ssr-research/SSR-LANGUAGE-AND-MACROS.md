# SSR template language + macro registry (live IntelliJ introspection)

> **AI generated** — produced 2026-05-08 by running `steroid_execute_code` against the
> live IntelliJ 2026.1.1 instance (build IU-261.23567.161, mcp-steroid plugin
> 0.93.0.19999-SNAPSHOT). All numbers and PSI dumps in this document come from that
> running IDE; macro keyword definitions come from
> `community/platform/structuralsearch/source/com/intellij/structuralsearch/impl/matcher/compiler/StringToConstraintsTransformer.java:19-31, 412-484`.

## 1. The mental model: a template IS a PSI tree

This is the fact that determines everything else about SSR. Empirical proof from the
live IDE:

**Java input** (what the user types): `class '_C extends '_Base:[regex( *Number )] {}`

**`MatchOptions.fillSearchCriteria(...)` does two things at once**:

1. Re-emits the template into "internal" form by replacing each `'name` with `$name$`:
   `class $C$ extends $Base$ {}`.
2. Pulls every `:[…]` constraint block out and stores it on a `MatchVariableConstraint`
   keyed by the variable name. The constraint above becomes
   `'Base' → min=1 max=1 regex="Number" withinHierarchy`.

**The internal-form text is then parsed by the language profile (`PatternCompiler →
profile.createPatternTree`) into a real PSI subtree**. For the Java example the PSI
tree is:

```
PsiClassImpl  text="class __$_C extends __$_Base {}"
├── PsiKeywordImpl       text="class"
├── PsiIdentifierImpl    text="__$_C"
├── PsiReferenceListImpl text="extends __$_Base"
│   ├── PsiKeywordImpl                text="extends"
│   └── PsiJavaCodeReferenceElementImpl text="__$_Base"
│       └── PsiIdentifierImpl text="__$_Base"
├── PsiJavaTokenImpl text="{"
└── PsiJavaTokenImpl text="}"
```

Each variable is a real PSI leaf — for Java a `PsiIdentifier` with the **internal typed-
var prefix `__$_`** glued to the variable name, sitting wherever an identifier is legal.
The matcher then walks both the pattern PSI tree and the candidate PSI tree in parallel,
binding typed-var nodes to the candidate PSI subtrees they match. **The constraints
(`exprtype`, `regex`, `script`, …) are not in the PSI tree**: they live on the
`MatchVariableConstraint` and are evaluated by predicates after the structural shape
matches.

**Kotlin input**: `runCatching { '_BODY* }.onFailure { '_E -> '_HANDLER* }`

The compiled internal form `runCatching { $BODY$ }.onFailure { $E$ -> $HANDLER$ }`
parses to a `KtDotQualifiedExpression` with the variables embedded as
`KtNameReferenceExpression` / `KtParameter` nodes whose text starts with the **Kotlin
typed-var prefix `_____` (five underscores)**:

```
KtDotQualifiedExpression
├── KtCallExpression "runCatching { _____BODY }"
│   ├── KtNameReferenceExpression "runCatching"
│   └── KtLambdaArgument
│       └── KtLambdaExpression / KtFunctionLiteral
│           └── KtBlockExpression
│               └── KtNameReferenceExpression "_____BODY"
└── KtCallExpression "onFailure { _____E -> _____HANDLER }"
    └── KtLambdaArgument
        └── KtLambdaExpression / KtFunctionLiteral
            ├── KtParameterList
            │   └── KtParameter "_____E"
            └── KtBlockExpression
                └── KtNameReferenceExpression "_____HANDLER"
```

So the apparent "string template" is really a real Kotlin AST with placeholder
identifiers. **Anything you can write in Kotlin/Java/PHP/etc. you can put in a template.**
That is why SSR is **not** regex over text: it's structural matching over
language-specific PSI trees.

**Implication for skill articles**: an agent that thinks of SSR as "regex with macros"
will misuse it constantly. The skill articles must teach: *write a partial program in
the target language, replace identifiers with `'name` placeholders, attach constraints
in `:[…]` blocks*.

## 2. The two-form variable syntax — finally pinned down

`StringToConstraintsTransformer.transformCriteria` is the canonical compiler. It
recognises **only the apostrophe form** when scanning for variables (line 53). Source
quote (lines 60-83):

```
// typed variable; eat the name of typed var
int endIndex = ++index;
…
if (criteria.charAt(index) == '_') {
    target = false;
    if (endIndex == index + 1) {
        // anonymous var, make it unique for the case of constraints
        anonymousTypedVarsCount++;
        typedVar = "_" + anonymousTypedVarsCount;
    } else {
        typedVar = criteria.substring(index + 1, endIndex);
    }
} else {
    typedVar = criteria.substring(index, endIndex);
}
pattern.append("$").append(typedVar).append("$");
```

So the **compiled pattern** that ends up in `MatchOptions.searchPattern` and goes
through the language profile uses the dollar form `$name$`. The **user input** that
`fillSearchCriteria` accepts uses the apostrophe form.

| Form | When it appears | How variables are written |
|---|---|---|
| **Apostrophe (input form)** | What you write in any string passed to `MatchOptions.fillSearchCriteria(...)`, in saved configurations, in IntelliJ predefined templates, in `JavaPredefinedConfigurations.kt` source | `'name` (target), `'_name` (non-target), `'_` (anonymous), with optional quantifier and `:[...]` constraint block |
| **Dollar (compiled form)** | What ends up in `MatchOptions.searchPattern`. What the language profile sees and parses into PSI. What JetBrains help docs show because it's the storage/round-trip form. | `$name$` (no constraints inline; constraints live on `MatchVariableConstraint`) |
| **Internal typed-var prefix** | What appears inside the actual PSI tree leaves — depends on language profile | Java `__$_name`, Kotlin `_____name`, Groovy / XML / others vary |

**Rule of thumb for skill recipes**: for any programmatic call to
`fillSearchCriteria(...)`, **use the apostrophe form** — that's the only form whose
constraint syntax (`:[regex(...)]`, `:[exprtype(...)]`, etc.) the transformer parses.
The dollar form has no inline-constraint syntax; constraints in the dialog UI are
entered via separate filter controls and stored in `MatchVariableConstraint`.

## 3. The complete macro / quantifier reference (from `StringToConstraintsTransformer.java`)

### 3.1 Variable + quantifier syntax

Grammar of a single variable token in the apostrophe form:

```
'<name>[ <quantifier> [ <non-greedy> ] ][ ":" <hierarchy-prefix> "[" <conditions> "]" ]
```

- `<name>` is `_name` (non-target), `name` (target), or `_` (anonymous → autogenerated `_1`, `_2`, …).
- `<quantifier>`:
  - `?` — 0..1 (`min=0, max=1`)
  - `+` — 1..∞ (`min=1, max=Integer.MAX_VALUE`)
  - `*` — 0..∞ (`min=0, max=Integer.MAX_VALUE`)
  - `{n,m}` — explicit range. Empty m (`{n,}`) means `Integer.MAX_VALUE`. Single number (`{n}`) means `min=max=n`. (Lines 113-158.)
- Trailing `?` after a quantifier flips greedy off (`greedy=false`). (Lines 160-166.)
- Two targets in one template throw `MalformedPatternException("error.only.one.target.allowed")`. (Line 175.)

### 3.2 Hierarchy / inversion prefix BEFORE the `[…]` block

After `:` and before `[`:

| Char | Effect | Source line |
|---|---|---|
| `!` | `setInvertRegExp(true)` (the *trailing-regex* form, not the bracketed form) | `StringToConstraintsTransformer.java:247-252` |
| `+` | `setStrictlyWithinHierarchy(true)` (only proper subtypes) | `:255-256` |
| `*` | `setWithinHierarchy(true)` (this type or subtype) | `:255-258` |

These prefixes apply to a bare regex constraint after the colon (`'_x:[!]regex_text` is
the trailing-regex form). The `:[…]` bracket form below uses its own per-option `!`
inversion.

### 3.3 The 9 macros recognised inside `:[…]`

Source: `StringToConstraintsTransformer.java:19-31` declares the keyword set:

```java
private static final String REF      = "ref";
private static final String REGEX    = "regex";
private static final String REGEXW   = "regexw";
private static final String EXPRTYPE = "exprtype";
private static final String FORMAL   = "formal";
private static final String SCRIPT   = "script";
private static final String CONTAINS = "contains";
private static final String WITHIN   = "within";
private static final String CONTEXT  = "context";

private static final Set<String> knownOptions =
    Set.of(REF, REGEX, REGEXW, EXPRTYPE, FORMAL, SCRIPT, CONTAINS, WITHIN, CONTEXT);
```

Plus a 10th category: any name that **starts with `_`** (like `_AlsoMatchVal`) is
accepted as a custom additional constraint and routed to the language profile via
`MatchVariableConstraint.putAdditionalConstraint(name, value)` (line 477-479).

| Macro | What it does | Storage on `MatchVariableConstraint` | Special arg modifiers | Inversion via `!` | Restrictions |
|---|---|---|---|---|---|
| `ref(<template-name>)` | Variable matches another saved template | `setReferenceConstraint`, `setInvertReference` | none | yes | — |
| `regex(<pattern>)` | Text regex on the matched node's source text | `setRegExp`, `setInvertRegExp` | leading `*` arg → also sets `withinHierarchy` | yes | — |
| `regexw(<pattern>)` | Same as `regex` but with `setWholeWordsOnly(true)` | `setRegExp`, `setWholeWordsOnly`, `setInvertRegExp` | leading `*` arg → `withinHierarchy` | yes | — |
| `exprtype(<types>)` | Expression's resolved type matches one of `\|`-separated FQNs | `setExpressionTypes`, `setInvertExprType`, `setExprTypeWithinHierarchy` | leading `*` → within hierarchy; leading `~` → arg is a regex (stored via `setNameOfExprType` instead) | yes | language-profile must accept (`isApplicableConstraint`) |
| `formal(<types>)` | Expected/formal-argument type at this position matches | `setExpectedTypes`, `setInvertFormalType`, `setFormalArgTypeWithinHierarchy` | leading `*` → within hierarchy | yes | Java-style only |
| `script("<groovy code>")` | Groovy script filter; node bound as the variable name; `__context__` available | `setScriptCodeConstraint` | none | **no** (line 456) | — |
| `contains("<sub-template>")` | Subtree contains a match of `<sub-template>` | `setContainsConstraint`, `setInvertContainsConstraint` | none | yes | — |
| `within("<sub-template>")` | Whole template lives inside a match of `<sub-template>` | `setWithinConstraint`, `setInvertWithinConstraint` | none | yes | **only on `__context__`** (line 463-465) |
| `context(<template-name>)` | Whole template runs in context of named template | `setContextConstraint` | none | **no** (line 470) | **only on `__context__`** |
| `_<custom>(<arg>)` | Language-profile-specific extension | `additionalConstraint[<custom>]` | none | **no** (line 477-478) | profile decides what to do |

### 3.4 Combining options

Source lines 380-394: multiple options on the same variable are joined with `&&`:

```
'_x:[regex(foo) && exprtype(*Number) && !script("x.parent != null")]
```

Whitespace separates options. **`||` is not supported** — only AND. To express OR you
need separate templates or a script filter that does the OR internally.

### 3.5 Implicit `__context__` variable

`StringToConstraintsTransformer.java:39` always adds a hidden variable named
`__context__` to every parsed template. It refers to the entire matched element —
useful targets:

- `:[within("…")]` — only allowed on `__context__` (whole match must be inside the
  given outer pattern).
- `:[context(<saved-template>)]` — only allowed on `__context__`.
- `__context__` is also accessible inside `script("…")` filters as the bound PSI element
  for the whole match.

Inline `__context__` constraints are written by starting the template with a `[…]`
block (line 45-49 — the special opening bracket form).

## 4. Language profiles registered at runtime (live enumeration)

`StructuralSearchProfile.EP_NAME.extensionList` returned **10 profiles** in this IDE
build (which has the standard IDEA plugin set + Go + Kotlin + SQL):

| # | Profile FQN | Default file type | Shorten FQN | Static import | Replace handler | Predefined templates | Pattern contexts |
|---|---|---|---:|---:|---|---:|---|
| 0 | `com.intellij.structuralsearch.XmlStructuralSearchProfile` | (n/a) | no | no | `XmlReplaceHandler` | **10** | (none) |
| 1 | `com.intellij.lang.properties.structuralsearch.PropertiesStructuralSearchProfile` | (n/a) | no | no | `DocumentBasedReplaceHandler` | 2 | (none) |
| 2 | `o.j.k.idea.k2.codeinsight.structuralsearch.KotlinStructuralSearchProfile` | Kotlin | **yes** | no | `KotlinStructuralReplaceHandler` | **34** | `default`, `property` |
| 3 | `com.intellij.structuralsearch.JavaStructuralSearchProfile` | JAVA | **yes** | **yes** | (init quirk in probe) | **98** | `default`, `member` |
| 4 | `com.intellij.sql.structuralsearch.SqlStructuralSearchProfile` | (n/a) | no | no | `DocumentBasedReplaceHandler` | 3 | (none) |
| 5 | `com.intellij.structuralsearch.groovy.GroovyStructuralSearchProfile` | (n/a) | no | no | `DocumentBasedReplaceHandler` | 0 | `File`, `Class` |
| 6 | `com.intellij.structuralsearch.extenders.CssStructuralSearchProfile` | (n/a) | no | no | `DocumentBasedReplaceHandler` | 0 | (none) |
| 7 | `com.intellij.structuralsearch.JSStructuralSearchProfile` | (n/a) | no | no | `DocumentBasedReplaceHandler` | **13** | (none) |
| 8 | `com.goide.structuralsearch.GoStructuralSearchProfile` | Go | no | no | `DocumentBasedReplaceHandler` | **21** | (none) |
| 9 | `com.intellij.structuralsearch.extenders.JSStructuralSearchProfile2` | (n/a) | no | no | `DocumentBasedReplaceHandler` | 0 | (none) |

Corrections to the prior synthesis based on this live data:

- **Java has 98 predefined templates**, not the 12 quoted in A3 — the `getCustomPredicates`-only sample in A3 was non-exhaustive.
- **Java has TWO pattern contexts: `default` AND `member`** — A1 listed only "default"; A2 mentioned `member` in passing.
- **Kotlin K2 ships 34 predefined templates** (the K1 module is not in the running classpath because `KotlinPluginMode.K2` is active in this IDE).
- **PHP profile is NOT in this build** (PHP plugin not installed). When PHP plugin is loaded, `PhpStructuralSearchProfile` joins the EP list (per A5 source-tree evidence).
- **The `defaultFileType` returns `null` for several profiles** when called with a `null` `LanguageFileType` argument — they only resolve a default file type given a context (e.g. `null` argument doesn't probe the registered alias). This is an artefact of the API, not a missing feature.

## 5. Custom (`_`-prefixed) macros per profile

These are language-profile extensions that the universal transformer routes via
`putAdditionalConstraint`. They are NOT in the global `knownOptions` set. Source-grounded
list (cross-referenced from wave-1 facts):

### Kotlin (K1 + K2) — both modules register the same set

| Custom macro | Effect | Source |
|---|---|---|
| `_AlsoMatchVal(ENABLED)` | A `var` pattern also matches `val` declarations | `KotlinFilterProvider`, applied via `KotlinAlsoMatchValVarPredicate` |
| `_AlsoMatchVar(ENABLED)` | A `val` pattern also matches `var` declarations | same |
| `_AlsoMatchCompanionObject(ENABLED)` | A non-companion `object` pattern also matches a companion object | `KotlinAlsoMatchCompanionObjectPredicate` |
| `_MatchCallSemantics(ENABLED)` | Argument-order-insensitive matching for `KtCallElement` (positional ↔ named argument matching) | `KotlinMatchCallSemantics` (no-op as predicate; the matcher visitor implements the semantics) |

These show up in shipped Kotlin templates such as:

```
'_:[_MatchCallSemantics(ENABLED)](true, 0, 1)         // matches A(true, 0, 1), A(b=true, c=0, d=1), …
var '_x:[_AlsoMatchVal(ENABLED)] = '_init             // also matches val declarations
object '_o:[_AlsoMatchCompanionObject(ENABLED)] {…}   // also matches companion objects
```

### Java

No `_`-prefixed custom macros via `putAdditionalConstraint` were observed. Java's
profile-specific predicates (`ExprTypePredicate`, `FormalArgTypePredicate`) are wired to
the standard `exprtype` / `formal` macros instead.

### Other profiles

XML, JS, SQL, Go, Groovy, CSS, properties: no `_`-prefixed custom macros observed in
their predefined templates. Custom predicates exist in source but don't surface as named
macros — they are wired to applicability tables.

## 6. Per-variable constraint flags reachable from `MatchVariableConstraint`

Cross-reference of every getter/setter pair on the constraint object that an agent can
read or programmatically set, derived from the macro decoder above:

| Getter | Setter | Macro it backs |
|---|---|---|
| `getRegExp` | `setRegExp` | `regex(…)` (and `regexw`) |
| `isWholeWordsOnly` | `setWholeWordsOnly` | `regexw(…)` |
| `isInvertRegExp` | `setInvertRegExp` | `:[!regex(…)]` or trailing `'_x:!<pattern>` |
| `isWithinHierarchy` | `setWithinHierarchy` | text hierarchy: `regex(*…)` arg or trailing `'_x:*<…>` |
| `isStrictlyWithinHierarchy` | `setStrictlyWithinHierarchy` | trailing `'_x:+<…>` |
| `getExpressionTypes` | `setExpressionTypes` | `exprtype(<types>)` (literal form) |
| `getNameOfExprType` | `setNameOfExprType` | `exprtype(~<regex>)` |
| `isExprTypeWithinHierarchy` | `setExprTypeWithinHierarchy` | `exprtype(*…)` arg |
| `isInvertExprType` | `setInvertExprType` | `:[!exprtype(…)]` |
| `getExpectedTypes` | `setExpectedTypes` | `formal(…)` |
| `isFormalArgTypeWithinHierarchy` | `setFormalArgTypeWithinHierarchy` | `formal(*…)` arg |
| `isInvertFormalType` | `setInvertFormalType` | `:[!formal(…)]` |
| `getReferenceConstraint` | `setReferenceConstraint` | `ref(<template-name>)` |
| `isInvertReference` | `setInvertReference` | `:[!ref(…)]` |
| `getScriptCodeConstraint` | `setScriptCodeConstraint` | `script("…")` |
| `getContainsConstraint` | `setContainsConstraint` | `contains("…")` |
| `isInvertContainsConstraint` | `setInvertContainsConstraint` | `:[!contains(…)]` |
| `getWithinConstraint` | `setWithinConstraint` | `within("…")` (only on `__context__`) |
| `isInvertWithinConstraint` | `setInvertWithinConstraint` | `:[!within(…)]` |
| `getContextConstraint` | `setContextConstraint` | `context(<template-name>)` |
| `getMinCount` / `getMaxCount` | `setMinCount` / `setMaxCount` | quantifiers `?`, `+`, `*`, `{n,m}` |
| `isGreedy` | `setGreedy` | trailing `?` after quantifier |
| `isPartOfSearchResults` | `setPartOfSearchResults` | "this variable is the target" — first `'name` (without leading `_`) automatically becomes target |
| `getAdditionalConstraint` (map) | `putAdditionalConstraint(name, value)` | `_<custom>(…)` macros |

This means an agent script that wants to construct a `MatchOptions` purely
programmatically (without ever building the apostrophe-form string) can call these
setters directly. The two paths are equivalent.

## 7. Empirical canonical recipe (works in this IDE today)

Verified end-to-end during this research session with `steroid_execute_code` against
the `mcp-steroid` project:

```kotlin
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.structuralsearch.MatchOptions
import com.intellij.structuralsearch.Matcher
import com.intellij.structuralsearch.plugin.util.CollectingMatchResultSink

val opts = MatchOptions().apply {
    setFileType(JavaFileType.INSTANCE)
    fillSearchCriteria("System.out.println('_msg:[exprtype( java\\.lang\\.String )]);")
    setScope(GlobalSearchScope.projectScope(project))
}
// Validate and search (Matcher manages its own read actions internally; do NOT wrap)
val sink = CollectingMatchResultSink()
Matcher(project, opts).findMatches(sink)
println("matches=${sink.matches.size}")
```

For replacement, see `SYNTHESIS.md` §4 + A6's r01 entry on the bus — the threading
caveats (don't wrap `findMatches` in outer `readAction`; use `Replacer.replace(info)`
singular from a coroutine; wrap all replacements in one `CommandProcessor.executeCommand`)
all hold and are independently confirmed by the wave-1 source-trace.

## 8. Skill-article take-aways (input to wave 3)

1. **Lead with the PSI-tree mental model.** Every other piece of the language follows
   from "templates are real PSI subtrees with placeholder identifiers". Show the
   `class '_C extends '_Base {}` → `PsiClassImpl(class __$_C extends __$_Base {})` dump
   verbatim — it converts confusion into intuition.
2. **Document the apostrophe vs dollar form explicitly.** The skill must say:
   > "When you write a template in any string passed to `fillSearchCriteria`, in saved
   > XML, or in a predefined template, **use the apostrophe form `'_x`**. The dollar
   > form `$x$` you see in JetBrains help docs is the *compiled* form that the dialog
   > round-trips through. The apostrophe form is the only one whose `:[…]` constraint
   > syntax is understood."
3. **Macro reference is finite — list it once.** §3.3 above is the complete table; an
   agent that has it in context can build any constraint without trial and error.
4. **Custom `_`-prefixed macros are language-specific.** The skill should list per-
   profile customs (Kotlin's four are the canonical example) and explicitly note that
   `_<anything>(…)` is the escape hatch — you can't add new global macros from outside
   the platform jar.
5. **`__context__` is the implicit variable.** Always present, target of `within(…)`
   and `context(…)`, accessible in `script("…")` filters.
6. **Quantifiers map mechanically to `min/max`.** `*` → `(0, MAX)`, `+` → `(1, MAX)`,
   `?` → `(0, 1)`, `{n,m}` → `(n, m)`.
7. **Pattern context matters for some profiles.** Java `default` vs `member`, Kotlin
   `default` vs `property`, Groovy `File` vs `Class`. The skill should show one
   non-default-context example (e.g. Kotlin "Properties with explicit getter" template
   under the `property` context).

## 9. Loose ends discovered (for the wave-2 reviewers)

- **Profile #3 (Java) `getReplaceHandler` threw** when called with an empty
  `ReplaceOptions()` during runtime probe. Worth verifying whether this is a
  legitimate API contract (Java's replace handler requires a populated
  `ReplaceOptions`) or a regression. Not blocking — the recipe path
  (`ReplaceOptions(matchOptions).apply { … }`) does populate it.
- **`MatchOptions.fillSearchCriteria` accepts the dollar form too**, but the inline
  `:[…]` constraint syntax is silently dropped — `'_x:[regex(foo)]` works,
  `$x$:[regex(foo)]` would not. This needs an empirical confirmation on the live IDE
  before the skill ships.
- **`ConfigurationManager.getInstance(project).getAllConfigurations()` lists** every
  saved/predefined template in this IDE. If the skill ever wants to expose
  "list templates" as a recipe, that's the API; we did not enumerate it in this turn.
