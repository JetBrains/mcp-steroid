/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * One place a declaration is named by a STRING rather than by a symbol.
 *
 * [kind] is what the finding is, not how bad it is: a caller decides whether a bare literal is a
 * coincidence, but a caller cannot decide what it never saw.
 */
data class LiteralNameLookup(
    val kind: LiteralNameLookupKind,
    val path: String,
    val line: Int,
    val text: String,
)

/** The shapes [RippleNameEscapeRule] recognises, ordered from the most conclusive to the weakest. */
enum class LiteralNameLookupKind {
    /**
     * `<call>(<Type>.class, "<name>")` — a type plus a member name, which is how JAX-RS'
     * `UriBuilder.path(Class, String)` and `UriBuilder.fromMethod(Class, String)` address a method.
     * Conclusive: the pair exists to resolve a member by name.
     */
    CLASS_AND_NAME_PAIR,

    /** `getMethod("<name>"` / `getDeclaredMethod("<name>"` — reflection naming a method directly. */
    REFLECTIVE_METHOD_LOOKUP,

    /**
     * The name as a bare string literal anywhere. Weakest and noisiest, and still required: the two
     * shapes above are the idioms this repository happens to use today, and a benchmark target must
     * survive the ones it does not use yet.
     */
    BARE_STRING_LITERAL,
}

/**
 * The behaviour-preservation rule for a name that a transformation changes.
 *
 * **Why this exists.** The pilot case renamed `RealmResource.roles()` on the recorded premise that the
 * method's `@Path("roles")` annotation carried the HTTP contract, so the Java name was free. Two files
 * — `tests/utils/.../AdminEventPaths.java` and
 * `testsuite/integration-arquillian/tests/base/.../AdminEventPaths.java` — call
 * `UriBuilder.fromUri("").path(RealmResource.class, "roles")`, and RESTEasy resolves that second
 * argument as a METHOD NAME. After the rename it throws
 * `RESTEASY003645: No public @Path annotated method for org.keycloak.admin.client.resource.RealmResource.roles`.
 * Neither the compiler nor a PSI reference search sees a string literal, so an arm was graded a
 * perfect rename — P1 to P4 true, recall 1.0, precision 1.0, no missed sites, compile gate PASS,
 * hidden consumer green — over broken runtime behaviour.
 *
 * **The rule.** Before a case is pinned, the transformed name must be shown to appear in no string
 * literal in the tree at the base commit. The earlier version of this rule checked non-`.java` files,
 * `Class.forName` and `getMethod`; the shape that slipped through was inside `.java`, was not
 * reflection, and named the method as the second argument of a JAX-RS call. So the rule is now
 * stated over string literals in general rather than over a list of blessed idioms.
 *
 * **Where it is enforced.** Three places, deliberately, because prose in a report is deleted and
 * prose in a KDoc is skipped:
 *
 * 1. `RippleTargetSurveyScripts.survey` measures `SURVEY_STRING_LITERAL_NAMES` for every wide
 *    rename-method candidate through the string index, and excludes any JAX-RS-annotated method from
 *    the rename-method kind outright — an annotated method is exactly the population
 *    `path(X.class, "name")` can address.
 * 2. `RenameMethod.captureFragment` re-measures it inside the arm and REPORTS every file holding such
 *    a literal as `GOLD_STRING_LITERAL_NAME`; `SemanticGold.checkTripwires` is what REFUSES to grade,
 *    and it checks this first, before the count tripwires, because it is the only one about whether
 *    the case is well-posed rather than about whether the measurement still matches. The split is
 *    deliberate: the script reports per file and the Kotlin side judges, because only the case knows
 *    which files are its own overlay — the hidden consumer names the method reflectively on purpose.
 * 3. [gitGrepCommands] states the offline confirmation a case author runs against the bare repository
 *    at the pinned commit, and [lookupsNaming] is that confirmation as code.
 */
object RippleNameEscapeRule {

    /**
     * Every literal naming of [name] in one file's text.
     *
     * Line-based rather than PSI-based on purpose: this runs against a bare repository at a pinned
     * commit with no IDE, which is the only place a case author can check a candidate BEFORE paying
     * for a container.
     */
    fun lookupsNaming(name: String, path: String, content: String): List<LiteralNameLookup> {
        val quoted = Regex.escape(name)
        val classAndName = Regex("""\(\s*[\w.]+\.class\s*,\s*"$quoted"\s*[,)]""")
        val reflective = Regex("""\bget(?:Declared)?Method\s*\(\s*"$quoted"\s*[,)]""")
        val bare = Regex(""""$quoted"""")
        return content.lines().withIndex().flatMap { (index, text) ->
            val kind = when {
                classAndName.containsMatchIn(text) -> LiteralNameLookupKind.CLASS_AND_NAME_PAIR
                reflective.containsMatchIn(text) -> LiteralNameLookupKind.REFLECTIVE_METHOD_LOOKUP
                bare.containsMatchIn(text) -> LiteralNameLookupKind.BARE_STRING_LITERAL
                else -> null
            }
            if (kind == null) emptyList()
            else listOf(LiteralNameLookup(kind, path, index + 1, text.trim()))
        }
    }

    /**
     * The offline confirmation, as commands, to be run inside the bare repository at
     * [SemanticRippleSpec.baseCommit].
     *
     * Returned as strings rather than executed because the bare repository is a build-directory
     * cache: it exists on a developer machine that has already run the family once, and never on a
     * fresh CI agent. A test that ran these would be a test that passes by not finding the repository.
     */
    fun gitGrepCommands(name: String, commit: String = SemanticRippleSpec.baseCommit): List<String> = listOf(
        """git grep -nE '\([A-Za-z0-9_.]+\.class,[ ]*"$name"' $commit""",
        """git grep -nE 'get(Declared)?Method\([ ]*"$name"' $commit""",
        """git grep -nE '"$name"' $commit""",
    )
}
