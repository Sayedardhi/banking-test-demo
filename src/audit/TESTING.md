# Testing the audit service

Requirements: Node 24 (`node:sqlite`; `source ~/.nvm/nvm.sh && nvm use 24`), Python 3 for report
summaries, Docker for E2E. All data is synthetic. Run commands from `src/audit` unless noted.

| Layer | Command | What it exercises |
|---|---|---|
| Unit | `bash scripts/report-tests.sh unit reports/unit` | `normalizeEvent`, `AuditStore` (in-memory SQLite, mocked clock), HTTP handler in-process |
| Integration | `bash scripts/report-tests.sh integration reports/integration` | compiled `server.js` as a child process over real HTTP, fresh temporary SQLite file per suite, rows read back directly |
| E2E (audit) | `bash scripts/report-tests.sh e2e reports/e2e` | browser deposit/payment -> masked audit record; audit outage; read auth (needs a running stack) |
| All service layers | `npm ci && npm test` | unit then integration |
| Dashboard | from the repo root: `python3 tools/test-observability/dashboard.py run --service audit` | the three suites registered in `tools/test-observability/config.json` |
| CI | `.github/workflows/test-audit.yaml` | typecheck/build, unit and integration in parallel, then source-built E2E, summary, dashboard run |

Each layer writes `junit.xml`; unit/integration also write `coverage.xml` (Cobertura) and
`coverage-html.tar.gz`; E2E writes `playwright-report.zip` and `playwright-results.zip`
(traces, screenshots, videos on failure). The script exits non-zero on compile errors, test
failures, or zero discovered tests.

## Coverage scope

c8 measures all of `src/**/*.ts` (`--all`, so unloaded files count as 0%), remapped from the
compiled JavaScript through source maps; tests are excluded. Integration coverage is collected
from the spawned server process. Unit and integration are reported separately, never merged or
averaged. E2E is black-box: it has no code coverage, and it runs against the container image.

## E2E

- `tests/e2e/` here is the audit-specific Playwright suite. It targets `E2E_BASE_URL`
  (default `http://localhost:8080`) and `AUDIT_URL` (default `http://localhost:8090`). The outage
  test stops and restarts the audit container of compose project `E2E_COMPOSE_PROJECT`
  (default `banking-demo`); the local demo volume is not removed.
- The shared login/deposit/payment journeys live in the repo-level `tests/e2e`, owned by the
  frontend work. When this branch does not contain it, CI checks out only that directory from
  commit `9c96622ccb4c45ef19bc5de793f704d1198657c4` (PR #10) and keeps application code from
  this checkout. CI builds every service from source (`compose.source.yaml`) and asserts that the
  audit container runs `banking-e2e/audit:source`.
- The Cypress specs in `.github/workflows/ui-tests` (deposit, transfer, login) are left in place.
  The Playwright journeys cover the same flows plus audit assertions; Cypress is not run here.

## Known findings and limits

- `Content-Type: Application/JSON` is rejected with 415 because the media-type check is
  case-sensitive (RFC 9110 media types are case-insensitive). The integration test for it is
  expected to fail until production code is fixed.
- No outbox: a transaction confirmed during an audit outage is never audited (the frontend shows
  "audit recording unavailable"). E2E asserts that current behavior; it is a documented gap.
- Masking keeps only the last four digits, so two accounts sharing them are indistinguishable in
  the audit log.
- `/health` only checks the SQLite connection, not the schema.
