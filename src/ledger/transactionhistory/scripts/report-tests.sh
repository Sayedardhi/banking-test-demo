#!/usr/bin/env bash
# Runs one transactionhistory test layer once and writes JUnit XML + JaCoCo (XML and HTML) to <out>.
#   unit         every *Test class except *IntegrationTest (JUnit 5, Mockito, no Docker)
#   integration  *IntegrationTest classes (Spring Boot + Testcontainers postgres:16-alpine; needs Docker)
# Usage: scripts/report-tests.sh <unit|integration> <out>
# Exit code: Maven's, or non-zero when no tests ran, a test failed, or coverage XML is missing.
set -uo pipefail

layer="${1:?usage: report-tests.sh <unit|integration> <out>}"
out="${2:?usage: report-tests.sh <unit|integration> <out>}"
scripts="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
module="$(cd "$scripts/.." && pwd)"
repo="$(cd "$module/../../.." && pwd)"

case "$layer" in
  unit) selection='!*IntegrationTest' ;;
  integration) selection='*IntegrationTest' ;;
  *) echo "unknown layer '$layer' (expected unit or integration)" >&2; exit 2 ;;
esac

mkdir -p "$out" && out="$(cd "$out" && pwd)"
rm -rf "$out/junit" "$out/jacoco.xml" "$out/jacoco-html"
# Stale reports must never be mistaken for this run's results.
rm -rf "$module/target/surefire-reports" "$module/target/site/jacoco" "$module/target/jacoco.exec"

cd "$repo"
"${MVN:-./mvnw}" -B -pl src/ledger/transactionhistory verify \
  -Dcheckstyle.skip=true -Dmaven.test.failure.ignore=true \
  -Dtest="$selection" -Dsurefire.failIfNoSpecifiedTests=false
maven=$?

mkdir -p "$out/junit"
cp "$module"/target/surefire-reports/TEST-*.xml "$out/junit/" 2>/dev/null
cp "$module/target/site/jacoco/jacoco.xml" "$out/jacoco.xml" 2>/dev/null
[ -d "$module/target/site/jacoco" ] && cp -r "$module/target/site/jacoco" "$out/jacoco-html"

python3 "$scripts/summarize.py" --check "$layer=$out"
checked=$?
[ "$maven" -ne 0 ] && { echo "Maven exited $maven" >&2; exit "$maven"; }
exit "$checked"
