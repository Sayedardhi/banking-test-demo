#!/usr/bin/env python3
"""Summarize ledgerwriter JUnit + JaCoCo reports per test layer.

Usage: summarize.py [--check] [--markdown] layer=dir [layer=dir ...]
--check exits 1 if any layer has failures/errors, no tests, or missing reports.
--markdown prints a GitHub step-summary table instead of plain text.
--harness prints harness=ok when every layer executed tests and produced coverage
(test failures allowed), else harness=broken; used to gate the E2E job.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def junit_counts(folder):
    totals = {"tests": 0, "failed": 0, "skipped": 0}
    for path in glob.glob(os.path.join(folder, "junit", "TEST-*.xml")):
        suite = ET.parse(path).getroot()
        totals["tests"] += int(suite.get("tests", 0))
        totals["failed"] += int(suite.get("failures", 0)) + int(suite.get("errors", 0))
        totals["skipped"] += int(suite.get("skipped", 0))
    totals["passed"] = totals["tests"] - totals["failed"] - totals["skipped"]
    return totals


def jacoco_counts(folder):
    path = os.path.join(folder, "jacoco.xml")
    if not os.path.exists(path):
        return None
    report = ET.parse(path).getroot()
    result = {}
    for counter in report.findall("counter"):
        kind = counter.get("type")
        if kind in ("LINE", "BRANCH"):
            covered, missed = int(counter.get("covered")), int(counter.get("missed"))
            result[kind.lower()] = (covered, covered + missed)
    return result


def fmt(pair):
    if not pair:
        return "unmeasured"
    covered, total = pair
    return f"{covered}/{total} ({100 * covered / total:.1f}%)" if total else f"{covered}/{total}"


def main(argv):
    check = "--check" in argv
    markdown = "--markdown" in argv
    harness = "--harness" in argv
    layers = [a.split("=", 1) for a in argv if "=" in a]
    ok = True
    rows = []
    for layer, folder in layers:
        tests = junit_counts(folder)
        cov = jacoco_counts(folder) or {}
        problem = ""
        if tests["tests"] == 0:
            problem = "no tests discovered"
        elif tests["failed"]:
            problem = f"{tests['failed']} failed"
        if not cov:
            problem = (problem + "; " if problem else "") + "coverage report missing"
        ok = ok and not problem
        rows.append((layer, tests, cov, problem))
    if harness:
        ran = all(t["tests"] > 0 and cov for _, t, cov, _ in rows)
        print("harness=" + ("ok" if rows and ran else "broken"))
        return 0
    if markdown:
        print("### ledgerwriter test results\n")
        print("| Layer | Tests | Passed | Failed | Skipped | Line coverage (covered/total) | Branch coverage (covered/total) | Status |")
        print("|---|---|---|---|---|---|---|---|")
        for layer, t, cov, problem in rows:
            status = "OK" if not problem else problem
            print(f"| {layer} | {t['tests']} | {t['passed']} | {t['failed']} | {t['skipped']} | "
                  f"{fmt(cov.get('line'))} | {fmt(cov.get('branch'))} | {status} |")
        print("\nCoverage is per layer for the whole ledgerwriter module (all classes under src/main), not averaged across services.")
    else:
        for layer, t, cov, problem in rows:
            print(f"[ledgerwriter {layer}] tests={t['tests']} passed={t['passed']} failed={t['failed']} "
                  f"skipped={t['skipped']} line={fmt(cov.get('line'))} branch={fmt(cov.get('branch'))}"
                  + (f" PROBLEM: {problem}" if problem else ""))
    return 0 if (ok or not check) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
