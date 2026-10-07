# ledgerwriter testing guide

ledgerwriter is the only service that writes to the ledger, so its tests focus on transaction
integrity: authentication of the sender, validation, balance checks, idempotency and what is (and is
not) persisted when something fails.

## Layers

| Layer | Classes | What is real | What is substituted |
| --- | --- | --- | --- |
| Unit | `TransactionValidatorRulesTest`, `LedgerWriterControllerRulesTest`, `JWTVerifierGeneratorTest`, `TransactionTest` (+ the retained `LedgerWriterControllerTest`, `TransactionValidatorTest`) | Validator, controller, idempotency cache, JWT verifier with real RSA keys | `TransactionRepository`, balancereader `RestTemplate` and (for controller tests) `JWTVerifier` are Mockito mocks |
| Integration | `LedgerWriterPostgresIntegrationTest` | HTTP -> Spring Boot -> JPA -> PostgreSQL 16 (Testcontainers) with the real `ledger-db/initdb/0_init_tables.sql` schema, real RS256 JWT verification | balancereader (separate service) via `MockRestServiceServer` |

Classes ending in `IntegrationTest` are the integration layer; everything else is unit. (JUnit `@Tag`
filtering via `-Dgroups` silently ran zero tests in this repo, so layers are split by class name.)

## Commands (from the repository root)

```sh
# Unit layer -> JUnit XML + JaCoCo XML in .local/lw/unit
src/ledger/ledgerwriter/scripts/report-tests.sh unit .local/lw/unit
# Integration layer (needs Docker for Testcontainers) -> .local/lw/integration
src/ledger/ledgerwriter/scripts/report-tests.sh integration .local/lw/integration
# Summary table (add --check to fail on failures / zero tests)
python3 src/ledger/ledgerwriter/scripts/summarize.py --markdown unit=.local/lw/unit integration=.local/lw/integration
# Plain Maven, all layers
./mvnw -B -pl src/ledger/ledgerwriter verify
# Evidence dashboard (both layers are registered in tools/test-observability/config.json)
python3 tools/test-observability/dashboard.py run --service ledgerwriter
```

`report-tests.sh` exits non-zero on a build error, any failed/errored test, a missing coverage report,
or zero discovered tests. Set `MVN=/path/to/mvn` to use a local Maven instead of `./mvnw`.

Requirements: JDK 17, Docker (integration only), Python 3 (summary). Surefire sets
`HOSTNAME=ledgerwriter-test` and `ENABLE_METRICS=false`, which the Spring context needs to start.

## CI pipeline (`.github/workflows/test-ledgerwriter.yaml`)

Runs on pull requests, pushes to `demo-baseline` and `main`, and `workflow_dispatch`.

| Job | Runs | Notes |
| --- | --- | --- |
| `checks` | `./mvnw -B -pl src/ledger/ledgerwriter checkstyle:check`, then `test-compile` | Existing `checkstyle.xml`; uploads `ledgerwriter-checkstyle` |
| `unit` | `report-tests.sh unit` | Parallel with `checks`/`integration`; uploads `ledgerwriter-unit-reports` (JUnit, `jacoco.xml`, JaCoCo HTML) |
| `integration` | `report-tests.sh integration` | Testcontainers PostgreSQL, one container per test class, leftovers removed with `if: always()`; uploads `ledgerwriter-integration-reports` |
| `e2e` | Builds the whole stack (ledgerwriter included) from the checkout with `tests/e2e/compose.source.yaml`, waits for readiness, runs Playwright | Gated: `checks` must pass and both service layers must have executed with reports (known findings do not block it). Uses `tests/e2e` from the checkout, or the PR #10 suite pinned in `E2E_SUITE_REF` when the checkout has none. Uploads `e2e-junit`, `e2e-playwright-report` (HTML + traces/screenshots/videos), `e2e-service-logs`; stack is stopped with `docker compose down` even on failure |
| `report` | Job summary by layer with artifact links; `scripts/dashboard_run.py` packages the same reports as a dashboard run | Uploads `ledgerwriter-dashboard-run`; fails if any job did not succeed, so test failures stay visible |

Each suite runs once. To view a CI run in the dashboard, unzip `ledgerwriter-dashboard-run` into
`.local/test-observability/runs/` and run `python3 tools/test-observability/dashboard.py serve`.

## Coverage scope

JaCoCo covers every class under `src/main/java` of this module (7 source files, including the Spring
Boot application class), per layer. Coverage is not merged across layers or averaged with other services.

## Limitations

- The default `compose.yaml` runs a prebuilt upstream ledgerwriter image; only the CI `e2e` job (via
  `tests/e2e/compose.source.yaml`) exercises branch changes in the browser journeys.
- balancereader is substituted in both layers; real cross-service balance consistency is not covered here.
- Tests that encode a documented requirement the current code does not meet are kept failing on purpose
  (see the PR "Findings"); they are not skipped.
