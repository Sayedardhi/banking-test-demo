# Demo test evidence dashboard

A local dashboard for showing measured **before and after** results. No sample coverage numbers, simulated progress, or injected tests. The dashboard is independent of the banking application and does not add missing application harnesses on Devin's behalf.

## Demo workflow

From the repository root, with Docker Desktop and the demo Compose services running:

```sh
python3 tools/test-observability/dashboard.py serve
# In a second terminal:
python3 tools/test-observability/dashboard.py run
python3 tools/test-observability/dashboard.py baseline RUN_ID
```

Open http://localhost:8081. Replace RUN_ID with the ID printed by the collector. Freeze the baseline **once**, before Devin starts. After pulling/applying Devin's changes, run the same collector again. The dashboard refreshes every five seconds and compares the latest run against the saved baseline. Use the selectors to present historical runs. Run with `--service userservice` for a focused check; other services in that run remain unmeasured, not inherited from an older run. Use a full run for your presentation.

Each badge opens evidence: executed command, exit code, test names and assertions, JUnit XML, coverage XML, and execution log. Line and branch coverage use covered/total counts. We do not average unrelated percentages into an invented repo-wide number. New coverage is labeled “New measurement”; absent measurements never become 0%. File-scope or format changes suppress percentage-point comparisons. Even with matching filenames, review changed denominators and source changes when interpreting deltas.

## What runs today

- Java: the retained JUnit/Mockito tests in ledgerwriter, balancereader, and transactionhistory, with existing Maven/JaCoCo configuration. Runs copied current source in Maven 3.9 / JDK 17 containers and caches Maven downloads.
- Python userservice unit: existing `test_userservice.py`, with mocked API dependencies.
- Python userservice integration: existing `test_db.py`, using **in-memory SQLite**, separately reported. This does not demonstrate PostgreSQL or cross-service behavior.
- contacts, audit and frontend unit harness gaps remain visible. Existing Cypress journeys are marked not run; the collector does not silently replace them or claim Playwright exists.
- Python uses the existing Compose image's runtime dependencies, adds pinned pytest/pytest-cov, and executes copied current source. Runtime dependencies therefore come from that image rather than being freshly resolved from the source lockfile. Rebuild/update the runner when dependency requirements change.

First execution downloads Maven/test dependencies. Allow several minutes and rehearse with a warm cache. Default per-suite timeout is 600 seconds; customize with `--timeout`. A failed/blocked suite produces a nonzero collector exit. Missing/unconfigured suites remain explicit but do not fail the collector. Passing coverage is not a regulatory certification or proof of all business requirements.

## Contract for Devin's new suites

Reuse existing frameworks. When bootstrapping audit/contacts or adding integration/Playwright tests, add entries to `suites` in `config.json`:

```json
{
  "service": "audit",
  "layer": "unit",
  "cwd": "src/audit",
  "command": ["bash", "scripts/report-tests.sh", "{output}"],
  "junit": ["junit.xml"],
  "coverage": "coverage.xml"
}
```

This is an example contract; the script must be implemented by Devin alongside its harness. Allowed layers are `unit`, `integration`, `e2e`; service IDs are listed in the config, plus `journeys` for cross-service browser tests. Each command must execute the tests, return their actual exit code, and write fresh reports to the absolute `{output}` folder. `{repo}` expands to the repository root. Commands are argument arrays, not interpolated shell strings. Review these executable commands as normal repository code.

Use JUnit XML for pytest/JUnit/Vitest/Playwright results and JaCoCo or Cobertura XML for coverage. Configure coverage to include the full intended production source, including unimported files; exclude tests/generated files. Do not narrow coverage scope to inflate results. Keep unit and integration reports separate. Playwright should emit JUnit plus a zipped HTML report/trace artifacts into `{output}`. E2E pass counts do not imply backend code coverage.

A configured unit runner replaces the built-in runner for that service. A configured userservice integration runner replaces the SQLite runner. One entry per service/layer. When adding a harness, update the service `tests` glob if needed. `runner` can remain null when using a custom suite.

The dashboard discovers only configured, executed reports. It does not connect to Devin automatically or observe its private cloud session. For the simplest demo, pull Devin's changes locally and rerun. Alternatively run the collector in Devin/CI, archive `.local/test-observability/runs/<RUN_ID>/`, and copy that complete unique directory into the local `runs/` directory. Preserve your local `baseline.json`. The page discovers imported runs automatically, retaining their original commit and timestamp. Source differences are flagged, not hidden.

## Evidence storage and maintenance

All snapshots live under ignored `.local/test-observability/`; nothing is published. Each run records commit, branch, working-tree state, source fingerprint, timestamps, commands, and reports. Changes during a run block baseline freezing; a later changed checkout is labeled historical evidence. Baseline selection is manual; new runs never overwrite it. Results are local records, not signed/tamper-proof attestations.

The web server binds to 127.0.0.1 and is read-only. It cannot start tests or modify your repo from the browser. XML/log artifacts open as text; downloadable report archives should be opened locally. If the process is interrupted, check for remaining `test-<run>-<service>` Docker containers before removing a stale collector.lock. Stop the server with Ctrl-C.

Collector regression checks (separate from application test evidence):

```sh
python3 -m unittest discover -s tools/test-observability -p 'test_*.py'
```
