#!/usr/bin/env bash
# Runs one ledgerwriter test layer and writes fresh reports to OUTPUT.
#
#   scripts/test-ledgerwriter.sh unit        <output-dir>
#   scripts/test-ledgerwriter.sh integration <output-dir>   # Testcontainers PostgreSQL
#   scripts/test-ledgerwriter.sh e2e         <output-dir>   # Compose + Playwright
#
# Exit code is the test runner's exit code. Requires Java 17, Maven (set MVN
# to override), Docker, and for e2e Node.js with tests/e2e dependencies.
set -uo pipefail
LAYER=${1:?layer: unit|integration|e2e}
OUTPUT=${2:?output directory}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
MODULE="$ROOT/src/ledger/ledgerwriter"
MVN=${MVN:-mvn}
mkdir -p "$OUTPUT"
OUTPUT=$(cd "$OUTPUT" && pwd)
rm -rf "${OUTPUT:?}"/*

copy() { # copy <src> <dest>, ignoring missing sources
  [[ -e "$1" ]] && mkdir -p "$(dirname "$2")" && cp -r "$1" "$2"
  return 0
}

build_source_jar() {
  (cd "$MODULE" && "$MVN" -B -q package -DskipTests) || return 1
  mkdir -p "$ROOT/.local/ledgerwriter-source"
  cp "$(ls "$MODULE"/target/ledgerwriter-*.jar | grep -v original | head -1)" \
    "$ROOT/.local/ledgerwriter-source/ledgerwriter.jar"
}

case "$LAYER" in
  unit)
    (cd "$MODULE" && "$MVN" -B clean test jacoco:report) > "$OUTPUT/maven.log" 2>&1
    status=$?
    copy "$MODULE/target/surefire-reports" "$OUTPUT/surefire-reports"
    copy "$MODULE/target/site/jacoco/jacoco.xml" "$OUTPUT/jacoco.xml"
    ;;
  integration)
    (cd "$MODULE" && "$MVN" -B clean verify -DskipUTs=true) > "$OUTPUT/maven.log" 2>&1
    status=$?
    copy "$MODULE/target/failsafe-reports" "$OUTPUT/failsafe-reports"
    copy "$MODULE/target/site/jacoco-it/jacoco.xml" "$OUTPUT/jacoco.xml"
    ;;
  e2e)
    export LEDGERWRITER_SOURCE_VERSION="source-$(git -C "$ROOT" rev-parse --short HEAD)"
    COMPOSE=(docker compose -f "$ROOT/compose.yaml" -f "$ROOT/compose.ledgerwriter-source.yaml")
    {
      build_source_jar &&
      bash "$ROOT/scripts/start-local.sh" >/dev/null &&
      "${COMPOSE[@]}" up -d --wait --force-recreate ledgerwriter
    } > "$OUTPUT/setup.log" 2>&1
    status=$?
    if [[ $status -eq 0 ]]; then
      (cd "$ROOT/tests/e2e" && [[ -d node_modules ]] || npm ci --no-audit --no-fund) >> "$OUTPUT/setup.log" 2>&1
      (cd "$ROOT/tests/e2e" && \
        PLAYWRIGHT_JUNIT_OUTPUT_FILE="$OUTPUT/junit.xml" \
        PLAYWRIGHT_HTML_OUTPUT_DIR="$OUTPUT/playwright-report" \
        E2E_RESULTS_DIR="$OUTPUT/test-results" \
        E2E_EXPECTED_LEDGERWRITER_VERSION="$LEDGERWRITER_SOURCE_VERSION" \
        npx playwright test) > "$OUTPUT/playwright.log" 2>&1
      status=$?
      (cd "$OUTPUT" && python3 -m zipfile -c playwright-report.zip playwright-report test-results) >/dev/null 2>&1
      "${COMPOSE[@]}" logs --no-color ledgerwriter > "$OUTPUT/ledgerwriter.log" 2>&1
    fi
    ;;
  *) echo "unknown layer: $LAYER" >&2; exit 2 ;;
esac
exit "$status"
