# Testing the audit service

Runner: Node 24 built-in `node:test` (no extra test framework) + `c8` for V8 coverage, remapped to the TypeScript sources.
Node 24 is required because the service uses `node:sqlite` (`source ~/.nvm/nvm.sh && nvm use 24` on the Devin VM).

| Layer | Location | What it exercises | Boundaries |
|---|---|---|---|
| Unit | `tests/unit/` | `normalizeEvent` validation, allowlisting and masking; `AuditStore` dedup/conflict/timestamp/listing decisions | SQLite `DatabaseSync.prepare` replaced by a scripted fake; `Date` controlled with `mock.timers` |
| Integration | `tests/integration/` | `AuditStore` on a real SQLite file; the compiled `src/server.ts` spawned as a child process and called over HTTP | Real SQLite in a fresh `mkdtemp` directory per test/suite (never the Compose `audit-data` volume) |

## Commands

```sh
cd src/audit
npm ci
npm run typecheck                 # production + test sources
npm run test:unit                 # -> test-results/unit/{junit.xml,coverage.xml,lcov.info,coverage-html.zip}
npm run test:integration          # -> test-results/integration/...
npm test                          # both layers in one run -> test-results/all/...
bash scripts/report-tests.sh <unit|integration|all> <output-dir>   # what CI and the dashboard call
```

`scripts/report-tests.sh` compiles to `build/`, runs the layer under `c8 --all` (every file in `src/` is in the denominator, including files the layer never loads), writes JUnit XML and Cobertura XML, returns the test runner's exit code, and exits 3 if no tests were discovered.
`scripts/merge-coverage.sh` merges raw V8 data from both layers into one service-level report (CI sets `C8_RAW_DIR`).
`scripts/ci-summary.mjs` renders the GitHub job summary.

CI: `.github/workflows/test-audit.yaml` (unit, then integration, then merged service coverage; artifacts `audit-unit-reports`, `audit-integration-reports`, `audit-service-coverage` are uploaded even on failure).
Dashboard: `python3 tools/test-observability/dashboard.py run --service audit` from the repo root.

## Conventions

- Synthetic data only (`tests/helpers.ts`). Sensitive-looking values are fake and are asserted to never appear in responses, logs or any database file (including WAL/SHM sidecars).
- Integration tests run the compiled server from this checkout, not the Compose image.
- Mock only the SQLite boundary in unit tests; never the logic under test.

## Known limitations

- V8 coverage reports one branch for a file a layer never loads (server.ts in the unit layer), so unit branch totals are not comparable with integration/service totals.
- `src/server.ts` defaults (`AUDIT_DB_PATH`, `PORT`) are not exercised: tests always pass explicit values.
- Cross-service behavior (frontend emits the event after a confirmed ledger write, audit outage warning) belongs to the browser journey suite, not this service suite.
- Passing tests and coverage figures are not evidence of regulatory compliance.
