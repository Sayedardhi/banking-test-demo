#!/usr/bin/env bash
# Runs the Playwright customer journeys and writes evidence to OUTPUT_DIR:
#   junit.xml, playwright-report.zip (HTML report + traces), playwright-results.zip, compose-logs.txt
#
# E2E_STACK=source (default) builds every backend from this checkout with compose.source.yaml as
# compose project "banking-e2e" (ports 18080/18090, tmpfs databases) and stops it afterwards.
# E2E_STACK=existing runs against an already running stack (E2E_BASE_URL / AUDIT_URL).
# Exit code is Playwright's; zero discovered tests is a failure.
set -uo pipefail
OUTPUT_DIR="$(mkdir -p "${1:?usage: report-tests.sh OUTPUT_DIR}" && cd "$1" && pwd)"
E2E_DIR="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$E2E_DIR/../.." && pwd)"
STACK="${E2E_STACK:-source}"
PROJECT="${E2E_COMPOSE_PROJECT:-banking-e2e}"
compose() { docker compose -p "$PROJECT" -f "$REPO/compose.yaml" -f "$E2E_DIR/compose.source.yaml" "$@"; }

if [[ -s "${NVM_DIR:-$HOME/.nvm}/nvm.sh" ]]; then source "${NVM_DIR:-$HOME/.nvm}/nvm.sh" >/dev/null; nvm use 24 >/dev/null 2>&1 || true; fi
cd "$E2E_DIR"
[[ -d node_modules/@playwright/test ]] || npm ci --no-audit --no-fund || exit 2

discovered=$(npx playwright test --list 2>/dev/null | grep -cE '^\s+\[chromium\]')
echo "Discovered $discovered Playwright tests"
if [[ "$discovered" -eq 0 ]]; then echo "No Playwright tests discovered" >&2; exit 3; fi

if [[ "$STACK" == source ]]; then
  export E2E_BASE_URL="http://localhost:${E2E_FRONTEND_PORT:-18080}" AUDIT_URL="http://localhost:${E2E_AUDIT_PORT:-18090}"
  mkdir -p "$REPO/.local/keys"
  [[ -f "$REPO/.local/keys/privatekey" ]] || openssl genrsa -out "$REPO/.local/keys/privatekey" 2048
  openssl rsa -in "$REPO/.local/keys/privatekey" -pubout -out "$REPO/.local/keys/publickey" 2>/dev/null
  # Fresh containers every run: tmpfs databases start from the seeded schema.
  compose down --remove-orphans >/dev/null 2>&1
  if ! compose up -d --build --wait --wait-timeout "${E2E_STACK_TIMEOUT:-600}"; then
    compose ps; compose logs --no-color > "$OUTPUT_DIR/compose-logs.txt" 2>&1; compose down >/dev/null 2>&1
    echo "Source-built stack failed to become healthy" >&2; exit 4
  fi
  compose images > "$OUTPUT_DIR/stack-images.txt"
fi

E2E_REPORT_DIR="$OUTPUT_DIR" E2E_TRACE="${E2E_TRACE:-on}" npx playwright test
status=$?

(cd "$OUTPUT_DIR" && [[ -d playwright-report ]] && zip -qr playwright-report.zip playwright-report && rm -rf playwright-report)
(cd "$OUTPUT_DIR" && [[ -d test-results ]] && zip -qr playwright-results.zip test-results && rm -rf test-results)
if [[ "$STACK" == source ]]; then
  compose logs --no-color > "$OUTPUT_DIR/compose-logs.txt" 2>&1
  [[ "${E2E_KEEP_STACK:-0}" == 1 ]] || compose down >/dev/null 2>&1
fi
exit $status
