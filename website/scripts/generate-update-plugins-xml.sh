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
# Usage: generate-update-plugins-xml.sh <version> <zip-download-url>
set -euo pipefail

VERSION="$1"
ZIP_URL="$2"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
NOTES_FILE="$SCRIPT_DIR/../../release/notes/${VERSION}.md"

exec uv run "$SCRIPT_DIR/generate-update-plugins-xml.py" "$VERSION" "$ZIP_URL" "$NOTES_FILE"
