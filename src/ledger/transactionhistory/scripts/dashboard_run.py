#!/usr/bin/env python3
"""Build a tools/test-observability run from reports CI already produced (suites are not re-run).

Usage: dashboard_run.py SERVICE:LAYER:EXIT_CODE:REPORT_DIR [...]
EXIT_CODE may be 'none' when the job did not run. Prints the new run id; the run is written to
.local/test-observability/runs/<id>/ and can be copied into any checkout to view with dashboard.py serve.
"""
import shutil
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(REPO / 'tools' / 'test-observability'))
import dashboard  # noqa: E402

JUNIT = ('junit/TEST-*.xml', 'junit.xml')
COVERAGE = ('jacoco.xml', 'coverage.xml')


def layer_result(run, service, layer, code, source, suite):
    folder = dashboard.DATA / 'runs' / run['id'] / service / layer
    folder.mkdir(parents=True, exist_ok=True)
    if Path(source).is_dir():
        shutil.copytree(source, folder, dirs_exist_ok=True)
    exit_code = int(code) if code.lstrip('-').isdigit() else None
    result = {'command': suite.get('command', ['github-actions']), 'exitCode': exit_code, 'tests': None,
              'coverage': None, 'startedAt': run['startedAt'], 'finishedAt': dashboard.now(),
              'source': 'github-actions'}
    try:
        reports = sorted({p for pattern in JUNIT for p in folder.glob(pattern)})
        result['tests'] = dashboard.junit(reports) if reports else None
        cov = next((p for pattern in COVERAGE for p in sorted(folder.glob(pattern))), None)
        result['coverage'] = dashboard.coverage(cov) if cov else None
        t = result['tests']
        result['status'] = ('failed' if t and t['failed'] else
                            'passed' if exit_code == 0 and t and t['total'] > t['skipped'] else 'blocked')
        if result['status'] == 'blocked':
            result['error'] = 'The CI job did not run or produced no test results. See the workflow log.'
    except Exception as exc:
        result['status'] = 'blocked'
        result['error'] = f'Report parsing failed: {exc}'
    result['artifacts'] = dashboard.artifacts(run, folder)
    return result


def main(specs):
    suites = {(s['service'], s['layer']): s for s in dashboard.config().get('suites', [])}
    run = dashboard.new_run()
    for spec in specs:
        service, layer, code, source = spec.split(':', 3)
        row = next(r for r in run['services'] if r['id'] == service)
        row['layers'][layer] = layer_result(run, service, layer, code, source, suites.get((service, layer), {}))
    run['finishedAt'] = dashboard.now()
    run['note'] = 'Assembled from GitHub Actions reports; each suite ran once in CI. ' + run.get('note', '')
    dashboard.persist(run)
    print(run['id'])


if __name__ == '__main__':
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1:])
