#!/usr/bin/env python3
"""Package already-produced CI reports into a tools/test-observability dashboard run (no test execution).

Usage: dashboard_run.py SERVICE:LAYER:EXIT_CODE:DIR [...]   (EXIT_CODE may be 'none' when the job did not run)
Reads junit/TEST-*.xml or junit.xml, plus jacoco.xml if present; prints the new run id.
"""
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[4] / 'tools' / 'test-observability'))
import dashboard  # noqa: E402


def main(specs):
    run = dashboard.new_run()
    run['note'] = 'Assembled from GitHub Actions artifacts; suites were not re-executed. ' + run['note']
    for spec in specs:
        service, layer, code, folder = spec.split(':', 3)
        source = Path(folder)
        row = next(s for s in run['services'] if s['id'] == service)
        target = dashboard.DATA / 'runs' / run['id'] / service / layer
        target.mkdir(parents=True, exist_ok=True)
        if source.is_dir():
            for item in source.rglob('*'):
                if item.is_file():
                    dest = target / item.relative_to(source)
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(item, dest)
        junit = sorted(target.glob('junit/TEST-*.xml')) or sorted(target.glob('junit.xml'))
        result = {'command': ['github-actions', layer], 'exitCode': None if code == 'none' else int(code),
                  'tests': dashboard.junit(junit) if junit else None, 'coverage': None}
        if (target / 'jacoco.xml').is_file():
            result['coverage'] = dashboard.coverage(target / 'jacoco.xml')
        tests = result['tests']
        if tests and tests['failed']:
            result['status'] = 'failed'
        elif result['exitCode'] == 0 and tests and tests['total'] > tests['skipped']:
            result['status'] = 'passed'
        else:
            result['status'] = 'blocked'
            result['error'] = 'Job did not run or produced no test results.'
        result['finishedAt'] = dashboard.now()
        result['artifacts'] = dashboard.artifacts(run, target)
        row['layers'][layer] = result
    run['finishedAt'] = dashboard.now()
    dashboard.persist(run)
    print(run['id'])


if __name__ == '__main__':
    main(sys.argv[1:])
