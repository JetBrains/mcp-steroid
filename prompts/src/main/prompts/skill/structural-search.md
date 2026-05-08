Structural Search and Replace — overview and index

What IntelliJ SSR is, when to reach for it, what every supported language profile shares, and pointers to the per-topic articles.

# Structural Search and Replace (SSR)

Structural Search and Replace is IntelliJ's PSI-aware grep/sed: a matching engine that understands the syntax and semantics of the source code, not its raw bytes. Use it when:

- A textual `grep` returns too many false positives (e.g. "find every `foo()` call but not the `foo` field).
- A regex would have to encode a multi-line code shape (`if (...) { ... } else { ... }`).
- You need a refactor that the standard *Find Usages* / *Rename* refactorings can't express directly (e.g. "replace `Lists.newArrayList()` with `new ArrayList<>()` everywhere except module X").
- You want to ship a project-level inspection (`SSBasedInspection`) without writing a plugin.

If your edit is mechanical and structural — same shape, different identifiers, different overload — SSR is the right tool. If it is one-off or text-only, regular Find/Replace is faster.

## Quickstarts — required reading per task type

- **Java search-only audit** (e.g. `Optional.get()`, `System.out.println` callsite finders): required = [api-recipe](mcp-steroid://skill/structural-search-api-recipe) + [syntax](mcp-steroid://skill/structural-search-syntax) + [use-cases](mcp-steroid://skill/structural-search-use-cases). Background-only = coverage and Kotlin articles. Don't read those first.
- **Authoring a Kotlin pattern**: required = [structural-search-kotlin](mcp-steroid://skill/structural-search-kotlin) for the canonical recipe with imports, `setRecursiveSearch(true)`, `setSearchInjectedCode(false)`. Skim api-recipe and syntax only if you need the rules behind the recipe.
- **Kotlin single-file audit**: required reading order = this overview → [api-recipe § Single-file scope (LocalSearchScope)](mcp-steroid://skill/structural-search-api-recipe) → [syntax](mcp-steroid://skill/structural-search-syntax) → [structural-search-kotlin](mcp-steroid://skill/structural-search-kotlin). LocalSearchScope avoids the project-wide indexing cost when the target file is known.
- **Java search + replace**: [api-recipe](mcp-steroid://skill/structural-search-api-recipe) is mandatory (covers validation, `Replacer.replace(info)` vs `replaceAll`, `CommandProcessor.executeCommand`, smart-pointer invalidation). Then [use-cases](mcp-steroid://skill/structural-search-use-cases) for ready-to-paste search/replace pairs.
- **Hierarchy / "find implementors / subclasses" task**: required = [use-cases § F](mcp-steroid://skill/structural-search-use-cases) + [syntax § "Java `implements` matches both"](mcp-steroid://skill/structural-search-syntax). The `:*Type` modifier widens to transitive hierarchy in one template; you do NOT need separate `implements` and `extends` patterns.
- **Just need to check which profiles are available, or enumerate predefined templates?** → jump to [coverage § Programmatic enumeration of all profiles](mcp-steroid://skill/structural-search-coverage). The full api-recipe is overkill for read-only introspection.
- **First time with SSR?** → read this overview top-to-bottom, then [syntax](mcp-steroid://skill/structural-search-syntax) for the template language.

## The mental model — templates are PSI trees

The single fact that explains everything else: an SSR template is real source code in the chosen language with placeholder identifiers (variables). When you call `MatchOptions.fillSearchCriteria(...)`, the language profile parses your template into an actual PSI subtree. Variable placeholders become `PsiIdentifier`-style leaves with internal prefixes (`__$_x` in Java, `_____x` in Kotlin). The matcher walks both pattern PSI and candidate PSI in parallel and binds each variable to the candidate subtree it matches.

This is why SSR is **not** regex over text — and why the template language is exactly the language you are searching in.

## Three pieces every recipe touches

1. **Template language** — variable forms (`'_x` and `$x$`), the nine inline macros (`regex`, `exprtype`, `formal`, `ref`, `script`, `contains`, `within`, `context`, `regexw`), quantifiers, custom `_<extension>` macros. See [Template language and macros](mcp-steroid://skill/structural-search-syntax).
2. **Programmatic API** — `MatchOptions`, `Matcher`, `Replacer`, `MatchResult`, `StructuralSearchProfile`. The canonical Kotlin recipe for `steroid_execute_code` plus threading and validation rules. See [Canonical API recipe](mcp-steroid://skill/structural-search-api-recipe).
3. **Language coverage** — which language profiles ship in IntelliJ, which require a plugin, which support replace/shorten-FQN/static-import, which are stuck on text-only document replace. See [Language coverage](mcp-steroid://skill/structural-search-coverage).

## Safety box — read this before any rewrite

> **Any executable rewrite via `steroid_execute_code` MUST go through [Canonical API recipe](mcp-steroid://skill/structural-search-api-recipe).** That article is non-optional: it covers `Matcher.validate(project, options)` (without it, malformed patterns silently match nothing), the "do not wrap `findMatches` in an outer `readAction`" rule, the `searchInjectedCode=true` default that broadens project scans, the K2 asynchronous FQN-shortening caveat, and the `SmartPsiElementPointer` invalidation rules during multi-match replacement. Recipes copied from this overview without consulting api-recipe will deadlock the EDT, silently emit zero matches, or rewrite the wrong files.

## Search-only first; replace later

Always run a template as search-only first (set `replacement = null`). Inspect the matches, confirm the count matches your expectation, then enable replacement. The `MatchOptions` is a tiny object; rebuilding it twice costs nothing and saves the cost of an undo across N files.

## More articles

- [Template language and macros](mcp-steroid://skill/structural-search-syntax)
- [Canonical API recipe](mcp-steroid://skill/structural-search-api-recipe)
- [Language coverage matrix](mcp-steroid://skill/structural-search-coverage)
- [Use-case gallery](mcp-steroid://skill/structural-search-use-cases)
- [Kotlin SSR — K2 profile, custom filters, limitations](mcp-steroid://skill/structural-search-kotlin)

## Further reading (JetBrains help)

- [Structural search and replace](https://www.jetbrains.com/help/idea/structural-search-and-replace.html) — definition + dialog walkthrough
- [Search templates](https://www.jetbrains.com/help/idea/search-templates.html) — variable + modifier reference
- [Examples](https://www.jetbrains.com/help/idea/structural-search-and-replace-examples.html) — recipe corpus
- [Creating custom inspections](https://www.jetbrains.com/help/idea/creating-custom-inspections.html) — promoting a template to an `SSBasedInspection`
