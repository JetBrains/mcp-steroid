# Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# /// script
# requires-python = ">=3.10"
# ///
"""Generates updatePlugins.xml for IntelliJ custom plugin repository.

Downloads the release ZIP, extracts the plugin version from the JAR's
plugin.xml, and generates the updatePlugins.xml with the exact version
from the artifact. The download URL comes from the GitHub release API.
"""

import io
import re
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from urllib.request import urlopen
from xml.dom.minidom import Document

PLUGIN_ID = "com.jonnyzzz.mcp-steroid"

GITHUB_RELEASE_URL_PATTERN = re.compile(
    r"^https://github\.com/jonnyzzz/mcp-steroid/releases/download/[^/]+/.+\.zip$"
)


def validate_url(zip_url: str, version: str) -> None:
    """Validate the download URL format before generating XML."""
    errors = []

    if not zip_url.startswith("https://"):
        errors.append(f"must start with https://, got: {zip_url}")

    if not zip_url.endswith(".zip"):
        errors.append(f"must end with .zip, got: {zip_url}")

    if not GITHUB_RELEASE_URL_PATTERN.match(zip_url):
        errors.append(
            f"must match GitHub release URL pattern "
            f"(https://github.com/jonnyzzz/mcp-steroid/releases/download/TAG/FILE.zip), "
            f"got: {zip_url}"
        )

    if version not in zip_url:
        errors.append(f"must contain version '{version}' in the URL, got: {zip_url}")

    if errors:
        for err in errors:
            print(f"Error: download URL {err}", file=sys.stderr)
        sys.exit(1)


def extract_plugin_version(zip_url: str) -> tuple[str, str, str]:
    """Download the release ZIP and extract id, version, since-build from plugin.xml.

    Returns (plugin_id, plugin_version, since_build).
    """
    print(f"Downloading {zip_url} ...", file=sys.stderr)
    with urlopen(zip_url) as resp:
        zip_data = resp.read()

    outer_zip = zipfile.ZipFile(io.BytesIO(zip_data))

    # Find the ij-plugin JAR inside the ZIP
    jar_names = [n for n in outer_zip.namelist() if "/lib/ij-plugin-" in n and n.endswith(".jar")]
    if not jar_names:
        print("Error: no ij-plugin-*.jar found in release ZIP", file=sys.stderr)
        sys.exit(1)

    jar_data = outer_zip.read(jar_names[0])
    jar_zip = zipfile.ZipFile(io.BytesIO(jar_data))

    with jar_zip.open("META-INF/plugin.xml") as f:
        tree = ET.parse(f)

    root = tree.getroot()
    plugin_id = root.findtext("id")
    plugin_version = root.findtext("version")
    idea_version_el = root.find("idea-version")
    since_build = idea_version_el.get("since-build") if idea_version_el is not None else None

    if not plugin_id or not plugin_version or not since_build:
        print(
            f"Error: plugin.xml missing required fields: "
            f"id={plugin_id}, version={plugin_version}, since-build={since_build}",
            file=sys.stderr,
        )
        sys.exit(1)

    assert plugin_id == PLUGIN_ID, f"Expected plugin id {PLUGIN_ID}, got {plugin_id}"

    print(f"Plugin version from JAR: {plugin_version} (since-build: {since_build})", file=sys.stderr)
    return plugin_id, plugin_version, since_build


def markdown_to_html(notes_path: Path, version: str) -> str:
    """Convert release notes markdown to HTML for change-notes."""
    if not notes_path.exists():
        return f"<p>Release notes not available for version {version}.</p>"

    lines = notes_path.read_text().splitlines()
    html_parts = [f"<h2>What's New in v{version}</h2>"]
    in_section = False

    for line in lines:
        if not in_section:
            if line.startswith("## "):
                in_section = True
            else:
                continue

        if in_section:
            if line.startswith("## "):
                heading = line[3:]
                html_parts.append(f"<h3>{heading}</h3>")
            elif line.startswith("- "):
                item = line[2:]
                # Convert **bold** to <b>bold</b>
                item = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", item)
                html_parts.append(f"<li>{item}</li>")
            elif line.strip():
                html_parts.append(f"<p>{line}</p>")

    html_parts.append(
        f'<p>Full release notes: <a href="https://mcp-steroid.jonnyzzz.com/releases/{version}/">'
        f"mcp-steroid.jonnyzzz.com/releases/{version}/</a></p>"
    )
    return "\n".join(html_parts)


DESCRIPTION_HTML = """\
<p><b>MCP Steroid</b> brings the full power of the IntelliJ Platform to AI agents \
through the Model Context Protocol (MCP).</p>
<p>IntelliJ platform works for AI agents as great as for human developers.</p>
<ul>
<li><b>8 MCP Tools:</b> Control IntelliJ IDEA programmatically \u2014 execute code, \
take screenshots, debug, and more</li>
<li><b>58 MCP Resources:</b> Comprehensive guides covering LSP, IDE operations, \
debugger, tests, VCS, and more</li>
<li><b>Vision Capabilities:</b> AI agents can see your IDE with screenshots and OCR</li>
<li><b>Deep Integration:</b> Access PSI, inspections, refactorings, and full \
IntelliJ Platform API</li>
</ul>
<p>Compatible with all IntelliJ Platform-based IDEs: IntelliJ IDEA, PyCharm, \
WebStorm, GoLand, CLion, Rider, and more.</p>
<p>Requirements: IntelliJ IDEA 2025.3 or newer (build 253 or later).</p>
<p>Visit <a href="https://mcp-steroid.jonnyzzz.com">mcp-steroid.jonnyzzz.com</a> \
for documentation and examples.</p>"""


def build_xml(
    plugin_id: str,
    plugin_version: str,
    since_build: str,
    zip_url: str,
    notes_path: Path,
    release_version: str,
) -> str:
    """Build the updatePlugins.xml using DOM API with proper CDATA sections."""
    doc = Document()

    plugins = doc.createElement("plugins")
    doc.appendChild(plugins)

    plugin = doc.createElement("plugin")
    plugin.setAttribute("id", plugin_id)
    plugin.setAttribute("url", zip_url)
    plugin.setAttribute("version", plugin_version)
    plugins.appendChild(plugin)

    idea_version = doc.createElement("idea-version")
    idea_version.setAttribute("since-build", since_build)
    plugin.appendChild(idea_version)

    name = doc.createElement("name")
    name.appendChild(doc.createTextNode("MCP Steroid"))
    plugin.appendChild(name)

    vendor = doc.createElement("vendor")
    vendor.appendChild(doc.createTextNode("jonnyzzz.com"))
    plugin.appendChild(vendor)

    description = doc.createElement("description")
    description.appendChild(doc.createCDATASection(DESCRIPTION_HTML))
    plugin.appendChild(description)

    change_notes_html = markdown_to_html(notes_path, release_version)
    change_notes = doc.createElement("change-notes")
    change_notes.appendChild(doc.createCDATASection(change_notes_html))
    plugin.appendChild(change_notes)

    return doc.toprettyxml(indent="  ", encoding="UTF-8").decode("UTF-8")


def main() -> None:
    if len(sys.argv) != 4:
        print(
            f"Usage: {sys.argv[0]} <version> <zip-download-url> <notes-file>",
            file=sys.stderr,
        )
        sys.exit(1)

    release_version = sys.argv[1]
    zip_url = sys.argv[2]
    notes_path = Path(sys.argv[3])

    validate_url(zip_url, release_version)

    plugin_id, plugin_version, since_build = extract_plugin_version(zip_url)

    assert release_version in plugin_version, (
        f"Release version '{release_version}' not found in plugin version '{plugin_version}'"
    )

    print(
        build_xml(plugin_id, plugin_version, since_build, zip_url, notes_path, release_version)
    )


if __name__ == "__main__":
    main()
