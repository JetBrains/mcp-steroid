/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ocr.app

import com.jonnyzzz.mcpSteroid.ocr.OcrLevel
import com.jonnyzzz.mcpSteroid.ocr.OcrRect
import com.jonnyzzz.mcpSteroid.ocr.OcrResult
import com.jonnyzzz.mcpSteroid.ocr.OcrTextBlock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.sourceforge.tess4j.ITessAPI
import net.sourceforge.tess4j.Tesseract
import net.sourceforge.tess4j.Word
import org.bytedeco.javacpp.Loader
import org.bytedeco.leptonica.global.leptonica
import org.bytedeco.tesseract.global.tesseract
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Comparator
import javax.imageio.ImageIO
import kotlin.system.exitProcess

private data class OcrOptions(
    val imagePath: Path,
    val language: String,
    val level: OcrLevel,
)

private val json = Json { encodeDefaults = true }

fun main(args: Array<String>) {
    val options = parseArgs(args) ?: return
    try {
        val result = extractText(options)
        println(json.encodeToString(result))
    } catch (e: Throwable) {
        System.err.println("OCR failed: ${e.message}")
        e.printStackTrace(System.err)
        exitProcess(1)
    }
}

private fun parseArgs(args: Array<String>): OcrOptions? {
    val expanded = expandArgFiles(args)
    if (expanded.isEmpty() || expanded.contains("--help")) {
        printUsage()
        return null
    }

    var imagePath: Path? = null
    var language = "eng"
    var level = OcrLevel.TEXT_LINE

    var index = 0
    while (index < expanded.size) {
        when (val arg = expanded[index]) {
            "--image" -> {
                imagePath = expanded.getOrNull(++index)?.let { Paths.get(it) }
            }
            "--lang" -> {
                language = expanded.getOrNull(++index) ?: language
            }
            "--level" -> {
                val raw = expanded.getOrNull(++index)
                if (raw != null) {
                    level = parseLevel(raw)
                }
            }
            else -> {
                System.err.println("Unknown argument: $arg")
                printUsage()
                exitProcess(2)
            }
        }
        index++
    }

    if (imagePath == null) {
        System.err.println("Missing --image argument")
        printUsage()
        exitProcess(2)
    }

    return OcrOptions(imagePath, language, level)
}

/**
 * Expand @argfile references in the argument list.
 * Each line in an argfile becomes a separate argument.
 * Supports the same convention as kotlinc: arguments starting with `@`
 * are treated as paths to argument files.
 */
private fun expandArgFiles(args: Array<String>): List<String> {
    val result = mutableListOf<String>()
    for (arg in args) {
        if (arg.startsWith("@")) {
            val argFile = Paths.get(arg.substring(1))
            if (Files.exists(argFile)) {
                Files.readAllLines(argFile, java.nio.charset.StandardCharsets.UTF_8)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { result.add(it) }
            } else {
                System.err.println("Argument file not found: $argFile")
                exitProcess(2)
            }
        } else {
            result.add(arg)
        }
    }
    return result
}

private fun parseLevel(raw: String): OcrLevel {
    val normalized = raw.replace('-', '_').uppercase()
    return try {
        OcrLevel.valueOf(normalized)
    } catch (e: IllegalArgumentException) {
        System.err.println("Unknown OCR level: $raw")
        printUsage()
        exitProcess(2)
    }
}

private fun printUsage() {
    System.err.println(
        """
        |Usage: ocr-tesseract --image <path> [--lang <lang>] [--level text_line|word]
        |
        |Outputs OCR JSON to stdout.
        """.trimMargin()
    )
}

private fun extractText(options: OcrOptions): OcrResult {
    val image = readImage(options.imagePath)
    ensureNativeLibraries()
    val tesseract = Tesseract()
    tesseract.setDatapath(ensureTessdataDir().toAbsolutePath().toString())
    tesseract.setLanguage(options.language)
    tesseract.setVariable("user_defined_dpi", "300")
    tesseract.setPageSegMode(ITessAPI.TessPageSegMode.PSM_AUTO)

    val words = tesseract.getWords(listOf(image), toTessLevel(options.level))
    val blocks = words.mapNotNull(::toTextBlock)
    return OcrResult(blocks)
}

private fun readImage(path: Path): BufferedImage {
    val image = ImageIO.read(path.toFile())
    return image ?: error("Unable to read OCR image: $path")
}

private fun toTessLevel(level: OcrLevel): Int {
    return when (level) {
        OcrLevel.TEXT_LINE -> ITessAPI.TessPageIteratorLevel.RIL_TEXTLINE
        OcrLevel.WORD -> ITessAPI.TessPageIteratorLevel.RIL_WORD
    }
}

private fun toTextBlock(word: Word): OcrTextBlock? {
    val text = word.text?.trim().orEmpty()
    if (text.isBlank()) return null

    val box = word.boundingBox
    return OcrTextBlock(
        text = text,
        bounds = OcrRect(box.x, box.y, box.width, box.height)
    )
}

private fun ensureTessdataDir(): Path {
    // Find tessdata relative to the application installation directory.
    // Distribution structure: ocr-tesseract/{bin,lib,tessdata}
    val appRoot = findAppRoot()
    val tessdataDir = appRoot.resolve("tessdata")

    if (!Files.isDirectory(tessdataDir)) {
        error("Tessdata directory not found: $tessdataDir. Ensure the application was built with './gradlew installDist'.")
    }

    // Verify required files exist
    val requiredFiles = listOf("eng.traineddata", "osd.traineddata")
    for (file in requiredFiles) {
        val path = tessdataDir.resolve(file)
        if (!Files.exists(path)) {
            error("Required tessdata file not found: $path")
        }
    }

    return tessdataDir
}

private fun findAppRoot(): Path {
    // Try to find the app root from the classloader's jar location
    val jarLocation = OcrResult::class.java.protectionDomain?.codeSource?.location?.toURI()
    if (jarLocation != null) {
        val jarPath = Paths.get(jarLocation)
        // Expected structure: .../lib/ocr-common.jar or .../lib/ocr-tesseract.jar
        // App root is parent of 'lib'
        val parent = jarPath.parent
        if (parent?.fileName?.toString() == "lib") {
            return parent.parent
        }
    }

    // Fallback: check current working directory
    val cwd = Paths.get("").toAbsolutePath()
    if (Files.isDirectory(cwd.resolve("tessdata"))) {
        return cwd
    }

    error("Cannot determine application root directory. Run from the distribution directory or ensure TESSDATA_PREFIX is set.")
}

private fun ensureNativeLibraries() {
    val leptonicaJniLib = Paths.get(Loader.load(leptonica::class.java))
    val tesseractJniLib = Paths.get(Loader.load(tesseract::class.java))

    val leptonicaLibDir = leptonicaJniLib.parent ?: error("Cannot resolve Leptonica JNI directory from $leptonicaJniLib")
    val tesseractLibDir = tesseractJniLib.parent ?: error("Cannot resolve Tesseract JNI directory from $tesseractJniLib")

    val leptonicaVersionedLib = findFirstSharedLibrary(
        leptonicaLibDir,
        "libleptonica.so.",
        "libleptonica."
    )
    val tesseractVersionedLib = findFirstSharedLibrary(
        tesseractLibDir,
        "libtesseract.so.",
        "libtesseract."
    )

    // Tess4J/Lept4J look for unversioned sonames, while JavaCPP provides only versioned files.
    // Create local aliases in JavaCPP cache dirs so JNA binds to matching bundled natives.
    ensureLibraryAlias(leptonicaLibDir, "libleptonica.so", leptonicaVersionedLib)
    ensureLibraryAlias(leptonicaLibDir, "liblept.so", leptonicaVersionedLib)
    ensureLibraryAlias(leptonicaLibDir, "liblept.so.5", leptonicaVersionedLib)
    ensureLibraryAlias(leptonicaLibDir, "libleptonica.dylib", leptonicaVersionedLib)
    ensureLibraryAlias(tesseractLibDir, "libtesseract.so", tesseractVersionedLib)
    ensureLibraryAlias(tesseractLibDir, "libtesseract.dylib", tesseractVersionedLib)

    val existing = System.getProperty("jna.library.path").orEmpty()
    val updated = buildList {
        add(leptonicaLibDir.toString())
        add(tesseractLibDir.toString())
        if (existing.isNotBlank()) add(existing)
    }.joinToString(File.pathSeparator)

    System.setProperty("jna.library.path", updated)
    System.setProperty("jna.nosys", "true")
}

private fun findFirstSharedLibrary(directory: Path, vararg prefixes: String): Path {
    val requiredPrefixes = prefixes.toList()
    Files.list(directory).use { entries ->
        return entries
            .filter {
                Files.isRegularFile(it) && requiredPrefixes.any { prefix ->
                    it.fileName.toString().startsWith(prefix)
                }
            }
            .sorted(Comparator.comparing<Path, String> { it.fileName.toString() }.reversed())
            .findFirst()
            .orElseThrow {
                IllegalStateException(
                    "No shared library matching any of ${requiredPrefixes.joinToString { "'$it*'" }} found in $directory"
                )
            }
    }
}

private fun ensureLibraryAlias(directory: Path, aliasName: String, target: Path) {
    val alias = directory.resolve(aliasName)
    if (Files.exists(alias)) return

    try {
        Files.createSymbolicLink(alias, target.fileName)
    } catch (_: UnsupportedOperationException) {
        Files.copy(target, alias, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: SecurityException) {
        Files.copy(target, alias, StandardCopyOption.REPLACE_EXISTING)
    }
}
