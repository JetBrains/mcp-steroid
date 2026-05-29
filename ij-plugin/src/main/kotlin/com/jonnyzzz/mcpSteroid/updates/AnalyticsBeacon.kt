/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.updates

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.jonnyzzz.mcpSteroid.PluginDescriptorProvider
import com.posthog.server.PostHog
import com.posthog.server.PostHogCaptureOptions
import com.posthog.server.PostHogConfig
import com.posthog.server.PostHogInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

inline val analyticsBeacon: AnalyticsBeacon get() = service()

/**
 * Minimalistic analytics via PostHog.
 *
 * Tracks only:
 * - IDE build / version / product code
 * - Plugin version
 * - Project ID (random UUID, no real project name)
 * - exec_code call events (success / error)
 * - execute_feedback events (success rating, explanation summary)
 * - status_score events (0-100 score as dedicated metric)
 * - mcp_session_initialized events (MCP client + server name/version on handshake)
 *
 * Registry key: mcp.steroid.analytics.enabled (default: true)
 */
@Service(Service.Level.APP)
class AnalyticsBeacon(
    private val coroutineScope: CoroutineScope
) : Disposable {
    private val log = thisLogger()

    private val posthog: PostHogInterface? by lazy {
        try {
            val config = PostHogConfig
                .builder("phc_IPtbjwwy9YIGg0YNHNxYBePijvTvHEcKAjohah6obYW")
                .host("https://us.i.posthog.com")
                .flushIntervalSeconds(15)
                .flushAt(10)
                .maxQueueSize(50)
                .maxBatchSize(5)
                .preloadFeatureFlags(false)
                .remoteConfig(false)
                .sendFeatureFlagEvent(false)
                .build()

            val hog = PostHog.with(config)
            Disposer.register(this) {
                hog.flush()
                hog.close()
            }

            hog
        } catch (e: Exception) {
            log.debug("PostHog init failed", e)
            null
        }
    }

    override fun dispose() = Unit

    fun runHeartbeat() {
        coroutineScope.launch {
            while (isActive) {
                yield()
                delay(30.minutes)
                capture("heartbeat")
            }
        }
    }

    fun flush() {
        posthog?.flush()
    }

    fun capture(event: String, project: Project? = null, properties: Map<String, Any> = emptyMap()) {
        if (!Registry.`is`("mcp.steroid.analytics.enabled", true)) return

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val enrichedProps = properties.toMutableMap()

                // Add project ID if project is provided
                if (project != null) {
                    val projectId = getOrCreateProjectId(project)
                    enrichedProps["project"] = projectId
                }

                sendEventInternal(event, enrichedProps)
            } catch (e: Exception) {
                log.debug("Analytics capture failed", e)
            }
        }
    }

    /**
     * Capture a status score event (0-100).
     * This is a dedicated event type for tracking success/quality metrics.
     *
     * @param score Score from 0 to 100
     * @param context Additional context (e.g., "code_execution", "feedback")
     * @param project Project to associate with this score
     * @param properties Additional properties to include
     */
    fun captureScore(score: Int, context: String, project: Project? = null, properties: Map<String, Any> = emptyMap()) {
        require(score in 0..100) { "Score must be between 0 and 100, got: $score" }

        val enrichedProps = properties.toMutableMap()
        enrichedProps["score"] = score
        enrichedProps["context"] = context

        capture("status_score", project, enrichedProps)
    }

    internal fun sendEventInternal(event: String, properties: Map<String, Any>) {
        val ph = posthog ?: return
        val appInfo = ApplicationInfo.getInstance()
        val pluginVersion = PluginDescriptorProvider.getInstance().version

        val opts = PostHogCaptureOptions.builder()
        properties.forEach { (k, v) ->
            opts.property(k, v)
        }

        opts.property("ide_build", appInfo.build.asString())
        opts.property("ide_version", appInfo.fullVersion)
        opts.property("ide_product", appInfo.build.productCode)
        opts.property("plugin_version", pluginVersion)

        opts.appendFeatureFlags(false)
        opts.timestamp(LocalDateTime.now().toInstant(ZoneOffset.UTC))

        ph.capture(distinctId(), "ide_$event", opts.build())
    }

    private fun distinctId(): String {
        val props = PropertiesComponent.getInstance()
        return props.getValue("mcp.steroid.analytics.distinct.id")
            ?: UUID.randomUUID().toString().also { props.setValue("mcp.steroid.analytics.distinct.id", it) }
    }

    private fun getOrCreateProjectId(project: Project): String {
        val props = PropertiesComponent.getInstance(project)
        return props.getValue("mcp.steroid.analytics.project.id")
            ?: UUID.randomUUID().toString().also { props.setValue("mcp.steroid.analytics.project.id", it) }
    }
}
