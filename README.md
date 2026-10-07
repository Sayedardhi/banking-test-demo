# Banking Test Demo — incomplete demo baseline

A local banking application for demonstrating critical-path test improvement across services. Uses synthetic accounts and transactions only; this is not a real bank.

## Run locally

Install and start Docker Desktop (or Docker Engine with Compose), then run:

```sh
./scripts/start-local.sh
```

Open **http://localhost:8080**. Demo login: **testuser / bankofanthos**.

The script creates a local JWT key pair (gitignored). The frontend (8080) and authenticated audit API (8090) are published, bound to localhost. Two PostgreSQL databases remain inside the Compose network. No cloud account or Kubernetes is required. First startup downloads pinned upstream release images; ARM machines run these amd64 images under emulation.

```sh
docker compose ps                    # service status
docker compose logs --tail 30        # diagnostics
docker compose down                 # stop; preserve database contents
```

## Current baseline

- Java: ledger writer, balance reader, transaction history.
- Python: user authentication, contacts, original frontend.
- PostgreSQL: accounts and ledger databases.
- Selected ledgerwriter/userservice tests and the contacts test harness are intentionally removed. Other service tests and Cypress E2E remain. See DEMO-BASELINE.md.
- Neutral Demo Bank branding; cloud tracing/metrics and traffic generator are not enabled in the local setup.

**Runtime:** Java and account services use pinned upstream images. Frontend Python, templates, and static assets are mounted from this checkout; audit builds from TypeScript source. Backend integration testing against edited source still needs source-based builds. No TypeScript frontend rewrite is planned.

## Demo preparation

Keep the original tests as references, derive acceptance criteria from them, and then deliberately remove selected tests on a separate demo branch. Disclose the prepared gaps. Measure coverage rather than assuming the case study's 30% baseline. Demonstrate critical behaviors and reviewable execution evidence, not only coverage percentages.

## Attribution

Adapted from [Bank of Anthos](https://github.com/GoogleCloudPlatform/bank-of-anthos), copyright Google LLC and contributors, under the Apache License 2.0. Original license and source notices are retained. Local modifications add Compose startup and neutral presentation. See [LICENSE](LICENSE). Existing upstream cloud deployment documentation remains available in `docs/` as reference.

## Frontend styling

The local demo uses a custom responsive theme on the existing Bootstrap Material Design components. Templates and static assets are mounted from this checkout; restart the frontend after template edits. Login, registration, payment, and deposit keep the original service routes and form IDs. The existing Bootstrap, jQuery, and Popper versions are vendored under `src/frontend/static/vendor/` with their original license notices and SHA-384 integrity checks. Material Icons still loads from Google Fonts.

## TypeScript audit demo service

See [the audit service plan and behavior checklist](src/audit/README.md). Successful browser payments/deposits now emit masked audit records. Run `docker compose up -d --build audit frontend`; inspect the authenticated API on localhost:8090. The frontend Python entrypoint is mounted from source for this integration; other existing backend services still use upstream images. The audit service intentionally has no automated test harness or coverage/CI job, ready for the Devin exercise.
