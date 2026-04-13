#!/usr/bin/env bash
# Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

OUT_DIR="$ROOT_DIR/release/out"
mkdir -p "$OUT_DIR"

STABLE_PRODUCT="${RELEASE_STABLE_PRODUCT:-idea}"
STABLE_VERSION="${RELEASE_STABLE_VERSION:-2025.3.1}"
EAP_PRODUCT="${RELEASE_EAP_PRODUCT:-idea}"
EAP_VERSION="${RELEASE_EAP_VERSION:-2026.1}"
RELEASE_NOTES_VERSION="${RELEASE_NOTES_VERSION:-$(tr -d '[:space:]' < "$ROOT_DIR/VERSION")}"

GRADLE_COMMON=(./gradlew --no-daemon --stacktrace --console=plain)
GRADLE_STAGE_EXCLUDES=(
  -x :test-integration:test
  -x :test-experiments:test
)

is_build_number() {
  local value="$1"
  [[ "$value" =~ ^[0-9]{3}\.[0-9]+\.[0-9]+$ ]]
}

normalize_eap_request() {
  local value="${1//[[:space:]]/}"
  value="${value%\.EAP}"
  value="${value%-EAP}"
  value="${value%_EAP}"
  value="${value%\.eap}"
  value="${value%-eap}"
  value="${value%_eap}"
  printf '%s\n' "$value"
}

product_code_for() {
  local product="$1"
  case "${product,,}" in
    idea|iiu|intellij|intellijidea|intellijideaultimate) printf 'IIU\n' ;;
    pycharm|pcp|python) printf 'PCP\n' ;;
    goland|go|golang) printf 'GO\n' ;;
    webstorm|ws|web) printf 'WS\n' ;;
    *)
      echo "Unsupported IDE product '$product' for EAP resolution (expected idea, pycharm, goland, or webstorm)." >&2
      exit 2
      ;;
  esac
}

resolve_eap_build() {
  local product="$1"
  local request="$2"
  local normalized
  normalized="$(normalize_eap_request "$request")"

  if is_build_number "$normalized"; then
    printf '%s\n' "$normalized"
    return 0
  fi

  local major_filter=""
  if [[ -n "$normalized" && "${normalized,,}" != "latest" ]]; then
    major_filter="$normalized"
  fi

  local product_code
  product_code="$(product_code_for "$product")"
  local endpoint="https://data.services.jetbrains.com/products?code=${product_code}&release.type=eap"
  local resolved

  resolved="$(curl -fsSL "$endpoint" | jq -r --arg major "$major_filter" '
    .[0].releases
    | map(select(.type == "eap"))
    | map(select((.build // "") != ""))
    | map(select($major == "" or (.majorVersion // "") == $major or (.version // "") == $major))
    | .[0].build // empty
  ')"

  if [[ -z "$resolved" ]]; then
    echo "Failed to resolve EAP build for product '$product' and request '$request' from $endpoint" >&2
    exit 1
  fi

  printf '%s\n' "$resolved"
}

EAP_VERSION_RESOLVED="$(resolve_eap_build "$EAP_PRODUCT" "$EAP_VERSION")"

if is_build_number "$EAP_VERSION"; then
  IDEA_EAP_BUILD_RESOLVED="$(resolve_eap_build idea latest)"
  PYCHARM_EAP_BUILD_RESOLVED="$(resolve_eap_build pycharm latest)"
  GOLAND_EAP_BUILD_RESOLVED="$(resolve_eap_build goland latest)"
  WEBSTORM_EAP_BUILD_RESOLVED="$(resolve_eap_build webstorm latest)"
else
  IDEA_EAP_BUILD_RESOLVED="$(resolve_eap_build idea "$EAP_VERSION")"
  PYCHARM_EAP_BUILD_RESOLVED="$(resolve_eap_build pycharm "$EAP_VERSION")"
  GOLAND_EAP_BUILD_RESOLVED="$(resolve_eap_build goland "$EAP_VERSION")"
  WEBSTORM_EAP_BUILD_RESOLVED="$(resolve_eap_build webstorm "$EAP_VERSION")"
fi

select_single_distribution_zip() {
  local dist_dir="$ROOT_DIR/build/distributions"
  if [[ ! -d "$dist_dir" ]]; then
    echo "Distribution directory missing after build: $dist_dir" >&2
    exit 1
  fi

  shopt -s nullglob
  local matches=("$dist_dir"/mcp-steroid-*.zip)
  shopt -u nullglob

  if [[ "${#matches[@]}" -ne 1 ]]; then
    echo "Expected exactly one plugin ZIP matching mcp-steroid-*.zip in $dist_dir, found ${#matches[@]}." >&2
    if [[ "${#matches[@]}" -gt 0 ]]; then
      echo "Matching ZIP files:" >&2
      printf '  %s\n' "${matches[@]}" >&2
    fi
    echo "Current contents of $dist_dir:" >&2
    ls -lah "$dist_dir" >&2 || true
    exit 1
  fi

  printf '%s\n' "${matches[0]}"
}

echo "== Stage 1: stable build (product=$STABLE_PRODUCT version=$STABLE_VERSION) =="
"${GRADLE_COMMON[@]}" \
  clean build buildPlugin \
  "${GRADLE_STAGE_EXCLUDES[@]}" \
  -Pmcp.release.build=true \
  -Pmcp.platform.product="$STABLE_PRODUCT" \
  -Pmcp.platform.version="$STABLE_VERSION" \
  -Pmcp.release.notes.version="$RELEASE_NOTES_VERSION"

stable_zip="$(select_single_distribution_zip)"

stable_copy="$OUT_DIR/plugin-${STABLE_PRODUCT}-${STABLE_VERSION}.zip"
cp "$stable_zip" "$stable_copy"
echo "Stable plugin ZIP saved: $stable_copy"

echo "== Stage 2: EAP build (product=$EAP_PRODUCT request=$EAP_VERSION resolved=$EAP_VERSION_RESOLVED) =="
"${GRADLE_COMMON[@]}" \
  clean build buildPlugin \
  "${GRADLE_STAGE_EXCLUDES[@]}" \
  -Pmcp.release.build=true \
  -Pmcp.platform.product="$EAP_PRODUCT" \
  -Pmcp.platform.version="$EAP_VERSION_RESOLVED" \
  -Pmcp.release.notes.version="$RELEASE_NOTES_VERSION"

echo "== Stage 3: selected integration matrix [IDEA,PyCharm,GoLand,WebStorm] x [stable,EAP] =="
"${GRADLE_COMMON[@]}" \
  -Pmcp.release.build=true \
  -Ptest.integration.idea.stable.version="$STABLE_VERSION" \
  -Ptest.integration.pycharm.stable.version="$STABLE_VERSION" \
  -Ptest.integration.goland.stable.version="$STABLE_VERSION" \
  -Ptest.integration.webstorm.stable.version="$STABLE_VERSION" \
  -Ptest.integration.idea.eap.build="$IDEA_EAP_BUILD_RESOLVED" \
  -Ptest.integration.pycharm.eap.build="$PYCHARM_EAP_BUILD_RESOLVED" \
  -Ptest.integration.goland.eap.build="$GOLAND_EAP_BUILD_RESOLVED" \
  -Ptest.integration.webstorm.eap.build="$WEBSTORM_EAP_BUILD_RESOLVED" \
  :test-integration:testReleaseSmokeMatrix

cat > "$OUT_DIR/build-summary.txt" <<EOF
stable_product=$STABLE_PRODUCT
stable_version=$STABLE_VERSION
stable_plugin_zip=$stable_copy
release_notes_version=$RELEASE_NOTES_VERSION
release_build_version_format=version+gitHash_no_snapshot
eap_product=$EAP_PRODUCT
eap_version_request=$EAP_VERSION
eap_version_resolved=$EAP_VERSION_RESOLVED
idea_eap_build_resolved=$IDEA_EAP_BUILD_RESOLVED
pycharm_eap_build_resolved=$PYCHARM_EAP_BUILD_RESOLVED
goland_eap_build_resolved=$GOLAND_EAP_BUILD_RESOLVED
webstorm_eap_build_resolved=$WEBSTORM_EAP_BUILD_RESOLVED
integration_matrix_task=:test-integration:testReleaseSmokeMatrix
timestamp_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF

echo "Build summary written: $OUT_DIR/build-summary.txt"
