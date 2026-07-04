#!/usr/bin/env bash
#
# Render the test-experiments dashboard from an already-collected input directory.
#
# This is the report PROCESSING step: it only reads collected run data (build logs, agent NDJSON, summary
# JSON) from <inputDir> and writes a self-contained HTML file. It performs no downloading and needs no
# network — point it at the output of a local test-experiments run, or at a directory of pre-collected
# data, and it renders the with-vs-without-MCP comparison the same way in either case.
#
# Usage:
#   experiments-report/render-dashboard.sh <inputDir> <outHtml> [title]
# or via env: INPUT_DIR / OUT_HTML / REPORT_TITLE
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"   # mcp-steroid repo root
INPUT="${1:-${INPUT_DIR:?usage: render-dashboard.sh <inputDir> <outHtml> [title]}}"
OUT="${2:-${OUT_HTML:?usage: render-dashboard.sh <inputDir> <outHtml> [title]}}"
TITLE="${3:-${REPORT_TITLE:-MCP Steroid — test-experiments dashboard}}"

cd "$ROOT"
./gradlew --no-daemon -q :experiments-report:generateExperimentsReport \
  --args="--input $INPUT --out $OUT --title \"$TITLE\""
echo "[render] dashboard written to $OUT"
