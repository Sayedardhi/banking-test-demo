#!/usr/bin/env bash
# Runs one balancereader test layer and writes fresh evidence to OUTPUT_DIR:
#   junit/TEST-*.xml  Surefire JUnit XML
#   jacoco.xml        JaCoCo XML for all balancereader production classes
#   summary.md        per-layer counts and line/branch coverage
# Exit code: nonzero on build error, test failure/error, or zero discovered tests.
set -uo pipefail

usage() { echo "usage: $0 unit|integration OUTPUT_DIR" >&2; exit 2; }
[[ $# -eq 2 ]] || usage
LAYER="$1"
OUT="$2"
case "$LAYER" in
  unit) FILTER='!*IntegrationTest' ;;
  integration) FILTER='*IntegrationTest' ;;
  *) usage ;;
esac

SERVICE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$SERVICE_DIR/../../.." && pwd)"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
rm -rf "$OUT/junit" "$OUT/jacoco.xml" "$OUT/summary.md"
mkdir -p "$OUT/junit"

cd "$REPO_ROOT"
./mvnw -B -pl src/ledger/balancereader clean verify \
  -Dcheckstyle.skip=true \
  -Dtest="$FILTER" \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dmaven.test.failure.ignore=true 2>&1 | tee "$OUT/maven.log"
MVN_STATUS=${PIPESTATUS[0]}

TARGET="$SERVICE_DIR/target"
cp "$TARGET"/surefire-reports/TEST-*.xml "$OUT/junit/" 2>/dev/null || true
cp "$TARGET/site/jacoco/jacoco.xml" "$OUT/jacoco.xml" 2>/dev/null || true

python3 "$SERVICE_DIR/scripts/summarize.py" "$LAYER" "$OUT" > "$OUT/summary.md"
SUMMARY_STATUS=$?
cat "$OUT/summary.md"

if [[ $MVN_STATUS -ne 0 ]]; then
  echo "maven exited with $MVN_STATUS" >&2
  exit "$MVN_STATUS"
fi
exit "$SUMMARY_STATUS"
