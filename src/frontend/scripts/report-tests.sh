#!/usr/bin/env bash
# Runs one frontend test layer once and writes JUnit + coverage reports to an output folder.
# Usage: report-tests.sh <unit|integration|e2e> <output-dir>
#   unit         pytest tests/unit         -> junit.xml, coverage.xml (Cobertura, line+branch), coverage-html.tar.gz
#   integration  pytest tests/integration  -> same; builds src/audit (Node 24) for the real audit service
#   e2e          Playwright tests/e2e against a running stack (FRONTEND_URL, AUDIT_URL)
#                -> junit.xml, playwright-report.zip, playwright-results.zip (traces/screenshots/videos)
# Exit code: the test runner's exit code; non-zero also when zero tests are discovered or reports are missing.
set -uo pipefail

layer="${1:?layer (unit|integration|e2e) required}"
out="${2:?output directory required}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
service_dir="$(cd "$here/.." && pwd)"
repo="$(cd "$service_dir/../.." && pwd)"
mkdir -p "$out"
out="$(cd "$out" && pwd)"

use_node24() {
  if [ -s "${NVM_DIR:-$HOME/.nvm}/nvm.sh" ] && ! node --version 2>/dev/null | grep -q '^v24\.'; then
    # shellcheck disable=SC1091
    . "${NVM_DIR:-$HOME/.nvm}/nvm.sh" >/dev/null && nvm use 24 >/dev/null
  fi
  node --version | grep -q '^v24\.' || { echo "Node 24 is required (node:sqlite)" >&2; return 1; }
}

run_pytest() {
  local suite="$1"
  rm -rf "$out/junit.xml" "$out/coverage.xml" "$out/coverage-html" "$out/coverage-html.tar.gz"
  cd "$service_dir" || return 2
  rm -f .coverage
  uv run --frozen pytest "tests/$suite" -p no:cacheprovider -o junit_family=xunit2 \
    --junitxml="$out/junit.xml" --cov --cov-branch \
    --cov-report=xml:"$out/coverage.xml" --cov-report=html:"$out/coverage-html" --cov-report=term
  local status=$?
  [ -d "$out/coverage-html" ] && tar -czf "$out/coverage-html.tar.gz" -C "$out" coverage-html && rm -rf "$out/coverage-html"
  python3 "$here/summarize.py" --check "$layer=$out" || { [ "$status" -eq 0 ] && status=1; }
  return "$status"
}

case "$layer" in
  unit)
    run_pytest unit; exit $? ;;
  integration)
    use_node24 || exit 2
    (cd "$repo/src/audit" && npm ci --no-audit --no-fund --silent && npm run build --silent) || {
      echo "audit service build failed" >&2; exit 2; }
    run_pytest integration; exit $? ;;
  e2e)
    use_node24 || exit 2
    e2e="$repo/tests/e2e"
    rm -rf "$out/junit.xml" "$out/playwright-report" "$out/artifacts" "$out"/playwright-*.zip "$out/results.json"
    frontend_url="${FRONTEND_URL:-http://localhost:18080}"
    if [ "${E2E_STACK:-auto}" != existing ] && ! curl -fsS -o /dev/null "$frontend_url/ready"; then
      echo "Starting the source-built banking-e2e stack (never removes volumes)"
      if [ ! -s "$repo/.local/keys/privatekey" ]; then
        mkdir -p "$repo/.local/keys"
        openssl genrsa -out "$repo/.local/keys/privatekey" 2048 2>/dev/null
        openssl rsa -in "$repo/.local/keys/privatekey" -pubout -out "$repo/.local/keys/publickey" 2>/dev/null
      fi
      (cd "$repo" && docker compose -p banking-e2e -f compose.yaml -f tests/e2e/compose.source.yaml \
        up -d --build --wait --wait-timeout 900) || { echo "E2E stack failed to start" >&2; exit 2; }
    fi
    cd "$e2e" || exit 2
    [ -d node_modules/@playwright/test ] || npm ci --no-audit --no-fund --silent || exit 2
    discovered=$(npx playwright test --list | grep -cE '^\s+\[chromium\]' || true)
    echo "Discovered $discovered Playwright tests"
    if [ "$discovered" -eq 0 ]; then echo "zero Playwright tests discovered" >&2; exit 1; fi
    E2E_OUTPUT_DIR="$out" npx playwright test
    status=$?

    (cd "$out" && [ -d playwright-report ] && zip -qr playwright-report.zip playwright-report)
    (cd "$out" && [ -d artifacts ] && zip -qr playwright-results.zip artifacts)
    [ -f "$out/junit.xml" ] || { echo "Playwright wrote no JUnit report" >&2; [ "$status" -eq 0 ] && status=1; }
    exit "$status" ;;
  *) echo "unknown layer: $layer" >&2; exit 2 ;;
esac
