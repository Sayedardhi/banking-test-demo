# Testing the contacts service

Contacts stores saved payees (account + routing numbers) per user, so tests focus
on JWT authorization, account/routing/label validation, self/duplicate rules,
error mapping, and keeping account numbers out of logs.

## Layers

| Layer | Location | Boundary | Notes |
| --- | --- | --- | --- |
| Unit | `tests/test_contacts_api.py`, `tests/test_db.py` | `ContactsDb` mocked for the API tests; `ContactsDb` row mapping runs on in-memory SQLite | JWTs are signed with a throwaway RSA key generated per run |
| Integration | `tests/integration/` | Real Flask app + real `ContactsDb` + PostgreSQL 16 (Testcontainers) using `../accounts-db/initdb/0-accounts-schema.sql` | Fresh container per run, tables truncated and seeded with synthetic users before every test. Never uses the Compose `accounts-db` volume |

All data is synthetic (`alice-synth`, `bob-synth`, made-up account numbers).

## Requirements

- [uv](https://docs.astral.sh/uv/) (Python 3.14 is installed by `uv python install`)
- Docker (integration layer only)

## Commands

From `src/accounts/contacts`:

```sh
uv sync --frozen --group dev
uv run python -m pytest tests --ignore=tests/integration   # unit
uv run python -m pytest tests/integration                  # integration (needs Docker)

# Report form used by CI and the dashboard (JUnit + Cobertura XML, real exit code):
scripts/report-tests.sh unit /tmp/contacts-reports/unit
scripts/report-tests.sh integration /tmp/contacts-reports/integration
python3 scripts/ci_summary.py /tmp/contacts-reports      # Markdown summary, fails on 0 tests
```

From the repo root, the evidence dashboard runs both layers:

```sh
python3 tools/test-observability/dashboard.py run --service contacts
```

Coverage uses branch mode with `source = .` (contacts.py, db.py, `__init__.py`).
Tests and scripts are omitted, and each layer is reported separately.

## CI

`.github/workflows/test-contacts.yaml` runs unit and then integration tests and
writes per-layer pass/fail/skip plus line and branch coverage (covered/total) to the
job summary. It uploads the `contacts-test-reports` artifact even when tests fail.

## Known limitations

- The Compose stack runs the prebuilt `contacts:v0.6.11` image, so browser journeys don't exercise this checkout. These layers do.
- Concurrent duplicate inserts (two simultaneous POSTs) aren't tested. The schema has no unique constraint, so duplicate prevention happens only in the application.
