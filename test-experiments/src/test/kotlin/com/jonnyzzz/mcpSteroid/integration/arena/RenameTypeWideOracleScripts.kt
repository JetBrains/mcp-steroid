/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

/**
 * The two PSI scripts behind the wide rename-type oracle, kept as plain strings so the parsing and
 * grading in [SemanticRippleOracle] stays pure and unit-tested.
 *
 * Both start by refreshing the VFS and waiting for smart mode. The post-condition script runs after
 * the agent has edited dozens of files, and a stale index would report a correct rename as a failed
 * one — or the reverse.
 *
 * The enclosing-declaration key is the nearest named parent of the reference. Line and offset keys
 * would shift with the agent's edits and produce false misses.
 *
 * The GOLD_ and POST_ line shapes are byte-identical to [SemanticRippleOracleScripts] so
 * [parseSemanticGold] and [parseSemanticPostcondition] are reused with no change: only the target
 * lookup (a class rather than a method), the precondition, and the two post-agent flags that a
 * method-rename oracle cannot express for a type differ.
 */
object RenameTypeWideOracleScripts {

    private val preamble = """
        import com.intellij.openapi.roots.ProjectFileIndex
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
            val all = cache.getClassesByName("${RenameTypeWideSpec.oldName}", scope).toList()
            val target = all.firstOrNull {
                it.qualifiedName == "${RenameTypeWideSpec.targetClassFqn}"
            } ?: error("Target ${RenameTypeWideSpec.targetClassFqn} not found")
            check(
                target.containingFile?.virtualFile
                    ?.let { ProjectFileIndex.getInstance(project).isInContent(it) } == true
            ) {
                "Target is not a project source file (a library class?); the rename oracle cannot " +
                    "validate a class this task does not own"
            }

            println("GOLD_TARGET ${RenameTypeWideSpec.targetClassFqn}|${RenameTypeWideSpec.oldName}|${RenameTypeWideSpec.newName}")

            val refs = ReferencesSearch.search(target, scope).findAll()
            refs.mapNotNull { siteKey(it) }.groupingBy { it }.eachCount()
                .toSortedMap().forEach { (key, n) -> println("GOLD_SITE " + key + "|" + n) }

            for (decoy in all) {
                if (decoy === target) continue
                val owner = decoy.qualifiedName ?: continue
                println("GOLD_DECOY " + owner + "|" + ReferencesSearch.search(decoy, scope).findAll().size)
            }

            println("GOLD_NEWNAME_DECLS " +
                (cache.getMethodsByName("${RenameTypeWideSpec.newName}", scope).size +
                 cache.getClassesByName("${RenameTypeWideSpec.newName}", scope).size))
            println("GOLD_END")
        }
    """.trimIndent()

    /** Emits POST_* lines: the alias check, the new type's sites, and every decoy's count. */
    fun postcondition(): String = preamble + "\n" + """
        smartReadAction(project) {
            val scope = GlobalSearchScope.projectScope(project)
            val cache = PsiShortNamesCache.getInstance(project)
            val facade = JavaPsiFacade.getInstance(project)

            val renamed = facade.findClass("${RenameTypeWideSpec.targetClassFqn.substringBeforeLast('.')}.${RenameTypeWideSpec.newName}", scope)
            println("POST_NEWNAME_DECLARED " + (renamed != null))
            println("POST_OLDNAME_ON_TARGET " +
                (if (facade.findClass("${RenameTypeWideSpec.targetClassFqn}", scope) != null) 1 else 0))

            val refs = renamed?.let { ReferencesSearch.search(it, scope).findAll() } ?: emptyList()
            refs.mapNotNull { siteKey(it) }.groupingBy { it }.eachCount()
                .toSortedMap().forEach { (key, n) -> println("POST_SITE " + key + "|" + n) }

            for (decoy in cache.getClassesByName("${RenameTypeWideSpec.oldName}", scope)) {
                val decoyOwner = decoy.qualifiedName ?: continue
                if (decoyOwner == "${RenameTypeWideSpec.targetClassFqn}") continue
                println("POST_DECOY " + decoyOwner + "|" +
                    ReferencesSearch.search(decoy, scope).findAll().size)
            }

            println("POST_TOTAL_NEW_REFS " + refs.size)
            println("POST_END")
        }
    """.trimIndent()
}
