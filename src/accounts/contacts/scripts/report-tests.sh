#!/usr/bin/env bash
# Runs one contacts test layer and writes evidence to OUTPUT_DIR.
#   unit         pytest tests/ (mocked ContactsDb)           -> junit.xml, coverage.xml, coverage-html/
#   integration  pytest tests/integration (Testcontainers)   -> junit.xml, coverage.xml, coverage-html/
#   e2e          Playwright tests/e2e against a running stack (E2E_BASE_URL, default :18080)
#                -> junit.xml, playwright-report.zip, playwright-results.zip
# Coverage source is the whole service directory (tests/ and scripts/ omitted), line + branch.
# Exit code is the test runner's; zero discovered tests is a failure.
set -uo pipefail
LAYER="${1:?usage: report-tests.sh unit|integration|e2e OUTPUT_DIR}"
OUTPUT_DIR="$(mkdir -p "${2:?usage: report-tests.sh unit|integration|e2e OUTPUT_DIR}" && cd "$2" && pwd)"
SERVICE="$(cd "$(dirname "$0")/.." && pwd)"
cd "$SERVICE"

pytest_layer() {
  local -a paths=("$@")
  uv run --frozen pytest "${paths[@]}" -p no:cacheprovider -rfE \
    --junitxml="$OUTPUT_DIR/junit.xml" -o junit_family=xunit2 \
    --cov --cov-branch --cov-report=term --cov-report="xml:$OUTPUT_DIR/coverage.xml" \
    --cov-report="html:$OUTPUT_DIR/coverage-html"
  local status=$?
  # pytest exit 5 = no tests collected
  [[ $status -eq 5 ]] && echo "No tests discovered" >&2
  return $status
}

case "$LAYER" in
  unit) pytest_layer tests --ignore=tests/integration --ignore=tests/e2e ;;
  integration) pytest_layer tests/integration ;;
  e2e)
    if [[ -s "${NVM_DIR:-$HOME/.nvm}/nvm.sh" ]]; then source "${NVM_DIR:-$HOME/.nvm}/nvm.sh" >/dev/null; nvm use 24 >/dev/null 2>&1 || true; fi
    cd tests/e2e
    [[ -d node_modules/@playwright/test ]] || npm ci --no-audit --no-fund || exit 2
    discovered=$(npx playwright test --list 2>/dev/null | grep -cE '^\s+\[chromium\]')
    echo "Discovered $discovered Playwright tests"
    [[ "$discovered" -gt 0 ]] || { echo "No Playwright tests discovered" >&2; exit 3; }
    E2E_REPORT_DIR="$OUTPUT_DIR" E2E_TRACE="${E2E_TRACE:-on}" npx playwright test
    status=$?
    (cd "$OUTPUT_DIR" && [[ -d playwright-report ]] && zip -qr playwright-report.zip playwright-report && rm -rf playwright-report)
    (cd "$OUTPUT_DIR" && [[ -d test-results ]] && zip -qr playwright-results.zip test-results && rm -rf test-results)
    exit $status ;;
  *) echo "unknown layer: $LAYER" >&2; exit 2 ;;
esac
