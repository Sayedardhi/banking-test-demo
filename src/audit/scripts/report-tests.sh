#!/usr/bin/env bash
# Run one audit test layer with coverage and write JUnit + Cobertura reports.
# Usage: scripts/report-tests.sh <unit|integration|all> <output-dir>
# Writes <output-dir>/junit.xml, coverage.xml (Cobertura), coverage-summary.json and coverage/ (HTML, lcov).
# Exit code: the test runner's exit code; 3 if no tests were discovered.
# Set C8_RAW_DIR to keep raw V8 coverage (used by CI to merge layers).
set -uo pipefail
layer=${1:?layer: unit|integration|all}
out=${2:?output directory}
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$here"
case "$layer" in
  unit) pattern='build/tests/unit/**/*.test.js' ;;
  integration) pattern='build/tests/integration/**/*.test.js' ;;
  all) pattern='build/tests/**/*.test.js' ;;
  *) echo "unknown layer: $layer" >&2; exit 2 ;;
esac
major=$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0)
if [ "$major" -lt 24 ] && [ -s "$HOME/.nvm/nvm.sh" ]; then . "$HOME/.nvm/nvm.sh" && nvm use 24 >/dev/null; fi
major=$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0)
[ "$major" -ge 24 ] || { echo "audit tests need Node 24 (node:sqlite); found $(node -v 2>&1)" >&2; exit 2; }
mkdir -p "$out" && out="$(cd "$out" && pwd)"
rm -rf "$out/junit.xml" "$out/coverage.xml" "$out/coverage-summary.json" "$out/coverage"
raw="${C8_RAW_DIR:-$out/.v8-raw}"

if [ ! -x node_modules/.bin/c8 ]; then npm ci --no-audit --no-fund || exit 2; fi
rm -rf build && node_modules/.bin/tsc -p tsconfig.test.json || exit 2

# --all counts production files that a layer never loads (e.g. server.ts in unit tests) as uncovered.
node_modules/.bin/c8 --all --src build/src --include 'build/src/**/*.js' \
  --reporter=cobertura --reporter=json-summary --reporter=html --reporter=lcov --reporter=text \
  --reports-dir "$out/coverage" --temp-directory "$raw" \
  node --test --test-concurrency=1 --test-timeout=30000 \
    --test-reporter=spec --test-reporter-destination=stdout \
    --test-reporter=junit --test-reporter-destination="$out/junit.xml" \
    "$pattern"
status=$?

[ -f "$out/coverage/cobertura-coverage.xml" ] && cp "$out/coverage/cobertura-coverage.xml" "$out/coverage.xml"
[ -f "$out/coverage/coverage-summary.json" ] && cp "$out/coverage/coverage-summary.json" "$out/coverage-summary.json"
[ -z "${C8_RAW_DIR:-}" ] && rm -rf "$raw"

cases=$(grep -c '<testcase' "$out/junit.xml" 2>/dev/null || true)
if [ "${cases:-0}" -eq 0 ]; then
  echo "ERROR: no $layer tests were discovered ($pattern)" >&2
  exit 3
fi
echo "audit $layer: $cases test cases, runner exit $status, reports in $out"
exit $status
