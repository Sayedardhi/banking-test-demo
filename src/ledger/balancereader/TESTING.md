# Testing balancereader

Java 17. Run from the repository root. Each command writes `junit/TEST-*.xml`, `jacoco.xml`,
`jacoco-html.zip`, `maven.log` and `summary.md` to the output folder and exits nonzero on test
failures, zero discovered tests, or missing coverage. `MVN=mvn` overrides the default `./mvnw`.

| Layer | Command | Scope |
|---|---|---|
| Unit | `src/ledger/balancereader/scripts/report-tests.sh unit reports/unit` | Every class except `*IntegrationTest`: controller authorization/balance logic with the real Guava cache and RSA JWT verifier (repository and ledger thread substituted), `BalanceCache`, `LedgerReader` polling, `JWTVerifierGenerator`, `Transaction`. |
| Integration | `src/ledger/balancereader/scripts/report-tests.sh integration reports/integration` | `*IntegrationTest`: full Spring Boot app over HTTP, real JWT verification, PostgreSQL 16 via Testcontainers with `src/ledger/ledger-db/initdb/0_init_tables.sql` (no demo seed data). Needs Docker. |
| E2E | `cd tests/e2e && bash scripts/report-tests.sh ../../reports/e2e` | Playwright journeys against a stack built from this checkout (`banking-e2e/*:source`). `tests/e2e` comes from PR #10 (pinned `9c96622c`) until it lands on `demo-baseline`. |
| All (CI) | `.github/workflows/test-balancereader.yaml` | Checkstyle + compile, unit and integration in parallel; E2E when those produced valid reports; summary + dashboard run. |

Dashboard: `python3 tools/test-observability/dashboard.py run --service balancereader`
(uses the two suites registered in `tools/test-observability/config.json`).

Fixtures are synthetic: RSA key pairs generated per JVM (`TestTokens`), account numbers
`1011226111`-style for unit tests and unique `2000000001+` per integration test because the ledger
table is append-only (UPDATE/DELETE rules) and balances are cached.

Coverage scope is the whole module (`anthos.samples.bankofanthos.balancereader`, 182 lines / 44
branches), reported per layer; layers are not merged.

Known findings are kept as failing tests (see the PR): an account with only debits reports `0`;
a balance first read between a ledger insert and the next ledger poll is counted twice; a header
consisting only of `Bearer ` makes `getBalance` throw instead of returning 401.
