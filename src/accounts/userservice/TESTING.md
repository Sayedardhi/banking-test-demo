# userservice testing

userservice owns signup (`POST /users`), login/JWT issuance (`GET /login`) and credential storage
(bcrypt hashes in `accounts-db`). Tests are split into three layers.

| Layer | Location | What is real | What is substituted |
|---|---|---|---|
| Unit | `tests/test_signup.py`, `tests/test_login.py`, `tests/test_app_startup.py`, `tests/test_userservice.py` | Flask app from `create_app()`, bleach, bcrypt, PyJWT (RS256 with fixture keys) | `UserDb` (`MagicMock`), key files (`mock_open`) |
| Integration | `tests/integration/`, `tests/test_db.py` | Flask app + `UserDb` + PostgreSQL 16 (Testcontainers) loaded with `src/accounts/accounts-db/initdb/0-accounts-schema.sql`; `test_db.py` keeps the original in-memory SQLite checks | Nothing on the API to database path; RSA keys generated per session |
| E2E | shared Playwright suite `tests/e2e` (owned by the journeys/frontend work) | Whole stack built from source, including `banking-e2e/userservice:source` | - |

All data is synthetic (`alice_*`, `900-00-*` SSNs, `1 Test Way`). Each integration test truncates
`users` in its own disposable container; the local demo database is never touched.

## Commands

Requirements: [uv](https://docs.astral.sh/uv/) (Python 3.14 from `.python-version`), and Docker for
integration and E2E. Run from `src/accounts/userservice` unless noted.

```bash
scripts/report-tests.sh unit out/unit                 # junit.xml, coverage.xml (line + branch), htmlcov/
scripts/report-tests.sh integration out/integration   # needs Docker (postgres:16-alpine)
python3 scripts/summarize.py --markdown unit=out/unit integration=out/integration
```

Coverage source is the whole service package (`userservice.py`, `db.py`, `__init__.py`), tests and
scripts excluded, so unexecuted modules count in the denominator. Line and branch coverage are
reported separately per layer and are never averaged.

E2E (from the repo root, against a source-built stack):

```bash
docker compose -p banking-e2e -f compose.yaml -f tests/e2e/compose.source.yaml up -d --build --wait
(cd tests/e2e && npm ci && npx playwright install chromium && E2E_STACK=existing bash scripts/report-tests.sh ../../out/e2e)
docker compose -p banking-e2e -f compose.yaml -f tests/e2e/compose.source.yaml down --remove-orphans   # never -v
```

If `tests/e2e` is not on your branch, check it out from the journeys commit used by CI:
`git fetch origin 9c96622ccb4c45ef19bc5de793f704d1198657c4 && git checkout FETCH_HEAD -- tests/e2e`
(and `git reset -q` so it is not staged).

Complete workflow: `.github/workflows/test-userservice.yaml` (checks, unit and integration in
parallel, then E2E, then a report job that packages a `tools/test-observability` run without
re-running tests). Dashboard collector: `python3 tools/test-observability/dashboard.py run --service userservice`.

## Known failing tests (production findings, intentionally red)

- `test_login.py::test_missing_credential_parameter_is_400[*]` - `/login` without `username` or
  `password` returns 500 (`bleach.clean(None)` raises) instead of 400.
- `test_signup.py::test_whitespace_only_required_field_is_400_and_creates_nothing[*]` - whitespace-only
  required fields (first name, SSN) pass validation and the account is created (201) instead of 400.
- `test_signup.py::test_signup_debug_logs_never_contain_password_or_ssn` - DEBUG log line
  `validating create user request` contains the plaintext password and SSN.
- `test_postgres_auth.py::test_database_error_logs_do_not_expose_ssn_or_password_hash` - on a
  PostgreSQL error the ERROR log contains the SQL parameters, including SSN and password hash.
- `test_postgres_auth.py::test_oversized_profile_field_is_rejected_as_invalid_input` - a value longer
  than the schema allows (e.g. 6-digit zip) is accepted by validation and fails as a 500.
- `test_postgres_auth.py::test_concurrent_signups_for_one_username_create_one_account_and_report_conflict` -
  the check-then-insert duplicate check races; the losing request gets 500 instead of 409.
