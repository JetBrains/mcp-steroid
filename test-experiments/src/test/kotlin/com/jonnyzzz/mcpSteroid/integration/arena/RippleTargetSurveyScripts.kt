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
 * a supertype that already has it is a no-op. [breadth] for a pull-up candidate is therefore the
 * inheritor count of that supertype, not of the declaring class.
 */
object RippleTargetSurveyScripts {

    fun survey(): String = """
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
                    // Cheap name-index lookup first, on every candidate: resolving `candidate.supers`
                    // below is a PSI resolve, not a cache read, and is too costly to pay for on every
                    // one of the thousands of ambiguous-name candidates. Only candidates that already
                    // have a name-ambiguous, non-static, non-private method are worth resolving supers for.
                    val ambiguousMethods = candidate.methods.filter { method ->
                        !method.hasModifierProperty(PsiModifier.STATIC) &&
                            !method.hasModifierProperty(PsiModifier.PRIVATE) &&
                            cache.getMethodsByName(method.name, scope).size - 1 >= 3
                    }
                    if (ambiguousMethods.isEmpty()) continue

                    for (superType in candidate.supers) {
                        val superFqn = superType.qualifiedName ?: continue
                        if (superFqn == "java.lang.Object") continue
                        for (method in ambiguousMethods) {
                            if (superType.findMethodsBySignature(method, true).isNotEmpty()) continue
                            val sameName = cache.getMethodsByName(method.name, scope).size - 1
                            // Memoized per supertype: breadth is a property of the SUPERTYPE alone, so
                            // recomputing it per method (and again for every subtype that shares the
                            // supertype) re-walks the same inheritor set thousands of times. That is
                            // what exhausted the IDE mid-run — the un-memoized query killed the IDE
                            // ~1600 candidates in, while the whole survey is ~3400. Same numbers, one
                            // search per supertype.
                            val pullUpBreadth = breadthBySuperFqn.getOrPut(superFqn) {
                                ClassInheritorsSearch.search(superType, scope, true).findAll().size
                            }
                            emit("pull-up", fqn, method.name, method, pullUpBreadth, sameName)
                        }
                    }
                }
            }
            println("SURVEY_END")
        }
    """.trimIndent()
}
