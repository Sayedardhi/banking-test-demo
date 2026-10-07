#!/usr/bin/env python3
"""Summarize balancereader layer reports written by report-tests.sh.

  summarize.py --markdown LAYER=DIR [...]   per-layer table: tests, pass/fail/skip, line + branch covered/total
  summarize.py --harness LAYER=DIR [...]    prints harness=ok|broken (ok = tests ran and coverage XML exists;
                                            failing tests still count as ok)
  summarize.py --exit-code [--maven-code N] LAYER=DIR   exit status for the layer
"""
import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def layer_result(folder):
    folder = Path(folder)
    cases = []
    for path in sorted(folder.glob('junit/TEST-*.xml')):
        cases += ET.parse(path).getroot().iter('testcase')
    failed = sum(c.find('failure') is not None or c.find('error') is not None for c in cases)
    skipped = sum(c.find('skipped') is not None for c in cases)
    result = {'tests': len(cases), 'failed': failed, 'skipped': skipped,
              'passed': len(cases) - failed - skipped, 'line': None, 'branch': None,
              'failures': [f"{c.get('classname', '').rsplit('.', 1)[-1]}.{c.get('name')}" for c in cases
                           if c.find('failure') is not None or c.find('error') is not None]}
    jacoco = folder / 'jacoco.xml'
    if jacoco.is_file():
        root = ET.parse(jacoco).getroot()
        for counter in root.findall('counter'):
            kind = counter.get('type', '').lower()
            if kind in ('line', 'branch'):
                covered = int(counter.get('covered', 0))
                result[kind] = (covered, covered + int(counter.get('missed', 0)))
    return result


def ratio(value):
    if value is None:
        return 'not measured'
    covered, total = value
    return f'{covered}/{total} ({100 * covered / total:.1f}%)' if total else '0/0'


def main():
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--markdown', action='store_true')
    mode.add_argument('--harness', action='store_true')
    mode.add_argument('--exit-code', action='store_true')
    parser.add_argument('--maven-code', type=int, default=0)
    parser.add_argument('layers', nargs='+', metavar='LAYER=DIR')
    args = parser.parse_args()
    layers = [(name, layer_result(path)) for name, path in (item.split('=', 1) for item in args.layers)]

    if args.harness:
        ok = all(r['tests'] > 0 and r['line'] is not None for _, r in layers)
        print('harness=' + ('ok' if ok else 'broken'))
        return 0
    if args.exit_code:
        name, r = layers[0]
        if r['tests'] == 0:
            print(f'::error::balancereader {name}: zero tests discovered', file=sys.stderr)
            return 3
        if r['failed']:
            return 1
        if r['line'] is None:
            print(f'::error::balancereader {name}: no JaCoCo report', file=sys.stderr)
            return 4
        return args.maven_code
    print('| Layer | Tests | Passed | Failed | Skipped | Line covered/total | Branch covered/total |')
    print('|---|---|---|---|---|---|---|')
    for name, r in layers:
        print(f"| {name} | {r['tests']} | {r['passed']} | {r['failed']} | {r['skipped']} | "
              f"{ratio(r['line'])} | {ratio(r['branch'])} |")
    for name, r in layers:
        for failure in r['failures']:
            print(f'- {name} failed: `{failure}`')
    return 0


if __name__ == '__main__':
    sys.exit(main())
