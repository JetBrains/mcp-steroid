#!/usr/bin/env bash
# Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license.
set -euo pipefail

# Upload the plugin ZIP to JetBrains Marketplace.
#
# Usage: release/scripts/publish-marketplace.sh [plugin-zip]
#
# The marketplace token is read from ~/.marketplace (first line, trimmed).
# This script never reads the token into a variable — it pipes the file
# content directly to curl.
#
# Arguments:
#   plugin-zip  Path to the plugin ZIP (default: auto-detected from ij-plugin/build/distributions/)

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MARKETPLACE_TOKEN_FILE="$HOME/.marketplace"
PLUGIN_ID="com.jonnyzzz.mcp-steroid"

if [[ ! -f "$MARKETPLACE_TOKEN_FILE" ]]; then
  echo "Missing marketplace token file: $MARKETPLACE_TOKEN_FILE" >&2
  echo "Create it with your JetBrains Marketplace permanent token (one line)." >&2
  exit 1
fi

# Resolve plugin ZIP path
if [[ -n "${1:-}" ]]; then
  PLUGIN_ZIP="$1"
else
  PLUGIN_ZIP="$(ls -1 "$ROOT_DIR/ij-plugin/build/distributions"/mcp-steroid-*.zip 2>/dev/null | head -1)"
fi

if [[ -z "$PLUGIN_ZIP" || ! -f "$PLUGIN_ZIP" ]]; then
  echo "Plugin ZIP not found: ${PLUGIN_ZIP:-<none>}" >&2
  echo "Build first: ./gradlew :ij-plugin:buildPlugin -Pmcp.release.build=true" >&2
  exit 1
fi

echo "Plugin ZIP: $PLUGIN_ZIP"
echo "Plugin ID:  $PLUGIN_ID"
echo "Uploading to JetBrains Marketplace..."

# Upload using curl — token is read from file
# The xmlId parameter accepts the string plugin ID (com.jonnyzzz.mcp-steroid)
curl -i \
  --header "Authorization: Bearer $(head -1 "$MARKETPLACE_TOKEN_FILE" | tr -d '[:space:]')" \
  --form "xmlId=$PLUGIN_ID" \
  --form "file=@$PLUGIN_ZIP" \
  --form "channel=Stable" \
  "https://plugins.jetbrains.com/plugin/uploadPlugin"

echo ""
echo "Upload completed. Check https://plugins.jetbrains.com/plugin/edit?pluginId=$PLUGIN_ID for status."
