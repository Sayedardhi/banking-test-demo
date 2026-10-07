#!/usr/bin/env bash
# Runs one userservice test layer once and writes evidence to OUTPUT_DIR:
#   junit.xml, coverage.xml (Cobertura, line + branch), htmlcov/, pytest.log
#
#   scripts/report-tests.sh unit OUTPUT_DIR         # mocked UserDb; Flask test client
#   scripts/report-tests.sh integration OUTPUT_DIR  # test_db.py (SQLite) + PostgreSQL 16 via Testcontainers (needs Docker)
#
# Exit code is pytest's (5 = no tests collected, which is a failure).
set -uo pipefail
LAYER="${1:?usage: report-tests.sh unit|integration OUTPUT_DIR}"
OUTPUT_DIR="$(mkdir -p "${2:?usage: report-tests.sh unit|integration OUTPUT_DIR}" && cd "$2" && pwd)"
SERVICE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$SERVICE_DIR"

case "$LAYER" in
  unit) TARGETS=(tests --ignore=tests/integration --ignore=tests/test_db.py) ;;
  integration) TARGETS=(tests/test_db.py tests/integration) ;;
  *) echo "unknown layer: $LAYER" >&2; exit 2 ;;
esac

# Keep the virtualenv outside the service directory: the Dockerfile copies the whole directory.
export UV_PROJECT_ENVIRONMENT="${UV_PROJECT_ENVIRONMENT:-${XDG_CACHE_HOME:-$HOME/.cache}/banking-test-demo/userservice-venv}"
uv sync --frozen --group dev --quiet || exit 2
rm -f "$OUTPUT_DIR"/junit.xml "$OUTPUT_DIR"/coverage.xml .coverage

uv run --frozen --no-sync python -m pytest "${TARGETS[@]}" -p no:cacheprovider -W ignore::DeprecationWarning \
  --junitxml="$OUTPUT_DIR/junit.xml" -o junit_suite_name="userservice-$LAYER" \
  --cov --cov-report=xml:"$OUTPUT_DIR/coverage.xml" --cov-report=html:"$OUTPUT_DIR/htmlcov" \
  --cov-report=term 2>&1 | tee "$OUTPUT_DIR/pytest.log"
status=${PIPESTATUS[0]}
rm -f .coverage
exit "$status"
