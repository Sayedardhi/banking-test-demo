# Audit service: demo plan and behavior contract

This small TypeScript service is the intentionally untested service in the banking demo. Its initial state has a compiler and a working API, but no test runner, test scripts, coverage configuration, mocking conventions, or service-specific CI test job. No other services' tests have been removed.

## Implemented flow

Browser payment/deposit → Python frontend → Java ledgerwriter → confirmed response → frontend POST to audit service → SQLite record.

The frontend emits an event after a successful ledger response. Direct calls to the ledger bypass this integration. This is a deliberately bounded demonstration, not comprehensive or compliance-certified bank auditing. Authentication events and failed transactions are not recorded yet.

Audit delivery has a two-second timeout. A delivery failure preserves the successful banking result and displays an audit-unavailable warning; it is logged without customer data. There is no durable outbox or automatic reconciliation, so outages can leave gaps. Guaranteed delivery would require an outbox at the transaction boundary and retry/reconciliation work.

SQLite provides a real persistent database without another server. Docker's `audit-data` volume survives container recreation. Node 24's built-in SQLite API is used. The service runs as a non-root user. The local development token is deliberately public, for synthetic data only; port 8090 binds only to localhost.

## Run and inspect

From repository root: `docker compose up -d --build audit frontend`.

- `GET http://localhost:8090/health`: database readiness, no authentication.
- `POST /events`: authenticated event ingestion.
- `GET /events`: authenticated inspection of the latest 100 records, newest first.

Example inspection:

```sh
curl -s http://localhost:8090/events \
  -H 'Authorization: Bearer local-demo-audit-token'
```

POST body:

```json
{
  "eventId": "d7633a92-c4c7-4bdf-bd41-46ed17e1a113",
  "action": "payment",
  "outcome": "succeeded",
  "amountCents": 10000,
  "fromAccount": "1011226111",
  "toAccount": "1033623433"
}
```

## Acceptance checklist for the Devin playbook

- Valid payment/deposit events return 201 and persist across restarts.
- Store server-generated `recordedAt` in UTC; ignore client timestamp fields.
- Require UUID event ID, supported action, `succeeded` outcome, positive safe integer cents, and ten-digit account strings. Reject missing/null/incorrectly typed fields with 400.
- Mask accounts to `******6111` before persistence. Store only the defined fields. Extra password, token, SSN, email, and nested metadata fields must never appear in responses or database records. Raw account numbers arrive over the internal API but are not stored.
- Identical normalized events with the same ID return 200 without another row. Conflicting normalized content returns 409. Deduplication compares the redacted record; it cannot distinguish accounts with identical last four digits.
- Missing/wrong bearer token returns 401 for read and write operations.
- Malformed JSON returns 400, unsupported media type 415, payload over 16 KiB 413, unknown route 404, unsupported method 405.
- Storage errors return generic 503 without request bodies, credentials, or SQL details in logs or responses.
- Integration tests use a temporary real SQLite database, isolated per run. Do not use the demo's persistent volume.
- Browser tests verify login and a payment/deposit updating balance/history. Correlate its UUID with an audit record through the authenticated API.
- Verify audit-service failure leaves the confirmed payment successful with an explicit warning. Do not retry the payment to retry auditing.

## Suggested assignment

Inspect `src/audit` and this contract. Establish unit and real-database integration testing using the project's TypeScript build setup. Prioritize validation, sensitive-field exclusion, idempotency, and storage failures. Add a service-specific CI job with coverage artifacts and a before/after report. Baseline: no configured test harness; coverage is unmeasured, not a fabricated percentage. Execute all new tests and report remaining gaps. Do not alter production behavior to satisfy tests; report discovered defects separately. Keep changes scoped to this service and its CI configuration. Add cross-service Playwright tests in a separate task.

## Preparation versus live demo

The API implementation, compiler, runtime integration, and this behavior contract are prepared. Initial functionality is manually smoke-checked. The test framework, fixtures, unit/integration suites, coverage reporting, and CI job are the work for Devin. No claim of regulatory adequacy follows from a coverage percentage.
