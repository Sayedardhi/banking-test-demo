# transactionhistory testing

`transactionhistory` serves `GET /transactions/{accountId}`: it verifies the caller's RS256 JWT,
checks the token's `acct` claim against the requested account, and returns that account's ledger
history from a Guava cache that a background `LedgerReader` keeps up to date by polling the ledger.

## Layers

| Layer | What runs | Command |
|---|---|---|
| Unit | Every `*Test` class except `*IntegrationTest`. JUnit 5, Mockito, AssertJ, real RS256 keys generated per run. No Docker. | `src/ledger/transactionhistory/scripts/report-tests.sh unit reports/unit` |
| Integration | `*IntegrationTest`: the Spring Boot app + MockMvc against a fresh Testcontainers `postgres:16-alpine` per class, initialised with `src/ledger/ledger-db/initdb/0_init_tables.sql`. Synthetic rows only; containers are discarded afterwards. | `src/ledger/transactionhistory/scripts/report-tests.sh integration reports/integration` |
| E2E | Shared Playwright journeys (`tests/e2e`, owned by the frontend work) against a stack where transactionhistory and every other service is built from this checkout (`banking-e2e/transactionhistory:source`). | see below |
| Everything | `.github/workflows/test-transactionhistory.yaml` (checks, unit and integration in parallel, then E2E, then a summary + dashboard run). | `workflow_dispatch` or any PR |

Run commands from the repository root. `report-tests.sh` writes `junit/TEST-*.xml`, `jacoco.xml` and
`jacoco-html/` into the output folder and exits non-zero on a test failure, zero discovered tests or
missing coverage. `scripts/summarize.py` prints per-layer counts and line/branch covered/total.

E2E locally (when `tests/e2e` is not on the branch, take it from the journeys commit used by CI):

```sh
git archive 9c96622ccb4c45ef19bc5de793f704d1198657c4 tests/e2e | tar -x   # do not commit it here
docker compose -p banking-e2e -f compose.yaml -f tests/e2e/compose.source.yaml up -d --build --wait
(cd tests/e2e && npm ci && npx playwright install chromium && E2E_STACK=existing \
  E2E_BASE_URL=http://localhost:18080 AUDIT_URL=http://localhost:18090 bash scripts/report-tests.sh ../../reports/e2e)
docker compose -p banking-e2e -f compose.yaml -f tests/e2e/compose.source.yaml down --remove-orphans   # never -v
```

Dashboard: suites are registered in `tools/test-observability/config.json`
(`python3 tools/test-observability/dashboard.py run --service transactionhistory`).

## Requirements

- JDK 17 and the repo's Maven wrapper. If Maven Central returns HTTP 429, add a `central` mirror
  (`https://maven-central.storage-download.googleapis.com/maven2/`) to `~/.m2/settings.xml`; for the
  E2E Docker build export `MAVEN_MIRROR_URL` with the same URL.
- Docker for the integration and E2E layers. Node 24 for Playwright.
- Surefire sets `HOSTNAME=transactionhistory-test` and `ENABLE_METRICS=false`; the Stackdriver config
  needs a hostname containing `-` to start a Spring context.

## Measurements (JaCoCo, all classes in `src/main`, line and branch reported separately)

| | Tests | Line covered/total | Branch covered/total |
|---|---|---|---|
| Baseline (`demo-baseline` 3580550, 8 existing tests) | 8 unit, no integration/E2E harness | 35/189 (18.5%) | 7/46 (15.2%) |
| Unit (this branch) | 45 (43 pass, 2 findings) | 145/189 (76.7%) | 35/46 (76.1%) |
| Integration (this branch) | 10 (8 pass, 2 findings) | 161/189 (85.2%) | 33/46 (71.7%) |

The layers' coverage is reported per layer and is not merged or averaged.

## Findings (tests intentionally left failing)

1. **Duplicate history row after a fresh cache load.** If an account's history is loaded into the cache
   after a transaction is committed but before the `LedgerReader`'s next poll, the reader then prepends
   the same row again, so the customer sees the payment twice until the cache expires.
   `TransactionHistoryCacheUpdateTest.rowLoadedThenDeliveredAppearsOnce`,
   `HistoryConsistencyIntegrationTest.paymentListedOnceWhenHistoryOpenedBeforeNextPoll`.
2. **`Authorization: Bearer ` (empty credentials) gives HTTP 500, not 401.** `"Bearer ".split("Bearer ")`
   returns an empty array and the `ArrayIndexOutOfBoundsException` escapes the controller.
   `TransactionHistoryControllerAuthorizationTest.emptyBearerCredentialsRejected`,
   `TransactionHistoryApiIntegrationTest.emptyBearerRejectedAs401`.

## Limitations

- A request without an `Authorization` header gets Spring's 400 (missing header), not 401; the tests
  only require a 4xx with no history in the body.
- The integration consistency test relies on a 4 s poll window after a sentinel row; a run slower
  than that between two statements would see the reader deliver first and pass without showing the
  duplicate (it cannot produce a false failure).
- E2E journeys are cross-service and do not measure transactionhistory code coverage; they confirm
  that history shown in the browser changes correctly with transactionhistory built from source.
- Tracing/metrics exporters (Stackdriver) are disabled in tests and not exercised.
