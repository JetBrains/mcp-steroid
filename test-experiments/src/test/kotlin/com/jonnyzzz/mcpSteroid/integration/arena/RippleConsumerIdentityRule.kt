/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * How a hidden consumer may name the identity its case transforms away.
 *
 * **The trap this closes.** Every case's prompt orders that no use of the old identity survive, so a
 * thorough agent text-searches the tree for it. The hidden consumer is a file the agent is never told
 * about and whose modification VOIDS the arm, so an old name spelled out there turns diligence into a
 * disqualification — build 1031230755, pilot pass 3, claude+mcp. Worse than a plain bug on two counts:
 * it is intermittent, so it voids the careful arms and keeps the careless ones, and it is
 * self-inflicted, since [RippleNameEscapeRule] already refuses a TARGET whose name is addressable as a
 * string while our own overlay introduced exactly such a string.
 *
 * **The rule, in the same words [findings] enforces.** For each of its kind's
 * [RippleTarget.oldIdentitySearchTokens], a consumer must satisfy BOTH:
 *
 * 1. the token appears on no line of the patch contiguously — code, comment and message alike, because
 *    a text search does not care which it lands in; and
 * 2. after adjacent string fragments are joined and each constant helper is inlined at its call sites,
 *    the token appears on a line whose CODE — comments blanked, string contents blanked — performs a
 *    [nameResolvingCalls] lookup, and that line sits inside a `@Test` method or a method reachable
 *    from one.
 *
 * **What this guarantees, exactly.** The token and a lookup call-site appear on one joined line inside
 * a method the test can reach. That proves the assembled name is passed to something that resolves it
 * at runtime, so deleting the lookup breaks the guard — which is the property
 * `RippleCaseRegistryTest.every consumer passes on its lookup and not on its message` ablates and
 * asserts for all seven. It does NOT prove the lookup's result is asserted on, that the call is on a
 * taken branch, or that its exception is not swallowed. Those are the acknowledged boundary of a
 * textual rule (below), and a positive control that reaches them is caught by the probe rather than
 * here.
 *
 * **This guard has been defeated three times in review, and every bypass is a fixture in
 * `RippleCaseRegistryTest`.** That is the only reason each version can be called stronger rather than
 * merely newer, and it is why a fourth relaxation arriving without a fixture of its own should be
 * refused.
 *
 * - *Version 1* asked only that the token appear once the fragments were joined. Defeated by a dead
 *   helper: assemble the name, never call it, assert `true`.
 * - *Version 2* additionally required a lookup on the token's line, anywhere in the file. Defeated by a
 *   decoy: `private static void decoyLookupNeverCalled() { Class.forName(oldFqn()); }`.
 * - *Version 3* additionally required reachability from a `@Test` method — but scanned lines whose
 *   STRING CONTENTS were still live text. Defeated four ways at once, the sharpest needing no helper,
 *   no dead code and no reflection at all:
 *   `assertTrue(true, "Class.forName(" + "org.keycloak.tests.utils." + "Key" + "Utils" + ") must not resolve")`.
 *   The same channel resurrected the version-2 decoy — a string merely MENTIONING
 *   `decoyLookupNeverCalled()` made the dead method read as called.
 *
 * All three defeats are one defect: asking whether the right TEXT exists rather than whether the
 * assertion depends on it. Version 3's KDoc claimed message shapes could not qualify because assertion
 * wrappers were off the whitelist; that claim was FALSE as shipped, because the bypass never needed
 * `assertTrue(` to be whitelisted — it needed `Class.forName(` to survive inside a string. What makes
 * the claim true is [withoutStringContents], not the whitelist.
 *
 * **Direction: this rule errs strict, deliberately.** A false rejection is loud and [remedy] tells the
 * author what to write. A false acceptance produces a silently inert oracle that reads green
 * downstream, and nothing else in the harness catches it — the tamper check catches an EDITED consumer,
 * never a vacuous one. Every other guard in this family errs strict; this is the one that chose
 * leniency, and the one where leniency costs most. When a shape is ambiguous, reject it.
 *
 * **The boundary, recorded rather than chased.** Two bypasses are out of scope for a textual rule and
 * are not defects to fix here: a real lookup inside a never-taken branch, and a real lookup whose
 * exception is swallowed. Both need control- and data-flow, which means PSI over a compilable file
 * rather than a regex over a patch. They are why [findings] returning nothing is evidence about the
 * consumer's SHAPE, and the agentless probe — which runs the consumer and reads what threw — is the
 * evidence about its behaviour.
 */
object RippleConsumerIdentityRule {

    /**
     * Calls that resolve a name at RUNTIME, which is the only way a consumer can name an identity that
     * exists on just one side of the transformation.
     *
     * The family uses two of these today — `Class.forName(` for the type-level kinds and `getMethod(`
     * for the method-level ones. The rest are the same reflective family, listed so a future consumer
     * that reaches for the obvious neighbour is not failed for a distinction without a difference.
     * Matched only against code with string contents blanked, so the same text inside a message is not
     * a call.
     */
    val nameResolvingCalls: List<String> = listOf(
        "Class.forName(",
        "getMethod(",
        "getDeclaredMethod(",
        "getField(",
        "getDeclaredField(",
        "getConstructor(",
        "getDeclaredConstructor(",
    )

    /** What to do about a finding, appended to the failure so the reader does not have to guess. */
    val remedy: String = "Assemble the name from fragments on one line — `\"get\" + \"Name\"` — and pass " +
        "it to a reflective lookup that the @Test method itself runs, directly or through a helper it calls."

    /** Everything wrong with one consumer's spelling of one token; empty means the consumer is fine. */
    fun findings(patch: String, token: String): List<String> {
        val spelled = patch.lines().withIndex()
            .filter { it.value.contains(token) }
            .map { "spelled contiguously at line ${it.index + 1}: ${it.value.trim()}" }

        // The token is read from the resolved text, where it legitimately lives INSIDE a string literal:
        // that is what a reflective lookup takes. Everything that decides whether a line CALLS anything
        // is read from `code`, where string contents are gone, so a message quoting a call is not one.
        val resolved = resolveConstants(joinJavaLiteralFragments(patch))
        val code = withoutStringContents(withoutComments(resolved))
        val methods = declaredMethods(code, resolved)
        val reachable = reachableFromTests(code, methods)

        val lookups = code.indices.filter { line ->
            resolved[line].contains(token) && nameResolvingCalls.any { code[line].contains(it) }
        }
        val executed = lookups.filter { line -> methods.any { it.holds(line) && it.name in reachable } }

        val unused = when {
            executed.isNotEmpty() -> emptyList()
            lookups.isEmpty() -> listOf(
                "names '$token' through no runtime lookup: with its fragments joined, its constant " +
                    "helpers inlined and its comments and string contents blanked, no line both holds " +
                    "the token and CALLS one of ${nameResolvingCalls.joinToString(", ")} — so the " +
                    "assertion would report the same verdict whether or not the old identity survived"
            )
            else -> listOf(
                "looks up '$token' only from " +
                    "${lookups.map { line -> methods.firstOrNull { it.holds(line) }?.name ?: "<no method>" }}" +
                    ", which no @Test method reaches: the lookup is dead code, so the assertion would " +
                    "report the same verdict whether or not the old identity survived"
            )
        }
        return spelled + unused
    }

    /**
     * Every line with `helper()` replaced by the constant that `private static String helper()` returns.
     *
     * Textual on purpose: this runs over a PATCH, which is not a compilable file and has a `+` on every
     * line, so there is no PSI to ask. It recognises exactly the shape the family's consumers use — a
     * no-argument private static String helper whose body is a single string `return` — and nothing
     * cleverer, because a helper this rule cannot follow must fail the guard rather than pass it by
     * accident.
     */
    private fun resolveConstants(joined: String): List<String> {
        val declaration = Regex("""private\s+static\s+String\s+(\w+)\s*\(\s*\)""")
        val returned = Regex("""return\s+"([^"]*)"\s*;""")
        val lines = joined.lines()
        val constants = LinkedHashMap<String, String>()
        lines.forEachIndexed { index, line ->
            val name = declaration.find(line)?.groupValues?.get(1) ?: return@forEachIndexed
            val body = lines.drop(index).take(HELPER_BODY_LINES)
                .firstNotNullOfOrNull { returned.find(it)?.groupValues?.get(1) }
            if (body != null) constants[name] = body
        }
        return lines.map { line ->
            if (declaration.containsMatchIn(line)) line
            else constants.entries.fold(line) { acc, (name, value) -> acc.replace("$name()", "\"$value\"") }
        }
    }

    /**
     * The same lines with comment text blanked out.
     *
     * Braces and calls inside a comment are not code, and the family's own consumers carry `{@link X}`
     * in their KDoc — counting that brace would misplace every method boundary after it. Run BEFORE
     * [withoutStringContents], since a comment may contain an unbalanced quote.
     */
    private fun withoutComments(lines: List<String>): List<String> {
        var inBlock = false
        return lines.map { line ->
            var text = line
            if (inBlock) {
                val close = text.indexOf("*/")
                if (close < 0) return@map "" else {
                    inBlock = false
                    text = text.substring(close + 2)
                }
            }
            val open = text.indexOf("/*")
            if (open >= 0) {
                val close = text.indexOf("*/", open)
                text = if (close >= 0) text.removeRange(open, close + 2) else {
                    inBlock = true
                    text.substring(0, open)
                }
            }
            text.substringBefore("//")
        }
    }

    /**
     * The same lines with the CONTENTS of every string literal emptied, `"anything"` becoming `""`.
     *
     * This is what makes the rule's claim about messages true. A failure message is text, and every
     * consumer interpolates its assembled name into one; without this step a message reading
     * `"Class.forName(" + name + ") must not resolve"` is, after the fragments are joined,
     * indistinguishable from a reflective call — which is how version 3 was defeated four ways, none of
     * them needing an actual lookup. Blanking also removes braces and `foo()` mentions from strings, so
     * neither method boundaries nor the call graph can be steered by prose.
     *
     * Only call detection reads this; token detection reads the unblanked text, because a lookup's
     * argument IS a string literal and blanking it there would reject every honest consumer.
     */
    private fun withoutStringContents(lines: List<String>): List<String> {
        val literal = Regex(""""(?:\\.|[^"\\])*"""")
        return lines.map { line -> literal.replace(line, "\"\"") }
    }

    /**
     * The methods a consumer declares, by name and line range, and whether `@Test` sits above each.
     *
     * A method is recognised only at class level and only when its signature ends the line with `{`,
     * which is the shape every consumer uses; a one-line helper body is left as an ordinary line, since
     * [resolveConstants] is what reads those and a lookup has never been written that way.
     */
    private fun declaredMethods(code: List<String>, original: List<String>): List<JavaMethod> {
        val signature = Regex("""\b(\w+)\s*\([^;]*\)\s*(?:throws\s+[\w.,\s]+)?\{\s*$""")
        val methods = ArrayList<JavaMethod>()
        var depth = 0
        var pendingTest = false
        var index = 0
        while (index < code.size) {
            val line = code[index]
            if (original[index].contains("@Test")) pendingTest = true
            val name = if (depth == 1) signature.find(line)?.groupValues?.get(1) else null
            if (name != null) {
                var balance = 0
                var end = index
                while (end < code.size) {
                    balance += code[end].count { it == '{' } - code[end].count { it == '}' }
                    if (balance == 0 && end > index) break
                    end++
                }
                methods.add(JavaMethod(name, index..minOf(end, code.size - 1), pendingTest))
                pendingTest = false
                index = end + 1
                continue
            }
            depth += line.count { it == '{' } - line.count { it == '}' }
            index++
        }
        return methods
    }

    /**
     * Every method a `@Test` method can reach, by name, closed transitively over calls in blanked code.
     *
     * Transitive rather than one level deep: one level is what the consumers need today, and a rule
     * that stopped there would reject an honest two-step helper chain for no reason the reader could
     * defend. What it must never admit is a method NOTHING calls — including one merely NAMED inside a
     * string, which is how the version-2 decoy was resurrected against version 3.
     */
    private fun reachableFromTests(code: List<String>, methods: List<JavaMethod>): Set<String> {
        val reachable = methods.filter { it.isTest }.map { it.name }.toMutableSet()
        var growing = true
        while (growing) {
            growing = false
            for (caller in methods.filter { it.name in reachable }) {
                for (candidate in methods.filter { it.name !in reachable }) {
                    val called = Regex("""\b${Regex.escape(candidate.name)}\s*\(""")
                    if (caller.range.any { called.containsMatchIn(code[it]) }) {
                        reachable.add(candidate.name)
                        growing = true
                    }
                }
            }
        }
        return reachable
    }

    /** One method of a consumer: its name, the lines it spans, and whether it is a `@Test`. */
    private data class JavaMethod(val name: String, val range: IntRange, val isTest: Boolean) {
        fun holds(line: Int): Boolean = line in range
    }

    /** How far past a helper's signature its `return` may sit; the family writes it on the next line. */
    private const val HELPER_BODY_LINES = 4
}

/**
 * Joins adjacent Java string-literal fragments: `"a" + "b"` becomes `"ab"`.
 *
 * Only a `+` between two literals ON ONE LINE is joined, which is the shape the family's consumers
 * use. A concatenation split across lines is deliberately NOT joined: a patch body carries a leading
 * `+` on every line, so a multi-line concat cannot be recognised without guessing, and a consumer
 * written that way must fail the guard rather than pass it by accident.
 */
fun joinJavaLiteralFragments(text: String): String =
    text.lines().joinToString("\n") { line -> line.replace(Regex("""" *\+ *""""), "") }
