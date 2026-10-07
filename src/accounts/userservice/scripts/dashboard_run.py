#!/usr/bin/env python3
"""Package CI reports as a tools/test-observability run (no tests are re-executed).

Usage: dashboard_run.py SERVICE:LAYER:EXIT_CODE:REPORT_DIR [...]
Creates .local/test-observability/runs/<RUN_ID>/ with run.json and per-layer reports using the
dashboard's own run schema and parsers, then prints the run id.
"""
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / 'tools/test-observability'))
import dashboard as d  # noqa: E402  pylint: disable=wrong-import-position,import-error

JUNIT = ['junit.xml']
COVERAGE = ['coverage.xml']


def layer_result(run, suites, spec):
    service, layer, code, src = spec.split(':', 3)
    folder = d.DATA / 'runs' / run['id'] / service / layer
    if Path(src).is_dir():
        shutil.copytree(src, folder, dirs_exist_ok=True)
    folder.mkdir(parents=True, exist_ok=True)
    result = {'status': 'blocked', 'command': suites.get((service, layer), {}).get('command', ['ci']),
              'coverage': None, 'tests': None, 'exitCode': int(code) if code.lstrip('-').isdigit() else None,
              'startedAt': run['startedAt'], 'finishedAt': d.now(), 'source': 'github-actions'}
    junit = sorted({p for pattern in JUNIT for p in folder.glob(pattern)})
    coverage = next((p for pattern in COVERAGE for p in folder.glob(pattern)), None)
    try:
        result['tests'] = d.junit(junit) if junit else None
        if coverage:
            result['coverage'] = d.coverage(coverage)
        tests = result['tests']
        result['status'] = ('failed' if tests and tests['failed'] else
                            'passed' if result['exitCode'] == 0 and tests and tests['total'] > tests['skipped']
                            else 'blocked')
        if result['status'] == 'blocked':
            result['error'] = 'No test results were produced or the job did not complete. Inspect the CI log.'
    except Exception as exc:  # pylint: disable=broad-except
        result['error'] = 'Report parsing failed: ' + str(exc)
    result['artifacts'] = d.artifacts(run, folder)
    next(x for x in run['services'] if x['id'] == service)['layers'][layer] = result


def main(specs):
    suites = {(s['service'], s['layer']): s for s in d.config().get('suites', [])}
    run = d.new_run()
    for spec in specs:
        layer_result(run, suites, spec)
    run['finishedAt'] = d.now()
    run['note'] = 'Imported from GitHub Actions reports (suites executed once in CI). ' + run['note']
    d.persist(run)
    print(run['id'])


if __name__ == '__main__':
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1:])
