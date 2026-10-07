# Testing: ledgerwriter, audit, and browser journeys

Commands for the test layers added on top of `demo-baseline`. CI runs the same commands in
[`.github/workflows/test-coverage.yaml`](.github/workflows/test-coverage.yaml) and publishes counts,
coverage and reports as job summaries and artifacts.

| Layer | Command | Requires |
| --- | --- | --- |
| ledgerwriter unit (JUnit 5 + Mockito) | `./mvnw -B -pl src/ledger/ledgerwriter -am test -Dtest='!*IntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false` | JDK 17 |
| ledgerwriter integration (Testcontainers Postgres, real ledger schema) | `./mvnw -B -pl src/ledger/ledgerwriter -am test -Dtest='*IntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false` | JDK 17, Docker |
| ledgerwriter all + JaCoCo | `./mvnw -B -pl src/ledger/ledgerwriter -am verify` (report: `src/ledger/ledgerwriter/target/site/jacoco/`) | JDK 17, Docker |
| audit unit (`node:test`) | `cd src/audit && npm ci && npm run pretest && npm run test:unit` | Node 24 |
| audit integration (real temp SQLite + spawned HTTP server) | `cd src/audit && npm run pretest && npm run test:integration` | Node 24 |
| audit all + c8 coverage | `cd src/audit && npm run coverage` (report: `src/audit/coverage/`) | Node 24 |
| Playwright E2E | `./scripts/start-local.sh`, then `cd tests/e2e && npm ci && npx playwright install chromium && npx playwright test` | Docker, Node 24 |
| Everything | Run the rows above in order: service checks first, then E2E. | |

## Notes

- **Isolation.** The Postgres integration tests use a disposable container built from
  `src/ledger/ledger-db/initdb/0_init_tables.sql`. The audit tests create a temporary SQLite file per
  suite. The Playwright tests sign up a new synthetic customer for each test, so they don't depend on
  or change `testuser`'s balance. They do add rows to the running demo databases (Compose volumes).
  Nothing deletes volumes.
- **Substitutes.** The ledgerwriter integration test replaces balancereader (a separate service) with
  `MockRestServiceServer`. The JWT checks, controller, JPA and Postgres are real.
- **Which code the E2E tests run.** In Compose, `audit` is built from `src/audit` and `frontend`
  mounts `src/frontend`. ledgerwriter, balancereader, transactionhistory, userservice and contacts run
  pinned prebuilt `v0.6.11` images, so the E2E tests do not exercise Java/Python source changes on a
  branch.
- **Cypress.** The suite in `.github/workflows/ui-tests/` is unchanged and still the only browser
  coverage for signup validation, contacts and external-account management. Playwright adds login
  failure, deposit, payment, server-side overdraft rejection, cross-account crediting and audit-record
  correlation.
- **Failing tests marked `DEFECT`.** Some tests assert documented behaviour that the current code
  does not meet. They are expected to fail until the production code is fixed. Do not skip them.
