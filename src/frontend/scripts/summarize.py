#!/usr/bin/env python3
"""Summarize frontend JUnit + Cobertura reports per test layer.

Usage: summarize.py [--check] [--markdown] [--harness] layer=dir [layer=dir ...]
--check     exit 1 if any layer has failures/errors, no tests, or (for unit/integration) no coverage.
--markdown  print a GitHub step-summary table.
--harness   print harness=ok when every layer executed tests and produced its reports (test
            failures allowed), else harness=broken; used to gate the E2E job.
E2E is a browser black-box layer: no frontend coverage is collected for it.
"""
import os
import sys
import xml.etree.ElementTree as ET


def junit_counts(folder):
    totals = {'tests': 0, 'failed': 0, 'skipped': 0, 'failures': []}
    path = os.path.join(folder, 'junit.xml')
    if os.path.exists(path):
        for case in ET.parse(path).getroot().iter('testcase'):
            totals['tests'] += 1
            if case.find('failure') is not None or case.find('error') is not None:
                totals['failed'] += 1
                totals['failures'].append(f"{case.get('classname', '')}::{case.get('name')}")
            elif case.find('skipped') is not None:
                totals['skipped'] += 1
    totals['passed'] = totals['tests'] - totals['failed'] - totals['skipped']
    return totals


def coverage_counts(folder):
    path = os.path.join(folder, 'coverage.xml')
    if not os.path.exists(path):
        return None
    root = ET.parse(path).getroot()
    return {'line': (int(root.get('lines-covered')), int(root.get('lines-valid'))),
            'branch': (int(root.get('branches-covered')), int(root.get('branches-valid')))}


def fmt(pair):
    if not pair:
        return 'not measured'
    covered, total = pair
    return f'{covered}/{total} ({100 * covered / total:.1f}%)' if total else f'{covered}/{total}'


def main(argv):
    rows = []
    for layer, folder in (a.split('=', 1) for a in argv if '=' in a):
        tests, cov = junit_counts(folder), coverage_counts(folder)
        needs_cov = layer != 'e2e'
        problems = []
        if tests['tests'] == 0:
            problems.append('no tests discovered')
        elif tests['failed']:
            problems.append(f"{tests['failed']} failed")
        if needs_cov and not cov:
            problems.append('coverage report missing')
        rows.append((layer, tests, cov, needs_cov, problems))
    if '--harness' in argv:
        ok = rows and all(t['tests'] > 0 and (cov or not need) for _, t, cov, need, _ in rows)
        print('harness=' + ('ok' if ok else 'broken'))
        return 0
    if '--markdown' in argv:
        print('### frontend test results\n')
        print('| Layer | Tests | Passed | Failed | Skipped | Line coverage (covered/total) '
              '| Branch coverage (covered/total) | Status |')
        print('|---|---|---|---|---|---|---|---|')
        for layer, t, cov, need, problems in rows:
            cov = cov or {}
            line = fmt(cov.get('line')) if need else 'n/a (browser black-box)'
            branch = fmt(cov.get('branch')) if need else 'n/a (browser black-box)'
            print(f"| {layer} | {t['tests']} | {t['passed']} | {t['failed']} | {t['skipped']} | "
                  f"{line} | {branch} | {'; '.join(problems) or 'OK'} |")
        print('\nCoverage is per layer for the whole frontend package (all .py files, tests '
              'omitted), branch coverage enabled; layers are not merged or averaged.')
        for layer, t, *_ in rows:
            for name in t['failures']:
                print(f'- {layer} failure: `{name}`')
    else:
        for layer, t, cov, need, problems in rows:
            cov = cov or {}
            print(f"{layer}: {t['tests']} tests, {t['passed']} passed, {t['failed']} failed, "
                  f"{t['skipped']} skipped; line {fmt(cov.get('line'))}; "
                  f"branch {fmt(cov.get('branch'))}; {'; '.join(problems) or 'OK'}")
    if '--check' in argv:
        return 1 if any(problems for *_, problems in rows) else 0
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
