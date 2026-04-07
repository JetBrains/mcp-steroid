/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.time.Duration.Companion.seconds

class ScriptClassLoaderFactoryTest : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    fun testIdeClasspathContainsCurrentClassEntry(): Unit = timeoutRunBlocking(30.seconds) {
        val ideEntries = scriptClassLoaderFactory.ideClasspath()
        assertTrue("Expected ideClasspath to contain entries", ideEntries.isNotEmpty())

        val resourceEntry = classpathEntryFromResource(javaClass)
        assertTrue(
            "Expected ideClasspath to contain $resourceEntry",
            ideEntries.any { it.normalize() == resourceEntry.normalize() },
        )
    }

    fun testIdeClasspathContainsProgramRunnerUtilEntry(): Unit = timeoutRunBlocking(30.seconds) {
        val ideEntries = scriptClassLoaderFactory.ideClasspath()
        assertTrue("Expected ideClasspath to contain entries", ideEntries.isNotEmpty())

        val programRunnerUtilClass = Class.forName("com.intellij.execution.ProgramRunnerUtil")
        val resourceEntry = classpathEntryFromResource(programRunnerUtilClass)
        assertTrue(
            "Expected ideClasspath to contain ProgramRunnerUtil classpath entry: $resourceEntry",
            ideEntries.any { it.normalize() == resourceEntry.normalize() },
        )
    }

    fun testExecCodeClassloaderLoadsClassFromJar(): Unit = timeoutRunBlocking(30.seconds) {
        val root = Files.createTempDirectory("script-classloader")
        val resourceEntry = classpathEntryFromResource(javaClass)
        val jar = if (Files.isRegularFile(resourceEntry)) {
            resourceEntry
        } else {
            createClassJar(root, javaClass)
        }

        val loader = scriptClassLoaderFactory.execCodeClassloader(jar)
        val loaded = loader.loadClass(javaClass.name)
        assertEquals(javaClass.name, loaded.name)
    }

    fun testIdeClasspathContainsContentModuleClasses(): Unit = timeoutRunBlocking(30.seconds) {
        // AnnotatedElementsSearch lives in the intellij.java.indexing content module,
        // which has its own PluginClassLoader separate from the main com.intellij.java plugin.
        // ideClasspath() must include content module JARs for kotlinc to compile scripts that use them.
        // In test sandbox, content modules may share the main plugin classloader,
        // so this test verifies the general contract.
        val contentModuleClass = Class.forName("com.intellij.psi.search.searches.AnnotatedElementsSearch")
        val resourceEntry = classpathEntryFromResource(contentModuleClass)

        val ideEntries = scriptClassLoaderFactory.ideClasspath()
        assertTrue(
            "Expected ideClasspath to contain content module JAR for AnnotatedElementsSearch: $resourceEntry",
            ideEntries.any { it.normalize() == resourceEntry.normalize() },
        )
    }

    fun testIdeClasspathIncludesAllContentModuleClassloaderFiles(): Unit = timeoutRunBlocking(30.seconds) {
        // In production IDEs (2025.3+), plugins are split into content modules with separate
        // classloaders. ideClasspath() must include JARs from these content module classloaders,
        // not just the main plugin classloader. Without this, kotlinc cannot compile scripts
        // that reference classes from content modules (e.g. AnnotatedElementsSearch from
        // intellij.java.indexing). See https://github.com/jonnyzzz/mcp-steroid/issues/16
        //
        // NOTE: The test sandbox may load all content modules into the main plugin classloader
        // (no separate classloaders), so this test may pass trivially. The bug reproduces in
        // production IDEs where content modules get their own PluginClassLoader instances.
        // This test will catch the regression once the sandbox starts supporting content module
        // splitting, or serves as a canary if ideClasspath() logic changes.
        val ideEntries = scriptClassLoaderFactory.ideClasspath().map { it.normalize() }.toSet()

        val missingJars = mutableListOf<String>()
        for (descriptor in com.intellij.ide.plugins.PluginManagerCore.loadedPlugins) {
            if (!com.intellij.ide.plugins.PluginManagerCore.isLoaded(descriptor.pluginId)) continue

            val contentModules = try {
                descriptor::class.java.getMethod("getContentModules").invoke(descriptor) as? List<*>
            } catch (_: NoSuchMethodException) {
                null
            } ?: continue

            for (cm in contentModules) {
                val loader = try {
                    cm!!::class.java.getMethod("getPluginClassLoader").invoke(cm)
                        as? com.intellij.util.lang.UrlClassLoader
                } catch (_: Exception) {
                    null
                } ?: continue

                for (file in loader.files) {
                    if (java.nio.file.Files.exists(file) && file.normalize() !in ideEntries) {
                        missingJars += "${descriptor.pluginId} -> $file"
                    }
                }
            }
        }

        assertTrue(
            "ideClasspath() is missing ${missingJars.size} JARs from plugin content modules.\n" +
                "First 10:\n${missingJars.take(10).joinToString("\n") { "  $it" }}",
            missingJars.isEmpty(),
        )
    }

    private fun classpathEntryFromResource(klass: Class<*>): Path {
        val resourcePath = klass.name.replace('.', '/') + ".class"
        val resourceUrl = klass.classLoader.getResource(resourcePath)
            ?: error("Resource not found for $resourcePath")
        return when (resourceUrl.protocol) {
            "jar" -> Paths.get(URI.create(jarPathFromUrl(resourceUrl)))
            "file" -> {
                var path = Paths.get(resourceUrl.toURI())
                repeat(resourcePath.split('/').size) { path = path.parent }
                path
            }
            else -> error("Unsupported resource protocol: ${resourceUrl.protocol}")
        }
    }

    private fun jarPathFromUrl(url: java.net.URL): String {
        val text = url.toString()
        val bangIndex = text.indexOf("!/")
        require(text.startsWith("jar:") && bangIndex > 0) { "Unexpected jar URL: $text" }
        return text.substring("jar:".length, bangIndex)
    }

    private fun createClassJar(root: Path, klass: Class<*>): Path {
        val jarPath = root.resolve("class.jar")
        val resourcePath = klass.name.replace('.', '/') + ".class"
        JarOutputStream(Files.newOutputStream(jarPath)).use { jar ->
            jar.putNextEntry(JarEntry(resourcePath))
            jar.write(classBytes(klass))
            jar.closeEntry()
        }
        return jarPath
    }

    private fun classBytes(klass: Class<*>): ByteArray {
        val resourcePath = klass.name.replace('.', '/') + ".class"
        val stream = klass.classLoader.getResourceAsStream(resourcePath)
            ?: error("Resource not found for $resourcePath")
        return stream.use { it.readBytes() }
    }
}
