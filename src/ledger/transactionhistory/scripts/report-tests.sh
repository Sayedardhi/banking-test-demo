#!/usr/bin/env bash
# Runs one transactionhistory test layer and writes fresh reports to an output folder.
#   usage: report-tests.sh <unit|integration> <output-dir>
# Writes: junit/TEST-*.xml, jacoco.xml, jacoco-html.zip, maven.log, summary.md
# Exit code: Maven's exit code, or 1 if any test failed/errored or zero tests were discovered.
set -uo pipefail

layer="${1:?layer (unit|integration) required}"
out="${2:?output directory required}"
svc_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo="$(cd "$svc_dir/../../.." && pwd)"

case "$layer" in
  unit) filter='!*IntegrationTest' ;;
  integration) filter='*IntegrationTest' ;;
  *) echo "unknown layer: $layer" >&2; exit 2 ;;
esac

mkdir -p "$out"
out="$(cd "$out" && pwd)"
rm -rf "$out/junit" "$out/jacoco.xml" "$out/jacoco-html.zip" "$out/summary.md"
rm -rf "$svc_dir/target/surefire-reports" "$svc_dir/target/site/jacoco" "$svc_dir/target/jacoco.exec"

(cd "$repo" && ./mvnw -B -pl src/ledger/transactionhistory verify \
  -Dcheckstyle.skip=true \
  -Dtest="$filter" -Dsurefire.failIfNoSpecifiedTests=false \
  -Dmaven.test.failure.ignore=true) > "$out/maven.log" 2>&1
mvn_rc=$?

mkdir -p "$out/junit"
cp "$svc_dir"/target/surefire-reports/TEST-*.xml "$out/junit/" 2>/dev/null || true
cp "$svc_dir/target/site/jacoco/jacoco.xml" "$out/jacoco.xml" 2>/dev/null || true
[ -d "$svc_dir/target/site/jacoco" ] && (cd "$svc_dir/target/site" && zip -qr "$out/jacoco-html.zip" jacoco)

python3 - "$layer" "$out" "$mvn_rc" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
layer, out, mvn_rc = sys.argv[1], sys.argv[2], int(sys.argv[3])
t = f = e = s = 0
for path in glob.glob(f"{out}/junit/TEST-*.xml"):
    r = ET.parse(path).getroot()
    t += int(r.get("tests", 0)); f += int(r.get("failures", 0))
    e += int(r.get("errors", 0)); s += int(r.get("skipped", 0))
    for tc in r.iter("testcase"):
        bad = tc.find("failure") if tc.find("failure") is not None else tc.find("error")
        if bad is not None:
            msg = " ".join((bad.get("message") or bad.get("type") or "").split())[:300]
            print(f"FAILED {tc.get('classname', '').rsplit('.', 1)[-1]}.{tc.get('name')}: {msg}")
cov = {}
try:
    root = ET.parse(f"{out}/jacoco.xml").getroot()
    for c in root.findall("counter"):
        m, x = int(c.get("missed")), int(c.get("covered"))
        cov[c.get("type")] = (x, m + x)
except (OSError, ET.ParseError):
    pass
def fmt(kind):
    if kind not in cov: return "unmeasured"
    x, n = cov[kind]
    return f"{x}/{n} ({100 * x / n:.1f}%)" if n else "0/0"
rc = mvn_rc or (1 if (f or e or t == 0) else 0)
status = "PASS" if rc == 0 else ("NO TESTS DISCOVERED" if t == 0 else "FAIL")
with open(f"{out}/summary.md", "w") as fh:
    fh.write(f"| {layer} | {status} | {t} | {t - f - e - s} | {f + e} | {s} | {fmt('LINE')} | {fmt('BRANCH')} |\n")
with open(f"{out}/exit-code", "w") as fh:
    fh.write(str(rc))
print(f"transactionhistory {layer}: {status} tests={t} failed={f + e} skipped={s} line={fmt('LINE')} branch={fmt('BRANCH')}")
PY
exit "$(cat "$out/exit-code" 2>/dev/null || echo 1)"
