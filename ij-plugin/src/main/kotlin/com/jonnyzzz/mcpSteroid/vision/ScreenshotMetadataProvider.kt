/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.vision

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import java.awt.Component
import java.nio.file.Path

/**
 * Context passed to screenshot metadata providers during capture.
 *
 * @property project The IntelliJ project
 * @property component The captured AWT component
 * @property executionDir Directory where metadata files are stored
 * @property collectedMetadata Results from providers that have already completed (keyed by type)
 */
data class ScreenCaptureContext(
    val project: Project,
    val component: Component,
    val executionDir: Path,
    val collectedMetadata: List<ScreenshotMetadata> = emptyList(),
) {
    /**
     * Create a new context with additional metadata from a provider.
     */
    fun withMetadata(metadata: List<ScreenshotMetadata>): ScreenCaptureContext {
        return copy(collectedMetadata = collectedMetadata + metadata)
    }

    /**
     * Find all image metadata from collected results.
     */
    fun findImages(): List<ScreenshotMetadata> =
        collectedMetadata.filter { it.isImage() }

    /**
     * Find first image metadata from collected results.
     */
    fun findFirstImage(): ScreenshotMetadata? = findImages().firstOrNull()
}

/**
 * Result from a metadata provider.
 */
sealed class ProviderResult {
    /**
     * Provider produced one or more metadata items.
     */
    data class Success(val metadata: List<ScreenshotMetadata>) : ProviderResult() {
        constructor(vararg items: ScreenshotMetadata) : this(items.toList())
        constructor(item: ScreenshotMetadata) : this(listOf(item))
    }

    /**
     * Provider depends on other providers to run first.
     * Will be retried after other providers complete.
     */
    data object DependsOnOthers : ProviderResult()

    /**
     * Provider skipped (not applicable for this context).
     */
    data object Skip : ProviderResult()
}

/**
 * Metadata produced by a provider.
 *
 * Can contain either text content or binary content (for images).
 */
data class ScreenshotMetadata(
    /** Unique identifier for this metadata type (e.g., "swing-tree", "ocr", "screenshot") */
    val type: String,
    /** File name for storing this metadata */
    val fileName: String,
    /** MIME type of the content */
    val mimeType: String = "text/plain",
    /** Text content (mutually exclusive with binaryContent) */
    val content: String? = null,
    /** Binary content for images (mutually exclusive with content) */
    val binaryContent: ByteArray? = null,
) {
    init {
        require(content != null || binaryContent != null) {
            "Either content or binaryContent must be provided"
        }
        require(content == null || binaryContent == null) {
            "Cannot provide both content and binaryContent"
        }
    }

    /**
     * Check if this metadata contains an image.
     */
    fun isImage(): Boolean = mimeType.startsWith("image/")

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenshotMetadata) return false
        return type == other.type &&
                fileName == other.fileName &&
                mimeType == other.mimeType &&
                content == other.content &&
                binaryContent.contentEquals(other.binaryContent)
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + (content?.hashCode() ?: 0)
        result = 31 * result + (binaryContent?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Extension point for providing screenshot metadata.
 *
 * Providers are called iteratively:
 * 1. All providers are called once
 * 2. Providers returning DependsOnOthers are retried after others complete
 * 3. Each provider is called at most once per capture (unless it returns DependsOnOthers)
 * 4. Iteration continues until all providers have returned Success or Skip
 *
 * The context is updated with collected metadata after each provider completes,
 * allowing dependent providers to access results from earlier providers.
 */
interface ScreenshotMetadataProvider {
    /**
     * Unique identifier for this provider type.
     * Used to track which providers have completed and for dependency resolution.
     */
    val type: String

    /**
     * When true, this provider is NOT run during [VisionService.capture]; instead it is
     * queued on the project service scope and runs AFTER capture returns. Use for heavy /
     * external work (e.g. OCR via an external Tesseract process) that must never block the
     * screenshot — and therefore must never block the DialogKiller's capture→close path.
     * Deferred providers may still depend on inline ones (the image is already written).
     */
    val deferred: Boolean get() = false

    /**
     * Provide metadata for the captured screenshot.
     *
     * @param context The capture context containing project, component, image, and collected metadata
     * @return Provider result indicating success, dependency, or skip
     */
    suspend fun provide(context: ScreenCaptureContext): ProviderResult

    companion object {
        val EP_NAME: ExtensionPointName<ScreenshotMetadataProvider> =
            ExtensionPointName.create("com.jonnyzzz.mcp-steroid.screenshotMetadataProvider")
    }
}
