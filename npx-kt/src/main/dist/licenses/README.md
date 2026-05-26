# Bundled licenses

This directory aggregates the licensing material of every third-party
component shipped inside the devrig distribution. Files
also exist in each component's own folder; this index is the
operator-facing single point of reference.

## Components

| Component | License location |
|---|---|
| 7-Zip (NSIS-capable Unix builds)     | `seven-zip/License.txt`        |
| MCP Steroid (devrig + IntelliJ plugin) | `mcp-steroid/EULA`       |

## License summaries

* **7-Zip** — LGPL v2.1 or later, with unRAR restriction (we don't
  ship the RAR codec). Upstream: https://www.7-zip.org/license.txt.
* **MCP Steroid** — see `mcp-steroid/EULA`.

## Externally fetched components

`devrig` does not bundle a JVM. It expects `java` on the
system PATH (or `JAVA_HOME` set). This package intentionally does not
patch its launcher with a Gradle-wrapper-level JVM downloader. When a
future release adds a bootstrap that auto-fetches a per-OS Amazon
Corretto 21 JDK, the Corretto legal text will travel with that
download into `~/.mcp-steroid/jdk/<version>/legal/` rather than living
in this package. See `TODO-NPX-BOOTSTRAPPER.md` in the repo root for
the plan.
