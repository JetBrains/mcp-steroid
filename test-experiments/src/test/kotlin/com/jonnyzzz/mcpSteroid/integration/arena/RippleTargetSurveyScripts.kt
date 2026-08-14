/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * The PSI script that picks the family's targets by measurement.
 *
 * It exists because guessing a target is expensive to get wrong: the pilot's obvious destination name
 * `realmRoles` turned out to be declared five times, which only a PSI query revealed. Everything the
 * case registry pins — references, files, modules, same-name declarations, hierarchy breadth — is
 * printed here, so a registry constant can always be traced to a run of this script.
 *
 * Candidate pools are bounded on purpose. An exhaustive `ReferencesSearch` over every declaration in
 * a 189-module project does not finish inside any timeout this harness allows; the pools below are
 * the widest that do.
 *
 * Pull-up is measured on the SUBTYPE, not the interface: a candidate is a method declared on a class
 * that HAS a supertype which does not already declare a matching signature — pulling the method onto
 * a supertype that already has it is a no-op. Breadth for a pull-up candidate is therefore the
 * inheritor count of that supertype, not of the declaring class.
 *
 * **A qualifying fan-out is not a qualifying target: the name must not be load-bearing.** Every
 * number this script prints is about how far a transformation reaches, and none of them is about
 * whether the transformation is behaviour-preserving. That question is [RippleNameEscapeRule]'s, and
 * it is not optional — the family's founding case was pinned on the belief that a `@Path`-annotated
 * method's Java name was free, while two files addressed the method BY NAME through
 * `UriBuilder.path(RealmResource.class, "roles")`. So this script:
 *
 * - never offers a JAX-RS-annotated method as a `rename-method` candidate (it prints
 *   `SURVEY_JAXRS_EXCLUDED` instead), because such a method is exactly what that idiom can address;
 * - prints `SURVEY_STRING_LITERAL_NAMES` for every wide `rename-method` candidate, measured through
 *   the string index. **A candidate with a non-zero count is disqualified however good its fan-out
 *   is**, and a candidate with no printed count must not be pinned at all;
 * - prints `SURVEY_MODULE_NAMES` for every wide candidate, because a case's compile gate is a list of
 *   Maven artifactIds and a reference can only be mapped back to one through the index that found it.
 *
 * Before pinning, confirm the literal reading offline as well, with
 * [RippleNameEscapeRule.gitGrepCommands] against the bare repository at the pinned commit. The
 * in-arm re-measurement in `RenameMethod.captureFragment` is the last line of defence, not the first.
 *
 * **Pull-up is a SEPARATE script ([pullUp]) from the other three kinds ([survey]).** Sharing one
 * script killed the IDE mid-run twice: the pull-up query multiplies each candidate by its supertypes
 * and its methods, and it cannot afford to spend the process's budget on kinds that are already
 * pinned. Two calls also mean the cheap kinds' output survives a pull-up death.
 */
object RippleTargetSurveyScripts {

    fun survey(): String = """
        import com.intellij.openapi.module.ModuleUtilCore
        import com.intellij.openapi.vfs.VirtualFileManager
        import com.intellij.psi.*
        import com.intellij.psi.search.GlobalSearchScope
        import com.intellij.psi.search.PsiSearchHelper
        import com.intellij.psi.search.PsiShortNamesCache
        import com.intellij.psi.search.UsageSearchContext
        import com.intellij.psi.search.searches.ReferencesSearch

        VirtualFileManager.getInstance().asyncRefresh()
        waitForSmartMode()

        smartReadAction(project) {
            val scope = GlobalSearchScope.projectScope(project)
            val cache = PsiShortNamesCache.getInstance(project)
            val helper = PsiSearchHelper.getInstance(project)
            val literalsByName = HashMap<String, Int>()

            // How many Java string literals in the project spell this name exactly. A method named by
            // a literal is renamed by no compiler and by no reference search — see the class comment.
            fun stringLiteralsNaming(name: String): Int = literalsByName.getOrPut(name) {
                var hits = 0
                helper.processElementsWithWord({ element, _ ->
                    if (element is PsiLiteralExpression && element.value == name) hits++
                    true
                }, scope, name, UsageSearchContext.IN_STRINGS, true)
                hits
            }

            // ONE reference search per declaration, however many kinds read it. rename-type and
            // move-class see the same references, and so do change-signature and rename-method; a
            // search per kind doubled the cost of the pool for no extra measurement.
            fun emit(kinds: List<String>, owner: String, name: String, element: PsiElement, breadth: Int, sameName: Int) {
                val refs = ReferencesSearch.search(element, scope).findAll()
                if (refs.isEmpty()) return
                val files = refs.mapNotNull { it.element.containingFile?.virtualFile?.path }.toSet()
                val modules = refs.mapNotNull {
                    it.element.containingFile?.virtualFile?.let { f -> ModuleUtilCore.findModuleForFile(f, project)?.name }
                }.toSet()
                for (kind in kinds) {
                    println("SURVEY_CANDIDATE " + kind + "|" + owner + "|" + name + "|" +
                        refs.size + "|" + files.size + "|" + modules.size + "|" + sameName + "|" + breadth)
                }
                // Only for a fan-out that can qualify as wide, so the extra lines and the extra
                // string-index lookups are paid for by candidates a case could actually be built on.
                if (refs.size >= $MIN_WIDE_REFERENCES) {
                    println("SURVEY_MODULE_NAMES " + owner + "|" + name + "|" + modules.sorted().joinToString(","))
                    if (kinds.contains("rename-method")) {
                        println("SURVEY_STRING_LITERAL_NAMES " + owner + "|" + name + "|" +
                            stringLiteralsNaming(name))
                    }
                }
            }

            val classNames = cache.allClassNames.toList()
            for (simpleName in classNames) {
                val classes = cache.getClassesByName(simpleName, scope)
                if (classes.size < 2) continue      // no lexical ambiguity, skip early
                for (candidate in classes) {
                    val fqn = candidate.qualifiedName ?: continue
                    emit(listOf("rename-type", "move-class"), fqn, simpleName, candidate, 0, classes.size - 1)
                    if (candidate.isInterface) {
                        for (method in candidate.methods) {
                            val sameName = cache.getMethodsByName(method.name, scope).size - 1
                            if (sameName < 3) continue
                            // A JAX-RS resource method is addressable by NAME from
                            // `UriBuilder.path(X.class, "name")`, which resolves through the
                            // annotation, not through the compiler — so its name is load-bearing and
                            // it is not a rename-method candidate. It stays a change-signature
                            // candidate: adding a parameter leaves the name the idiom looks up intact.
                            val jaxRs = method.annotations.any { a ->
                                val q = a.qualifiedName ?: ""
                                q.startsWith("jakarta.ws.rs") || q.startsWith("javax.ws.rs")
                            }
                            if (jaxRs) println("SURVEY_JAXRS_EXCLUDED " + fqn + "|" + method.name)
                            val kinds = if (jaxRs) listOf("change-signature")
                                else listOf("change-signature", "rename-method")
                            emit(kinds, fqn, method.name, method, 0, sameName)
                        }
                    }
                }
            }
            println("SURVEY_END")
        }
    """.trimIndent()

    /**
     * The rename-method query over the pool [survey] cannot reach.
     *
     * [survey] examines a class only when at least two project classes share its SIMPLE NAME, because
     * that is the ambiguity axis of the kinds that transform a TYPE. A method's ambiguity axis is the
     * METHOD-name count, and the two do not imply each other: `KeycloakSession` is uniquely named, so
     * every one of its widely-used, widely-shadowed methods is invisible to [survey]. That blind spot
     * is why the family's founding case was never surveyed at all.
     *
     * Three bounds, each of them a property of what a wide case needs rather than a convenience:
     *
     * 1. **A cheap word-index pre-gate before any reference search.** A wide case needs references in
     *    at least [MIN_WIDE_FILES] files, and a reference to a method occurs in a file that contains
     *    its name as a word — so `processAllFilesWithWord` bounds the fan-out from above at index
     *    cost, and only names that survive it are ever searched. Without it this query searches every
     *    declaration of every ambiguous name in the project, which is the shape that killed the IDE
     *    twice during the pull-up survey.
     * 2. **Only INTERFACE methods, and only ones that override nothing.** The kind renames a
     *    declaration and every reference to it; a method that already overrides a supertype's is not
     *    the root of its own ripple, and measuring it would measure the supertype's.
     * 3. **Names shared by more than [MAX_SAME_NAME_DECLARATIONS] declarations are skipped.** Ambiguity
     *    is required, but `getId` has 1021 declarations, and one reference search per declaration of a
     *    name that common is exactly the cost this query cannot pay. Any verdict read off this script
     *    is a verdict about the surveyed pool.
     *
     * The evidence lines are the same as [survey]'s, plus `SURVEY_OVERRIDES`: how many project
     * declarations of the name are inside the target's own hierarchy. A correct rename must move all
     * of them, so they are not decoys — the count is what a case's decoy pin has to subtract.
     */
    fun renameMethod(): String = """
        import com.intellij.openapi.module.ModuleUtilCore
        import com.intellij.openapi.roots.ProjectFileIndex
        import com.intellij.openapi.vfs.VirtualFileManager
        import com.intellij.psi.*
        import com.intellij.psi.search.GlobalSearchScope
        import com.intellij.psi.search.PsiSearchHelper
        import com.intellij.psi.search.PsiShortNamesCache
        import com.intellij.psi.search.UsageSearchContext
        import com.intellij.psi.search.searches.ClassInheritorsSearch
        import com.intellij.psi.search.searches.ReferencesSearch

        VirtualFileManager.getInstance().asyncRefresh()
        waitForSmartMode()

        smartReadAction(project) {
            val scope = GlobalSearchScope.projectScope(project)
            val cache = PsiShortNamesCache.getInstance(project)
            val helper = PsiSearchHelper.getInstance(project)
            val fileIndex = ProjectFileIndex.getInstance(project)

            fun filesNamingWord(word: String): Int {
                var n = 0
                helper.processAllFilesWithWord(word, scope, { _ -> n++; true }, true)
                return n
            }

            fun stringLiteralsNaming(name: String): Int {
                var hits = 0
                helper.processElementsWithWord({ element, _ ->
                    if (element is PsiLiteralExpression && element.value == name) hits++
                    true
                }, scope, name, UsageSearchContext.IN_STRINGS, true)
                return hits
            }

            for (methodName in cache.allMethodNames.toList()) {
                val declarations = cache.getMethodsByName(methodName, scope)
                val sameName = declarations.size - 1
                if (sameName < 3) continue
                if (sameName > $MAX_SAME_NAME_DECLARATIONS) continue
                if (filesNamingWord(methodName) < $MIN_WIDE_FILES) continue

                for (method in declarations) {
                    val owner = method.containingClass ?: continue
                    if (!owner.isInterface) continue
                    val fqn = owner.qualifiedName ?: continue
                    val ownerFile = owner.containingFile?.virtualFile ?: continue
                    if (!fileIndex.isInContent(ownerFile)) continue
                    if (method.hasModifierProperty(PsiModifier.STATIC)) continue
                    if (method.findSuperMethods().isNotEmpty()) continue

                    val refs = ReferencesSearch.search(method, scope).findAll()
                    if (refs.size < $MIN_WIDE_REFERENCES) continue
                    val files = refs.mapNotNull { it.element.containingFile?.virtualFile?.path }.toSet()
                    val modules = refs.mapNotNull {
                        it.element.containingFile?.virtualFile?.let { f -> ModuleUtilCore.findModuleForFile(f, project)?.name }
                    }.toSet()
                    println("SURVEY_CANDIDATE rename-method|" + fqn + "|" + methodName + "|" +
                        refs.size + "|" + files.size + "|" + modules.size + "|" + sameName + "|0")
                    println("SURVEY_MODULE_NAMES " + fqn + "|" + methodName + "|" + modules.sorted().joinToString(","))
                    val jaxRs = method.annotations.any { a ->
                        val q = a.qualifiedName ?: ""
                        q.startsWith("jakarta.ws.rs") || q.startsWith("javax.ws.rs")
                    }
                    if (jaxRs) println("SURVEY_JAXRS_EXCLUDED " + fqn + "|" + methodName)
                    println("SURVEY_STRING_LITERAL_NAMES " + fqn + "|" + methodName + "|" +
                        stringLiteralsNaming(methodName))
                    // The target's own override family: a correct rename MUST move these, so they are
                    // not decoys, and a case's decoy pin is the same-name count minus this one.
                    val related = HashSet<PsiClass>()
                    related.add(owner)
                    related.addAll(ClassInheritorsSearch.search(owner, scope, true).findAll())
                    val overridingDeclarations = declarations.filter { d ->
                        d !== method && d.containingClass?.let { it in related } == true
                    }
                    println("SURVEY_OVERRIDES " + fqn + "|" + methodName + "|" + overridingDeclarations.size)
                    // The MODULES those overrides live in, which a rename-method gate must cover on
                    // top of the reference modules — an override is not a reference, so a module can
                    // hold three of them and appear in no line above. Printed as names for the same
                    // reason SURVEY_MODULE_NAMES is: a case pins Maven artifactIds, and only the index
                    // that found the declaration can map one back.
                    val overrideModules = overridingDeclarations.mapNotNull { d ->
                        d.containingFile?.virtualFile?.let { f -> ModuleUtilCore.findModuleForFile(f, project)?.name }
                    }.toSet()
                    println("SURVEY_OVERRIDE_MODULES " + fqn + "|" + methodName + "|" +
                        overrideModules.sorted().joinToString(","))
                }
            }
            println("SURVEY_END")
        }
    """.trimIndent()

    /**
     * The pull-up query, alone, with the whole process budget to itself.
     *
     * Four narrowings, each of them a precondition of the verdict rather than a convenience — the
     * un-narrowed query killed the IDE mid-script twice (once ~1600 candidates in, once at 396s with
     * an empty reply):
     *
     * 1. **Breadth gates the SUPERTYPE before any of its methods are touched.** `breadth >= 8` is
     *    already required of every qualifying pull-up candidate ([MIN_PULL_UP_BREADTH], shared with
     *    [qualifiesForPullUp] so the pre-gate and the verdict cannot disagree), and breadth is a
     *    property of the supertype alone. So one memoized inheritor search per supertype decides
     *    whether that supertype is worth a single `findMethodsBySignature` or `ReferencesSearch`.
     * 2. **Only a supertype that lives in project sources can receive a pulled-up method at all.** A
     *    method cannot be pulled up into a library or JDK type — there is no source to change — so a
     *    non-project supertype was never a candidate destination, only a cost.
     * 3. **Only interfaces.** Every case in this family transforms an interface member, and a pull-up
     *    onto an abstract class would additionally have to reason about field and constructor state
     *    the family's behaviour-preservation argument does not cover.
     * 4. **One emit per method, on its widest qualifying supertype.** A method with three qualifying
     *    supertypes used to pay three `ReferencesSearch` calls for one identical reference set.
     *
     * Two further narrowings are inherited from the shared loop this query grew out of, and they
     * bound the POOL rather than the ranking — any verdict read off this script is a verdict about
     * the surveyed pool, not about Keycloak:
     *
     * 5. **Only classes whose SIMPLE NAME is shared by at least two project classes are considered**
     *    (`classes.size < 2 -> continue`). That is the ambiguity axis the other three kinds need,
     *    where the transformation lands on the type. Pull-up's own ambiguity axis is the METHOD-name
     *    count, which `qualifiesForPullUp` reads and this filter does not imply — so a uniquely-named
     *    class holding a widely-ambiguous method is never examined.
     * 6. **Only DIRECT supertypes** (`candidate.supers`). A method missing from a wide INDIRECT
     *    ancestor — the grandparent interface — is never evaluated as a pull-up.
     *
     * Every supertype that was evaluated also prints `SURVEY_PULLUP_SUPER`, breadth included, before
     * the gate applies. That costs one index line and no search, and it is what makes a
     * `NONE QUALIFYING` verdict reportable: the best near-miss on breadth is in the output even
     * though no candidate under the threshold is ever emitted.
     */
    fun pullUp(): String = """
        import com.intellij.openapi.module.ModuleUtilCore
        import com.intellij.openapi.vfs.VirtualFileManager
        import com.intellij.psi.*
        import com.intellij.psi.search.GlobalSearchScope
        import com.intellij.psi.search.PsiShortNamesCache
        import com.intellij.psi.search.searches.ClassInheritorsSearch
        import com.intellij.psi.search.searches.ReferencesSearch

        VirtualFileManager.getInstance().asyncRefresh()
        waitForSmartMode()

        smartReadAction(project) {
            val scope = GlobalSearchScope.projectScope(project)
            val cache = PsiShortNamesCache.getInstance(project)
            val breadthBySuperFqn = HashMap<String, Int>()

            fun emit(kind: String, owner: String, name: String, element: PsiElement, breadth: Int, sameName: Int) {
                val refs = ReferencesSearch.search(element, scope).findAll()
                if (refs.isEmpty()) return
                val files = refs.mapNotNull { it.element.containingFile?.virtualFile?.path }.toSet()
                val modules = refs.mapNotNull {
                    it.element.containingFile?.virtualFile?.let { f -> ModuleUtilCore.findModuleForFile(f, project)?.name }
                }.toSet()
                println("SURVEY_CANDIDATE " + kind + "|" + owner + "|" + name + "|" +
                    refs.size + "|" + files.size + "|" + modules.size + "|" + sameName + "|" + breadth)
            }

            // Breadth of a supertype, memoized, printed once, and only ever computed for a supertype
            // that is a project-source interface — the two properties that make it a possible
            // destination in the first place, both readable without a search.
            fun destinationBreadth(superType: PsiClass): Int? {
                val superFqn = superType.qualifiedName ?: return null
                if (superFqn == "java.lang.Object") return null
                if (!superType.isInterface) return null
                val superFile = superType.containingFile?.virtualFile ?: return null
                if (!scope.contains(superFile)) return null
                breadthBySuperFqn[superFqn]?.let { return it }
                val breadth = ClassInheritorsSearch.search(superType, scope, true).findAll().size
                breadthBySuperFqn[superFqn] = breadth
                println("SURVEY_PULLUP_SUPER " + superFqn + "|" + breadth)
                return breadth
            }

            for (simpleName in cache.allClassNames.toList()) {
                val classes = cache.getClassesByName(simpleName, scope)
                if (classes.size < 2) continue      // no lexical ambiguity, skip early
                for (candidate in classes) {
                    val fqn = candidate.qualifiedName ?: continue
                    // Cheap name-index lookup first: resolving `candidate.supers` is a PSI resolve,
                    // not a cache read, and is too costly on every one of thousands of candidates.
                    val ambiguousMethods = candidate.methods.filter { method ->
                        !method.hasModifierProperty(PsiModifier.STATIC) &&
                            !method.hasModifierProperty(PsiModifier.PRIVATE) &&
                            cache.getMethodsByName(method.name, scope).size - 1 >= 3
                    }
                    if (ambiguousMethods.isEmpty()) continue

                    val destinations = candidate.supers
                        .mapNotNull { s -> destinationBreadth(s)?.let { b -> s to b } }
                        .filter { (_, breadth) -> breadth >= $MIN_PULL_UP_BREADTH }
                        .sortedByDescending { (_, breadth) -> breadth }
                    if (destinations.isEmpty()) continue

                    for (method in ambiguousMethods) {
                        val best = destinations.firstOrNull { (superType, _) ->
                            superType.findMethodsBySignature(method, true).isEmpty()
                        } ?: continue
                        emit("pull-up", fqn, method.name, method, best.second,
                            cache.getMethodsByName(method.name, scope).size - 1)
                    }
                }
            }
            println("SURVEY_END")
        }
    """.trimIndent()
}
