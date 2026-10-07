# Customer journey tests (Playwright)

Browser tests for the cross-service banking journeys: sign-in, deposit and payment between two
customers, plus the failure paths that must not move money or write audit records. Each test signs up
its own synthetic customers in separate browser contexts, so tests share no balances, cookies or history.

| Spec | Behaviour verified |
|---|---|
| `specs/auth.spec.ts` | Wrong password is rejected with no session cookie; correct password opens that customer's dashboard; sign-out removes the token and `/home` redirects to login; an unauthenticated `POST /deposit` gets 401 and moves no money |
| `specs/deposit.spec.ts` | Deposit credits balance and history and writes exactly one audit record (`deposit`, `succeeded`, amount in cents, masked accounts, no raw account numbers or customer identity) keyed by the browser's transaction UUID; a deposit that claims this bank's routing number is rejected with no balance, history or audit change |
| `specs/payment.spec.ts` | Payment debits the sender, credits the recipient, both histories update, and one masked `payment` audit record is written; overdraft with the browser `max` removed is rejected by the server with no balance, history or audit change on either side; a negative amount posted around the browser checks cannot pull money from the recipient; replaying a payment with the same UUID is rejected (sender debited once, audited once) |

Audit limitations are respected: failed transactions and auth events are not audited by design, so the
tests assert the *absence* of an audit record on failure paths and do not expect auth audit events.
Success alerts include the Material icon text `check_circle`, so alert assertions use regexes such as
`/\bDeposit successful\s*$/`.

## Running

Requirements: Docker with Compose v2.20+, Node 24 (`source ~/.nvm/nvm.sh && nvm use 24`), `openssl`, `zip`.

```sh
cd tests/e2e
npm ci && npx playwright install chromium

# Default: build every backend from this checkout and run the suite (writes junit.xml,
# playwright-report.zip, playwright-results.zip, compose-logs.txt, stack-images.txt)
bash scripts/report-tests.sh /tmp/e2e-out

# Against a stack that is already running (e.g. `docker compose up -d` on ports 8080/8090)
E2E_STACK=existing E2E_BASE_URL=http://localhost:8080 AUDIT_URL=http://localhost:8090 \
  bash scripts/report-tests.sh /tmp/e2e-out

# Interactive / single test
E2E_BASE_URL=http://localhost:18080 AUDIT_URL=http://localhost:18090 npx playwright test -g overdraft
npx playwright show-report /tmp/e2e-out/playwright-report    # after unzipping playwright-report.zip
```

If Maven Central rate-limits image builds (HTTP 429), set
`MAVEN_MIRROR_URL=https://maven-central.storage-download.googleapis.com/maven2`.
Through the dashboard: `python3 tools/test-observability/dashboard.py run --service journeys`.

## Source-built stack

`compose.source.yaml` overrides the root `compose.yaml` so that userservice, contacts, ledgerwriter,
balancereader, transactionhistory, both databases, frontend and audit are all built from this checkout
(`banking-e2e/*:source` images) instead of the pinned upstream `v0.6.11` images. Java services build
with `docker/java.Dockerfile`. The stack runs as compose project `banking-e2e` on ports 18080
(frontend) and 18090 (audit), with tmpfs databases, so the demo stack and its volumes are not
touched. The runner stops it with `docker compose down` (never `-v`).

`transactionhistory/pom.xml` names `TransActionHistoryApplication` as its Spring Boot main class
but the class is `TransactionHistoryApplication`, so the jar built from source cannot start as-is.
The override passes the correct class via `MAIN_CLASS` (Spring Boot `PropertiesLauncher`) without
changing the service.

## CI

`.github/workflows/test-e2e.yaml`: discovers tests (fails on zero), builds and starts the source
stack, runs service checks (frontend ready, audit health, audit rejects unauthenticated reads, every
image is a local source build), runs the suite, writes pass/fail/skip counts to the job summary, and
uploads `e2e-junit`, `e2e-playwright-report` (HTML report + traces, screenshots and videos for
failures) and `e2e-diagnostics` (compose logs, images) with `if: always()`. Traces are recorded for
every test (`E2E_TRACE=on`). Line/branch coverage does not apply to black-box browser tests; backend
coverage is reported by each service's own workflow.

## Relation to the Cypress suite

`.github/workflows/ui-tests` (Cypress) is unchanged and still covers page loading, navigation,
signup validation, pre-filled demo credentials, deposit form validation and the shared `testuser`
account. Playwright replaces its money-movement journeys (login, deposit, payment) with versions that
use isolated synthetic customers instead of the shared demo user, verify both sides of a payment,
check server-side rejection when browser validation is bypassed, assert that rejected operations
leave balance, history and audit unchanged, and check the masked audit record for each success.
