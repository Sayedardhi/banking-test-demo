#!/usr/bin/env python3
"""Summarise one balancereader test layer from JUnit XML and JaCoCo XML.

Prints a Markdown row: layer | tests | passed | failed | errors | skipped |
line covered/total | branch covered/total. Exits 1 when any test failed or
errored, or when zero tests were discovered.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def junit_counts(folder):
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for path in sorted(glob.glob(os.path.join(folder, "junit", "TEST-*.xml"))):
        root = ET.parse(path).getroot()
        suites = [root] if root.tag == "testsuite" else root.iter("testsuite")
        for suite in suites:
            for key in totals:
                totals[key] += int(suite.get(key, 0))
    return totals


def jacoco_counter(folder, kind):
    path = os.path.join(folder, "jacoco.xml")
    if not os.path.exists(path):
        return None
    root = ET.parse(path).getroot()
    for counter in root.findall("counter"):
        if counter.get("type") == kind:
            missed, covered = int(counter.get("missed")), int(counter.get("covered"))
            return covered, covered + missed
    return None


def fmt(cov):
    if cov is None:
        return "unmeasured"
    covered, total = cov
    pct = 100.0 * covered / total if total else 0.0
    return f"{covered}/{total} ({pct:.1f}%)"


def main():
    layer, folder = sys.argv[1], sys.argv[2]
    c = junit_counts(folder)
    passed = c["tests"] - c["failures"] - c["errors"] - c["skipped"]
    print("| Layer | Tests | Passed | Failed | Errors | Skipped | Line coverage | Branch coverage |")
    print("|---|---|---|---|---|---|---|---|")
    print(f"| {layer} | {c['tests']} | {passed} | {c['failures']} | {c['errors']} | "
          f"{c['skipped']} | {fmt(jacoco_counter(folder, 'LINE'))} | "
          f"{fmt(jacoco_counter(folder, 'BRANCH'))} |")
    if c["tests"] == 0:
        print(f"\n**ERROR: zero {layer} tests discovered.**")
        return 1
    if c["failures"] or c["errors"]:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
