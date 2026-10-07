#!/usr/bin/env bash
# Measure test coverage for the compliance-critical services and print a Markdown table.
# Requires JDK 17+ and uv; Node 24 only once the audit service has a coverage script.
set -euo pipefail
cd "$(dirname "$0")/.."

rows=()
add_row() { rows+=("| $1 | $2 | $3 | $4 | $5 |"); }

java_modules=(ledgerwriter balancereader transactionhistory)
./mvnw -q -B -pl "$(printf 'src/ledger/%s,' "${java_modules[@]}" | sed 's/,$//')" -am verify >/tmp/coverage-java.log 2>&1 \
  || { echo "Java tests failed; see /tmp/coverage-java.log" >&2; exit 1; }
for m in "${java_modules[@]}"; do
  csv="src/ledger/$m/target/site/jacoco/jacoco.csv"
  read -r line branch < <(awk -F, 'NR>1{lm+=$8;lc+=$9;bm+=$6;bc+=$7} END{printf "%d/%d %d/%d\n",lc,lm+lc,bc,bm+bc}' "$csv")
  area=$([ "$m" = ledgerwriter ] && echo "Transaction processing" || echo "Transaction reads")
  add_row "$m (Java)" "$area" "JUnit + JaCoCo" "$line lines" "$branch branches"
done

py_cov() {
  local dir=$1 name=$2 area=$3
  if [ -d "src/accounts/$dir/tests" ]; then
    (cd "src/accounts/$dir" && uv run --group dev pytest -q -p no:warnings --cov=. --cov-branch \
      --cov-report=json:/tmp/coverage-$dir.json >/tmp/coverage-$dir.log 2>&1) \
      || { echo "$dir tests failed; see /tmp/coverage-$dir.log" >&2; exit 1; }
    read -r line branch < <(python3 - "/tmp/coverage-$dir.json" <<'PY'
import json, sys
files = json.load(open(sys.argv[1]))["files"]
s = [v["summary"] for k, v in files.items() if not k.startswith("tests")]
print(f'{sum(x["covered_lines"] for x in s)}/{sum(x["num_statements"] for x in s)}',
      f'{sum(x["covered_branches"] for x in s)}/{sum(x["num_branches"] for x in s)}')
PY
)
    add_row "$name (Python)" "$area" "pytest + pytest-cov" "$line statements" "$branch branches"
  else
    add_row "$name (Python)" "$area" "**none**" "0 (no tests)" "-"
  fi
}
py_cov userservice userservice "Authentication"
py_cov contacts contacts "PII (account/routing numbers)"

if grep -q '"coverage"' src/audit/package.json; then
  if command -v npm >/dev/null; then
    (cd src/audit && npm ci --silent && npm run --silent coverage) | tail -20
    add_row "audit (TypeScript)" "Audit logging" "see output above" "-" "-"
  else
    add_row "audit (TypeScript)" "Audit logging" "configured (npm not installed)" "-" "-"
  fi
else
  add_row "audit (TypeScript)" "Audit logging" "**none**" "0 (no tests)" "-"
fi

echo "| Service | Compliance area | Test harness | Covered | Branches |"
echo "| --- | --- | --- | --- | --- |"
printf '%s\n' "${rows[@]}"
