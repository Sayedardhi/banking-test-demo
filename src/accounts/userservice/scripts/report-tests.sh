#!/usr/bin/env bash
# Run one userservice test layer and write JUnit + Cobertura reports.
#   scripts/report-tests.sh unit|integration OUTPUT_DIR
#   scripts/report-tests.sh combined OUTPUT_DIR UNIT_DIR INTEGRATION_DIR
# Exit code is pytest's (5 = no tests collected).
set -uo pipefail

layer="${1:?usage: report-tests.sh unit|integration|combined OUTPUT_DIR}"
out="${2:?usage: report-tests.sh unit|integration|combined OUTPUT_DIR}"
service_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$out"
out="$(cd "$out" && pwd)"
cd "$service_dir" || exit 2
export PYTHONDONTWRITEBYTECODE=1

uv sync --frozen --quiet || exit 2

case "$layer" in
  unit)
    # API/business logic with the database adapter mocked.
    targets=(tests --ignore=tests/integration --ignore=tests/test_db.py) ;;
  integration)
    # Existing in-memory SQLite UserDb tests + real PostgreSQL (Testcontainers).
    targets=(tests/test_db.py tests/integration) ;;
  combined)
    unit_dir="${3:?unit report dir required}"
    integration_dir="${4:?integration report dir required}"
    export COVERAGE_FILE="$out/.coverage"
    uv run --frozen --no-sync coverage combine --keep \
      "$unit_dir/.coverage" "$integration_dir/.coverage" || exit 2
    uv run --frozen --no-sync coverage xml --rcfile=scripts/coverage.ini -o "$out/coverage.xml"
    exit $? ;;
  *)
    echo "unknown layer: $layer" >&2
    exit 2 ;;
esac

COVERAGE_FILE="$out/.coverage" uv run --frozen --no-sync pytest "${targets[@]}" \
  -p no:cacheprovider -o junit_family=xunit2 -o junit_suite_name="userservice-$layer" \
  --junitxml="$out/junit.xml" \
  --cov=. --cov-branch --cov-config=scripts/coverage.ini \
  --cov-report=xml:"$out/coverage.xml" --cov-report=term
