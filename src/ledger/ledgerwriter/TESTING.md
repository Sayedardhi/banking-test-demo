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

CI: `.github/workflows/test-ledgerwriter.yaml` runs unit then integration, writes per-layer counts and
line/branch coverage to the job summary, and uploads `ledgerwriter-unit-reports` and
`ledgerwriter-integration-reports` even on failure.

## Coverage scope

JaCoCo covers every class under `src/main/java` of this module (7 source files, including the Spring
Boot application class), per layer. Coverage is not merged across layers or averaged with other services.

## Limitations

- Docker Compose runs a prebuilt upstream ledgerwriter image, so browser/E2E journeys do not exercise
  changes made on a branch. These tests run the checked-out source.
- balancereader is substituted in both layers; real cross-service balance consistency is not covered here.
- Tests that encode a documented requirement the current code does not meet are kept failing on purpose
  (see the PR "Findings"); they are not skipped.
