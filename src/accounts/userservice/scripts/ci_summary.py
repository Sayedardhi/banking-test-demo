#!/usr/bin/env python3
"""Summarize userservice JUnit + Cobertura reports as a Markdown table.

usage: ci_summary.py LAYER=DIR [LAYER=DIR ...] [--coverage-only NAME=DIR]
Exits 1 if any test layer is missing reports or discovered zero tests.
"""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def junit_counts(path):
    """(total, passed, failed, skipped) from a JUnit XML file."""
    root = ET.parse(path).getroot()
    suites = [root] if root.tag == 'testsuite' else root.findall('testsuite')
    total = sum(int(s.get('tests', 0)) for s in suites)
    failed = sum(int(s.get('failures', 0)) + int(s.get('errors', 0)) for s in suites)
    skipped = sum(int(s.get('skipped', 0)) for s in suites)
    return total, total - failed - skipped, failed, skipped


def cobertura(path):
    """Line and branch (covered, valid) pairs from a Cobertura XML file."""
    root = ET.parse(path).getroot()
    return ((int(root.get('lines-covered')), int(root.get('lines-valid'))),
            (int(root.get('branches-covered')), int(root.get('branches-valid'))))


def ratio(pair):
    """'covered/total (pct%)'."""
    covered, total = pair
    return '{}/{} ({:.1f}%)'.format(covered, total, 100.0 * covered / total) if total else 'n/a'


def main(argv):  # pylint: disable=too-many-locals
    """Print the summary and return the exit code."""
    status = 0
    lines = ['### userservice test results', '',
             '| Layer | Tests | Passed | Failed | Skipped | Line coverage | Branch coverage |',
             '| --- | ---: | ---: | ---: | ---: | --- | --- |']
    coverage_only = False
    for arg in argv:
        if arg == '--coverage-only':
            coverage_only = True
            continue
        name, folder = arg.split('=', 1)
        folder = Path(folder)
        cov = folder / 'coverage.xml'
        line, branch = cobertura(cov) if cov.exists() else ((0, 0), (0, 0))
        cov_cells = ('{} | {}'.format(ratio(line), ratio(branch)) if cov.exists()
                     else 'missing | missing')
        if coverage_only:
            lines.append('| {} (service, all layers) | | | | | {} |'.format(name, cov_cells))
            coverage_only = False
            continue
        junit = folder / 'junit.xml'
        if not junit.exists():
            lines.append('| {} | **no report** | | | | {} |'.format(name, cov_cells))
            status = 1
            continue
        total, passed, failed, skipped = junit_counts(junit)
        if total == 0:
            status = 1
        if failed:
            status = 1
        lines.append('| {} | {} | {} | {} | {} | {} |'.format(
            name, total if total else '**0 (no tests discovered)**', passed, failed, skipped,
            cov_cells))
    lines += ['', 'Coverage scope: src/accounts/userservice production modules '
              '(`__init__.py`, `db.py`, `userservice.py`); tests and tooling excluded. '
              'Covered/total counts; not averaged with other services.']
    print('\n'.join(lines))
    return status


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
