#!/usr/bin/env bash
# Merge raw V8 coverage from several layers into one service-level Cobertura report.
# Usage: scripts/merge-coverage.sh <output-dir> <raw-dir>...   (raw dirs come from C8_RAW_DIR runs)
set -euo pipefail
out=${1:?output directory}; shift
cd "$(dirname "${BASH_SOURCE[0]}")/.."
mkdir -p "$out" && out="$(cd "$out" && pwd)"
merged="$out/.v8-merged"; rm -rf "$merged" "$out/coverage"; mkdir -p "$merged"
for raw in "$@"; do [ -d "$raw" ] && cp "$raw"/*.json "$merged"/ 2>/dev/null || true; done
[ -d build/src ] || node_modules/.bin/tsc -p tsconfig.test.json
node_modules/.bin/c8 report --all --src build/src --include 'build/src/**/*.js' \
  --reporter=cobertura --reporter=json-summary --reporter=html --reporter=text \
  --reports-dir "$out/coverage" --temp-directory "$merged"
cp "$out/coverage/cobertura-coverage.xml" "$out/coverage.xml"
rm -rf "$merged"
