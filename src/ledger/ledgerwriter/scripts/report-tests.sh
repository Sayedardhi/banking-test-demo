#!/usr/bin/env bash
# Runs one ledgerwriter test layer and writes JUnit + JaCoCo reports to an output folder.
# Usage: report-tests.sh <unit|integration> <output-dir>
# Exit code: non-zero on build error, test failure/error, or zero discovered tests.
set -uo pipefail

layer="${1:?layer (unit|integration) required}"
out="${2:?output directory required}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
service_dir="$(cd "$here/.." && pwd)"
repo="$(cd "$service_dir/../../.." && pwd)"
mvn="${MVN:-$repo/mvnw}"

case "$layer" in
  unit) filter=(-Dtest='!*IntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false) ;;
  integration) filter=(-Dtest='*IntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false) ;;
  *) echo "unknown layer: $layer" >&2; exit 2 ;;
esac

mkdir -p "$out"
out="$(cd "$out" && pwd)"
rm -rf "$out/junit" "$out/jacoco.xml" "$out/jacoco-html.tar.gz"

cd "$repo"
"$mvn" -B -pl src/ledger/ledgerwriter clean verify \
  -Dcheckstyle.skip=true -Dmaven.test.failure.ignore=true "${filter[@]}"
mvn_status=$?

target="$service_dir/target"
mkdir -p "$out/junit"
cp "$target"/surefire-reports/TEST-*.xml "$out/junit/" 2>/dev/null || true
cp "$target/site/jacoco/jacoco.xml" "$out/jacoco.xml" 2>/dev/null || true
if [ -d "$target/site/jacoco" ]; then
  tar -czf "$out/jacoco-html.tar.gz" -C "$target/site" jacoco
fi

python3 "$here/summarize.py" --check "$layer=$out"
summary_status=$?

if [ "$mvn_status" -ne 0 ]; then
  echo "Maven exited with $mvn_status" >&2
  exit "$mvn_status"
fi
exit "$summary_status"
