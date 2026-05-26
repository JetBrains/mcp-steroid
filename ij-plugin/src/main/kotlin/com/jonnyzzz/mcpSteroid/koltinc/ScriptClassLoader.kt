/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.plugins.contentModules
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.util.text.StringHash
import com.intellij.util.lang.UrlClassLoader
import java.io.IOException
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

inline val scriptClassLoaderFactory get(): ScriptClassLoaderFactory = service()

@Service(Service.Level.APP)
class ScriptClassLoaderFactory {
    private fun orderedPluginDescriptors(): List<IdeaPluginDescriptor> {
        return PluginManagerCore.loadedPlugins
            .filter {
                //make sure we are not dealing with removed plugins
                PluginManagerCore.isLoaded(it.pluginId)
            }
    }

    fun execCodeClassloader(jar: Path): ClassLoader {
        //we cannot keep the newIdeClassloader to enforce classes GC
        return URLClassLoader(arrayOf(jar.toUri().toURL()), newIdeClassloader())
    }

    fun ideClasspath(): List<Path> {
        return orderedPluginDescriptors()
            .asSequence()
            .flatMap { descriptor ->
                // Main plugin classloader
                val loaders = mutableListOf(descriptor.pluginClassLoader)
                // Content module classloaders (IntelliJ 2025.3+)
                // Plugins are split into content modules with separate PluginClassLoader instances.
                // Without including these, kotlinc cannot compile scripts that reference classes
                // from content modules (e.g. AnnotatedElementsSearch from intellij.java.indexing).
                loaders += descriptor.contentModules.mapNotNull { it.pluginClassLoader }
                loaders.asSequence()
            }
            .filterIsInstance<UrlClassLoader>()
            .distinct()
            .flatMap { it.files }
            .distinct()
            .filter { Files.exists(it) }
            .toList()
    }

    /**
     * Candidate classloaders consulted by [newIdeClassloader] in delegation order.
     * Exposed (test-only) so regressions in the "include content modules" policy are
     * detectable without spelunking inside the anonymous delegate's findClass cache.
     */
    @org.jetbrains.annotations.TestOnly
    internal fun productionCandidateLoaders(): List<ClassLoader> =
        productionCandidateLoaderSeq().toList()

    // Walk main + content-module classloaders in stable order, mirroring ideClasspath().
    // Without content modules a script that compiles against a class in a content module
    // (e.g. AiaActivationAuthFacade in 2025.3+) cannot resolve it at runtime, causing
    // same-FQN-different-Class CCEs on service<T>() casts. See #76.
    private fun productionCandidateLoaderSeq(): Sequence<ClassLoader> =
        orderedPluginDescriptors().asSequence().flatMap { d ->
            sequenceOf(d.pluginClassLoader) +
                d.contentModules.asSequence().map { it.pluginClassLoader }
        }.filterNotNull()

    @org.jetbrains.annotations.TestOnly
    internal fun newIdeDelegateLoaderForTests(loaders: List<ClassLoader>): ClassLoader =
        newIdeClassloader(loaders.asSequence())

    private fun newIdeClassloader(): ClassLoader = newIdeClassloader(productionCandidateLoaderSeq())

    private fun newIdeClassloader(candidates: Sequence<ClassLoader>): ClassLoader {
        return object : ClassLoader(null) {
            val myLuckyGuess: ConcurrentMap<Long, ClassLoader> = ConcurrentHashMap()

            @Throws(ClassNotFoundException::class)
            override fun findClass(name: String): Class<*> {
                val hash = StringHash.buz(name.substringBefore("$"))

                var c: Class<*>? = null
                val guess1: ClassLoader? = myLuckyGuess[hash] // cached loader or "this" if not found
                val guess2: ClassLoader? = myLuckyGuess[0L]   // last recently used

                for (loader in setOf(guess1, guess2)) {
                    if (loader === this) throw ClassNotFoundException(name)
                    if (loader == null) continue

                    try {
                        return loader.loadClass(name)
                    } catch (_: ClassNotFoundException) {
                        //nop
                    }
                }

                for (l in candidates) {
                    if (l === guess1 || l === guess2) continue

                    try {
                        c = l.loadClass(name)
                        myLuckyGuess[hash] = l
                        myLuckyGuess[0L] = l
                        break
                    } catch (_: ClassNotFoundException) {
                        //nop
                    }
                }

                if (c != null) {
                    return c
                } else {
                    myLuckyGuess[hash] = this
                    throw ClassNotFoundException(name)
                }
            }

            override fun findResource(name: String?): URL? {
                for (l in candidates) {
                    val url = l.getResource(name) ?: continue
                    return url
                }
                return null
            }

            @Throws(IOException::class)
            override fun findResources(name: String?): Enumeration<URL> {
                return sequence {
                    for (l in candidates) {
                        val urls = l.getResources(name) ?: continue
                        for (url in urls) {
                            yield(url ?: continue)
                        }
                    }
                }.toEnumeration()
            }
        }
    }

    private fun <T> Sequence<T>.toEnumeration(): Enumeration<T> {
        val iterator = this.iterator()
        return object : Enumeration<T> {
            override fun hasMoreElements(): Boolean = iterator.hasNext()
            override fun nextElement(): T? = iterator.next()
        }
    }
}
