# Testing ledgerwriter

ledgerwriter accepts `POST /transactions` (payments and external deposits), verifies the caller's JWT,
rejects replayed request UUIDs, validates account/routing/amount, checks the sender's balance through
balancereader, and appends the transaction to the ledger database. The tests below cover those decisions.

| Layer | Classes | Runtime | Boundary |
|---|---|---|---|
| Unit | `*Test` except `*IntegrationTest` | JUnit 5 + Mockito, no Docker | Real `TransactionValidator`, real RSA-signed JWTs; repository and balancereader `RestTemplate` mocked |
| Integration | `*IntegrationTest` | Spring Boot on a random port + Testcontainers `postgres:16-alpine` | Real HTTP, Spring MVC, JPA and PostgreSQL with `src/ledger/ledger-db/initdb/0_init_tables.sql`; balancereader replaced by an in-process HTTP stub (`BalanceReaderStub`) |
| E2E | Playwright journeys in `tests/e2e` (owned by the frontend/journeys work) | Full Compose stack built from this checkout | Browser login, payment and deposit journeys |

Integration tests start one throwaway PostgreSQL container per JVM (plus a separate one that
`LedgerDatabaseOutageIntegrationTest` stops), truncate `TRANSACTIONS` before every test and never touch
the Compose demo database. Test data is synthetic (accounts `1011226111`, `1033623433`, external `9099791699`).

## Commands

From the repository root (Java 17; integration needs Docker):

```sh
src/ledger/ledgerwriter/scripts/report-tests.sh unit        reports/unit
src/ledger/ledgerwriter/scripts/report-tests.sh integration reports/integration
./mvnw -B -pl src/ledger/ledgerwriter checkstyle:check -Dcheckstyle.includeTestSourceDirectory=true
python3 src/ledger/ledgerwriter/scripts/summarize.py --markdown unit=reports/unit integration=reports/integration
```

Each run writes `TEST-*.xml`, `jacoco.xml`, `jacoco-html/` and `maven.log` to the output folder. Exit codes:
0 passed, 1 test failures, 3 zero tests executed, 4 no coverage report. JaCoCo scope is every class in
`src/main/java`; unit and integration coverage are separate reports and are never merged.

Directly with Maven (surefire sets `HOSTNAME=ledgerwriter-test` and `ENABLE_METRICS=false`):

```sh
./mvnw -B -pl src/ledger/ledgerwriter verify -Dtest='!*IntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -pl src/ledger/ledgerwriter verify -Dtest='*IntegrationTest'  -Dsurefire.failIfNoSpecifiedTests=false
```

`-Dgroups` does not select anything here; use the class filters above. If Maven Central returns HTTP 429,
add a `central` mirror (e.g. `https://maven-central.storage-download.googleapis.com/maven2/`) to `~/.m2/settings.xml`.

E2E (source-built stack, ports 18080/18090, tmpfs databases):

```sh
docker compose -p banking-e2e -f compose.yaml -f tests/e2e/compose.source.yaml up -d --build --wait
docker compose -p banking-e2e -f compose.yaml -f tests/e2e/compose.source.yaml ps --format '{{.Service}} {{.Image}}'  # ledgerwriter banking-e2e/ledgerwriter:source
(cd tests/e2e && E2E_STACK=existing bash scripts/report-tests.sh ../../reports/e2e)
docker compose -p banking-e2e -f compose.yaml -f tests/e2e/compose.source.yaml down --remove-orphans   # never -v
```

If `tests/e2e` is not on the branch, check out only that directory from journeys commit
`9c96622ccb4c45ef19bc5de793f704d1198657c4` (PR #10); the CI workflow does this automatically.

Dashboard: `python3 tools/test-observability/dashboard.py run --service ledgerwriter`, then
`python3 tools/test-observability/dashboard.py serve` (http://localhost:8081).

## CI

`.github/workflows/test-ledgerwriter.yaml`: Checkstyle + compile, unit and integration run in parallel; the
Playwright job runs when checks pass and both layers produced reports (`harness=ok`), even if tests failed.
The `report` job writes the per-layer summary and uploads `ledgerwriter-dashboard-run` built from the same
reports. Failing tests keep the workflow red.

## Known failing tests (production findings)

These tests encode the expected behaviour and fail against the current implementation:

| Test | Expected | Actual |
|---|---|---|
| `IdempotencyIntegrationTest.concurrentDuplicate` | two concurrent submissions of one request UUID write one row | both are written (check-then-put on the UUID cache is not atomic) |
| `LedgerWriteIntegrationTest.missingField`, `TransactionValidatorRulesTest.rejectsMissingFieldsAsValidationErrors` | missing account/routing/amount is a 400 validation error | `NullPointerException`, HTTP 500 |
| `LedgerWriteIntegrationTest.fractionalCents` | `amount: 2550.75` is rejected | accepted and truncated to 2550 cents |
| `LedgerWriterControllerRulesTest$Authentication.emptyBearerToken` | `Authorization: Bearer ` (no token) answered with 401 | `ArrayIndexOutOfBoundsException` from `split("Bearer ")[1]` escapes the handler (HTTP 500) |

The UUID cache is in memory per pod with a one-hour expiry, so replays across restarts or replicas are not
detected; that limitation is documented, not tested.
