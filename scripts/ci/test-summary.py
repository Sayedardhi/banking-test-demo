#!/usr/bin/env python3
"""Print a Markdown test/coverage summary (appended to $GITHUB_STEP_SUMMARY in CI).

  test-summary.py junit LABEL FILE... [--exclude SUBSTRING]   exits 1 on failures/errors or zero tests
  test-summary.py jacoco LABEL jacoco.csv
  test-summary.py c8 LABEL coverage-summary.json
"""
import csv
import json
import os
import sys
import xml.etree.ElementTree as ET


def emit(text):
    print(text)
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as fh:
            fh.write(text + "\n")


def pct(covered, total):
    return f"{covered}/{total} ({100 * covered / total:.1f}%)" if total else "n/a"


def junit(label, files, exclude=None):
    files = [f for f in files if not (exclude and exclude in os.path.basename(f))]
    counts = {"tests": 0, "failed": 0, "errors": 0, "skipped": 0}
    failing = []
    for path in files:
        if not os.path.exists(path):
            continue
        for case in ET.parse(path).getroot().iter("testcase"):
            counts["tests"] += 1
            name = f"{case.get('classname', '')} > {case.get('name')}".strip(" >")
            if case.find("failure") is not None:
                counts["failed"] += 1
                failing.append(name)
            elif case.find("error") is not None:
                counts["errors"] += 1
                failing.append(name)
            elif case.find("skipped") is not None:
                counts["skipped"] += 1
    passed = counts["tests"] - counts["failed"] - counts["errors"] - counts["skipped"]
    emit(f"#### {label}\n\n| tests | passed | failed | errors | skipped |\n|---|---|---|---|---|\n"
         f"| {counts['tests']} | {passed} | {counts['failed']} | {counts['errors']} | {counts['skipped']} |\n")
    if failing:
        emit("Failing:\n" + "\n".join(f"- `{n}`" for n in failing[:25]) + "\n")
    if counts["tests"] == 0:
        emit(f"**No tests discovered for {label}** (files: {', '.join(files) or 'none'})\n")
        return 1
    return 1 if counts["failed"] or counts["errors"] else 0


def jacoco(label, path):
    lines = [0, 0]
    branches = [0, 0]
    with open(path, newline="") as fh:
        for row in csv.DictReader(fh):
            lines[0] += int(row["LINE_COVERED"])
            lines[1] += int(row["LINE_COVERED"]) + int(row["LINE_MISSED"])
            branches[0] += int(row["BRANCH_COVERED"])
            branches[1] += int(row["BRANCH_COVERED"]) + int(row["BRANCH_MISSED"])
    emit(f"#### {label}\n\n| lines | branches |\n|---|---|\n| {pct(*lines)} | {pct(*branches)} |\n")
    return 0


def c8(label, path):
    total = json.load(open(path))["total"]
    emit(f"#### {label}\n\n| statements | lines | branches |\n|---|---|---|\n"
         f"| {pct(total['statements']['covered'], total['statements']['total'])} "
         f"| {pct(total['lines']['covered'], total['lines']['total'])} "
         f"| {pct(total['branches']['covered'], total['branches']['total'])} |\n")
    return 0


if __name__ == "__main__":
    kind, label, *rest = sys.argv[1:]
    exclude = None
    if "--exclude" in rest:
        i = rest.index("--exclude")
        exclude = rest[i + 1]
        rest = rest[:i] + rest[i + 2:]
    sys.exit({"junit": lambda: junit(label, rest, exclude),
              "jacoco": lambda: jacoco(label, rest[0]),
              "c8": lambda: c8(label, rest[0])}[kind]())
