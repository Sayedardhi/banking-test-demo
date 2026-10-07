#!/usr/bin/env bash
# Runs one balancereader test layer once and writes evidence to OUTPUT_DIR:
#   junit/TEST-*.xml, jacoco.xml, jacoco-html.zip, maven.log, summary.md
# Usage: report-tests.sh unit|integration OUTPUT_DIR
# Test failures do not stop Maven (reports are always written); the exit code is nonzero when any test
# failed, zero tests ran, coverage is missing, or the build itself failed.
# MVN overrides the Maven command (default: the repository's ./mvnw).
set -uo pipefail
layer="${1:?usage: report-tests.sh unit|integration OUTPUT_DIR}"
out="$(mkdir -p "${2:?usage: report-tests.sh unit|integration OUTPUT_DIR}" && cd "$2" && pwd)"
service="$(cd "$(dirname "$0")/.." && pwd)"
repo="$(cd "$service/../../.." && pwd)"
case "$layer" in
  unit) filter='!*IntegrationTest' ;;
  integration) filter='*IntegrationTest' ;;
  *) echo "unknown layer: $layer" >&2; exit 2 ;;
esac
mvn_cmd="${MVN:-$repo/mvnw}"

rm -rf "$service/target/surefire-reports" "$service/target/site/jacoco" "$service/target/jacoco.exec" \
  "$out/junit" "$out/jacoco.xml" "$out/jacoco-html.zip"
cd "$repo" || exit 2
"$mvn_cmd" -B -pl src/ledger/balancereader verify -Dcheckstyle.skip=true \
  -Dmaven.test.failure.ignore=true "-Dtest=$filter" -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tee "$out/maven.log"
maven_code=${PIPESTATUS[0]}

mkdir -p "$out/junit"
cp "$service"/target/surefire-reports/TEST-*.xml "$out/junit/" 2>/dev/null
cp "$service/target/site/jacoco/jacoco.xml" "$out/jacoco.xml" 2>/dev/null
[[ -d "$service/target/site/jacoco" ]] &&
  python3 -c 'import shutil,sys; shutil.make_archive(sys.argv[1], "zip", sys.argv[2], "jacoco")' \
    "$out/jacoco-html" "$service/target/site"

python3 "$service/scripts/summarize.py" --markdown "$layer=$out" | tee "$out/summary.md"
python3 "$service/scripts/summarize.py" --exit-code --maven-code "$maven_code" "$layer=$out"
