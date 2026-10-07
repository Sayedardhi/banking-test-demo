# balancereader testing guide

balancereader serves `GET /balances/{accountId}` from a Guava cache that is
loaded from `ledger-db` and kept current by a background `LedgerReader`
poller. Requests must carry an RS256 JWT whose `acct` claim equals the
requested account.

## Environment

- JDK 17 (the Maven wrapper at the repo root downloads Maven).
- Docker for the integration layer (Testcontainers starts `postgres:16-alpine`
  with `src/ledger/ledger-db/initdb/0_init_tables.sql`). Nothing else is needed:
  no Compose stack, no demo database volumes, no network services.
- Surefire sets `HOSTNAME=balancereader-test` and `ENABLE_METRICS=false`
  (startup code calls `HOSTNAME.indexOf("-")`).
- All data is synthetic: generated 10-digit accounts, a throwaway RSA key pair
  generated per test class, fixed timestamps.

## Commands

Run from the repository root.

```sh
# unit layer (no Docker)
./mvnw -B -pl src/ledger/balancereader verify -Dcheckstyle.skip=true \
  -Dtest='!*IntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false

# integration layer (Docker required)
./mvnw -B -pl src/ledger/balancereader verify -Dcheckstyle.skip=true \
  -Dtest='*IntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false

# evidence runner (used by CI and the dashboard): JUnit XML, JaCoCo XML and
# summary.md written to OUTPUT_DIR; nonzero on failures or zero tests
bash src/ledger/balancereader/scripts/report-tests.sh unit OUTPUT_DIR
bash src/ledger/balancereader/scripts/report-tests.sh integration OUTPUT_DIR

# local evidence dashboard
python3 tools/test-observability/dashboard.py run --service balancereader
python3 tools/test-observability/dashboard.py serve   # http://localhost:8081
```

Layers are split by class-name filter; `-Dgroups` tag filtering silently ran
zero tests in this repo. CI: `.github/workflows/test-balancereader.yaml`.

## Layers

| Layer | Classes | What is real | What is substituted |
|---|---|---|---|
| unit | `BalanceReaderControllerRulesTest`, `BalanceReaderControllerTest` (pre-existing), `BalanceCacheTest`, `LedgerReaderTest`, `JWTVerifierGeneratorTest`, `TransactionTest` | Auth0 JWT verifier with real RSA keys, Guava cache, controller callback, LedgerReader thread | `TransactionRepository` (database boundary) and `LedgerReader` (in controller tests) are Mockito mocks |
| integration | `BalanceReaderPostgresIntegrationTest`, `LedgerAvailabilityPostgresIntegrationTest` | Full Spring Boot app on a random port, HTTP, JPA/native queries, poller, PostgreSQL 16 with the production ledger-db schema | none inside the service; ledgerwriter is replaced by direct synthetic `INSERT`s |

Integration tests wait for the poller to consume all committed rows
(`PostgresLedgerFixture.awaitPollerCaughtUp`) before a first balance read, so
they do not depend on poll timing. Outage tests pause/unpause the Testcontainers
database; each gets a fresh application context.

## Results (local, JaCoCo, all 7 production classes in the denominator)

| | Tests | Line covered/total | Branch covered/total |
|---|---|---|---|
| Baseline (`demo-baseline` cc8525ac), unit | 9 (9 pass) | 35/182 (19.2%) | 6/44 (13.6%) |
| Baseline, integration | no harness / unmeasured | – | – |
| Final, unit | 45 (44 pass, 1 fail) | 139/182 (76.4%) | 32/44 (72.7%) |
| Final, integration | 16 (15 pass, 1 fail) | 159/182 (87.4%) | 34/44 (77.3%) |

Coverage is reported per layer and is not merged or averaged.

## Findings (tests kept as written; production code unchanged)

- `BalanceReaderControllerRulesTest.transactionInLoadedBalanceIsNotDoubleCounted`:
  a transaction committed before an account's first balance load, but not yet
  consumed by the poller, is counted twice (once in the loaded balance, again by
  the callback). Before the integration tests waited for the poller, the same
  race produced balances such as 4001 for a 2001 ledger.
- `BalanceReaderPostgresIntegrationTest.debitOnlyAccountReportsNegativeBalance`:
  `findBalance` returns `NULL` for an account with outgoing but no incoming
  rows (`SUM` of no rows minus a value), so the API reports 0 instead of the
  negative net balance.

## Known limitations / remaining gaps

- An outage during `findLatest` ends the poller thread (only
  `latestTransactionId` errors are caught); the service then relies on `/healthy`
  returning 500 for a restart. The outage test accepts either recovery or an
  unhealthy liveness probe, and rejects "healthy but stale".
- Cache expiry by time and Stackdriver metrics export are not exercised.
- The Compose stack runs a prebuilt upstream balancereader image, so these
  tests run against source, not the Compose container.
- Coverage shows which lines ran, not that every requirement is met; it is not
  evidence of regulatory compliance.
