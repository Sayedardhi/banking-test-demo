#!/usr/bin/env python3
"""Summarise ledgerwriter layer reports written by report-tests.sh, without re-running anything.

  summarize.py --markdown unit=DIR [integration=DIR]   -> Markdown table for $GITHUB_STEP_SUMMARY
  summarize.py --harness  unit=DIR                     -> harness=ok|broken for $GITHUB_OUTPUT

harness=ok means tests executed and a JaCoCo report exists; failing tests (known findings) are still ok.
"""
import argparse, sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[4] / 'tools' / 'test-observability'))
from dashboard import coverage, junit  # noqa: E402


def layer(folder):
    folder = Path(folder)
    reports = sorted(folder.glob('TEST-*.xml'))
    tests = junit(reports) if reports else None
    cov = coverage(folder / 'jacoco.xml') if (folder / 'jacoco.xml').is_file() else None
    return tests, cov


def ratio(cov, kind):
    if not cov or kind not in cov:
        return 'no report'
    r = cov[kind]
    return f"{r['covered']}/{r['total']} ({r['percent']}%)"


def main():
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--markdown', action='store_true')
    mode.add_argument('--harness', action='store_true')
    parser.add_argument('layers', nargs='+', metavar='LAYER=DIR')
    args = parser.parse_args()
    rows = []
    for spec in args.layers:
        name, _, folder = spec.partition('=')
        tests, cov = layer(folder)
        rows.append((name, tests, cov))
    if args.harness:
        ok = all(t and t['total'] > t['skipped'] and c for _, t, c in rows)
        print('harness=' + ('ok' if ok else 'broken'))
        return
    print('| Layer | Tests | Passed | Failed | Skipped | Line covered/total | Branch covered/total |')
    print('|---|---|---|---|---|---|---|')
    for name, t, c in rows:
        if not t:
            print(f'| {name} | no JUnit report | | | | {ratio(c, "line")} | {ratio(c, "branch")} |')
            continue
        print(f"| {name} | {t['total']} | {t['passed']} | {t['failed']} | {t['skipped']} "
              f"| {ratio(c, 'line')} | {ratio(c, 'branch')} |")
    for name, t, _ in rows:
        for case in (t or {}).get('cases', []):
            if case['status'] == 'failed':
                print(f"- {name} failed: `{case['class'].rsplit('.', 1)[-1]}.{case['name']}`")


if __name__ == '__main__':
    main()
