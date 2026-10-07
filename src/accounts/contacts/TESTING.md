# Testing contacts

Three layers, each run by `scripts/report-tests.sh <layer> <output-dir>`, which writes JUnit
(`junit.xml`) and, for pytest layers, Cobertura line + branch coverage (`coverage.xml`,
`coverage-html/`) and returns the runner's exit code.

| Layer | What is real | What is substituted | Command |
|---|---|---|---|
| unit (`tests/test_contacts_api.py`) | Flask routes, JWT (RS256) checks, validation, duplicate/self rules, logging | `ContactsDb` (mocked at the DB boundary); keys are generated per session | `scripts/report-tests.sh unit /tmp/contacts/unit` |
| integration (`tests/integration/`) | API -> `ContactsDb` -> PostgreSQL 16 with `src/accounts/accounts-db/initdb/0-accounts-schema.sql` | nothing below HTTP; disposable Testcontainers database, rows deleted after each test | `scripts/report-tests.sh integration /tmp/contacts/integration` |
| e2e (`tests/e2e/specs/contacts.spec.ts`) | Browser -> frontend -> contacts/userservice/ledger services | none; needs a running stack | `scripts/report-tests.sh e2e /tmp/contacts/e2e` |

Coverage source is the whole service directory (`contacts.py`, `db.py`, `__init__.py`; tests and
scripts omitted). Layers are reported separately, never merged or averaged.

## Requirements

- Python 3.14 via `uv` (`uv sync --frozen` installs the `dev` group). pytest runs with
  `--import-mode=importlib` because `contacts/` is a package and `contacts.py` imports `from db`.
- Docker for the integration layer (Testcontainers pulls `postgres:16-alpine`).
- Node 24 + Playwright for E2E: `cd tests/e2e && npm ci && npx playwright install chromium`.

## E2E stack

The E2E layer targets `E2E_BASE_URL` (default `http://localhost:18080`). Build every service,
including contacts, from the checkout with the shared overlay (`tests/e2e/compose.source.yaml`,
owned by the shared journeys suite; on branches without it, check out `tests/e2e` from
`9c96622ccb4c45ef19bc5de793f704d1198657c4`):

```bash
docker compose -p banking-e2e -f compose.yaml -f tests/e2e/compose.source.yaml up -d --build --wait
docker compose -p banking-e2e -f compose.yaml -f tests/e2e/compose.source.yaml ps --format '{{.Service}} {{.Image}}'  # contacts banking-e2e/contacts:source
src/accounts/contacts/scripts/report-tests.sh e2e /tmp/contacts/e2e
docker compose -p banking-e2e -f compose.yaml -f tests/e2e/compose.source.yaml down --remove-orphans  # never -v
```

The overlay uses tmpfs databases; the demo `docker compose up` volumes are not touched.

## CI and dashboard

`.github/workflows/test-contacts.yaml` runs pylint + compile, unit and integration in parallel,
then E2E (contacts journeys + shared journeys) on the source-built stack, then a report job that
packages the same reports with `scripts/dashboard_run.py` into the `contacts-dashboard-run`
artifact. Locally: `python3 tools/test-observability/dashboard.py run --service contacts`.

Known findings are intentionally failing tests (see the PR); a red run caused only by them is
expected.
