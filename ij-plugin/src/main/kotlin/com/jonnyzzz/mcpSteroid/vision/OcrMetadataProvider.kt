/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.vision

import com.intellij.openapi.diagnostic.thisLogger
import com.jonnyzzz.mcpSteroid.ocr.OcrLevel
import com.jonnyzzz.mcpSteroid.ocr.OcrProcessClient
import com.jonnyzzz.mcpSteroid.ocr.OcrResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files

/**
 * Provides OCR text extraction metadata for screenshots.
 *
 * Uses the bundled Tesseract OCR engine to extract text from images.
 * Processes all images in the collected metadata and outputs a Markdown
 * file with detected text blocks and their positions.
 *
 * Depends on image providers (e.g., ScreenshotImageProvider) to run first.
 */
class OcrMetadataProvider : ScreenshotMetadataProvider {
    private val log = thisLogger()

    override val type: String = TYPE

    // OCR shells out to the bundled Tesseract (external process) — heavy and irrelevant to
    // unblocking a modal. Run it AFTER capture returns so it never blocks the screenshot
    // (and therefore never blocks the DialogKiller's capture→close→restore-modality path).
    override val deferred: Boolean = true

    override suspend fun provide(context: ScreenCaptureContext): ProviderResult {
        val ocrClient = OcrProcessClient.getInstance()

        // Check if OCR is available
        if (!ocrClient.isAvailable()) {
            return ProviderResult.Skip
        }

        // Find all images from collected metadata
        val images = context.findImages()
        if (images.isEmpty()) {
            // No images yet - depend on other providers
            return ProviderResult.DependsOnOthers
        }

        return try {
            // Process all images
            val allResults = mutableListOf<Pair<String, OcrResult>>()

            for (imageMetadata in images) {
                val imagePath = context.executionDir.resolve(imageMetadata.fileName)
                if (!Files.exists(imagePath)) {
                    continue
                }

                val result = withContext(Dispatchers.IO) {
                    ocrClient.extractText(imagePath, language = "eng", level = OcrLevel.TEXT_LINE)
                }
                allResults.add(imageMetadata.fileName to result)
            }

            if (allResults.isEmpty()) {
                return ProviderResult.Skip
            }

            val content = buildOcrMarkdown(allResults)
            ProviderResult.Success(
                ScreenshotMetadata(
                    type = TYPE,
                    fileName = FILE_NAME,
                    mimeType = "text/markdown",
                    content = content,
                )
            )
        } catch (e: Throwable) {
            log.warn("OCR failed for images: ${images.joinToString(", ") { it.fileName }}: ${e.message}", e)
            // OCR failed - skip rather than fail the entire capture
            ProviderResult.Skip
        }
    }

    private fun buildOcrMarkdown(results: List<Pair<String, OcrResult>>): String {
        val builder = StringBuilder()
        builder.appendLine("# OCR Results")
        builder.appendLine()

        val totalBlocks = results.sumOf { it.second.blocks.size }
        if (totalBlocks == 0) {
            builder.appendLine("No text detected in the screenshot(s).")
            return builder.toString()
        }

        for ((imageName, result) in results) {
            if (results.size > 1) {
                builder.appendLine("## Image: $imageName")
                builder.appendLine()
            }

            if (result.blocks.isEmpty()) {
                builder.appendLine("No text detected.")
                builder.appendLine()
                continue
            }

            builder.appendLine("Detected ${result.blocks.size} text block(s):")
            builder.appendLine()

            for ((index, block) in result.blocks.withIndex()) {
                val bounds = block.bounds
                builder.appendLine("### Block ${index + 1}")
                builder.appendLine("- Position: (${bounds.x}, ${bounds.y})")
                builder.appendLine("- Size: ${bounds.width}x${bounds.height}")
                builder.appendLine("- Text: \"${block.text}\"")
                builder.appendLine()
            }
        }

        return builder.toString()
    }

    companion object {
        const val TYPE = "ocr"
        const val FILE_NAME = "screenshot-ocr.md"
    }
}
