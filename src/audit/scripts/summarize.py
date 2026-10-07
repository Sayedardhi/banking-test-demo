#!/usr/bin/env python3
"""Summarize audit JUnit + Cobertura reports per test layer.

Usage: summarize.py [--check|--markdown|--harness] layer=dir [layer=dir ...]
  --check     exit 1 if a layer has failures, no tests, or no coverage report
  --markdown  print a GitHub step-summary table
  --harness   print harness=ok when every layer ran tests and wrote coverage
              (test failures allowed; used to gate E2E), else harness=broken
Coverage is per layer for all of src/**/*.ts (Cobertura totals), never averaged.
"""
import os
import sys
import xml.etree.ElementTree as ET


def junit_counts(folder):
    totals = {"tests": 0, "failed": 0, "skipped": 0}
    path = os.path.join(folder, "junit.xml")
    if os.path.exists(path):
        for case in ET.parse(path).getroot().iter("testcase"):
            totals["tests"] += 1
            if case.find("failure") is not None or case.find("error") is not None:
                totals["failed"] += 1
            elif case.find("skipped") is not None:
                totals["skipped"] += 1
    totals["passed"] = totals["tests"] - totals["failed"] - totals["skipped"]
    return totals


def failed_names(folder):
    path = os.path.join(folder, "junit.xml")
    if not os.path.exists(path):
        return []
    return [c.get("name") for c in ET.parse(path).getroot().iter("testcase")
            if c.find("failure") is not None or c.find("error") is not None]


def cobertura_counts(folder):
    path = os.path.join(folder, "coverage.xml")
    if not os.path.exists(path):
        return None
    root = ET.parse(path).getroot()
    return {"line": (int(root.get("lines-covered")), int(root.get("lines-valid"))),
            "branch": (int(root.get("branches-covered")), int(root.get("branches-valid")))}


def fmt(pair):
    if not pair:
        return "unmeasured"
    covered, total = pair
    return f"{covered}/{total} ({100 * covered / total:.1f}%)" if total else f"{covered}/{total}"


def main(argv):
    layers = [a.split("=", 1) for a in argv if "=" in a]
    rows = []
    for layer, folder in layers:
        tests, cov = junit_counts(folder), cobertura_counts(folder) or {}
        problems = []
        if tests["tests"] == 0:
            problems.append("no tests discovered")
        elif tests["failed"]:
            problems.append(f"{tests['failed']} failed")
        if not cov:
            problems.append("coverage report missing")
        rows.append((layer, folder, tests, cov, "; ".join(problems)))
    if "--harness" in argv:
        ok = rows and all(t["tests"] > 0 and cov for _, _, t, cov, _ in rows)
        print("harness=" + ("ok" if ok else "broken"))
        return 0
    if "--markdown" in argv:
        print("### audit test results\n")
        print("| Layer | Tests | Passed | Failed | Skipped | Line coverage (covered/total) | Branch coverage (covered/total) | Status |")
        print("|---|---|---|---|---|---|---|---|")
        for layer, _, t, cov, problem in rows:
            print(f"| {layer} | {t['tests']} | {t['passed']} | {t['failed']} | {t['skipped']} | "
                  f"{fmt(cov.get('line'))} | {fmt(cov.get('branch'))} | {problem or 'OK'} |")
        for layer, folder, *_ in rows:
            for name in failed_names(folder):
                print(f"- {layer} failed: {name}")
        print("\nCoverage: Cobertura (c8/V8) per layer over all of `src/audit/src/**/*.ts`, not averaged across layers or services.")
        return 0
    for layer, _, t, cov, problem in rows:
        print(f"[audit {layer}] tests={t['tests']} passed={t['passed']} failed={t['failed']} skipped={t['skipped']} "
              f"line={fmt(cov.get('line'))} branch={fmt(cov.get('branch'))}" + (f" PROBLEM: {problem}" if problem else ""))
    return 1 if "--check" in argv and any(r[4] for r in rows) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
