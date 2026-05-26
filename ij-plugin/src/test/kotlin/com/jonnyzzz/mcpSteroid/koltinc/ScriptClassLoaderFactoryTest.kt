/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.plugins.contentModules
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.net.URI
import java.net.URLClassLoader
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

    fun testProductionCandidateLoadersIncludeContentModuleLoaders(): Unit = timeoutRunBlocking(30.seconds) {
        // Regression for https://github.com/jonnyzzz/mcp-steroid/issues/76.
        //
        // newIdeClassloader() must consult content-module classloaders, not only each
        // descriptor's main pluginClassLoader. Otherwise scripts compile against classes
        // in content modules (since #16 added them to ideClasspath()) but fail at runtime,
        // producing same-FQN-different-Class CCEs from service<T>() for plugins whose
        // registered impl lives in a content-module classloader (e.g. AIA's
        // AiaActivationAuthFacadeImpl in 2025.3+ IntelliJ).
        //
        // In a test sandbox that folds content modules into the main classloader,
        // `expected` is empty and this test passes trivially as a canary.
        val expected: Set<ClassLoader> = PluginManagerCore.loadedPlugins
            .asSequence()
            .filter { PluginManagerCore.isLoaded(it.pluginId) }
            .flatMap { d ->
                d.contentModules.asSequence()
                    .mapNotNull { it.pluginClassLoader }
                    .filter { it !== d.pluginClassLoader }
            }
            .toSet()

        val candidates = scriptClassLoaderFactory.productionCandidateLoaders().toSet()
        val missing = expected - candidates
        assertTrue(
            "productionCandidateLoaders() is missing ${missing.size} content-module classloader(s):\n" +
                missing.take(5).joinToString("\n") { "  $it" },
            missing.isEmpty(),
        )
    }

    fun testIdeDelegateFindsClassOnlyVisibleViaContentModuleLoader(): Unit = timeoutRunBlocking(30.seconds) {
        // Regression for https://github.com/jonnyzzz/mcp-steroid/issues/76.
        //
        // Real-IDE failure: the AIA plugin (`com.intellij.ml.llm`) registers
        // AiaActivationAuthFacadeImpl through a content-module classloader, while the
        // facade interface .class also exists in that same content-module loader.
        // The script's `newIdeClassloader()`-delegated `URLClassLoader` used to walk only
        // each descriptor's main `pluginClassLoader`, so it returned the *main*-loader copy
        // of the interface — a different `Class` object than the impl extended. The
        // resulting CCE blocked `service<T>()` for split plugins.
        //
        // The sandbox-trivial check at `testExecCodeClassloaderResolvesContentModuleClasses`
        // cannot exhibit the split (content modules fold into the main CL). This test
        // builds the split synthetically: two URLClassLoaders, only the second has `Foo`,
        // both wired into a `newIdeClassloader()`-style delegate. Without #76's fix the
        // delegate only consults the first loader and `loadClass("Foo")` throws CNFE.
        val root = Files.createTempDirectory("ide-cl-cm")
        val mainJar = createEmptyJar(root, "main.jar")
        val contentJar = createSyntheticClassJar(root, "content.jar", "ScriptClassLoaderCMRegression")

        val mainCl = URLClassLoader(arrayOf(mainJar.toUri().toURL()), null)
        val contentCl = URLClassLoader(arrayOf(contentJar.toUri().toURL()), null)

        val delegate = scriptClassLoaderFactory.newIdeDelegateLoaderForTests(
            listOf(mainCl, contentCl),
        )
        val loaded = delegate.loadClass("ScriptClassLoaderCMRegression")
        assertEquals("ScriptClassLoaderCMRegression", loaded.name)
        assertSame(
            "class must come from the content-module loader, not the main loader",
            contentCl,
            loaded.classLoader,
        )
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

    private fun createEmptyJar(root: Path, name: String = "empty.jar"): Path {
        val jarPath = root.resolve(name)
        JarOutputStream(Files.newOutputStream(jarPath)).use { /* no entries */ }
        return jarPath
    }

    /**
     * Build a jar containing a single synthetic class with the given internal name.
     * The class has no methods or fields; it only needs to be loadable.
     */
    private fun createSyntheticClassJar(root: Path, jarName: String, className: String): Path {
        val jarPath = root.resolve(jarName)
        JarOutputStream(Files.newOutputStream(jarPath)).use { jar ->
            jar.putNextEntry(JarEntry("$className.class"))
            jar.write(syntheticClassBytes(className))
            jar.closeEntry()
        }
        return jarPath
    }

    /** Minimal valid class file: public class with default constructor, no fields/methods. */
    private fun syntheticClassBytes(name: String): ByteArray {
        val cw = org.objectweb.asm.ClassWriter(0)
        cw.visit(
            org.objectweb.asm.Opcodes.V1_8,
            org.objectweb.asm.Opcodes.ACC_PUBLIC or org.objectweb.asm.Opcodes.ACC_SUPER,
            name,
            null,
            "java/lang/Object",
            null,
        )
        val mv = cw.visitMethod(
            org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null,
        )
        mv.visitCode()
        mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
        mv.visitMethodInsn(
            org.objectweb.asm.Opcodes.INVOKESPECIAL,
            "java/lang/Object", "<init>", "()V", false,
        )
        mv.visitInsn(org.objectweb.asm.Opcodes.RETURN)
        mv.visitMaxs(1, 1)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
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
