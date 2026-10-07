# Ledger Writer Service

The ledger writer service accepts and validates incoming transactions before writing them to the ledger.

Implemented in Java with Spring Boot.

### Endpoints

| Endpoint           | Type  | Auth? | Description                                          |
| ------------------ | ----- | ----- | ---------------------------------------------------- |
| `/ready`           | GET   |       |  Readiness probe endpoint.                           |
| `/transactions`    | POST  | 🔒    |  Submits a transaction to be appended to the ledger. |
| `/version`         | GET   |       |  Returns the contents of `$VERSION`                  |

### Environment Variables

- `VERSION`
  - a version string for the service
- `PORT`
  - the port for the webserver
- `JVM_OPTS`
  - settings for the JVM. Used to obey container memory limits
- `LOG_LEVEL`
  - the service-wide [log level](https://logging.apache.org/log4j/2.x/manual/customloglevels.html) (default: INFO)
  
- ConfigMap `environment-config`:
  - `LOCAL_ROUTING_NUM`
    - the routing number for our bank
  - `PUB_KEY_PATH`
    - the path to the JWT signer's public key, mounted as a secret

- ConfigMap `service-api-config`
  - `BALANCES_API_ADDR`
    - the address and port of the `balancereader` service

- ConfigMap `ledger-db-config`:
  - `SPRING_DATASOURCE_URL`
    - URL of the `ledger-db` service
  - `SPRING_DATASOURCE_USERNAME`
    - username for the `ledger-db` database
  - `SPRING_DATASOURCE_PASSWORD`
    - password for the `ledger-db` database

### Kubernetes Resources

- [deployments/ledgerwriter](/kubernetes-manifests/ledger-writer.yaml)
- [service/ledgerwriter](/kubernetes-manifests/ledger-writer.yaml)

### Testing

Three layers, each run by [`scripts/test-ledgerwriter.sh`](/scripts/test-ledgerwriter.sh) `<layer> <output-dir>` from the repo root. The script returns the runner's exit code and writes fresh reports to the output directory. The same commands are registered as suites in [`tools/test-observability/config.json`](/tools/test-observability/config.json).

| Layer | What runs | Real | Stubbed | Reports |
| ----- | --------- | ---- | ------- | ------- |
| `unit` | `*Test.java` (JUnit 5 + Mockito) via Surefire | – | repository, balancereader `RestTemplate`, JWT verifier | `surefire-reports/`, `jacoco.xml` |
| `integration` | `*IT.java` via Failsafe: the Spring Boot app over HTTP | PostgreSQL 16 (Testcontainers, initialised with `ledger-db/initdb/0_init_tables.sql`), RSA256 JWT verification, JPA/Hikari | balancereader only (`BalanceReaderStub`, a local HTTP server) | `failsafe-reports/`, `jacoco.xml` |
| `e2e` | Playwright journeys in [`tests/e2e`](/tests/e2e) through the frontend | all Compose services; ledgerwriter built from this checkout | nothing | `junit.xml`, `playwright-report.zip` |

```sh
# needs Java 17, Maven 3.9 and Docker; e2e also needs Node.js 20+
scripts/test-ledgerwriter.sh unit        .local/ledgerwriter-tests/unit
scripts/test-ledgerwriter.sh integration .local/ledgerwriter-tests/integration
(cd tests/e2e && npm ci && npx playwright install chromium)
scripts/test-ledgerwriter.sh e2e         .local/ledgerwriter-tests/e2e
```

The `e2e` layer builds the jar, starts the demo stack and recreates ledgerwriter with [`compose.ledgerwriter-source.yaml`](/compose.ledgerwriter-source.yaml), which runs the jar on `eclipse-temurin:17-jre` in place of the pinned image. Playwright's global setup fails unless ledgerwriter reports `VERSION=source-<git sha>`. Each journey signs up new customers and uses random external accounts, and checks the balance, the history rows and the `transactions` rows in ledger-db.

Run everything through the dashboard with `python3 tools/test-observability/dashboard.py run --service ledgerwriter --service journeys`.
