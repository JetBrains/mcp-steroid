/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * The transformation a ripple case asks for.
 *
 * A variant contributes exactly three things and nothing else: how the target is found and checked
 * (its capture fragment), what is read back about it afterwards (its post-condition fragment), and
 * how the task is stated to the agent. Sites, decoys, conservation, the compile gate and every
 * environment paragraph are shared by the family and live outside this hierarchy — which is why P2,
 * P3 and P4 need no per-kind code at all.
 *
 * A fragment is the body of one `smartReadAction(project)` block, appended to
 * [RippleOracleScripts.preamble]. It must emit the GOLD_ / POST_ line shapes
 * [parseSemanticGold] and [parseSemanticPostcondition] already parse: a variant that invents a new
 * line shape would need its own parser, and the parsers are the part of this family that three
 * cases have proven is genuinely shared.
 */
sealed interface RippleTarget {
    /** Stable id used in reports and instance ids. */
    val kindId: String

    /** Human-readable identity of the symbol under transformation, for failure messages. */
    val targetDescription: String

    /**
     * The fully-qualified name of the TYPE the transformation lands on or in.
     *
     * A hidden consumer that sits outside this type's package cannot name it without an import, and
     * a consumer that cannot compile fails the gate for every run whatever the agent did — which is
     * why `RippleCaseRegistryTest` checks the overlay against this.
     */
    val targetTypeFqn: String

    /**
     * Human-readable identity of what the transformation must produce, for failure messages.
     *
     * The tripwire that rejects an already-taken destination names this, so it has to be the thing a
     * reader would search the tree for: a new method name, a new type name, or a new signature.
     */
    val destinationDescription: String

    /** What makes the transformation structurally behaviour-preserving, stated for the reader. */
    val behaviourPreservationEvidence: String

    /** Emits GOLD_TARGET, GOLD_SITE, GOLD_DECOY and GOLD_NEWNAME_DECLS for this target. */
    fun captureFragment(): String

    /** Reads back P1 and any kind-specific POST_ lines. */
    fun postconditionFragment(): String

    /** The `## Task` section of the prompt — the only part of the prompt a kind may change. */
    fun promptTaskSection(): String

    /** Kind-specific predicates, keyed by a stable id, computed from the post-condition output. */
    fun extraPredicates(output: String): Map<String, Boolean> = emptyMap()
}

/**
 * The two PSI scripts behind every ripple oracle, kept as plain strings so the parsing and grading in
 * [SemanticRippleOracle] stays pure and unit-tested.
 *
 * Both start by refreshing the VFS and waiting for smart mode. The post-condition script runs after
 * the agent has edited dozens of files, and a stale index would report a correct transformation as a
 * failed one — or the reverse.
 *
 * The enclosing-declaration key is the nearest named parent of the reference. Line and offset keys
 * would shift with the agent's edits and produce false misses.
 */
object RippleOracleScripts {

    val preamble: String = """
        import com.intellij.openapi.roots.ProjectFileIndex
        import com.intellij.openapi.vfs.VirtualFileManager
        import com.intellij.psi.*
        import com.intellij.psi.search.GlobalSearchScope
        import com.intellij.psi.search.PsiShortNamesCache
        import com.intellij.psi.search.searches.ClassInheritorsSearch
        import com.intellij.psi.search.searches.ReferencesSearch
        import com.intellij.psi.util.PsiTreeUtil

        VirtualFileManager.getInstance().asyncRefresh()
        waitForSmartMode()

        fun siteKey(ref: PsiReference): String? {
            val file = ref.element.containingFile?.virtualFile?.path ?: return null
            val owner = PsiTreeUtil.getParentOfType(ref.element, PsiMethod::class.java, false)
                ?.let { m -> (m.containingClass?.qualifiedName ?: "?") + "#" + m.name }
                ?: PsiTreeUtil.getParentOfType(ref.element, PsiClass::class.java, false)
                    ?.qualifiedName
                ?: "<file>"
            return file + "|" + owner
        }
    """.trimIndent()

    /** Emits GOLD_* lines for the target, its reference sites, every decoy, and the new-name check. */
    fun capture(case: RippleCase): String = preamble + "\n" + case.target.captureFragment()

    /** Emits POST_* lines: the P1 flags, the transformed symbol's sites, and every decoy. */
    fun postcondition(case: RippleCase): String = preamble + "\n" + case.target.postconditionFragment()
}

/** The pilot's kind: a method keeps its owner and its signature, and changes its name. */
data class RenameMethod(
    val targetClassFqn: String,
    val oldName: String,
    val newName: String,
    val returnTypeSimpleName: String,
    override val behaviourPreservationEvidence: String,
) : RippleTarget {

    override val kindId: String get() = "rename-method"

    override val targetDescription: String get() = "$targetClassFqn#$oldName"

    override val destinationDescription: String get() = newName

    override val targetTypeFqn: String get() = targetClassFqn

    override fun captureFragment(): String = """
        smartReadAction(project) {
            val scope = GlobalSearchScope.projectScope(project)
            val cache = PsiShortNamesCache.getInstance(project)
            val all = cache.getMethodsByName("$oldName", scope).toList()
            val target = all.firstOrNull {
                it.containingClass?.qualifiedName == "$targetClassFqn"
            } ?: error("Target $targetClassFqn#$oldName not found")
            check(target.containingClass?.isInterface == true) { "Target owner is not an interface" }
            check(target.annotations.any { it.text.contains("Path") }) {
                "Target has no @Path annotation; the rename would not be behaviour-preserving"
            }

            println("GOLD_TARGET $targetClassFqn|$oldName|$newName")

            val refs = ReferencesSearch.search(target, scope).findAll()
            refs.mapNotNull { siteKey(it) }.groupingBy { it }.eachCount()
                .toSortedMap().forEach { (key, n) -> println("GOLD_SITE " + key + "|" + n) }

            for (decoy in all) {
                if (decoy === target) continue
                val owner = decoy.containingClass?.qualifiedName ?: continue
                println("GOLD_DECOY " + owner + "|" + ReferencesSearch.search(decoy, scope).findAll().size)
            }

            println("GOLD_NEWNAME_DECLS " +
                (cache.getMethodsByName("$newName", scope).size +
                 cache.getClassesByName("$newName", scope).size))
            println("GOLD_END")
        }
    """.trimIndent()

    override fun postconditionFragment(): String = """
        smartReadAction(project) {
            val scope = GlobalSearchScope.projectScope(project)
            val cache = PsiShortNamesCache.getInstance(project)
            val owner = JavaPsiFacade.getInstance(project)
                .findClass("$targetClassFqn", scope)
                ?: error("$targetClassFqn no longer exists")

            val renamed = owner.findMethodsByName("$newName", false).firstOrNull()
            val declaredWithRightType = renamed != null &&
                renamed.returnType?.presentableText == "$returnTypeSimpleName"
            println("POST_NEWNAME_DECLARED " + declaredWithRightType)
            println("POST_OLDNAME_ON_TARGET " +
                owner.findMethodsByName("$oldName", false).size)

            val refs = renamed?.let { ReferencesSearch.search(it, scope).findAll() } ?: emptyList()
            refs.mapNotNull { siteKey(it) }.groupingBy { it }.eachCount()
                .toSortedMap().forEach { (key, n) -> println("POST_SITE " + key + "|" + n) }

            for (decoy in cache.getMethodsByName("$oldName", scope)) {
                val decoyOwner = decoy.containingClass?.qualifiedName ?: continue
                if (decoyOwner == "$targetClassFqn") continue
                println("POST_DECOY " + decoyOwner + "|" +
                    ReferencesSearch.search(decoy, scope).findAll().size)
            }

            println("POST_TOTAL_NEW_REFS " + refs.size)
            println("POST_END")
        }
    """.trimIndent()

    override fun promptTaskSection(): String = """
        Rename the method

            $returnTypeSimpleName $oldName()

        declared on the interface `$targetClassFqn`
        to `$newName`, throughout the whole project.

        Requirements:

        1. Every place that calls this method must call it by its new name. When you are done, no
           caller of this declaration may still use the old name.
        2. The old name must not survive on that interface in any form — not as a second method,
           not as a deprecated forwarder, not as a default method.
        3. Methods that happen to share the same simple name but are declared on **other types**
           are unrelated and MUST keep their current name. Changing one of them is a defect.
        4. External behaviour must not change. The HTTP contract of this endpoint is defined by
           its annotation, not by the Java method name, so a correct rename leaves it untouched.
        5. The project must compile, test sources included, when you are finished.
    """.trimIndent()
}

/** A type keeps its package and changes its simple name; the ripple travels through imports. */
data class RenameType(
    val oldFqn: String,
    val newSimpleName: String,
    override val behaviourPreservationEvidence: String,
) : RippleTarget {

    override val kindId: String get() = "rename-type"

    override val targetDescription: String get() = oldFqn

    override val destinationDescription: String get() = newSimpleName

    override val targetTypeFqn: String get() = oldFqn

    /** The old simple name, which is what a short-names lookup and the decoy set are keyed by. */
    val oldSimpleName: String get() = oldFqn.substringAfterLast('.')

    /** Where the renamed type must land: same package, new simple name. */
    val newFqn: String get() = oldFqn.substringBeforeLast('.') + "." + newSimpleName

    override fun captureFragment(): String = """
        smartReadAction(project) {
            val scope = GlobalSearchScope.projectScope(project)
            val cache = PsiShortNamesCache.getInstance(project)
            val all = cache.getClassesByName("$oldSimpleName", scope).toList()
            val target = all.firstOrNull {
                it.qualifiedName == "$oldFqn"
            } ?: error("Target $oldFqn not found")
            check(
                target.containingFile?.virtualFile
                    ?.let { ProjectFileIndex.getInstance(project).isInContent(it) } == true
            ) {
                "Target is not a project source file (a library class?); the rename oracle cannot " +
                    "validate a class this task does not own"
            }

            println("GOLD_TARGET $oldFqn|$oldSimpleName|$newSimpleName")

            val refs = ReferencesSearch.search(target, scope).findAll()
            refs.mapNotNull { siteKey(it) }.groupingBy { it }.eachCount()
                .toSortedMap().forEach { (key, n) -> println("GOLD_SITE " + key + "|" + n) }

            for (decoy in all) {
                if (decoy === target) continue
                val owner = decoy.qualifiedName ?: continue
                println("GOLD_DECOY " + owner + "|" + ReferencesSearch.search(decoy, scope).findAll().size)
            }

            println("GOLD_NEWNAME_DECLS " +
                (cache.getMethodsByName("$newSimpleName", scope).size +
                 cache.getClassesByName("$newSimpleName", scope).size))
            println("GOLD_END")
        }
    """.trimIndent()

    override fun postconditionFragment(): String = """
        smartReadAction(project) {
            val scope = GlobalSearchScope.projectScope(project)
            val cache = PsiShortNamesCache.getInstance(project)
            val facade = JavaPsiFacade.getInstance(project)

            val renamed = facade.findClass("$newFqn", scope)
            println("POST_NEWNAME_DECLARED " + (renamed != null))
            println("POST_OLDNAME_ON_TARGET " +
                (if (facade.findClass("$oldFqn", scope) != null) 1 else 0))

            val refs = renamed?.let { ReferencesSearch.search(it, scope).findAll() } ?: emptyList()
            refs.mapNotNull { siteKey(it) }.groupingBy { it }.eachCount()
                .toSortedMap().forEach { (key, n) -> println("POST_SITE " + key + "|" + n) }

            for (decoy in cache.getClassesByName("$oldSimpleName", scope)) {
                val decoyOwner = decoy.qualifiedName ?: continue
                if (decoyOwner == "$oldFqn") continue
                println("POST_DECOY " + decoyOwner + "|" +
                    ReferencesSearch.search(decoy, scope).findAll().size)
            }

            println("POST_TOTAL_NEW_REFS " + refs.size)
            println("POST_END")
        }
    """.trimIndent()

    override fun promptTaskSection(): String = """
        Rename the type

            $oldFqn

        to the simple name `$newSimpleName`, keeping it in its current package,
        throughout the whole project.

        Requirements:

        1. Every place that names this type must name it by its new name — declarations, uses,
           imports and the file it lives in. When you are done, no reference to this type may
           still use the old name.
        2. The old name must not survive as an alias of any kind — not as a subtype, not as a
           deprecated empty interface extending the new one.
        3. Leave other types that happen to share the same simple name in other packages
           unchanged — they are unrelated, and renaming one of them is a defect.
        4. External behaviour must not change: this type's name appears in no configuration
           file, no reflective lookup and no service descriptor, so a correct rename is not
           observable from outside the code.
        5. The project must compile, test sources included, when you are finished.
    """.trimIndent()
}

/**
 * A method gains a parameter. Every call site must be updated with a real argument, which no textual
 * substitution can do — and an added overload, which compiles and would satisfy P1–P4, is caught by
 * the arity predicate.
 *
 * **How this kind reads its decoys, and why it does not search them.** The other two kinds spend one
 * `ReferencesSearch` per same-named declaration to prove P3. That is affordable at 16 and at 3
 * decoys; it is not affordable at a thousand, and the same shape of load (one search per candidate,
 * un-memoized) is what exhausted the IDE twice while the family's targets were being surveyed. So the
 * rule here is: **a decoy is read by its declared parameter list, not by its reference count.** Each
 * same-named declaration contributes `GOLD_DECOY <owner>#<name>(<parameter types>)|<arity>`, and P3
 * fails if any of those keys disappears or any arity moves.
 *
 * That is not a weakening of P3 for this kind — it is the sharper test, but only because
 * [parseSemanticPostcondition] compares decoy key SETS rather than looking each gold key up with a
 * zero default. Over-reach means the agent applied THIS transformation to a same-named declaration
 * somewhere else, and for a signature change the transformation lands on the declaration itself: the
 * parameter list is where it shows. Adding a parameter to another `getId` retires the key
 * `X#getId()` and creates `X#getId(boolean)`, and both halves of that are over-reach; renaming or
 * deleting a decoy retires a key too. Under a lookup with a zero default none of it would register,
 * because a no-arg getter's arity IS zero and "absent" would read as "unchanged" — which is exactly
 * how this predicate was inert in its first version. The one shape the arity reading cannot see — a
 * decoy left structurally intact while some caller of it is rewritten — cannot compile unless that
 * decoy's own declaration changed too, so the scoped compile gate covers it. What the change costs is
 * a thousand `ReferencesSearch` calls that would have told us nothing the parameter list does not.
 *
 * **The target's own override family is not a decoy set.** Java requires every implementer of the
 * changed method to take the new parameter, and the prompt orders it, so a CORRECT solution moves
 * those declarations — which under a key-set comparison would read as over-reach and fail every
 * correct run. They are excluded by construction: one `ClassInheritorsSearch` over the target's owner
 * (plus the owners of the methods the target itself overrides) yields the related classes, and their
 * same-named methods are left out of both readings. Derived from the hierarchy, never listed by name,
 * so an implementer added upstream cannot silently become a decoy. Declarations whose owner cannot be
 * qualified — anonymous and local classes — are still tracked, under a file-path key.
 */
data class ChangeSignature(
    val targetClassFqn: String,
    val methodName: String,
    val addedParameterType: String,
    val addedParameterName: String,
    val returnTypeSimpleName: String,
    val newArity: Int,
    override val behaviourPreservationEvidence: String,
) : RippleTarget {

    override val kindId: String get() = "change-signature"

    override val targetDescription: String get() = "$targetClassFqn#$methodName"

    override val destinationDescription: String get() =
        "$methodName($addedParameterType $addedParameterName)"

    override val targetTypeFqn: String get() = targetClassFqn

    /** The arity the method has before the change — the anchor the capture script looks it up by. */
    val oldArity: Int get() = newArity - 1

    override fun extraPredicates(output: String): Map<String, Boolean> =
        mapOf("P5_ARITY" to parseArityPredicate(output, newArity))

    /**
     * The decoy key shared by both scripts: owner (or the file, for a declaration with no qualified
     * owner such as an anonymous class) plus the declared parameter types. Within a named class the
     * Java language already makes that unique, since two methods cannot share a name and a parameter
     * list; the trailing ordinal only ever fires for anonymous or local classes in one file, and it
     * exists so a colliding pair is disambiguated rather than dropped.
     */
    private fun decoyKeyHelper(): String = """
        fun decoyKey(m: PsiMethod): String {
            val owner = m.containingClass?.qualifiedName
                ?: ("<anonymous>@" + (m.containingFile?.virtualFile?.path ?: "<unknown>"))
            return owner + "#" + m.name + "(" +
                m.parameterList.parameters.joinToString(",") { p -> p.type.canonicalText } + ")"
        }

        fun relatedClasses(owner: PsiClass): Set<PsiClass> {
            val related = HashSet<PsiClass>()
            related.add(owner)
            related.addAll(ClassInheritorsSearch.search(owner, scope, true).findAll())
            for (m in owner.findMethodsByName("$methodName", false)) {
                for (s in m.findSuperMethods()) {
                    s.containingClass?.let { related.add(it) }
                }
            }
            return related
        }

        fun printDecoys(prefix: String, decoys: List<PsiMethod>) {
            val seen = HashMap<String, Int>()
            for (decoy in decoys.sortedBy { it.textOffset }) {
                val base = decoyKey(decoy)
                val n = seen[base] ?: 0
                seen[base] = n + 1
                val key = if (n == 0) base else base + "#" + n
                println(prefix + key + "|" + decoy.parameterList.parametersCount)
            }
        }
    """.trimIndent()

    override fun captureFragment(): String = """
        smartReadAction(project) {
            val scope = GlobalSearchScope.projectScope(project)
            val cache = PsiShortNamesCache.getInstance(project)
${decoyKeyHelper().prependIndent("            ")}

            val all = cache.getMethodsByName("$methodName", scope).toList()
            val target = all.firstOrNull {
                it.containingClass?.qualifiedName == "$targetClassFqn" &&
                    it.parameterList.parametersCount == $oldArity
            } ?: error("Target $targetClassFqn#$methodName with $oldArity parameters not found")
            check(target.containingClass?.isInterface == true) { "Target owner is not an interface" }

            println("GOLD_TARGET $targetClassFqn|$methodName/$oldArity|$methodName/$newArity")

            val refs = ReferencesSearch.search(target, scope).findAll()
            refs.mapNotNull { siteKey(it) }.groupingBy { it }.eachCount()
                .toSortedMap().forEach { (key, n) -> println("GOLD_SITE " + key + "|" + n) }

            // Computed ONCE, outside the filter: one inheritor search for the whole decoy set, never
            // one per candidate.
            val related = relatedClasses(target.containingClass!!)
            printDecoys("GOLD_DECOY ", all.filter { m -> m.containingClass?.let { it in related } != true })

            // The destination is the NEW SIGNATURE on the target owner, not the name: the name is
            // already taken by the method being changed, so a project-wide name count would report
            // the task as ill-posed against itself.
            println("GOLD_NEWNAME_DECLS " +
                (target.containingClass?.findMethodsByName("$methodName", false)
                    ?.count { it.parameterList.parametersCount == $newArity } ?: 0))
            println("GOLD_END")
        }
    """.trimIndent()

    override fun postconditionFragment(): String = """
        smartReadAction(project) {
            val scope = GlobalSearchScope.projectScope(project)
            val cache = PsiShortNamesCache.getInstance(project)
${decoyKeyHelper().prependIndent("            ")}

            val owner = JavaPsiFacade.getInstance(project)
                .findClass("$targetClassFqn", scope)
                ?: error("$targetClassFqn no longer exists")

            val declared = owner.findMethodsByName("$methodName", false).filter {
                it.parameterList.parametersCount == $newArity &&
                    it.parameterList.parameters.last().type.canonicalText == "$addedParameterType"
            }
            val changed = declared.firstOrNull()
            println("POST_NEWNAME_DECLARED " + (changed != null &&
                changed.returnType?.presentableText == "$returnTypeSimpleName"))
            // Non-zero means the old parameter list survived — an added overload rather than a
            // changed signature, which is the failure this kind exists to make visible.
            println("POST_OLDNAME_ON_TARGET " +
                owner.findMethodsByName("$methodName", false)
                    .count { it.parameterList.parametersCount == $oldArity })

            val refs = changed?.let { ReferencesSearch.search(it, scope).findAll() } ?: emptyList()
            refs.mapNotNull { siteKey(it) }.groupingBy { it }.eachCount()
                .toSortedMap().forEach { (key, n) -> println("POST_SITE " + key + "|" + n) }

            // Same exclusion as the capture, computed the same way from the same owner, so that a
            // correct solution — which MUST give every implementer the new parameter — moves no
            // decoy key at all.
            val related = relatedClasses(owner)
            printDecoys("POST_DECOY ", cache.getMethodsByName("$methodName", scope).toList()
                .filter { m -> m.containingClass?.let { it in related } != true })

            println("POST_TOTAL_NEW_REFS " + refs.size)
            println("POST_ARITY_EXPECTED $newArity")
            // A reference with no enclosing call — a method reference or a doc link — carries no
            // argument list to measure, so it counts as matching; it cannot compile against the old
            // signature either way, and the compile gate is what covers it.
            println("POST_ARITY_MATCHING " + refs.count { ref ->
                val call = ref.element.parent as? PsiMethodCallExpression
                val n = call?.argumentList?.expressionCount
                n == null || n == $newArity
            })
            println("POST_END")
        }
    """.trimIndent()

    override fun promptTaskSection(): String = """
        Add a parameter to the method

            $returnTypeSimpleName $methodName()

        declared on the interface `$targetClassFqn`,
        so that it reads

            $returnTypeSimpleName $methodName($addedParameterType $addedParameterName)

        throughout the whole project.

        Requirements:

        1. Every place that calls this method must pass the new argument, and must pass the literal
           `false`. When you are done, no caller of this declaration may still call it with the old
           argument list.
        2. The old parameter list must not survive on that interface in any form — not as a second
           method, not as a deprecated forwarder, not as a default method. An added overload is not
           a signature change.
        3. Every implementation of this method must take the new parameter too, and must ignore it:
           no implementation may read its value.
        4. Methods that happen to share the same simple name but are declared on **other types**
           are unrelated and MUST keep their current parameter list. Changing one of them is a
           defect.
        5. External behaviour must not change. The new parameter is read nowhere, and this type is
           an internal server-side model interface that takes no part in any HTTP contract, so a
           correct change is not observable from outside the code.
        6. The project must compile, test sources included, when you are finished.
    """.trimIndent()
}
