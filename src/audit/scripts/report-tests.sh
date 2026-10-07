#!/usr/bin/env bash
# Runs one audit test layer and writes reports to an output folder.
# Usage: scripts/report-tests.sh <unit|integration|e2e> <output-dir>
#   unit / integration: junit.xml, coverage.xml (Cobertura, all of src/**/*.ts), coverage-html.tar.gz
#   e2e: junit.xml, playwright-report.zip, playwright-results.zip (Playwright, see tests/e2e)
# Exit code: non-zero on compile error, test failure, or zero discovered tests.
set -uo pipefail

layer="${1:?layer (unit|integration|e2e) required}"
out="${2:?output directory required}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
service_dir="$(cd "$here/.." && pwd)"

if [[ "$(node -v 2>/dev/null)" != v24.* && -s "${NVM_DIR:-$HOME/.nvm}/nvm.sh" ]]; then
  # shellcheck disable=SC1091
  source "${NVM_DIR:-$HOME/.nvm}/nvm.sh" >/dev/null && nvm use 24 >/dev/null
fi
[[ "$(node -v)" == v24.* ]] || { echo "Node 24 is required (node:sqlite); found $(node -v)" >&2; exit 2; }

mkdir -p "$out"
out="$(cd "$out" && pwd)"
rm -rf "$out"/junit.xml "$out"/coverage.xml "$out"/coverage-html.tar.gz "$out"/playwright-*.zip

cd "$service_dir"
[[ -d node_modules/c8 ]] || npm ci --no-audit --no-fund || exit 2

if [[ "$layer" == e2e ]]; then
  cd tests/e2e
  [[ -d node_modules/@playwright/test ]] || npm ci --no-audit --no-fund || exit 2
  discovered=$(npx playwright test --list --reporter=list 2>/dev/null | grep -cE '^\s+\[chromium\]')
  echo "Discovered $discovered audit Playwright tests"
  [[ "$discovered" -gt 0 ]] || { echo "No Playwright tests discovered" >&2; exit 3; }
  E2E_REPORT_DIR="$out" npx playwright test
  status=$?
  (cd "$out" && [[ -d playwright-report ]] && zip -qr playwright-report.zip playwright-report && rm -rf playwright-report)
  (cd "$out" && [[ -d test-results ]] && zip -qr playwright-results.zip test-results && rm -rf test-results)
  exit $status
fi

case "$layer" in
  unit|integration) ;;
  *) echo "unknown layer: $layer" >&2; exit 2 ;;
esac

npm run --silent build:tests || { echo "TypeScript compilation of src + tests failed" >&2; exit 2; }
files=(.test-build/tests/"$layer"/*.test.js)
[[ -e "${files[0]}" ]] || { echo "No $layer test files discovered" >&2; exit 3; }

coverage_tmp="$(mktemp -d)"
# c8 collects V8 coverage from the test process and any child it spawns (the integration
# server), remaps through source maps to src/*.ts, and --all adds never-loaded files at 0%.
npx c8 --all --src . --include 'src/**/*.ts' --include '.test-build/src/**/*.js' --exclude-after-remap \
  --exclude 'tests/**' --reports-dir "$coverage_tmp" --temp-directory "$coverage_tmp/tmp" \
  --reporter=cobertura --reporter=html --reporter=text-summary \
  node --test --test-concurrency=1 --test-timeout=60000 \
    --test-reporter=spec --test-reporter-destination=stdout \
    --test-reporter=junit --test-reporter-destination="$out/junit.xml" \
    "${files[@]}"
status=$?

cp "$coverage_tmp/cobertura-coverage.xml" "$out/coverage.xml" 2>/dev/null || true
[[ -f "$coverage_tmp/index.html" ]] && tar -czf "$out/coverage-html.tar.gz" -C "$coverage_tmp" --exclude tmp --exclude cobertura-coverage.xml .
rm -rf "$coverage_tmp"

python3 "$here/summarize.py" --check "$layer=$out"
summary_status=$?
[[ "$status" -ne 0 ]] && exit "$status"
exit "$summary_status"
