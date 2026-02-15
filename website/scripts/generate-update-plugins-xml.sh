#!/bin/bash

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

# Generates updatePlugins.xml for IntelliJ custom plugin repository.
# Usage: generate-update-plugins-xml.sh <version> <zip-filename>
set -euo pipefail

VERSION="$1"
ZIP_NAME="$2"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
NOTES_FILE="$SCRIPT_DIR/../../release/notes/${VERSION}.md"

# Convert release notes markdown to plain HTML for change-notes.
# Strip the header (lines before first ## section) and convert to simple HTML.
generate_change_notes() {
    if [ ! -f "$NOTES_FILE" ]; then
        echo "Release notes not available for version ${VERSION}."
        return
    fi

    local in_section=false
    while IFS= read -r line; do
        # Skip metadata lines at the top (# title, - Previous tag, - Commit range, blank)
        if [ "$in_section" = false ]; then
            case "$line" in
                "## "*)  in_section=true ;;
                *)       continue ;;
            esac
        fi

        if [ "$in_section" = true ]; then
            case "$line" in
                "## "*)
                    local heading="${line#\#\# }"
                    echo "<h3>${heading}</h3>"
                    ;;
                "- "*)
                    local item="${line#- }"
                    # Escape XML special chars
                    item="${item//&/&amp;}"
                    item="${item//</&lt;}"
                    item="${item//>/&gt;}"
                    # Convert **bold** to <b>
                    item=$(echo "$item" | sed 's/\*\*\([^*]*\)\*\*/<b>\1<\/b>/g')
                    echo "<li>${item}</li>"
                    ;;
                "")
                    ;;
                *)
                    local text="$line"
                    text="${text//&/&amp;}"
                    text="${text//</&lt;}"
                    text="${text//>/&gt;}"
                    echo "<p>${text}</p>"
                    ;;
            esac
        fi
    done < "$NOTES_FILE"
}

CHANGE_NOTES=$(generate_change_notes)

cat <<XMLEOF
<?xml version="1.0" encoding="UTF-8"?>
<plugins>
  <plugin id="com.jonnyzzz.mcp-steroid" url="https://github.com/jonnyzzz/mcp-steroid/releases/download/${VERSION}/${ZIP_NAME}" version="${VERSION}">
    <idea-version since-build="253"/>
    <name>MCP Steroid</name>
    <vendor>jonnyzzz.com</vendor>
    <description><![CDATA[
      <p><b>MCP Steroid</b> brings the full power of the IntelliJ Platform to AI agents through the Model Context Protocol (MCP).</p>
      <p>IntelliJ platform works for AI agents as great as for human developers.</p>
      <ul>
        <li><b>9 MCP Tools:</b> Control IntelliJ IDEA programmatically — execute code, take screenshots, discover actions, debug, and more</li>
        <li><b>58 MCP Resources:</b> Comprehensive guides covering LSP, IDE operations, debugger, tests, VCS, and more</li>
        <li><b>Vision Capabilities:</b> AI agents can see your IDE with screenshots and OCR</li>
        <li><b>Deep Integration:</b> Access PSI, inspections, refactorings, and full IntelliJ Platform API</li>
        <li><b>Human Oversight:</b> Review and approve code execution with configurable safety controls</li>
      </ul>
      <p>Compatible with all IntelliJ Platform-based IDEs: IntelliJ IDEA, PyCharm, WebStorm, GoLand, CLion, Rider, and more.</p>
      <p>Requirements: IntelliJ IDEA 2025.3 or newer (build 253 or later).</p>
      <p>Visit <a href="https://mcp-steroid.jonnyzzz.com">mcp-steroid.jonnyzzz.com</a> for documentation and examples.</p>
    ]]></description>
    <change-notes><![CDATA[
      <h2>What's New in v${VERSION}</h2>
      ${CHANGE_NOTES}
      <p>Full release notes: <a href="https://mcp-steroid.jonnyzzz.com/releases/${VERSION}/">mcp-steroid.jonnyzzz.com/releases/${VERSION}/</a></p>
    ]]></change-notes>
  </plugin>
</plugins>
XMLEOF
