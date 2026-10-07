#!/usr/bin/env python3
"""Summarize userservice JUnit + Cobertura reports per test layer.

Usage: summarize.py [--markdown | --harness] layer=dir [layer=dir ...]
--markdown prints a GitHub step-summary table.
--harness prints harness=ok when every layer executed tests and produced coverage XML
(test failures allowed), else harness=broken; used to gate the E2E job.
"""
import os
import sys
import xml.etree.ElementTree as ET


def junit_counts(folder):
    totals = {'tests': 0, 'failed': 0, 'skipped': 0}
    path = os.path.join(folder, 'junit.xml')
    if os.path.exists(path):
        for case in ET.parse(path).getroot().iter('testcase'):
            totals['tests'] += 1
            if case.find('failure') is not None or case.find('error') is not None:
                totals['failed'] += 1
            elif case.find('skipped') is not None:
                totals['skipped'] += 1
    totals['passed'] = totals['tests'] - totals['failed'] - totals['skipped']
    return totals


def coverage_counts(folder):
    path = os.path.join(folder, 'coverage.xml')
    if not os.path.exists(path):
        return {}
    root = ET.parse(path).getroot()
    return {kind: (int(root.get(f'{attr}-covered')), int(root.get(f'{attr}-valid')))
            for kind, attr in (('line', 'lines'), ('branch', 'branches'))}


def fmt(pair):
    if not pair:
        return 'unmeasured'
    covered, total = pair
    return f'{covered}/{total} ({100 * covered / total:.1f}%)' if total else f'{covered}/{total}'


def main(argv):
    rows = []
    for layer, folder in (a.split('=', 1) for a in argv if '=' in a):
        tests, cov = junit_counts(folder), coverage_counts(folder)
        problem = 'no tests discovered' if not tests['tests'] else f"{tests['failed']} failed" if tests['failed'] else ''
        if not cov:
            problem = (problem + '; ' if problem else '') + 'coverage report missing'
        rows.append((layer, tests, cov, problem))
    if '--harness' in argv:
        ok = rows and all(t['tests'] > 0 and cov for _, t, cov, _ in rows)
        print('harness=' + ('ok' if ok else 'broken'))
        return 0
    if '--markdown' in argv:
        print('### userservice test results\n')
        print('| Layer | Tests | Passed | Failed | Skipped | Line coverage (covered/total) '
              '| Branch coverage (covered/total) | Status |')
        print('|---|---|---|---|---|---|---|---|')
    for layer, t, cov, problem in rows:
        if '--markdown' in argv:
            print(f"| {layer} | {t['tests']} | {t['passed']} | {t['failed']} | {t['skipped']} | "
                  f"{fmt(cov.get('line'))} | {fmt(cov.get('branch'))} | {problem or 'OK'} |")
        else:
            print(f"{layer}: tests={t['tests']} failed={t['failed']} line={fmt(cov.get('line'))} "
                  f"branch={fmt(cov.get('branch'))} {problem}")
    if '--markdown' in argv:
        print('\nCoverage is per layer over the whole userservice package (userservice.py, db.py, '
              '__init__.py; tests excluded), not averaged across services.')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
