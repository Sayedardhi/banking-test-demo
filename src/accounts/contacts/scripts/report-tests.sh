#!/usr/bin/env bash
# Run one contacts test layer and write JUnit + Cobertura XML to an output folder.
# Usage: scripts/report-tests.sh <unit|integration> <output-dir>
# Exit code is pytest's (5 = no tests collected).
set -uo pipefail

layer="${1:?layer (unit|integration) required}"
out="$(realpath -m "${2:?output dir required}")"
cd "$(dirname "$0")/.."
mkdir -p "$out"

case "$layer" in
  unit) selection=(tests --ignore=tests/integration) ;;
  integration) selection=(tests/integration) ;;
  *) echo "unknown layer: $layer" >&2; exit 2 ;;
esac

export COVERAGE_FILE="$out/.coverage" PYTHONDONTWRITEBYTECODE=1
uv run --frozen --group dev python -m pytest "${selection[@]}" \
  -p no:cacheprovider -o junit_suite_name="contacts-$layer" -o junit_family=xunit2 \
  --junitxml="$out/junit.xml" \
  --cov --cov-branch --cov-report=term-missing --cov-report=xml:"$out/coverage.xml"
