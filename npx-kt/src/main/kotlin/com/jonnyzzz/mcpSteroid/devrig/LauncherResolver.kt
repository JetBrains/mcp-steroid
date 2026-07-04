/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.ideDownloader.HostOs
import com.jonnyzzz.mcpSteroid.ideDownloader.resolveHostOs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

data class LauncherResolution(
    val launcherPath: String,
    val launcherAbsolutePath: Path,
    val productCode: String?,
    val buildNumber: String?,
)

class LauncherResolver(
    private val hostOs: HostOs = resolveHostOs(),
) {
    fun resolve(bundleDir: Path): LauncherResolution {
        val productInfoPath = productInfoPath(bundleDir)
        val productInfo = productInfoJson.decodeFromString<ProductInfo>(Files.readString(productInfoPath))
        val osName = hostOs.productInfoOsName()
        val launch = productInfo.launch.firstOrNull { it.os == osName }
            ?: error(
                "product-info.json at $productInfoPath has no launch entry for $osName. " +
                    "Available OS entries: ${productInfo.launch.joinToString { it.os }}"
            )
        require(launch.launcherPath.isNotBlank()) {
            "product-info.json at $productInfoPath has an empty launcherPath for $osName"
        }
        val bundleRoot = bundleDir.normalize()
        val launcherAbsolutePath = resolveLauncherPath(bundleRoot, productInfoPath, launch.launcherPath)
        val launcherPath = bundleRoot.relativize(launcherAbsolutePath).toString().replace('\\', '/')
        return LauncherResolution(
            launcherPath = launcherPath,
            launcherAbsolutePath = launcherAbsolutePath,
            productCode = productInfo.productCode,
            buildNumber = productInfo.buildNumber,
        )
    }

    private fun resolveLauncherPath(bundleRoot: Path, productInfoPath: Path, launcherPath: String): Path {
        val absolutePath = if (productInfoPath.parent != bundleRoot && launcherPath.startsWith("..")) {
            productInfoPath.parent.resolve(launcherPath).normalize()
        } else {
            bundleRoot.resolve(launcherPath).normalize()
        }
        require(absolutePath.startsWith(bundleRoot)) {
            "product-info.json at $productInfoPath points launcherPath outside bundle: $launcherPath"
        }
        return absolutePath
    }

    private fun productInfoPath(bundleDir: Path): Path {
        val candidates = listOf(
            bundleDir.resolve("product-info.json"),
            bundleDir.resolve("Contents/Resources/product-info.json"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error(
                "product-info.json not found under $bundleDir. " +
                    "Checked: ${candidates.joinToString()}"
            )
    }
}

private fun HostOs.productInfoOsName(): String = when (this) {
    HostOs.MAC -> "macOS"
    HostOs.LINUX -> "Linux"
    HostOs.WINDOWS -> "Windows"
}

@Serializable
private data class ProductInfo(
    val productCode: String? = null,
    val buildNumber: String? = null,
    val launch: List<ProductLaunch> = emptyList(),
)

@Serializable
private data class ProductLaunch(
    val os: String,
    val launcherPath: String,
)

private val productInfoJson = Json {
    ignoreUnknownKeys = true
}
