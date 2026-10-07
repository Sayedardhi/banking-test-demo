#!/usr/bin/env python3
"""Summarize contacts JUnit + Cobertura reports per test layer.

Usage: summarize.py [--markdown|--harness] layer=dir [layer=dir ...]
--markdown  GitHub step-summary table.
--harness   prints harness=ok when every layer ran tests (failures allowed) and, for pytest
            layers, wrote coverage.xml; otherwise harness=broken. Used to gate the E2E job.
"""
import os
import sys
import xml.etree.ElementTree as ET


def junit_counts(folder):
    """Test/failed/skipped/passed counts from folder/junit.xml."""
    counts = {"tests": 0, "failed": 0, "skipped": 0}
    path = os.path.join(folder, "junit.xml")
    if os.path.exists(path):
        for case in ET.parse(path).getroot().iter("testcase"):
            counts["tests"] += 1
            if case.find("failure") is not None or case.find("error") is not None:
                counts["failed"] += 1
            elif case.find("skipped") is not None:
                counts["skipped"] += 1
    counts["passed"] = counts["tests"] - counts["failed"] - counts["skipped"]
    return counts


def cobertura_counts(folder):
    """(covered, total) for line and branch from folder/coverage.xml, or None."""
    path = os.path.join(folder, "coverage.xml")
    if not os.path.exists(path):
        return None
    root = ET.parse(path).getroot()
    return {kind: (int(root.get(f"{attr}-covered")), int(root.get(f"{attr}-valid")))
            for kind, attr in (("line", "lines"), ("branch", "branches"))}


def fmt(pair):
    """covered/total (percent)."""
    if not pair:
        return "not measured"
    covered, total = pair
    return f"{covered}/{total} ({100 * covered / total:.1f}%)" if total else f"{covered}/{total}"


def main(argv):
    """Entry point; see module docstring."""
    rows = []
    for layer, folder in (a.split("=", 1) for a in argv if "=" in a):
        rows.append((layer, junit_counts(folder), cobertura_counts(folder)))
    if "--harness" in argv:
        ok = rows and all(t["tests"] > 0 and (cov or layer == "e2e") for layer, t, cov in rows)
        print("harness=" + ("ok" if ok else "broken"))
        return 0
    if "--markdown" in argv:
        print("### contacts test results\n")
        print("| Layer | Tests | Passed | Failed | Skipped | Line covered/total "
              "| Branch covered/total |")
        print("|---|---|---|---|---|---|---|")
    for layer, t, cov in rows:
        cov = cov or {}
        if "--markdown" in argv:
            print(f"| {layer} | {t['tests']} | {t['passed']} | {t['failed']} | "
                  f"{t['skipped']} | {fmt(cov.get('line'))} | {fmt(cov.get('branch'))} |")
        else:
            print(f"[contacts {layer}] tests={t['tests']} passed={t['passed']} "
                  f"failed={t['failed']} skipped={t['skipped']} "
                  f"line={fmt(cov.get('line'))} branch={fmt(cov.get('branch'))}")
    if "--markdown" in argv:
        print("\nCoverage is per layer over the whole contacts service (contacts.py, db.py, "
              "__init__.py); layers are not merged or averaged. "
              "Browser E2E is black-box (no coverage).")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
