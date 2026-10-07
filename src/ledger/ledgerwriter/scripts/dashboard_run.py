#!/usr/bin/env python3
"""Package already-produced CI reports into a dashboard-compatible run (no test re-execution).

  dashboard_run.py SERVICE:LAYER:EXIT_CODE:DIR [...]

EXIT_CODE is the layer's real exit code, or 'none' when the job did not run. Prints the run id; the run
is written to .local/test-observability/runs/<id>/ exactly as `dashboard.py run` would.
"""
import shutil, sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[4] / 'tools' / 'test-observability'))
import dashboard  # noqa: E402

JUNIT = ('TEST-*.xml', 'junit.xml')
COVERAGE = ('jacoco.xml', 'coverage.xml')


def main(specs):
    run = dashboard.new_run()
    for spec in specs:
        service, layer, code, source = spec.split(':', 3)
        source = Path(source)
        folder = dashboard.DATA / 'runs' / run['id'] / service / layer
        folder.mkdir(parents=True, exist_ok=True)
        if source.is_dir():
            for item in source.iterdir():
                (shutil.copytree if item.is_dir() else shutil.copy2)(item, folder / item.name)
        row = next(s for s in run['services'] if s['id'] == service)
        exit_code = None if code in ('', 'none') else int(code)
        result = {'status': 'not_run', 'command': ['CI', 'test-ledgerwriter.yaml', layer], 'exitCode': exit_code,
                  'tests': None, 'coverage': None, 'startedAt': run['startedAt'], 'finishedAt': dashboard.now()}
        reports = sorted({p for pattern in JUNIT for p in folder.glob(pattern)})
        covs = [folder / c for c in COVERAGE if (folder / c).is_file()]
        if reports:
            result['tests'] = dashboard.junit(reports)
        if covs:
            result['coverage'] = dashboard.coverage(covs[0])
        tests = result['tests']
        if exit_code is not None or tests:
            result['status'] = ('failed' if tests and tests['failed'] else
                                'passed' if exit_code == 0 and tests and tests['total'] > tests['skipped'] else
                                'blocked')
        if result['status'] == 'blocked':
            result['error'] = 'Job did not produce test results or exited abnormally; see the CI job log.'
        result['artifacts'] = dashboard.artifacts(run, folder)
        row['layers'][layer] = result
    run['finishedAt'] = dashboard.now()
    run['note'] = run['note'] + ' Packaged from GitHub Actions artifacts by ledgerwriter/scripts/dashboard_run.py.'
    dashboard.persist(run)
    print(run['id'])


if __name__ == '__main__':
    main(sys.argv[1:])
