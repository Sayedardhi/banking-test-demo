# transactionhistory testing guide

Transaction history reads: JWT-authorized `GET /transactions/{accountId}`, a Guava history cache,
a background `LedgerReader` that streams new ledger rows into cached histories, and JPA queries
against the ledger PostgreSQL database.

## Layers

| Layer | Classes | What runs for real | Substitutes |
|---|---|---|---|
| Unit | `*Test` (not `*IntegrationTest`) | Controller, cache loader, LedgerReader, JWT verifier, `Transaction` | `TransactionRepository` and `LedgerReader` mocked (Mockito); real RS256 keys/tokens generated per run |
| Integration | `*IntegrationTest` | Spring Boot app on a random port, HTTP, JWT verification, Guava cache, LedgerReader thread, JPA, PostgreSQL 16 (Testcontainers) with `src/ledger/ledger-db/initdb/0_init_tables.sql` | None inside the service; JWTs are signed by a test key pair instead of userservice |

All data is synthetic (account numbers `1…`/`2…`/`6…`, routing `123456789` local / `987654321` external).
The persistent demo database volumes are never used.

## Commands (from the repo root)

```sh
# Unit layer
./mvnw -B -pl src/ledger/transactionhistory verify -Dtest='!*IntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false
# Integration layer (needs Docker for Testcontainers)
./mvnw -B -pl src/ledger/transactionhistory verify -Dtest='*IntegrationTest'
# Either layer with reports (JUnit XML, JaCoCo XML + zipped HTML, maven.log, summary.md) in <out>
bash src/ledger/transactionhistory/scripts/report-tests.sh unit <out>
bash src/ledger/transactionhistory/scripts/report-tests.sh integration <out>
# Dashboard evidence (suites are registered in tools/test-observability/config.json)
python3 tools/test-observability/dashboard.py run --service transactionhistory
```

The runner exits non-zero on Maven failure, any failed/errored test, or zero discovered tests.
Surefire sets `HOSTNAME=transactionhistory-test` and `ENABLE_METRICS=false` for Spring contexts.

## Requirements

JDK 17, Docker (integration layer), network access to Maven Central (or a mirror in
`~/.m2/settings.xml`; set `MVNW_REPOURL` for the wrapper download if Central rate-limits).

## CI

`.github/workflows/test-transactionhistory.yaml` runs unit then integration, writes per-layer
counts and line/branch coverage (covered/total) to the job summary, uploads
`transactionhistory-test-reports` even on failure, and fails on test failures or zero tests.

## Known findings

Tests named `alreadyLoadedTransactionNotDuplicated` (unit) and `paymentBeforeFirstViewAppearsOnce`
(integration) intentionally fail against current production code: a transaction committed before
an account's first view is loaded from the database and then prepended again by the LedgerReader,
so it appears twice in the history. Production code is unchanged by design.
