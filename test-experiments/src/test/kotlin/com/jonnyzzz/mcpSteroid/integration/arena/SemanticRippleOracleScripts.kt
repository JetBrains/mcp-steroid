/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * The two PSI scripts behind the semantic-ripple oracle, kept as plain strings so the parsing and
 * grading in [SemanticRippleOracle] stays pure and unit-tested.
 *
 * Both start by refreshing the VFS and waiting for smart mode. The post-condition script runs after
 * the agent has edited dozens of files, and a stale index would report a correct rename as a failed
 * one — or the reverse.
 *
 * The enclosing-declaration key is the nearest named parent of the reference. Line and offset keys
 * would shift with the agent's edits and produce false misses.
 */
object SemanticRippleOracleScripts {

    private val preamble = """
        import com.intellij.openapi.vfs.VirtualFileManager
        import com.intellij.psi.*
        import com.intellij.psi.search.GlobalSearchScope
        import com.intellij.psi.search.PsiShortNamesCache
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
    fun capture(): String = preamble + "\n" + """
        smartReadAction(project) {
            val scope = GlobalSearchScope.projectScope(project)
            val cache = PsiShortNamesCache.getInstance(project)
            val all = cache.getMethodsByName("${SemanticRippleSpec.oldName}", scope).toList()
            val target = all.firstOrNull {
                it.containingClass?.qualifiedName == "${SemanticRippleSpec.targetClassFqn}"
            } ?: error("Target ${SemanticRippleSpec.targetClassFqn}#${SemanticRippleSpec.oldName} not found")
            check(target.containingClass?.isInterface == true) { "Target owner is not an interface" }
            check(target.annotations.any { it.text.contains("Path") }) {
                "Target has no @Path annotation; the rename would not be behaviour-preserving"
            }

            println("GOLD_TARGET ${SemanticRippleSpec.targetClassFqn}|${SemanticRippleSpec.oldName}|${SemanticRippleSpec.newName}")

            val refs = ReferencesSearch.search(target, scope).findAll()
            refs.mapNotNull { siteKey(it) }.groupingBy { it }.eachCount()
                .toSortedMap().forEach { (key, n) -> println("GOLD_SITE " + key + "|" + n) }

            for (decoy in all) {
                if (decoy === target) continue
                val owner = decoy.containingClass?.qualifiedName ?: continue
                println("GOLD_DECOY " + owner + "|" + ReferencesSearch.search(decoy, scope).findAll().size)
            }

            println("GOLD_NEWNAME_DECLS " +
                (cache.getMethodsByName("${SemanticRippleSpec.newName}", scope).size +
                 cache.getClassesByName("${SemanticRippleSpec.newName}", scope).size))
            println("GOLD_END")
        }
    """.trimIndent()

    /** Emits POST_* lines: the alias check, the new method's sites, and every decoy's count. */
    fun postcondition(): String = preamble + "\n" + """
        smartReadAction(project) {
            val scope = GlobalSearchScope.projectScope(project)
            val cache = PsiShortNamesCache.getInstance(project)
            val owner = JavaPsiFacade.getInstance(project)
                .findClass("${SemanticRippleSpec.targetClassFqn}", scope)
                ?: error("${SemanticRippleSpec.targetClassFqn} no longer exists")

            val renamed = owner.findMethodsByName("${SemanticRippleSpec.newName}", false).firstOrNull()
            val declaredWithRightType = renamed != null &&
                renamed.returnType?.presentableText == "${SemanticRippleSpec.targetReturnTypeSimpleName}"
            println("POST_NEWNAME_DECLARED " + declaredWithRightType)
            println("POST_OLDNAME_ON_TARGET " +
                owner.findMethodsByName("${SemanticRippleSpec.oldName}", false).size)

            val refs = renamed?.let { ReferencesSearch.search(it, scope).findAll() } ?: emptyList()
            refs.mapNotNull { siteKey(it) }.groupingBy { it }.eachCount()
                .toSortedMap().forEach { (key, n) -> println("POST_SITE " + key + "|" + n) }

            for (decoy in cache.getMethodsByName("${SemanticRippleSpec.oldName}", scope)) {
                val decoyOwner = decoy.containingClass?.qualifiedName ?: continue
                if (decoyOwner == "${SemanticRippleSpec.targetClassFqn}") continue
                println("POST_DECOY " + decoyOwner + "|" +
                    ReferencesSearch.search(decoy, scope).findAll().size)
            }

            println("POST_TOTAL_NEW_REFS " + refs.size)
            println("POST_END")
        }
    """.trimIndent()
}
