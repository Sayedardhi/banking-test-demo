# Frontend testing

Three layers, each run once through `scripts/report-tests.sh <layer> <output-dir>`, which writes
`junit.xml` (+ Cobertura `coverage.xml`, line and branch, whole frontend package, tests omitted, for
the Python layers) and returns the real test exit code. CI: `.github/workflows/test-frontend.yaml`.

| Layer | Location | What is real | Controlled substitutes |
|---|---|---|---|
| Unit (pytest) | `tests/unit` | Flask app from `create_app()`, JWT verification with a test RSA key, real `requests.Response` objects | outbound HTTP patched at `requests.get/post` |
| Integration (pytest) | `tests/integration` | frontend served over HTTP (werkzeug), real `src/audit` service (Node 24) on a temporary SQLite file | one recording HTTP stub for ledgerwriter/balancereader/transactionhistory/contacts/userservice (covered by their own suites) |
| E2E (Playwright) | `tests/e2e` (shared journeys suite) | whole stack built from this checkout (`tests/e2e/compose.source.yaml`), Chromium | none; fresh synthetic customers per test |

## Commands

Requirements: `uv` (Python 3.14 via `pyproject.toml`), Node 24 (`nvm use 24`), Docker Compose v2.

```sh
cd src/frontend
uv sync
bash scripts/report-tests.sh unit /tmp/frontend/unit
bash scripts/report-tests.sh integration /tmp/frontend/integration   # builds src/audit
bash scripts/report-tests.sh e2e /tmp/frontend/e2e                   # starts banking-e2e if needed
python3 scripts/summarize.py unit=/tmp/frontend/unit integration=/tmp/frontend/integration e2e=/tmp/frontend/e2e
```

E2E stack by hand (ports 18080 frontend / 18090 audit, own volumes; the local demo stack on
8080/8090 is untouched):

```sh
docker compose -p banking-e2e -f compose.yaml -f tests/e2e/compose.source.yaml up -d --build --wait
cd tests/e2e && npm ci && npx playwright install chromium && npx playwright test
docker compose -p banking-e2e -f compose.yaml -f tests/e2e/compose.source.yaml down   # never -v
```

If Maven Central rate-limits the Java image builds, export
`MAVEN_MIRROR_URL=https://maven-central.storage-download.googleapis.com/maven2` first.

Dashboard: `python3 tools/test-observability/dashboard.py run --service frontend --service journeys`.

## Browser coverage: Playwright vs. Cypress

Playwright (`tests/e2e/specs`) covers login/logout, failed login, anonymous and forged-session
access, signup, deposit (balance, history, masked audit record), payment between two customers
(both balances, history, masked audit record), local-routing deposit rejection, browser amount
validation, server-side insufficient-balance and self-payment rejection, and replayed transaction
IDs. The existing Cypress specs in `.github/workflows/ui-tests` are kept unchanged and are not run
by this pipeline; Playwright does not replace their visual/navigation checks of the home page.

## Known findings (tests intentionally failing)

- `payment()` returns HTTP 500 for `Infinity`/`-Infinity` amounts (`OverflowError` not caught).
- `deposit()` returns HTTP 500 for non-numeric amounts (`ValueError`/`DecimalException` not caught).
- `deposit()` checks the local routing number only for new accounts; a crafted saved-account value
  with the local routing number is forwarded to ledgerwriter.
