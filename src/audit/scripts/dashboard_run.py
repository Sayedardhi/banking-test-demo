#!/usr/bin/env python3
"""Turn already-produced CI reports into a tools/test-observability run (never re-runs tests).

Usage: dashboard_run.py SERVICE:LAYER:EXIT_CODE:REPORT_DIR [...]
  EXIT_CODE is the layer's captured exit code, or 'none' when the job did not run.
Writes .local/test-observability/runs/<RUN_ID>/run.json plus the copied reports and prints
RUN_ID. Uses the dashboard's own new_run(), junit() and coverage() so the schema matches.
"""
import shutil
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'tools' / 'test-observability'))
import dashboard  # noqa: E402

JUNIT_NAMES = ('junit.xml',)
COVERAGE_NAMES = ('coverage.xml',)


def layer_result(run, service, layer, code, reports, command):
    folder = dashboard.DATA / 'runs' / run['id'] / service / layer
    if reports.is_dir():
        shutil.copytree(reports, folder, dirs_exist_ok=True)
    folder.mkdir(parents=True, exist_ok=True)
    exit_code = int(code) if code.lstrip('-').isdigit() else None
    result = {'command': command, 'exitCode': exit_code, 'tests': None, 'coverage': None,
              'startedAt': run['startedAt'], 'finishedAt': dashboard.now(), 'source': 'github-actions'}
    try:
        junit = [folder / n for n in JUNIT_NAMES if (folder / n).is_file()]
        coverage = next((folder / n for n in COVERAGE_NAMES if (folder / n).is_file()), None)
        result['tests'] = dashboard.junit(junit) if junit else None
        result['coverage'] = dashboard.coverage(coverage) if coverage else None
        tests = result['tests']
        if tests and tests['failed']:
            result['status'] = 'failed'
        elif exit_code == 0 and tests and tests['total'] > tests['skipped']:
            result['status'] = 'passed'
        else:
            result['status'] = 'blocked'
            result['error'] = 'No test results, all tests skipped, or the job did not finish. See the CI log.'
    except Exception as exc:  # malformed XML is a harness problem, not a pass
        result['status'] = 'blocked'
        result['error'] = f'Report parsing failed: {exc}'
    result['artifacts'] = dashboard.artifacts(run, folder)
    return result


def main(specs):
    suites = {(s['service'], s['layer']): s['command'] for s in dashboard.config().get('suites', [])}
    run = dashboard.new_run()
    for spec in specs:
        service, layer, code, reports = spec.split(':', 3)
        command = suites.get((service, layer), ['github-actions'])
        row = next(s for s in run['services'] if s['id'] == service)
        row['layers'][layer] = layer_result(run, service, layer, code, Path(reports), command)
    run['finishedAt'] = dashboard.now()
    run['note'] = 'Packaged from GitHub Actions reports; each suite ran once in CI. ' + run['note']
    dashboard.persist(run)
    print(run['id'])


if __name__ == '__main__':
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1:])
