# userservice testing

Authentication service tests: signup validation, password hashing, duplicate
users, login/JWT issuance, error handling, and that passwords/SSNs are not
logged or returned.

## Layers

| Layer | Files | What is real | What is substituted |
| --- | --- | --- | --- |
| Unit | `tests/test_userservice.py`, `test_signup.py`, `test_login.py`, `test_sensitive_data.py`, `test_app_startup.py` | Flask app, validation, bleach, bcrypt, RS256 JWT signing | `UserDb` (mock), key files, environment, Cloud Trace exporter |
| Integration | `tests/test_db.py` | `UserDb` against **in-memory SQLite** (existing; not PostgreSQL) | — |
| Integration | `tests/integration/` | Flask app + `UserDb` + PostgreSQL 16 (Testcontainers) with the `accounts-db/initdb/0-accounts-schema.sql` schema | none; one test wraps `UserDb.get_user` with a barrier to force a concurrent signup race |

Integration tests start a throwaway `postgres:16-alpine` container per session
and truncate tables before each test. They never touch the Compose
`accounts-db` volume. Time (`utcnow`) and account-id randomness are controlled
where assertions depend on them. All data is synthetic.

## Commands

Requirements: [uv](https://docs.astral.sh/uv/) (installs Python 3.14 from
`.python-version`), Docker for the PostgreSQL layer.

```sh
cd src/accounts/userservice
bash scripts/report-tests.sh unit out/unit                # JUnit + Cobertura in out/unit
bash scripts/report-tests.sh integration out/integration
bash scripts/report-tests.sh combined out/combined out/unit out/integration
python3 scripts/ci_summary.py unit=out/unit integration=out/integration --coverage-only combined=out/combined
```

The script returns pytest's exit code (5 when no tests are collected).
Coverage uses `scripts/coverage.ini`: line + branch, scope = every production
module in this directory (`__init__.py`, `db.py`, `userservice.py`), tests and
`scripts/` excluded.

Dashboard: `python3 tools/test-observability/dashboard.py run --service userservice`
(suites registered in `tools/test-observability/config.json`).
CI: `.github/workflows/test-userservice.yaml`.

## Notes

- Failing tests describe required behaviour the current code does not meet.
  Do not skip or weaken them; fix the production code instead.
- Compose runs the prebuilt `userservice` image, so browser/E2E journeys do not
  exercise this source tree. These tests import the source directly.
