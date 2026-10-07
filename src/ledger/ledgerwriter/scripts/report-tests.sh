#!/usr/bin/env bash
# Runs one ledgerwriter test layer once and writes evidence to OUTPUT_DIR:
#   TEST-*.xml (JUnit), jacoco.xml + jacoco-html/ (coverage of all ledgerwriter main classes), maven.log
#
#   report-tests.sh unit|integration OUTPUT_DIR
#
# unit        = every *Test class except *IntegrationTest (JUnit 5 + Mockito, no Docker)
# integration = *IntegrationTest (Spring Boot on a random port + Testcontainers postgres:16-alpine)
# Exit code: 0 all passed, 1 test failures, 3 zero tests executed, 4 no coverage report, otherwise Maven's.
set -uo pipefail
LAYER="${1:?usage: report-tests.sh unit|integration OUTPUT_DIR}"
OUT="$(mkdir -p "${2:?usage: report-tests.sh unit|integration OUTPUT_DIR}" && cd "$2" && pwd)"
SERVICE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$SERVICE_DIR/../../.." && pwd)"
case "$LAYER" in
  unit) FILTER='!*IntegrationTest' ;;
  integration) FILTER='*IntegrationTest' ;;
  *) echo "unknown layer: $LAYER" >&2; exit 2 ;;
esac

cd "$REPO"
rm -rf "$OUT"/TEST-*.xml "$OUT"/jacoco.xml "$OUT"/jacoco-html
./mvnw -B -pl src/ledger/ledgerwriter clean verify \
  -Dcheckstyle.skip=true -Dmaven.test.failure.ignore=true \
  "-Dtest=$FILTER" -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tee "$OUT/maven.log"
mvn_status=${PIPESTATUS[0]}

target="$SERVICE_DIR/target"
cp "$target"/surefire-reports/TEST-*.xml "$OUT"/ 2>/dev/null
if [[ -f "$target/site/jacoco/jacoco.xml" ]]; then
  cp "$target/site/jacoco/jacoco.xml" "$OUT/jacoco.xml"
  cp -r "$target/site/jacoco" "$OUT/jacoco-html"
fi
[[ "$mvn_status" -eq 0 ]] || exit "$mvn_status"

python3 - "$OUT" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
out = sys.argv[1]
tests = failed = 0
for path in glob.glob(f'{out}/TEST-*.xml'):
    root = ET.parse(path).getroot()
    tests += int(root.get('tests', 0)) - int(root.get('skipped', 0))
    failed += int(root.get('failures', 0)) + int(root.get('errors', 0))
print(f'ledgerwriter: executed {tests} tests, {failed} failed')
sys.exit(3 if tests == 0 else 1 if failed else 0)
PY
status=$?
[[ "$status" -eq 3 ]] && { echo "No tests executed for layer $LAYER" >&2; exit 3; }
[[ -f "$OUT/jacoco.xml" ]] || { echo "No JaCoCo report for layer $LAYER" >&2; exit 4; }
exit "$status"
