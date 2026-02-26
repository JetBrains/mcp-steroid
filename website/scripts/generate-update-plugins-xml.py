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

"""Generates updatePlugins.xml for IntelliJ custom plugin repository.

Uses xml.etree.ElementTree for proper XML construction and escaping.
CDATA sections are used for description and change-notes since they contain HTML.
"""

import re
import sys
from pathlib import Path
from xml.dom.minidom import Document


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
<li><b>9 MCP Tools:</b> Control IntelliJ IDEA programmatically \u2014 execute code, \
take screenshots, discover actions, debug, and more</li>
<li><b>58 MCP Resources:</b> Comprehensive guides covering LSP, IDE operations, \
debugger, tests, VCS, and more</li>
<li><b>Vision Capabilities:</b> AI agents can see your IDE with screenshots and OCR</li>
<li><b>Deep Integration:</b> Access PSI, inspections, refactorings, and full \
IntelliJ Platform API</li>
<li><b>Human Oversight:</b> Review and approve code execution with configurable \
safety controls</li>
</ul>
<p>Compatible with all IntelliJ Platform-based IDEs: IntelliJ IDEA, PyCharm, \
WebStorm, GoLand, CLion, Rider, and more.</p>
<p>Requirements: IntelliJ IDEA 2025.3 or newer (build 253 or later).</p>
<p>Visit <a href="https://mcp-steroid.jonnyzzz.com">mcp-steroid.jonnyzzz.com</a> \
for documentation and examples.</p>"""


def build_xml(version: str, zip_name: str, notes_path: Path) -> str:
    """Build the updatePlugins.xml using DOM API with proper CDATA sections."""
    doc = Document()

    # XML declaration is handled by toxml()
    plugins = doc.createElement("plugins")
    doc.appendChild(plugins)

    plugin = doc.createElement("plugin")
    plugin.setAttribute("id", "com.jonnyzzz.mcp-steroid")
    plugin.setAttribute(
        "url",
        f"https://github.com/jonnyzzz/mcp-steroid/releases/download/{version}/{zip_name}",
    )
    plugin.setAttribute("version", version)
    plugins.appendChild(plugin)

    idea_version = doc.createElement("idea-version")
    idea_version.setAttribute("since-build", "253")
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

    change_notes_html = markdown_to_html(notes_path, version)
    change_notes = doc.createElement("change-notes")
    change_notes.appendChild(doc.createCDATASection(change_notes_html))
    plugin.appendChild(change_notes)

    return doc.toprettyxml(indent="  ", encoding="UTF-8").decode("UTF-8")


def main() -> None:
    if len(sys.argv) != 4:
        print(
            f"Usage: {sys.argv[0]} <version> <zip-filename> <notes-file>",
            file=sys.stderr,
        )
        sys.exit(1)

    version = sys.argv[1]
    zip_name = sys.argv[2]
    notes_path = Path(sys.argv[3])

    if not zip_name.endswith(".zip"):
        print(
            f"Error: zip filename must end with .zip, got: {zip_name}",
            file=sys.stderr,
        )
        sys.exit(1)

    print(build_xml(version, zip_name, notes_path))


if __name__ == "__main__":
    main()
