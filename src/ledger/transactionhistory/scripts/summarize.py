#!/usr/bin/env python3
"""Summarize transactionhistory JUnit + JaCoCo reports per layer (no tests are executed).

Usage: summarize.py [--check | --markdown | --harness] LAYER=DIR [LAYER=DIR ...]
  (default)  one plain-text line per layer
  --check    exit 1 if a layer ran no tests, has failures/errors, or lacks jacoco.xml
  --markdown GitHub step-summary table
  --harness  print harness=ok when every layer ran tests and produced coverage (failures allowed)
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def tests(folder):
    total = failed = skipped = 0
    for path in sorted(Path(folder).glob('junit/TEST-*.xml')):
        suite = ET.parse(path).getroot()
        total += int(suite.get('tests', 0))
        failed += int(suite.get('failures', 0)) + int(suite.get('errors', 0))
        skipped += int(suite.get('skipped', 0))
    return {'total': total, 'passed': total - failed - skipped, 'failed': failed, 'skipped': skipped}


def coverage(folder):
    path = Path(folder) / 'jacoco.xml'
    if not path.is_file():
        return {}
    counters = {c.get('type'): c for c in ET.parse(path).getroot().findall('counter')}
    out = {}
    for kind in ('LINE', 'BRANCH'):
        c = counters.get(kind)
        if c is not None:
            covered = int(c.get('covered'))
            out[kind.lower()] = (covered, covered + int(c.get('missed')))
    return out


def fmt(pair):
    if not pair:
        return 'unmeasured'
    covered, total = pair
    return f'{covered}/{total} ({100 * covered / total:.1f}%)' if total else '0/0'


def problems(t, cov):
    found = []
    if t['total'] == 0:
        found.append('no tests discovered')
    if t['failed']:
        found.append(f"{t['failed']} failed")
    if not cov:
        found.append('coverage report missing')
    return found


def main(argv):
    mode = next((a for a in argv if a.startswith('--')), '')
    rows = []
    for arg in (a for a in argv if '=' in a):
        layer, folder = arg.split('=', 1)
        t, cov = tests(folder), coverage(folder)
        rows.append((layer, t, cov, problems(t, cov)))
    if mode == '--harness':
        ok = rows and all(t['total'] > 0 and cov for _, t, cov, _ in rows)
        print('harness=' + ('ok' if ok else 'broken'))
        return 0
    if mode == '--markdown':
        print('### transactionhistory test results\n')
        print('| Layer | Tests | Passed | Failed | Skipped | Line covered/total | Branch covered/total | Status |')
        print('|---|---|---|---|---|---|---|---|')
        for layer, t, cov, p in rows:
            print(f"| {layer} | {t['total']} | {t['passed']} | {t['failed']} | {t['skipped']} | "
                  f"{fmt(cov.get('line'))} | {fmt(cov.get('branch'))} | {'; '.join(p) or 'OK'} |")
        print('\nCoverage is per layer over every class in src/ledger/transactionhistory/src/main (JaCoCo); layers are not averaged.')
        return 0
    for layer, t, cov, p in rows:
        print(f"[transactionhistory {layer}] tests={t['total']} passed={t['passed']} failed={t['failed']} "
              f"skipped={t['skipped']} line={fmt(cov.get('line'))} branch={fmt(cov.get('branch'))}"
              + (f" PROBLEM: {'; '.join(p)}" if p else ''))
    return 1 if mode == '--check' and any(p for *_, p in rows) else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
