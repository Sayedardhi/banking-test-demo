#!/usr/bin/env python3
"""Package CI reports as a tools/test-observability run (no tests are re-executed).

Usage: dashboard_run.py SERVICE:LAYER:EXIT_CODE:REPORT_DIR [...]
Writes .local/test-observability/runs/<RUN_ID>/ with run.json and the copied reports, using the
dashboard's own run schema and parsers, and prints the run id.
"""
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / "tools/test-observability"))
import dashboard  # noqa: E402  pylint: disable=wrong-import-position,import-error


def layer_result(run, suite, code, folder):
    """Dashboard layer result for one CI report folder (already copied into the run)."""
    result = {"status": "blocked", "command": suite.get("command", ["github-actions"]),
              "coverage": None, "tests": None,
              "exitCode": int(code) if code.lstrip("-").isdigit() else None,
              "startedAt": run["startedAt"], "finishedAt": dashboard.now(),
              "source": "github-actions"}
    try:
        junit = sorted(folder.glob("junit.xml"))
        tests = dashboard.junit(junit) if junit else {"total": 0, "skipped": 0, "failed": 0}
        result["tests"] = tests if junit else None
        if (folder / "coverage.xml").exists():
            result["coverage"] = dashboard.coverage(folder / "coverage.xml")
        if tests["failed"]:
            result["status"] = "failed"
        elif result["exitCode"] == 0 and tests["total"] > tests["skipped"]:
            result["status"] = "passed"
        else:
            result["error"] = ("No test results were produced or the job did not complete. "
                               "Inspect the CI log.")
    except Exception as exc:  # pylint: disable=broad-except
        result["error"] = "Report parsing failed: " + str(exc)
    result["artifacts"] = dashboard.artifacts(run, folder)
    return result


def main(specs):
    """Create one dashboard run from SERVICE:LAYER:EXIT_CODE:REPORT_DIR specs and print its id."""
    suites = {(s["service"], s["layer"]): s for s in dashboard.config().get("suites", [])}
    run = dashboard.new_run()
    for spec in specs:
        service, layer, code, src = spec.split(":", 3)
        folder = dashboard.DATA / "runs" / run["id"] / service / layer
        if Path(src).is_dir():
            shutil.copytree(src, folder, dirs_exist_ok=True)
        folder.mkdir(parents=True, exist_ok=True)
        row = next(x for x in run["services"] if x["id"] == service)
        row["layers"][layer] = layer_result(run, suites.get((service, layer), {}), code, folder)
    run["finishedAt"] = dashboard.now()
    run["note"] = ("Imported from GitHub Actions reports (each suite executed once in CI). "
                   + run["note"])
    dashboard.persist(run)
    print(run["id"])


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1:])
