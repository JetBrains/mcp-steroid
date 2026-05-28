/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile
import kotlin.time.Duration.Companion.seconds

@TestApplication
class KotlincCommandLineBuilderIntegrationTest {

    @Test
    fun compilesJarWithDirectoryClasspathAndJvmTarget(): Unit = timeoutRunBlocking(90.seconds) {
        val root = Files.createTempDirectory("kotlinc-builder")
        val outputJar = root.resolve("out/compiled.jar")
        val resourceEntry = classpathEntryFromResource(KotlincCommandLineBuilderIntegrationTest::class.java)
        val classpathDir = if (Files.isDirectory(resourceEntry)) {
            resourceEntry
        } else {
            createClassDirectory(root, KotlincCommandLineBuilderIntegrationTest::class.java)
        }
        val source = root.resolve("Main.kt")
        Files.writeString(
            source,
            """
            import ${KotlincCommandLineBuilderIntegrationTest::class.java.name}

            fun main(): String = ${KotlincCommandLineBuilderIntegrationTest::class.java.name}::class.java.name
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )

        val commandLine = KotlincCommandLineBuilder(outputJar)
            .addSource(source)
            .addClasspathEntry(classpathDir)
            .build()

        assertTrue(commandLine.args.contains("-jvm-target"))
        // jvm-target tracks the JBR the test runs on (DEFAULT_JVM_TARGET reads
        // java.specification.version). 253's JBR was JDK 21; 261's JBR is JDK 25.
        assertTrue(commandLine.args.contains(KotlincCommandLineBuilder.DEFAULT_JVM_TARGET))

        kotlincProcessClient.kotlinc(commandLine.args)

        assertTrue(Files.exists(commandLine.outputJar), "Expected output jar at ${commandLine.outputJar}")
        ZipFile(commandLine.outputJar.toFile()).use { zip ->
            assertNotNull(zip.getEntry("MainKt.class"), "Expected MainKt.class in jar")
        }
    }

    @Test
    fun compilesJarWithNoStdLibClasspathFromIdeClasspath(): Unit = timeoutRunBlocking(90.seconds) {
        val root = Files.createTempDirectory("kotlinc-nostdlib")
        val outputJar = root.resolve("out/nostdlib.jar")
        val ideClasspathEntries = ideClasspathEntries()
        val resourceEntry = classpathEntryFromResource(KotlincCommandLineBuilderIntegrationTest::class.java)
        val classpathJar = if (Files.isRegularFile(resourceEntry)) {
            resourceEntry
        } else {
            createClassJar(root, KotlincCommandLineBuilderIntegrationTest::class.java)
        }
        val source = root.resolve("Test.kt")
        Files.writeString(
            source,
            """
            import ${KotlincCommandLineBuilderIntegrationTest::class.java.name}

            fun marker(): String = ${KotlincCommandLineBuilderIntegrationTest::class.java.simpleName}::class.java.name
            fun main() {
                println(${KotlincCommandLineBuilderIntegrationTest::class.java.simpleName}::class.java.name)
            }
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )

        val builder = KotlincCommandLineBuilder(outputJar)
            .addSource(source)
            .withNoStdLib(true)
        for (entry in ideClasspathEntries) {
            builder.addClasspathEntry(entry)
        }
        builder.addClasspathEntry(classpathJar)
        val commandLine = builder.build()

        assertTrue(commandLine.args.contains("-no-stdlib"))

        kotlincProcessClient.kotlinc(commandLine.args)

        assertTrue(Files.exists(commandLine.outputJar), "Expected output jar at ${commandLine.outputJar}")
        ZipFile(commandLine.outputJar.toFile()).use { zip ->
            assertNotNull(zip.getEntry("TestKt.class"), "Expected TestKt.class in jar")
        }

        val runtimeUrls = buildList {
            add(commandLine.outputJar)
            addAll(ideClasspathEntries)
            add(classpathJar)
        }.map { it.toUri().toURL() }
            .toTypedArray()
        URLClassLoader(runtimeUrls, null).use { loader ->
            val klass = loader.loadClass("TestKt")
            val method = klass.getDeclaredMethod("marker")
            val result = method.invoke(null) as String
            assertEquals(KotlincCommandLineBuilderIntegrationTest::class.java.name, result)
        }
    }

    @Test
    fun compilesJarWithClasspathArgFile(): Unit = timeoutRunBlocking(90.seconds) {
        val root = Files.createTempDirectory("kotlinc-argfile")
        val outputJar = root.resolve("out/argfile.jar")
        val classpathEntry = classpathEntryFromResource(KotlincCommandLineBuilderIntegrationTest::class.java)
        val classpathDir = if (Files.isDirectory(classpathEntry)) {
            classpathEntry
        } else {
            createClassDirectory(root, KotlincCommandLineBuilderIntegrationTest::class.java)
        }
        val source = root.resolve("Main.kt")
        Files.writeString(
            source,
            """
            import ${KotlincCommandLineBuilderIntegrationTest::class.java.name}

            fun main(): String = ${KotlincCommandLineBuilderIntegrationTest::class.java.name}::class.java.name
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )

        val argFile = root.resolve("kotlinc.args")
        val commandLine = KotlincCommandLineBuilder(outputJar)
            .addSource(source)
            .addClasspathEntry(classpathDir)
            .build()
            .toArgFile(argFile)

        assertTrue(Files.exists(argFile), "Expected argfile to be created at $argFile")
        assertTrue(commandLine.args.any { it == "@${argFile.toAbsolutePath()}" })
        assertFalse(commandLine.args.contains("-classpath"), "Expected -classpath to be omitted from command line when using argfile")

        kotlincProcessClient.kotlinc(commandLine.args)

        assertTrue(Files.exists(commandLine.outputJar), "Expected output jar at ${commandLine.outputJar}")
        ZipFile(commandLine.outputJar.toFile()).use { zip ->
            assertNotNull(zip.getEntry("MainKt.class"), "Expected MainKt.class in jar")
        }
    }

    @Test
    fun compilesJarWithClasspathArgFileContainingSpaces(): Unit = timeoutRunBlocking(90.seconds) {
        val root = Files.createTempDirectory("kotlinc-spaces")
        val outputJar = root.resolve("out/spaces.jar")

        // Create a directory with a space in the name, simulating "JPA Model" plugin path
        val spacedDir = root.resolve("JPA Model/classes")
        val classpathDir = createClassDirectoryAt(spacedDir, KotlincCommandLineBuilderIntegrationTest::class.java)

        val source = root.resolve("Main.kt")
        Files.writeString(
            source,
            """
            import ${KotlincCommandLineBuilderIntegrationTest::class.java.name}

            fun main(): String = ${KotlincCommandLineBuilderIntegrationTest::class.java.name}::class.java.name
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )

        val argFile = root.resolve("kotlinc.args")
        val commandLine = KotlincCommandLineBuilder(outputJar)
            .addSource(source)
            .addClasspathEntry(classpathDir)
            .build()
            .toArgFile(argFile)

        // Verify the argfile uses whole-arg quoting (not IntelliJ per-character style)
        val argFileContent = Files.readString(argFile)
        assertTrue(
            argFileContent.contains("\""),
            "Argfile should contain quoted classpath due to space in path, but was: $argFileContent",
        )
        assertFalse(
            argFileContent.contains("\" \""),
            "Argfile should NOT use per-character quoting (IntelliJ style), but was: $argFileContent",
        )

        // Actually compile with kotlinc to verify the argfile is parsed correctly
        kotlincProcessClient.kotlinc(commandLine.args)

        assertTrue(Files.exists(commandLine.outputJar), "Expected output jar at ${commandLine.outputJar}")
        ZipFile(commandLine.outputJar.toFile()).use { zip ->
            assertNotNull(zip.getEntry("MainKt.class"), "Expected MainKt.class in jar")
        }
    }

    private fun ideClasspathEntries(): List<Path> {
        val entries = scriptClassLoaderFactory.ideClasspath()
        assertTrue(entries.isNotEmpty(), "Expected ideClasspath to contain entries")
        return entries
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

    private fun createClassDirectory(root: Path, klass: Class<*>): Path {
        return createClassDirectoryAt(root.resolve("class-dir"), klass)
    }

    private fun createClassDirectoryAt(outputDir: Path, klass: Class<*>): Path {
        val resourcePath = klass.name.replace('.', '/') + ".class"
        val target = outputDir.resolve(resourcePath)
        Files.createDirectories(target.parent)
        Files.write(target, classBytes(klass))
        return outputDir
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
