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
        import com.intellij.psi.search.PsiShortNamesCache
        import com.intellij.psi.search.searches.ReferencesSearch

        VirtualFileManager.getInstance().asyncRefresh()
        waitForSmartMode()

        smartReadAction(project) {
            val scope = GlobalSearchScope.projectScope(project)
            val cache = PsiShortNamesCache.getInstance(project)

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

            val classNames = cache.allClassNames.toList()
            for (simpleName in classNames) {
                val classes = cache.getClassesByName(simpleName, scope)
                if (classes.size < 2) continue      // no lexical ambiguity, skip early
                for (candidate in classes) {
                    val fqn = candidate.qualifiedName ?: continue
                    emit("rename-type", fqn, simpleName, candidate, 0, classes.size - 1)
                    emit("move-class", fqn, simpleName, candidate, 0, classes.size - 1)
                    if (candidate.isInterface) {
                        for (method in candidate.methods) {
                            val sameName = cache.getMethodsByName(method.name, scope).size - 1
                            if (sameName < 3) continue
                            emit("change-signature", fqn, method.name, method, 0, sameName)
                        }
                    }
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
