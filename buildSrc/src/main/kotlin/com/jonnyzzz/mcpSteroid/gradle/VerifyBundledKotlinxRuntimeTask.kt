/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * Forks a JVM with classpath = every jar under the IDE's `lib/` dir + the
 * probe jar, and runs `KotlinxRuntimeProbe.main()`. The probe exercises the
 * kotlinx APIs our plugin uses against the IDE-bundled `kotlinx-coroutines`
 * and `kotlinx-serialization`. A `LinkageError` / `NoClassDefFoundError`
 * surfaces as a non-zero exit code and the task fails the build.
 *
 * This is the JVM-side counterpart to the build-time binary equality
 * check (`VerifyBundledKotlinCompatibilityTask`): the former proves the
 * versions look right on paper, this one proves the bytecode actually
 * links + runs against what IDE 261 ships.
 *
 * Inputs:
 *  - [ideRoot] points at the unpacked IDE directory produced by
 *    `LocalIdeProvisioner.resolveAndUnpackLocally` (the lib child is
 *    what gets put on the probe's classpath).
 *  - [probeClasspath] carries the compiled `:intellij-downloader` jar (or
 *    just the slice containing `KotlinxRuntimeProbe`). Critically, the
 *    classpath MUST NOT include any `kotlinx-coroutines-core` or
 *    `kotlinx-serialization-...` jars on its own — those must resolve
 *    from the IDE's lib dir.
 *  - [probeArgs] forwarded as positional `main(args)` arguments;
 *    typically the IDE major + full-build for logging.
 *
 * Output: a UTF-8 report at [reportFile] capturing the probe's stdout/stderr.
 */
abstract class VerifyBundledKotlinxRuntimeTask
@Inject constructor(private val execOps: ExecOperations) : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val ideRoot: DirectoryProperty

    @get:Classpath
    abstract val probeClasspath: ConfigurableFileCollection

    @get:Input
    abstract val probeMainClass: Property<String>

    @get:Input
    @get:Optional
    abstract val probeArgs: ListProperty<String>

    @get:Input
    @get:Optional
    abstract val javaExecutable: Property<String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val ideDir = ideRoot.get().asFile
        val ideLib = File(ideDir, "lib")
        require(ideLib.isDirectory) {
            "IDE lib/ not found at $ideLib — pass an unpacked IDE root, not the .app bundle or the .tar.gz."
        }

        val ideLibJars = ideLib.listFiles { f -> f.isFile && f.name.endsWith(".jar") }.orEmpty().sortedBy { it.name }
        require(ideLibJars.isNotEmpty()) {
            "No .jar files under $ideLib — the IDE distribution looks incomplete."
        }

        val probeJars = probeClasspath.files.toList()
        require(probeJars.isNotEmpty()) {
            "probeClasspath must include at least the compiled probe jar."
        }

        // Defense-in-depth: refuse to run if the probe classpath itself
        // carries any external `kotlinx-*` jar. IDE 261/262 bundle several
        // kotlinx families (coroutines, serialization-{core,json,cbor,protobuf},
        // io, html, datetime, collections-immutable). ANY of them on the probe
        // classpath would silently shadow the IDE-bundled copy and defeat the
        // purpose of the probe — reject the whole family.
        val stowaway = probeJars.filter { jar -> jar.name.startsWith("kotlinx-") }
        require(stowaway.isEmpty()) {
            "probeClasspath must not carry external kotlinx-* jars; would shadow " +
                "the IDE bundle. Found: ${stowaway.joinToString { it.name }}"
        }

        val fullClasspath = (ideLibJars + probeJars).joinToString(File.pathSeparator) { it.absolutePath }
        val java = javaExecutable.orNull
            ?: System.getProperty("java.home")?.let { "$it/bin/java" }
            ?: "java"

        // Java 9+ `@argfile` syntax — bypass the Windows ~32 KB command-line
        // limit. IDE `lib/` carries 100+ jars and the joined classpath
        // routinely exceeds 40 KB; on Linux/Mac the kernel ARG_MAX caps are
        // much higher (typically 128 KB+) so it works there too, but Windows
        // CreateProcess errors with "command line exceed operating system
        // limits" before the JVM ever starts.
        //
        // Argfile syntax: one argument per line, double-quote any token that
        // contains whitespace (Windows install paths regularly do), and
        // escape backslashes inside quoted tokens. We quote unconditionally
        // because the classpath token always contains path separators that
        // could be misread.
        val argFile = temporaryDir.resolve("kotlinx-runtime-probe.argfile")
        argFile.parentFile.mkdirs()
        argFile.writeText(
            buildString {
                appendLine("-cp")
                // Inside a double-quoted argfile token, backslashes must be
                // escaped (`\\`). The classpath has a lot of them on Windows
                // — encode once, write once.
                val classpathEscaped = fullClasspath.replace("\\", "\\\\").replace("\"", "\\\"")
                append('"'); append(classpathEscaped); appendLine('"')
                appendLine(probeMainClass.get())
                probeArgs.getOrElse(emptyList()).forEach { appendLine(it) }
            },
            Charsets.UTF_8,
        )

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val result = execOps.exec {
            commandLine = listOf(java, "@${argFile.absolutePath}")
            standardOutput = stdout
            errorOutput = stderr
            isIgnoreExitValue = true
        }

        val out = stdout.toString(Charsets.UTF_8)
        val err = stderr.toString(Charsets.UTF_8)
        val report = buildString {
            appendLine("IDE root      : $ideDir")
            appendLine("Probe main    : ${probeMainClass.get()}")
            appendLine("Exit code     : ${result.exitValue}")
            appendLine()
            appendLine("=== STDOUT ===")
            append(out)
            appendLine("=== STDERR ===")
            append(err)
        }
        val report_ = reportFile.get().asFile
        report_.parentFile?.mkdirs()
        report_.writeText(report)

        if (result.exitValue != 0) {
            throw GradleException(
                "Bundled kotlinx runtime probe failed against IDE at $ideDir " +
                    "(exit ${result.exitValue}). Report: $report_\n" +
                    err.lines().take(20).joinToString("\n").ifBlank { "(no stderr)" }
            )
        }
        if (!out.contains("RUNTIME_PROBE_OK")) {
            throw GradleException(
                "Probe exited 0 but didn't print RUNTIME_PROBE_OK — likely a silent skip. " +
                    "Report: $report_"
            )
        }
    }
}
