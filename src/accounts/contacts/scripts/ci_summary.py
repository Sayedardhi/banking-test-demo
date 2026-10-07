#!/usr/bin/env python3
"""Summarise contacts test layers (JUnit + Cobertura XML) as GitHub Markdown.

Usage: ci_summary.py <reports-dir>   (expects <dir>/<layer>/junit.xml and coverage.xml)
Exits non-zero when a layer is missing or discovered zero tests.
"""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

LAYERS = ("unit", "integration")


def tests(path):
    """Count JUnit test cases by outcome."""
    counts = {"total": 0, "passed": 0, "failed": 0, "skipped": 0}
    for case in ET.parse(path).getroot().iter("testcase"):
        counts["total"] += 1
        if case.find("failure") is not None or case.find("error") is not None:
            counts["failed"] += 1
        elif case.find("skipped") is not None:
            counts["skipped"] += 1
        else:
            counts["passed"] += 1
    return counts


def ratio(covered, total):
    """Format covered/total with a percentage."""
    return "%d/%d (%.1f%%)" % (covered, total, 100.0 * covered / total) if total else "n/a"


def coverage(path):
    """Line and branch ratios from a Cobertura report."""
    root = ET.parse(path).getroot()
    return (ratio(int(root.get("lines-covered")), int(root.get("lines-valid"))),
            ratio(int(root.get("branches-covered")), int(root.get("branches-valid"))))


def main():
    """Print the summary table; return 1 on a missing or empty layer."""
    base = Path(sys.argv[1])
    ok = True
    lines = ["## contacts (PII: account/routing numbers) test results", "",
             "| Layer | Tests | Passed | Failed | Skipped | Line coverage | Branch coverage |",
             "| --- | ---: | ---: | ---: | ---: | --- | --- |"]
    for layer in LAYERS:
        junit, cov = base / layer / "junit.xml", base / layer / "coverage.xml"
        if not junit.is_file():
            lines.append("| %s | **no report** | | | | | |" % layer)
            ok = False
            continue
        t = tests(junit)
        line_cov, branch_cov = coverage(cov) if cov.is_file() else ("n/a", "n/a")
        if t["total"] == 0:
            ok = False
        lines.append("| %s | %d | %d | %d | %d | %s | %s |" % (
            layer, t["total"], t["passed"], t["failed"], t["skipped"], line_cov, branch_cov))
    lines += ["", "Coverage scope: every production module in `src/accounts/contacts` "
              "(contacts.py, db.py, __init__.py), tests excluded; reported per layer, "
              "not averaged with other services."]
    if not ok:
        lines += ["", "**ERROR: a layer produced no report or discovered zero tests.**"]
    print("\n".join(lines))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
